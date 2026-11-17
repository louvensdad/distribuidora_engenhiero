package br.com.engenheiro.api.logistics;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.documents.DocumentService;
import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import br.com.engenheiro.api.orders.OrderService;
import br.com.engenheiro.api.orders.OrderService.FulfillCommand;
import br.com.engenheiro.api.orders.OrderService.OpenLine;
import br.com.engenheiro.api.orders.OrderService.OrderHead;
import br.com.engenheiro.api.orders.OrderService.StopOrder;
import br.com.engenheiro.api.orders.PaymentService;
import br.com.engenheiro.api.orders.PaymentService.RegisterCommand;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Cursor;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Viagens e entregas (LOG-02, LOG-03, LOG-04, LOG-07, LOG-08; ADR-019).
 * DRAFT → ASSIGNED → DEPARTED → RETURNED → RECONCILED; CANCELLED só antes da saída.
 * Motorista = escopo ASSIGNED (driver_id). Entrega cumpre o pedido pelas mesmas regras do OrderService (cobertura de
 * pagamento, reconhecimento por quantidade); ocorrência nunca marca como entregue; retorno exige destino para cada
 * parada pendente e prestação do dinheiro recebido; conciliação por outra pessoa.
 */
@Service
public class TripService {
    static final String RESOURCE = "trips";
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("t.organization_id", "t.branch_id", "t.sector",
            "t.created_by", "t.driver_id");
    static final List<String> INCIDENT_KINDS = List.of("CUSTOMER_ABSENT", "WRONG_ADDRESS", "REFUSED", "PARTIAL",
            "DAMAGED", "VEHICLE", "OTHER");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final VehicleService vehicles;
    private final OrderService orders;
    private final PaymentService payments;
    private final DocumentService documents;

    public TripService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                       VehicleService vehicles, OrderService orders, PaymentService payments, DocumentService documents) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.vehicles = vehicles;
        this.orders = orders;
        this.payments = payments;
        this.documents = documents;
    }

    // ---------------- contrato ----------------
    public record CreateCommand(LocalDate planned_date, String notes) {
    }

    public record AssignCommand(UUID vehicle_id, UUID driver_id, List<UUID> order_ids) {
    }

    public record DepartCommand(String odometer) {
    }

    public record DeliverCommand(UUID stop_id, List<OrderService.LineQty> lines, String recipient_name,
                                 RegisterCommand payment, String partial_reason) {
    }

    public record IncidentCommand(UUID stop_id, String kind, String description, String requested_action) {
    }

    public record ReturnCommand(String odometer, String cash_declared, String justification) {
    }

    public record NoteCommand(String note) {
    }

    public record IncidentView(UUID id, UUID stop_id, String kind, String requested_action, String description,
                               String created_by_name, OffsetDateTime created_at) {
    }

    public record StopView(UUID id, int seq, String state, String recipient_name, OffsetDateTime delivered_at,
                           String delivered_by_name, StopOrder order, String load, List<IncidentView> incidents) {
    }

    public record TripView(UUID id, long number, String state, LocalDate planned_date, String notes, UUID vehicle_id,
                           String plate, String vehicle_model, String capacity_quantity, String capacity_unit,
                           String load, UUID driver_id, String driver_name, OffsetDateTime departed_at,
                           String departure_odometer, OffsetDateTime returned_at, String return_odometer,
                           String distance_km, String returned_by_name, String cash_collected, String cash_expenses,
                           String cash_expected, String cash_declared, String cash_difference, String return_note,
                           OffsetDateTime reconciled_at, String reconciled_by_name, String reconcile_note,
                           String cancel_reason, String created_by_name, OffsetDateTime created_at, long version,
                           List<StopView> stops, List<IncidentView> incidents) {
    }

    public record TripSummary(UUID id, long number, String state, LocalDate planned_date, String plate,
                              String driver_name, int stops, int delivered, OffsetDateTime created_at, long version) {
    }

    public record Page(List<TripSummary> items, String next_cursor) {
    }

    public record DriverOption(UUID id, String name, boolean busy) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, long number, String sector, String state,
               LocalDate plannedDate, String notes, UUID vehicleId, String plate, String vehicleModel,
               BigDecimal capacityQuantity, String capacityUnit, UUID driverId, String driverName,
               OffsetDateTime departedAt, BigDecimal departureOdometer, OffsetDateTime returnedAt,
               BigDecimal returnOdometer, UUID returnedBy, String returnedByName, BigDecimal cashCollected,
               BigDecimal cashExpenses, BigDecimal cashExpected, BigDecimal cashDeclared, BigDecimal cashDifference,
               String returnNote, OffsetDateTime reconciledAt, String reconciledByName, String reconcileNote,
               String cancelReason, UUID createdBy, String createdByName, OffsetDateTime createdAt, long version) {
    }

    record StopRow(UUID id, UUID tripId, UUID orderId, int seq, String state, boolean isOpen, String recipientName,
                   OffsetDateTime deliveredAt, String deliveredByName, long version) {
    }

    static final String SELECT = """
            SELECT t.id, t.organization_id, t.branch_id, t.number, t.sector, t.state, t.planned_date, t.notes,
                   t.vehicle_id, v.plate, v.model AS vehicle_model, v.capacity_quantity, v.capacity_unit, t.driver_id,
                   d.display_name AS driver_name, t.departed_at, t.departure_odometer, t.returned_at, t.return_odometer,
                   t.returned_by, rb.display_name AS returned_by_name, t.cash_collected, t.cash_expenses,
                   t.cash_expected, t.cash_declared, t.cash_difference, t.return_note, t.reconciled_at,
                   rc.display_name AS reconciled_by_name, t.reconcile_note, t.cancel_reason, t.created_by,
                   cb.display_name AS created_by_name, t.created_at, t.version
              FROM trips t JOIN app_users cb ON cb.id = t.created_by
              LEFT JOIN vehicles v ON v.id = t.vehicle_id LEFT JOIN app_users d ON d.id = t.driver_id
              LEFT JOIN app_users rb ON rb.id = t.returned_by LEFT JOIN app_users rc ON rc.id = t.reconciled_by
            """;

    // ---------------- criar ----------------
    @Transactional
    public TripView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        return idem.execute(actor, "trips.create", idemKey, cmd, TripView.class, () -> {
            LocalDate today = LocalDate.now(VehicleService.COMMERCIAL);
            LocalDate planned = cmd == null || cmd.planned_date() == null ? today : cmd.planned_date();
            String notes = cmd == null ? null : Text.trim(cmd.notes());
            List<FieldError> errors = new ArrayList<>();
            if (planned.isBefore(today.minusDays(1)) || planned.isAfter(today.plusDays(60))) {
                errors.add(new FieldError("planned_date", "Data fora do intervalo permitido"));
            }
            if (notes != null && notes.length() > 500) {
                errors.add(new FieldError("notes", "Até 500 caracteres"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            long number = jdbc.sql("""
                            INSERT INTO trip_counters (branch_id, last_no) VALUES (:b, 1)
                            ON CONFLICT (branch_id) DO UPDATE SET last_no = trip_counters.last_no + 1 RETURNING last_no
                            """).param("b", actor.branchId()).query(Long.class).single();
            UUID id = Ids.newId();
            jdbc.sql("""
                            INSERT INTO trips (id, organization_id, branch_id, number, sector, state, planned_date, notes,
                                               created_by)
                            VALUES (:id, :o, :b, :n, 'ENTREGAS', 'DRAFT', :p, :notes, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                    .param("n", number).param("p", planned).param("notes", notes).param("by", actor.userId()).update();
            audit.record(entry(actor, "trips.create", id, Map.of("number", number)));
            return view(actor, find(id).orElseThrow());
        });
    }

    // ---------------- atribuir veículo, motorista e pedidos ----------------
    @Transactional
    public TripView assign(Actor actor, UUID id, long expectedVersion, AssignCommand cmd) {
        Row t = lockRow(actor, id, "assign");
        if (!t.state().equals("DRAFT") && !t.state().equals("ASSIGNED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só viagem em rascunho ou atribuída pode ser alterada.");
        }
        List<FieldError> errors = new ArrayList<>();
        List<UUID> orderIds = cmd == null || cmd.order_ids() == null ? List.of() : cmd.order_ids();
        if (orderIds.isEmpty() || orderIds.size() > 30) {
            errors.add(new FieldError("order_ids", "Selecione de 1 a 30 pedidos de entrega"));
        }
        if (new HashSet<>(orderIds).size() != orderIds.size()) {
            errors.add(new FieldError("order_ids", "Pedido repetido"));
        }
        // Veículo: mesma unidade, ativo, documentos em dia, livre (cerebro/02 passo 3).
        VehicleService.Row v = cmd == null || cmd.vehicle_id() == null ? null : vehicles.lock(cmd.vehicle_id())
                .filter(x -> x.organizationId().equals(actor.organizationId()) && x.branchId().equals(t.branchId()))
                .orElse(null);
        LocalDate today = LocalDate.now(VehicleService.COMMERCIAL);
        if (v == null) {
            errors.add(new FieldError("vehicle_id", "Veículo não encontrado nesta unidade"));
        } else {
            if (!v.status().equals("ACTIVE")) {
                errors.add(new FieldError("vehicle_id", "Veículo indisponível (" + statusLabel(v.status()) + ")"));
            }
            if (v.registrationValidUntil() != null && v.registrationValidUntil().isBefore(today)) {
                errors.add(new FieldError("vehicle_id", "Licenciamento do veículo vencido"));
            }
            if (v.insuranceValidUntil() != null && v.insuranceValidUntil().isBefore(today)) {
                errors.add(new FieldError("vehicle_id", "Seguro do veículo vencido"));
            }
            if (v.currentTripId() != null && !v.currentTripId().equals(t.id())) {
                errors.add(new FieldError("vehicle_id", "Veículo já está na viagem V" + v.currentTripNumber()));
            }
        }
        // Motorista: vínculo ativo na unidade, app Entregas e capacidade de ver viagens; livre.
        if (cmd == null || cmd.driver_id() == null) {
            errors.add(new FieldError("driver_id", "Escolha o motorista"));
        } else {
            DriverOption d = drivers(actor.organizationId(), t.branchId()).stream()
                    .filter(x -> x.id().equals(cmd.driver_id())).findFirst().orElse(null);
            if (d == null) {
                errors.add(new FieldError("driver_id", "Pessoa não autorizada como motorista nesta unidade"));
            } else {
                Optional<Long> other = jdbc.sql("""
                                SELECT number FROM trips WHERE driver_id = :d AND state IN ('ASSIGNED', 'DEPARTED') AND id <> :t
                                """).param("d", cmd.driver_id()).param("t", t.id()).query(Long.class).optional();
                other.ifPresent(n -> errors.add(new FieldError("driver_id", "Motorista já está na viagem V" + n)));
            }
        }
        // Pedidos: entrega, mesma unidade, visíveis a quem atribui, abertos e sem outra viagem aberta.
        List<UUID> sorted = new ArrayList<>(orderIds);
        sorted.sort(UUID::compareTo);
        Map<UUID, BigDecimal> loadByOrder = new LinkedHashMap<>();
        Decision view = authz.decide(actor, "orders.delivery", "view");
        for (UUID oid : sorted) {
            jdbc.sql("SELECT id FROM orders WHERE id = :id FOR UPDATE").param("id", oid).query(UUID.class).optional();
            int i = orderIds.indexOf(oid);
            String f = "order_ids[" + i + "]";
            OrderHead h = orders.head(oid).orElse(null);
            if (h == null || !h.organizationId().equals(actor.organizationId()) || !view.allowed()
                    || !AccessPolicy.recordInScope(actor, view.scopes(), new RecordRef(h.organizationId(), h.branchId(),
                    Sector.valueOf(h.sector()), h.createdBy(), null))) {
                errors.add(new FieldError(f, "Pedido não encontrado"));
                continue;
            }
            if (!h.type().equals("DELIVERY") || !h.branchId().equals(t.branchId())) {
                errors.add(new FieldError(f, "Pedido #" + h.number() + " não é de entrega desta unidade"));
                continue;
            }
            if (!Set.of("CONFIRMED", "READY", "PARTIALLY_FULFILLED").contains(h.state())) {
                errors.add(new FieldError(f, "Pedido #" + h.number() + " está " + h.state() + " e não pode ser despachado"));
                continue;
            }
            Optional<Long> otherTrip = jdbc.sql("""
                            SELECT t.number FROM trip_stops s JOIN trips t ON t.id = s.trip_id
                             WHERE s.order_id = :o AND s.is_open AND s.trip_id <> :t
                            """).param("o", oid).param("t", t.id()).query(Long.class).optional();
            if (otherTrip.isPresent()) {
                errors.add(new FieldError(f, "Pedido #" + h.number() + " já está na viagem V" + otherTrip.get()));
                continue;
            }
            if (v != null) {
                BigDecimal load = BigDecimal.ZERO;
                for (OpenLine l : orders.openLines(oid)) {
                    if (l.openBase().signum() == 0) {
                        continue;
                    }
                    BigDecimal c = toCapacityUnit(l.productId(), l.baseUnit(), l.openBase(), v.capacityUnit());
                    if (c == null) {
                        errors.add(new FieldError(f, "Sem conversão de " + l.productName() + " para "
                                + v.capacityUnit() + "; capacidade não pode ser validada"));
                        load = null;
                        break;
                    }
                    load = load.add(c);
                }
                if (load != null) {
                    loadByOrder.put(oid, load);
                }
            }
        }
        if (errors.isEmpty() && v != null) {
            BigDecimal total = loadByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(v.capacityQuantity()) > 0) {
                errors.add(new FieldError("vehicle_id", "Carga " + Decimals.str(total) + " acima da capacidade do veículo ("
                        + Decimals.str(v.capacityQuantity()) + " " + v.capacityUnit() + ")"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A atribuição não é válida.", errors);
        }
        bump(t, expectedVersion);
        try {
            jdbc.sql("UPDATE trips SET state = 'ASSIGNED', vehicle_id = :v, driver_id = :d WHERE id = :id")
                    .param("v", v.id()).param("d", cmd.driver_id()).param("id", t.id()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Veículo ou motorista acabou de ser atribuído a outra viagem.");
        }
        List<StopRow> current = stops(t.id()).stream().filter(StopRow::isOpen).toList();
        Set<UUID> keep = new HashSet<>(orderIds);
        for (StopRow s : current) {
            if (!keep.contains(s.orderId())) {
                jdbc.sql("UPDATE trip_stops SET state = 'REMOVED', is_open = false, version = version + 1 WHERE id = :id")
                        .param("id", s.id()).update();
                audit.record(orderEntry(actor, "trips.order_removed", s.orderId(), Map.of("trip", t.number())));
            }
        }
        for (int i = 0; i < orderIds.size(); i++) {
            UUID oid = orderIds.get(i);
            StopRow existing = current.stream().filter(s -> s.orderId().equals(oid)).findFirst().orElse(null);
            if (existing != null) {
                jdbc.sql("UPDATE trip_stops SET seq = :s WHERE id = :id").param("s", i + 1).param("id", existing.id()).update();
                continue;
            }
            try {
                jdbc.sql("INSERT INTO trip_stops (id, trip_id, order_id, seq) VALUES (:id, :t, :o, :s)")
                        .param("id", Ids.newId()).param("t", t.id()).param("o", oid).param("s", i + 1).update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Um dos pedidos acabou de entrar em outra viagem.");
            }
            audit.record(orderEntry(actor, "trips.order_assigned", oid, Map.of("trip", t.number(), "plate", v.plate())));
        }
        audit.record(entry(actor, "trips.assign", t.id(), Map.of("vehicle", v.plate(), "driver", cmd.driver_id().toString(),
                "orders", orderIds.size())));
        return view(actor, find(t.id()).orElseThrow());
    }

    // ---------------- saída ----------------
    @Transactional
    public TripView depart(Actor actor, UUID id, long expectedVersion, DepartCommand cmd) {
        Row t = lockRow(actor, id, "confirm");
        if (!t.state().equals("ASSIGNED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só viagem atribuída pode sair.");
        }
        VehicleService.Row v = vehicles.lock(t.vehicleId()).orElseThrow();
        if (!v.status().equals("ACTIVE")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Veículo indisponível (" + statusLabel(v.status()) + ").");
        }
        List<FieldError> errors = new ArrayList<>();
        BigDecimal odo = VehicleService.parseKm(cmd == null ? null : cmd.odometer(), "odometer", true, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o odômetro de saída.", errors);
        }
        if (stops(t.id()).stream().noneMatch(StopRow::isOpen)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Viagem sem pedidos.");
        }
        bump(t, expectedVersion);
        vehicles.recordOdometer(actor, v.id(), odo, "DEPARTURE", "trip", t.id(), "odometer");
        jdbc.sql("UPDATE trips SET state = 'DEPARTED', departed_at = now(), departure_odometer = :o WHERE id = :id")
                .param("o", odo).param("id", t.id()).update();
        audit.record(entry(actor, "trips.depart", t.id(), Map.of("odometer", odo.toPlainString(), "plate", v.plate())));
        return view(actor, find(t.id()).orElseThrow());
    }

    // ---------------- entrega (total ou parcial) ----------------
    @Transactional
    public TripView deliver(Actor actor, UUID id, String idemKey, DeliverCommand cmd) {
        Row t = lockRow(actor, id, "confirm");
        Map<String, Object> req = new HashMap<>();
        req.put("trip", id.toString());
        req.put("body", cmd);
        return idem.execute(actor, "trips.deliver", idemKey, req, TripView.class, () -> {
            if (!t.state().equals("DEPARTED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Registre a saída antes de entregar.");
            }
            StopRow s = openStop(t, cmd == null ? null : cmd.stop_id());
            if (!s.state().equals("PENDING")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Esta parada já foi registrada (" + s.state() + ").");
            }
            String recipient = Text.trim(cmd.recipient_name());
            if (recipient == null || recipient.length() > 120) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe quem recebeu.",
                        List.of(new FieldError("recipient_name", "Destinatário obrigatório")));
            }
            if (cmd.payment() != null) {
                payments.registerForTrip(actor, s.orderId(), t.id(), cmd.payment());
            }
            OrderService.OrderView after = orders.fulfillForTrip(actor, s.orderId(), new FulfillCommand(cmd.lines()));
            boolean complete = after.lines().stream().allMatch(l -> new BigDecimal(l.remaining_quantity()).signum() == 0);
            String reason = Text.trim(cmd.partial_reason());
            if (!complete && (reason == null || reason.length() > 500)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Entrega parcial exige o motivo (gera ocorrência).",
                        List.of(new FieldError("partial_reason", "Informe o motivo da entrega parcial")));
            }
            UUID fid = jdbc.sql("SELECT id FROM fulfillments WHERE order_id = :o AND kind = 'FULFILLMENT' "
                            + "ORDER BY occurred_at DESC LIMIT 1").param("o", s.orderId()).query(UUID.class).single();
            jdbc.sql("""
                            UPDATE trip_stops SET state = :st, recipient_name = :r, delivered_at = now(), delivered_by = :by,
                                   fulfillment_id = :f, version = version + 1 WHERE id = :id
                            """).param("st", complete ? "DELIVERED" : "PARTIAL").param("r", recipient)
                    .param("by", actor.userId()).param("f", fid).param("id", s.id()).update();
            if (!complete) {
                insertIncident(actor, t, s, "PARTIAL", reason, "RESCHEDULE");
            }
            jdbc.sql("UPDATE trips SET version = version + 1 WHERE id = :id").param("id", t.id()).update();
            audit.record(entry(actor, "trips.deliver", t.id(), Map.of("order", after.number(),
                    "complete", complete, "with_payment", cmd.payment() != null)));
            return view(actor, find(t.id()).orElseThrow());
        });
    }

    /** Recebimento na parada sem entregar ainda (ex.: cobra antes de descarregar). */
    @Transactional
    public PaymentService.RegisterResult collect(Actor actor, UUID id, UUID stopId, String idemKey, RegisterCommand cmd) {
        Row t = lockRow(actor, id, "confirm");
        Map<String, Object> req = new HashMap<>();
        req.put("trip", id.toString());
        req.put("stop", String.valueOf(stopId));
        req.put("body", cmd);
        return idem.execute(actor, "trips.collect", idemKey, req, PaymentService.RegisterResult.class, () -> {
            if (!t.state().equals("DEPARTED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Registre a saída antes de receber.");
            }
            StopRow s = openStop(t, stopId);
            PaymentService.RegisterResult r = payments.registerForTrip(actor, s.orderId(), t.id(), cmd);
            jdbc.sql("UPDATE trips SET version = version + 1 WHERE id = :id").param("id", t.id()).update();
            return r;
        });
    }

    // ---------------- ocorrência ----------------
    @Transactional
    public TripView incident(Actor actor, UUID id, String idemKey, IncidentCommand cmd) {
        Row t = lockRow(actor, id, "confirm");
        Map<String, Object> req = new HashMap<>();
        req.put("trip", id.toString());
        req.put("body", cmd);
        return idem.execute(actor, "trips.incident", idemKey, req, TripView.class, () -> {
            if (!t.state().equals("DEPARTED") && !t.state().equals("ASSIGNED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Ocorrência só em viagem atribuída ou em rota.");
            }
            List<FieldError> errors = new ArrayList<>();
            if (cmd == null || cmd.kind() == null || !INCIDENT_KINDS.contains(cmd.kind())) {
                errors.add(new FieldError("kind", "Escolha o motivo"));
            }
            String action = cmd == null || cmd.requested_action() == null ? "NONE" : cmd.requested_action();
            if (!List.of("RESCHEDULE", "RETURN", "NONE").contains(action)) {
                errors.add(new FieldError("requested_action", "Use RESCHEDULE, RETURN ou NONE"));
            }
            String desc = cmd == null ? null : Text.trim(cmd.description());
            if (desc == null || desc.length() > 500) {
                errors.add(new FieldError("description", "Descreva o que aconteceu (até 500 caracteres)"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            StopRow s = cmd.stop_id() == null ? null : openStop(t, cmd.stop_id());
            if (s != null && List.of("CUSTOMER_ABSENT", "WRONG_ADDRESS", "REFUSED").contains(cmd.kind())
                    && !s.state().equals("PENDING")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Parada já registrada como " + s.state() + ".");
            }
            insertIncident(actor, t, s, cmd.kind(), desc, action);
            // Ocorrência de não entrega: a parada fica como não entregue (nunca "entregue" para fechar a viagem).
            if (s != null && s.state().equals("PENDING") && !cmd.kind().equals("PARTIAL") && !cmd.kind().equals("VEHICLE")
                    && !cmd.kind().equals("OTHER")) {
                jdbc.sql("UPDATE trip_stops SET state = 'FAILED', version = version + 1 WHERE id = :id")
                        .param("id", s.id()).update();
            }
            jdbc.sql("UPDATE trips SET version = version + 1 WHERE id = :id").param("id", t.id()).update();
            return view(actor, find(t.id()).orElseThrow());
        });
    }

    private void insertIncident(Actor actor, Row t, StopRow s, String kind, String desc, String action) {
        UUID iid = Ids.newId();
        jdbc.sql("""
                        INSERT INTO trip_incidents (id, trip_id, stop_id, kind, requested_action, description, created_by)
                        VALUES (:id, :t, :s, :k, :a, :d, :by)
                        """).param("id", iid).param("t", t.id()).param("s", s == null ? null : s.id()).param("k", kind)
                .param("a", action).param("d", desc).param("by", actor.userId()).update();
        audit.record(entry(actor, "trips.incident", t.id(), Map.of("kind", kind, "requested_action", action)));
        if (s != null) {
            audit.record(orderEntry(actor, "trips.incident", s.orderId(), Map.of("trip", t.number(), "kind", kind,
                    "requested_action", action)));
        }
    }

    // ---------------- retorno e prestação de contas ----------------
    @Transactional
    public TripView returnTrip(Actor actor, UUID id, String idemKey, long expectedVersion, ReturnCommand cmd) {
        Row t = lockRow(actor, id, "close");
        Map<String, Object> req = new HashMap<>();
        req.put("trip", id.toString());
        req.put("version", expectedVersion);
        req.put("body", cmd);
        return idem.execute(actor, "trips.return", idemKey, req, TripView.class, () -> {
            if (!t.state().equals("DEPARTED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só viagem em rota pode retornar.");
            }
            List<StopRow> pending = stops(t.id()).stream().filter(s -> s.isOpen() && s.state().equals("PENDING")).toList();
            if (!pending.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, pending.size() + " parada(s) sem registro: entregue ou "
                        + "registre a ocorrência antes de retornar (não se marca como entregue para fechar a viagem).");
            }
            List<FieldError> errors = new ArrayList<>();
            BigDecimal odo = VehicleService.parseKm(cmd == null ? null : cmd.odometer(), "odometer", true, errors);
            BigDecimal declared = Decimals.parse(cmd == null ? null : cmd.cash_declared(), Decimals.MONEY_SCALE, true,
                    "cash_declared", errors);
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            if (odo.compareTo(t.departureOdometer()) < 0) {
                throw VehicleService.regressive("odometer", odo, t.departureOdometer());
            }
            Cash c = cash(t, false);
            BigDecimal diff = declared.subtract(c.expected());
            String just = Text.trim(cmd.justification());
            if (diff.signum() != 0 && (just == null || just.length() > 500)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Diferença de " + diff.toPlainString()
                        + " no dinheiro: informe a justificativa.",
                        List.of(new FieldError("justification", "Justificativa obrigatória com diferença")));
            }
            bump(t, expectedVersion);
            vehicles.recordOdometer(actor, t.vehicleId(), odo, "RETURN", "trip", t.id(), "odometer");
            jdbc.sql("""
                            UPDATE trips SET state = 'RETURNED', returned_at = now(), return_odometer = :odo, returned_by = :by,
                                   cash_collected = :col, cash_expenses = :exp, cash_expected = :e, cash_declared = :d,
                                   cash_difference = :diff, return_note = :n WHERE id = :id
                            """).param("odo", odo).param("by", actor.userId()).param("col", c.collected())
                    .param("exp", c.expenses()).param("e", c.expected()).param("d", declared).param("diff", diff)
                    .param("n", just).param("id", t.id()).update();
            // Paradas não entregues voltam para despacho (a reserva continua); a viagem não fica com pedido aberto.
            jdbc.sql("UPDATE trip_stops SET is_open = false, version = version + 1 WHERE trip_id = :t AND is_open")
                    .param("t", t.id()).update();
            audit.record(entry(actor, "trips.return", t.id(), Map.of("odometer", odo.toPlainString(),
                    "cash_expected", c.expected().toPlainString(), "cash_declared", declared.toPlainString(),
                    "difference", diff.toPlainString())));
            return view(actor, find(t.id()).orElseThrow());
        });
    }

    /** Conferência da prestação por outra pessoa; gastos em dinheiro do motorista precisam estar decididos. */
    @Transactional
    public TripView reconcile(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row t = lockRow(actor, id, "close");
        if (!t.state().equals("RETURNED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só viagem retornada pode ser conciliada.");
        }
        if (actor.userId().equals(t.driverId()) || actor.userId().equals(t.returnedBy())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "A conferência deve ser feita por outra pessoa (não o motorista "
                    + "nem quem registrou o retorno).");
        }
        int pending = jdbc.sql("SELECT count(*) FROM vehicle_expenses WHERE trip_id = :t AND state = 'PENDING'")
                .param("t", t.id()).query(Integer.class).single();
        if (pending > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, pending + " gasto(s) da viagem aguardando aprovação.");
        }
        Cash c = cash(t, true);
        BigDecimal diff = t.cashDeclared().subtract(c.expected());
        String note = cmd == null ? null : Text.trim(cmd.note());
        if (diff.signum() != 0 && (note == null || note.length() > 500)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Diferença de " + diff.toPlainString()
                    + " na prestação: registre a observação da conferência.",
                    List.of(new FieldError("note", "Observação obrigatória com diferença")));
        }
        bump(t, expectedVersion);
        jdbc.sql("""
                        UPDATE trips SET state = 'RECONCILED', reconciled_at = now(), reconciled_by = :by, reconcile_note = :n,
                               cash_expenses = :exp, cash_expected = :e, cash_difference = :diff WHERE id = :id
                        """).param("by", actor.userId()).param("n", note).param("exp", c.expenses())
                .param("e", c.expected()).param("diff", diff).param("id", t.id()).update();
        audit.record(entry(actor, "trips.reconcile", t.id(), Map.of("cash_expected", c.expected().toPlainString(),
                "difference", diff.toPlainString())));
        return view(actor, find(t.id()).orElseThrow());
    }

    record Cash(BigDecimal collected, BigDecimal expenses, BigDecimal expected) {
    }

    /**
     * Dinheiro a prestar = recebido em dinheiro na viagem − gastos pagos com o dinheiro do motorista.
     * No retorno contam os não recusados; na conciliação, só os aprovados (recusado vira valor a devolver).
     */
    private Cash cash(Row t, boolean approvedOnly) {
        BigDecimal collected = jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM payments
                         WHERE trip_id = :t AND method = 'CASH' AND state = 'CONFIRMED'
                        """).param("t", t.id()).query(BigDecimal.class).single();
        BigDecimal expenses = jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM vehicle_expenses
                         WHERE trip_id = :t AND paid_with = 'DRIVER_CASH' AND created_by = :d
                           AND (CASE WHEN :approved THEN state = 'APPROVED' ELSE state <> 'REJECTED' END)
                        """).param("t", t.id()).param("d", t.driverId()).param("approved", approvedOnly)
                .query(BigDecimal.class).single();
        return new Cash(Decimals.money(collected), Decimals.money(expenses), Decimals.money(collected.subtract(expenses)));
    }

    // ---------------- cancelar (antes da saída) ----------------
    @Transactional
    public TripView cancel(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row t = lockRow(actor, id, "assign");
        if (!t.state().equals("DRAFT") && !t.state().equals("ASSIGNED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Viagem só pode ser cancelada antes da saída.");
        }
        String reason = cmd == null ? null : Text.trim(cmd.note());
        if (reason == null || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("note", "Motivo obrigatório")));
        }
        bump(t, expectedVersion);
        jdbc.sql("UPDATE trips SET state = 'CANCELLED', cancelled_at = now(), cancel_reason = :r WHERE id = :id")
                .param("r", reason).param("id", t.id()).update();
        jdbc.sql("UPDATE trip_stops SET state = 'REMOVED', is_open = false, version = version + 1 WHERE trip_id = :t AND is_open")
                .param("t", t.id()).update();
        audit.record(entry(actor, "trips.cancel", t.id(), Map.of("reason", reason)));
        return view(actor, find(t.id()).orElseThrow());
    }

    // ---------------- rateio analítico (LOG-08) ----------------
    public record AllocationLine(UUID stop_id, long order_number, String customer_name, String delivered_quantity,
                                 String share, String allocated_cost) {
    }

    public record ExpenseLine(UUID id, String kind, String amount, String state, String paid_with) {
    }

    public record Costs(UUID trip_id, String rule_code, int rule_version, String rule_description, String capacity_unit,
                        String total_cost, String allocated_total, String unallocated, String pending_cost,
                        String distance_km, String cost_per_km, List<AllocationLine> lines, List<ExpenseLine> expenses,
                        String message) {
    }

    @Transactional(readOnly = true)
    public Costs costs(Actor actor, UUID id) {
        Row t = requireRow(actor, id, "view");
        if (!actor.admin() && !authz.decide(actor, "expenses", "view").allowed()) {
            authz.require(actor, "expenses", "view"); // custo da viagem é dado financeiro
        }
        record Rule(String code, int version, String description) {
        }
        Rule rule = jdbc.sql("SELECT code, version, description FROM allocation_rules WHERE code = 'TRIP_COST' "
                + "AND valid_from <= now() ORDER BY version DESC LIMIT 1").query(Rule.class).single();
        List<ExpenseLine> expenses = jdbc.sql("""
                        SELECT id, kind, amount::text AS amount, state, paid_with FROM vehicle_expenses
                         WHERE trip_id = :t ORDER BY created_at
                        """).param("t", t.id()).query(ExpenseLine.class).list();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal pendingCost = BigDecimal.ZERO;
        for (ExpenseLine e : expenses) {
            if (e.state().equals("APPROVED")) {
                total = total.add(new BigDecimal(e.amount()));
            } else if (e.state().equals("PENDING")) {
                pendingCost = pendingCost.add(new BigDecimal(e.amount()));
            }
        }
        // Peso de cada parada: quantidade efetivamente entregue (na medida de capacidade do veículo).
        record Delivered(UUID stopId, long number, String customer, UUID productId, String baseUnit, BigDecimal qty) {
        }
        List<Delivered> rows = jdbc.sql("""
                        SELECT s.id AS stop_id, o.number, c.name AS customer, ol.product_id, p.base_unit,
                               sum(fl.base_quantity) AS qty
                          FROM trip_stops s JOIN orders o ON o.id = s.order_id JOIN customers c ON c.id = o.customer_id
                          JOIN fulfillment_lines fl ON fl.fulfillment_id = s.fulfillment_id
                          JOIN order_lines ol ON ol.id = fl.order_line_id JOIN products p ON p.id = ol.product_id
                         WHERE s.trip_id = :t AND s.state IN ('DELIVERED', 'PARTIAL')
                         GROUP BY s.id, s.seq, o.number, c.name, ol.product_id, p.base_unit ORDER BY s.seq
                        """).param("t", t.id()).query(Delivered.class).list();
        Map<UUID, BigDecimal> weight = new LinkedHashMap<>();
        Map<UUID, Delivered> head = new HashMap<>();
        for (Delivered d : rows) {
            BigDecimal q = t.capacityUnit() == null ? null : toCapacityUnit(d.productId(), d.baseUnit(), d.qty(), t.capacityUnit());
            weight.merge(d.stopId(), q == null ? BigDecimal.ZERO : q, BigDecimal::add);
            head.putIfAbsent(d.stopId(), d);
        }
        BigDecimal w = weight.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AllocationLine> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        String message = null;
        if (w.signum() == 0) {
            message = total.signum() == 0 ? "Sem custo aprovado para ratear."
                    : "Sem quantidade entregue: custo mantido como não alocado.";
        } else {
            List<UUID> ids = new ArrayList<>(weight.keySet());
            for (int i = 0; i < ids.size(); i++) {
                UUID sid = ids.get(i);
                BigDecimal share = weight.get(sid).divide(w, 6, RoundingMode.HALF_UP);
                BigDecimal value = i == ids.size() - 1 ? total.subtract(allocated)
                        : total.multiply(weight.get(sid)).divide(w, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(value);
                Delivered d = head.get(sid);
                lines.add(new AllocationLine(sid, d.number(), d.customer(), Decimals.str(weight.get(sid)),
                        Decimals.str(share), Decimals.moneyStr(value)));
            }
        }
        BigDecimal distance = t.returnOdometer() == null ? null : t.returnOdometer().subtract(t.departureOdometer());
        String perKm = distance == null || distance.signum() == 0 || total.signum() == 0 ? null
                : total.divide(distance, 2, RoundingMode.HALF_UP).toPlainString();
        return new Costs(t.id(), rule.code(), rule.version(), rule.description(), t.capacityUnit(),
                Decimals.moneyStr(total), Decimals.moneyStr(allocated), Decimals.moneyStr(total.subtract(allocated)),
                Decimals.moneyStr(pendingCost), distance == null ? null : distance.toPlainString(), perKm, lines,
                expenses, message);
    }

    // ---------------- consultas ----------------
    @Transactional(readOnly = true)
    public TripView get(Actor actor, UUID id) {
        return view(actor, requireRow(actor, id, "view"));
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String state, int limit, String cursor) {
        return list(actor, state, null, limit, cursor);
    }

    /** Busca opcional por número da viagem (exato) ou placa (contém, sem diferenciar maiúsculas). */
    @Transactional(readOnly = true)
    public Page list(Actor actor, String state, String query, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        String q = br.com.engenheiro.api.shared.web.SearchText.normalize(query);
        Long number = q != null && q.replaceFirst("^[Vv]", "").matches("\\d{1,12}") ? Long.valueOf(q.replaceFirst("^[Vv]", "")) : null;
        Cursor.checkLimit(limit);
        if (state != null && !List.of("DRAFT", "ASSIGNED", "DEPARTED", "RETURNED", "RECONCILED", "CANCELLED")
                .contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        ScopeSql.Predicate p = ScopeSql.of(actor, d.scopes(), COLUMNS);
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql("""
                        SELECT t.id, t.number, t.state, t.planned_date, v.plate, d.display_name AS driver_name,
                               (SELECT count(*) FROM trip_stops s WHERE s.trip_id = t.id AND s.state <> 'REMOVED') AS stops,
                               (SELECT count(*) FROM trip_stops s WHERE s.trip_id = t.id AND s.state = 'DELIVERED') AS delivered,
                               t.created_at, t.version
                          FROM trips t LEFT JOIN vehicles v ON v.id = t.vehicle_id LEFT JOIN app_users d ON d.id = t.driver_id
                         WHERE """ + p.sql()
                        + " AND (CAST(:st AS text) IS NULL OR t.state = CAST(:st AS text))"
                        + " AND (CAST(:q AS text) IS NULL OR t.number = CAST(:qn AS bigint)"
                        + "      OR replace(v.plate, '-', '') ILIKE '%' || replace(CAST(:q AS text), '-', '') || '%' ESCAPE '\\')"
                        + " AND (CAST(:cts AS text) IS NULL OR (t.created_at, t.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))"
                        + " ORDER BY t.created_at DESC, t.id DESC LIMIT :lim")
                .param("st", state).param("q", br.com.engenheiro.api.shared.web.SearchText.like(q)).param("qn", number).param("cts", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1);
        for (Map.Entry<String, Object> e : p.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<TripSummary> rows = spec.query(TripSummary.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().created_at().toString(), rows.getLast().id());
        }
        return new Page(rows, next);
    }

    public record HistoryItem(OffsetDateTime occurred_at, String action, String actor_name, String client_id) {
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> history(Actor actor, UUID id) {
        requireRow(actor, id, "view");
        return jdbc.sql("""
                        SELECT a.occurred_at, a.action, u.display_name AS actor_name, a.client_id
                          FROM audit_events a LEFT JOIN app_users u ON u.id = a.actor_user_id
                         WHERE a.organization_id = :org AND a.result = 'SUCCESS' AND a.entity_type = 'trip'
                           AND a.entity_id = :tid ORDER BY a.occurred_at, a.id
                        """).param("org", actor.organizationId()).param("tid", id.toString())
                .query(HistoryItem.class).list();
    }

    /** Motoristas que podem receber viagem: vínculo ativo na unidade, app Entregas e `trips.view` concedido. */
    @Transactional(readOnly = true)
    public List<DriverOption> eligibleDrivers(Actor actor) {
        authz.require(actor, RESOURCE, "assign");
        return drivers(actor.organizationId(), actor.branchId());
    }

    private List<DriverOption> drivers(UUID org, UUID branch) {
        return jdbc.sql("""
                        SELECT u.id, u.display_name AS name,
                               EXISTS (SELECT 1 FROM trips t WHERE t.driver_id = u.id AND t.state IN ('ASSIGNED', 'DEPARTED')) AS busy
                          FROM memberships m JOIN app_users u ON u.id = m.user_id
                         WHERE m.organization_id = :o AND m.branch_id = :b AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
                           AND EXISTS (SELECT 1 FROM membership_clients mc WHERE mc.membership_id = m.id
                                        AND mc.client_id = 'engenheiro-entregas')
                           AND EXISTS (SELECT 1 FROM permission_grants g WHERE g.membership_id = m.id AND g.resource = 'trips'
                                        AND g.action = 'view' AND g.effect = 'ALLOW' AND g.revoked_at IS NULL
                                        AND (g.expires_at IS NULL OR g.expires_at > now()))
                           AND NOT EXISTS (SELECT 1 FROM permission_grants g WHERE g.membership_id = m.id AND g.resource = 'trips'
                                        AND g.action = 'view' AND g.effect = 'DENY' AND g.revoked_at IS NULL
                                        AND (g.expires_at IS NULL OR g.expires_at > now()))
                         ORDER BY u.display_name
                        """).param("o", org).param("b", branch).query(DriverOption.class).list();
    }

    // ---------------- anexos (evidência da entrega) ----------------
    @Transactional
    public DocumentView attach(Actor actor, UUID id, UUID stopId, String fileName, byte[] bytes) {
        Row t = lockRow(actor, id, "confirm");
        StopRow s = stops(t.id()).stream().filter(x -> x.id().equals(stopId) && !x.state().equals("REMOVED"))
                .findFirst().orElseThrow(ApiException::notFound);
        if (!List.of("DEPARTED", "RETURNED").contains(t.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Evidência só durante ou logo após a viagem.");
        }
        return documents.attach(actor, t.branchId(), "trip_stop", s.id(), fileName, bytes);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> attachments(Actor actor, UUID id, UUID stopId) {
        Row t = requireRow(actor, id, "view");
        StopRow s = stops(t.id()).stream().filter(x -> x.id().equals(stopId)).findFirst().orElseThrow(ApiException::notFound);
        return documents.list("trip_stop", s.id());
    }

    /** Autoriza download de evidência da parada pelo escopo da viagem. */
    @Transactional(readOnly = true)
    public void requireStopVisible(Actor actor, UUID stopId) {
        UUID trip = jdbc.sql("SELECT trip_id FROM trip_stops WHERE id = :s").param("s", stopId).query(UUID.class)
                .optional().orElseThrow(ApiException::notFound);
        requireRow(actor, trip, "view");
    }

    // ---------------- apoio ----------------
    /**
     * Converte quantidade-base do produto para a medida de capacidade do veículo: mesma medida = 1; senão, fator
     * vigente do produto (quantas unidades-base cabem em 1 medida). Sem fator → null (não comparável).
     */
    BigDecimal toCapacityUnit(UUID productId, String baseUnit, BigDecimal baseQty, String capacityUnit) {
        if (baseUnit.equals(capacityUnit)) {
            return baseQty;
        }
        return jdbc.sql("""
                        SELECT factor FROM unit_conversions WHERE product_id = :p AND unit = :u AND valid_from <= now()
                         ORDER BY valid_from DESC LIMIT 1
                        """).param("p", productId).param("u", capacityUnit).query(BigDecimal.class).optional()
                .map(f -> baseQty.divide(f, 6, RoundingMode.HALF_UP)).orElse(null);
    }

    private StopRow openStop(Row t, UUID stopId) {
        if (stopId == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe a parada.",
                    List.of(new FieldError("stop_id", "Obrigatório")));
        }
        StopRow s = jdbc.sql(STOP_SELECT + " WHERE s.id = :id AND s.trip_id = :t FOR UPDATE OF s").param("id", stopId)
                .param("t", t.id()).query(StopRow.class).optional().orElse(null);
        if (s == null || !s.isOpen()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Parada não pertence a esta viagem.",
                    List.of(new FieldError("stop_id", "Parada inválida")));
        }
        return s;
    }

    private Row lockRow(Actor actor, UUID id, String action) {
        jdbc.sql("SELECT id FROM trips WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return requireRow(actor, id, action);
    }

    private Row requireRow(Actor actor, UUID id, String action) {
        Row t = find(id).orElse(null);
        if (t == null || !t.organizationId().equals(actor.organizationId())) {
            authz.require(actor, RESOURCE, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, action, "trip", id.toString(), ref(t));
        return t;
    }

    static RecordRef ref(Row t) {
        return new RecordRef(t.organizationId(), t.branchId(), Sector.valueOf(t.sector()), t.createdBy(), t.driverId());
    }

    private void bump(Row t, long expectedVersion) {
        int n = jdbc.sql("UPDATE trips SET version = version + 1 WHERE id = :id AND version = :v")
                .param("id", t.id()).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A viagem foi alterada por outra pessoa. Recarregue.");
        }
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(Row.class).optional();
    }

    static final String STOP_SELECT = """
            SELECT s.id, s.trip_id, s.order_id, s.seq, s.state, s.is_open, s.recipient_name, s.delivered_at,
                   u.display_name AS delivered_by_name, s.version
              FROM trip_stops s LEFT JOIN app_users u ON u.id = s.delivered_by
            """;

    List<StopRow> stops(UUID tripId) {
        return jdbc.sql(STOP_SELECT + " WHERE s.trip_id = :t ORDER BY s.seq, s.id").param("t", tripId)
                .query(StopRow.class).list();
    }

    TripView view(Actor actor, Row t) {
        List<IncidentView> incidents = jdbc.sql("""
                        SELECT i.id, i.stop_id, i.kind, i.requested_action, i.description, u.display_name AS created_by_name,
                               i.created_at
                          FROM trip_incidents i JOIN app_users u ON u.id = i.created_by
                         WHERE i.trip_id = :t ORDER BY i.created_at, i.id
                        """).param("t", t.id()).query(IncidentView.class).list();
        List<StopView> stopViews = new ArrayList<>();
        BigDecimal load = BigDecimal.ZERO;
        boolean loadKnown = t.capacityUnit() != null;
        for (StopRow s : stops(t.id())) {
            if (s.state().equals("REMOVED")) {
                continue;
            }
            BigDecimal stopLoad = BigDecimal.ZERO;
            if (t.capacityUnit() != null) {
                for (OpenLine l : orders.openLines(s.orderId())) {
                    BigDecimal q = s.state().equals("PENDING") ? l.openBase() : l.fulfilledBase();
                    BigDecimal c = q.signum() == 0 ? BigDecimal.ZERO
                            : toCapacityUnit(l.productId(), l.baseUnit(), q, t.capacityUnit());
                    if (c == null) {
                        loadKnown = false;
                        stopLoad = null;
                        break;
                    }
                    stopLoad = stopLoad.add(c);
                }
            }
            if (stopLoad != null && t.capacityUnit() != null) {
                load = load.add(stopLoad);
            }
            stopViews.add(new StopView(s.id(), s.seq(), s.state(), s.recipientName(), s.deliveredAt(), s.deliveredByName(),
                    orders.stopOrder(actor, s.orderId()), stopLoad == null || t.capacityUnit() == null ? null
                    : Decimals.str(stopLoad),
                    incidents.stream().filter(i -> s.id().equals(i.stop_id())).toList()));
        }
        BigDecimal distance = t.returnOdometer() == null ? null : t.returnOdometer().subtract(t.departureOdometer());
        return new TripView(t.id(), t.number(), t.state(), t.plannedDate(), t.notes(), t.vehicleId(), t.plate(),
                t.vehicleModel(), t.capacityQuantity() == null ? null : Decimals.str(t.capacityQuantity()), t.capacityUnit(),
                loadKnown ? Decimals.str(load) : null, t.driverId(), t.driverName(), t.departedAt(),
                km(t.departureOdometer()), t.returnedAt(), km(t.returnOdometer()), km(distance), t.returnedByName(),
                Decimals.moneyStr(t.cashCollected()), Decimals.moneyStr(t.cashExpenses()),
                Decimals.moneyStr(t.cashExpected()), Decimals.moneyStr(t.cashDeclared()),
                Decimals.moneyStr(t.cashDifference()), t.returnNote(), t.reconciledAt(), t.reconciledByName(),
                t.reconcileNote(), t.cancelReason(), t.createdByName(), t.createdAt(), t.version(), stopViews,
                incidents.stream().filter(i -> i.stop_id() == null).toList());
    }

    private static String km(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static String statusLabel(String s) {
        return switch (s) {
            case "MAINTENANCE" -> "em manutenção";
            case "INACTIVE" -> "inativo";
            default -> s;
        };
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "trip", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }

    static AuditEntry orderEntry(Actor actor, String action, UUID orderId, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "order", orderId.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
