package br.com.engenheiro.api.logistics;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.documents.DocumentService;
import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.finance.Journal.Line;
import br.com.engenheiro.api.finance.PayableService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import br.com.engenheiro.api.logistics.VehicleService.Row;
import br.com.engenheiro.api.orders.CashService;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gastos de veículo/viagem (LOG-05, LOG-06, ADR-019). Registrados uma vez; cadastro ≠ pagamento.
 * - Pago pela empresa (COMPANY): na aprovação vira despesa + conta a pagar (uma por gasto).
 * - Pago com o dinheiro do motorista (DRIVER_CASH): sai do caixa aberto dele no registro (adiantamento ao
 *   funcionário); na aprovação o adiantamento vira despesa. Recusado: segue como valor a acertar com o funcionário.
 * Combustível: `fuel.*`; manutenção/pneus/seguro/documentos/pedágio/outros: `vehicles.edit_draft` para registrar e
 * `expenses.approve` para aprovar. Quem registrou não aprova o próprio gasto (exceto administrador).
 */
@Service
public class VehicleExpenseService {
    static final List<String> OTHER_KINDS = List.of("MAINTENANCE", "TIRES", "INSURANCE", "DOCUMENTS", "TOLL", "OTHER");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final VehicleService vehicles;
    private final CashService cash;
    private final Journal journal;
    private final PayableService payables;
    private final DocumentService documents;

    public VehicleExpenseService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                                 VehicleService vehicles, CashService cash, Journal journal, PayableService payables,
                                 DocumentService documents) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.vehicles = vehicles;
        this.cash = cash;
        this.journal = journal;
        this.payables = payables;
        this.documents = documents;
    }

    public record FuelCommand(String odometer, String liters, String fuel_type, Boolean full_tank, String amount,
                              String station, String paid_with, LocalDate occurred_on, UUID trip_id, String notes) {
    }

    public record MaintenanceCommand(String kind, String maintenance_type, String odometer, String amount,
                                     String workshop, String parts, String next_service_km, String paid_with,
                                     LocalDate occurred_on, UUID trip_id, String description, LocalDate due_date) {
    }

    public record DecisionCommand(String note, LocalDate due_date) {
    }

    public record ExpenseView(UUID id, UUID vehicle_id, String plate, UUID trip_id, Long trip_number, String kind,
                              LocalDate occurred_on, String amount, String paid_with, String odometer, String liters,
                              String fuel_type, Boolean full_tank, String station, String maintenance_type,
                              String workshop, String parts, String next_service_km, String description, String state,
                              String created_by_name, OffsetDateTime created_at, String decided_by_name,
                              OffsetDateTime decided_at, String decided_note, long version) {
    }

    record ERow(UUID id, UUID organizationId, UUID branchId, UUID vehicleId, String plate, UUID tripId, Long tripNumber,
                String kind, LocalDate occurredOn, BigDecimal amount, String paidWith, UUID cashSessionId,
                BigDecimal odometer, BigDecimal liters, String fuelType, Boolean fullTank, String station,
                String maintenanceType, String workshop, String parts, BigDecimal nextServiceKm, String description,
                String state, UUID createdBy, String createdByName, OffsetDateTime createdAt, String decidedByName,
                OffsetDateTime decidedAt, String decidedNote, long version) {
    }

    static final String SELECT = """
            SELECT e.id, e.organization_id, e.branch_id, e.vehicle_id, v.plate, e.trip_id, t.number AS trip_number, e.kind,
                   e.occurred_on, e.amount, e.paid_with, e.cash_session_id, e.odometer, e.liters, e.fuel_type, e.full_tank,
                   e.station, e.maintenance_type, e.workshop, e.parts, e.next_service_km, e.description, e.state,
                   e.created_by, u.display_name AS created_by_name, e.created_at, d.display_name AS decided_by_name,
                   e.decided_at, e.decided_note, e.version
              FROM vehicle_expenses e JOIN vehicles v ON v.id = e.vehicle_id JOIN app_users u ON u.id = e.created_by
              LEFT JOIN trips t ON t.id = e.trip_id LEFT JOIN app_users d ON d.id = e.decided_by
            """;
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("e.organization_id", "e.branch_id", "'ENTREGAS'",
            "e.created_by", "t.driver_id");

    // ---------------- abastecimento ----------------
    @Transactional
    public ExpenseView registerFuel(Actor actor, UUID vehicleId, String idemKey, FuelCommand cmd) {
        Row v = vehicleFor(actor, vehicleId, "fuel", "create");
        Map<String, Object> req = new HashMap<>();
        req.put("vehicle", vehicleId.toString());
        req.put("body", cmd);
        return idem.execute(actor, "vehicles.fuel", idemKey, req, ExpenseView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            BigDecimal odo = VehicleService.parseKm(cmd.odometer(), "odometer", false, errors);
            BigDecimal liters = Decimals.parse(cmd.liters(), 3, false, "liters", errors);
            if (liters != null && liters.compareTo(new BigDecimal("2000")) > 0) {
                errors.add(new FieldError("liters", "Até 2000 litros"));
            }
            if (cmd.fuel_type() == null || !List.of("DIESEL", "GASOLINA", "ETANOL", "GNV").contains(cmd.fuel_type())) {
                errors.add(new FieldError("fuel_type", "Escolha o combustível"));
            }
            if (cmd.full_tank() == null) {
                errors.add(new FieldError("full_tank", "Informe se completou o tanque"));
            }
            BigDecimal amount = Decimals.parse(cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
            String station = Text.trim(cmd.station());
            if (station != null && station.length() > 120) {
                errors.add(new FieldError("station", "Até 120 caracteres"));
            }
            String notes = Text.trim(cmd.notes());
            if (notes != null && notes.length() > 500) {
                errors.add(new FieldError("notes", "Até 500 caracteres"));
            }
            String paidWith = paidWith(cmd.paid_with(), errors);
            LocalDate on = occurredOn(cmd.occurred_on(), errors);
            UUID trip = tripFor(v, cmd.trip_id(), errors);
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            UUID id = Ids.newId();
            UUID session = paidWith.equals("DRIVER_CASH") ? cash.requireOpenSession(actor) : null;
            vehicles.recordOdometer(actor, v.id(), odo, "FUEL", "vehicle_expense", id, "odometer");
            jdbc.sql("""
                            INSERT INTO vehicle_expenses (id, organization_id, branch_id, vehicle_id, trip_id, kind,
                                                          occurred_on, amount, paid_with, cash_session_id, odometer, liters,
                                                          fuel_type, full_tank, station, description, created_by)
                            VALUES (:id, :o, :b, :v, :t, 'FUEL', :on, :a, :pw, :cs, :odo, :l, :ft, :full, :st, :d, :by)
                            """)
                    .param("id", id).param("o", actor.organizationId()).param("b", v.branchId()).param("v", v.id())
                    .param("t", trip).param("on", on).param("a", amount).param("pw", paidWith).param("cs", session)
                    .param("odo", odo).param("l", liters).param("ft", cmd.fuel_type()).param("full", cmd.full_tank())
                    .param("st", station).param("d", notes).param("by", actor.userId()).update();
            afterRegister(actor, v, id, amount, paidWith, session);
            audit.record(entry(actor, "vehicles.fuel", id, Map.of("vehicle", v.plate(), "amount", amount.toPlainString(),
                    "liters", liters.toPlainString(), "paid_with", paidWith)));
            return view(find(id).orElseThrow());
        });
    }

    // ---------------- manutenção e demais gastos ----------------
    @Transactional
    public ExpenseView registerMaintenance(Actor actor, UUID vehicleId, String idemKey, MaintenanceCommand cmd) {
        Row v = vehicleFor(actor, vehicleId, "vehicles", "edit_draft");
        Map<String, Object> req = new HashMap<>();
        req.put("vehicle", vehicleId.toString());
        req.put("body", cmd);
        return idem.execute(actor, "vehicles.maintenance", idemKey, req, ExpenseView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            String kind = cmd.kind() == null ? "MAINTENANCE" : cmd.kind();
            if (!OTHER_KINDS.contains(kind)) {
                errors.add(new FieldError("kind", "Tipo inválido"));
            }
            if (kind.equals("MAINTENANCE") && (cmd.maintenance_type() == null
                    || !List.of("PREVENTIVE", "CORRECTIVE").contains(cmd.maintenance_type()))) {
                errors.add(new FieldError("maintenance_type", "Preventiva ou corretiva"));
            }
            BigDecimal odo = cmd.odometer() == null || cmd.odometer().isBlank() ? null
                    : VehicleService.parseKm(cmd.odometer(), "odometer", false, errors);
            BigDecimal amount = Decimals.parse(cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
            BigDecimal next = cmd.next_service_km() == null || cmd.next_service_km().isBlank() ? null
                    : VehicleService.parseKm(cmd.next_service_km(), "next_service_km", false, errors);
            String workshop = limit(cmd.workshop(), 120, "workshop", errors);
            String parts = limit(cmd.parts(), 300, "parts", errors);
            String description = limit(cmd.description(), 500, "description", errors);
            if (!kind.equals("MAINTENANCE") && description == null) {
                errors.add(new FieldError("description", "Descreva o gasto"));
            }
            String paidWith = paidWith(cmd.paid_with(), errors);
            LocalDate on = occurredOn(cmd.occurred_on(), errors);
            UUID trip = tripFor(v, cmd.trip_id(), errors);
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            UUID id = Ids.newId();
            UUID session = paidWith.equals("DRIVER_CASH") ? cash.requireOpenSession(actor) : null;
            if (odo != null) {
                vehicles.recordOdometer(actor, v.id(), odo, "MAINTENANCE", "vehicle_expense", id, "odometer");
            }
            jdbc.sql("""
                            INSERT INTO vehicle_expenses (id, organization_id, branch_id, vehicle_id, trip_id, kind,
                                                          occurred_on, amount, paid_with, cash_session_id, odometer,
                                                          maintenance_type, workshop, parts, next_service_km, description,
                                                          created_by)
                            VALUES (:id, :o, :b, :v, :t, :k, :on, :a, :pw, :cs, :odo, :mt, :w, :p, :n, :d, :by)
                            """)
                    .param("id", id).param("o", actor.organizationId()).param("b", v.branchId()).param("v", v.id())
                    .param("t", trip).param("k", kind).param("on", on).param("a", amount).param("pw", paidWith)
                    .param("cs", session).param("odo", odo)
                    .param("mt", kind.equals("MAINTENANCE") ? cmd.maintenance_type() : null).param("w", workshop)
                    .param("p", parts).param("n", next).param("d", description).param("by", actor.userId()).update();
            if (next != null) {
                jdbc.sql("UPDATE vehicles SET next_service_km = :n, version = version + 1 WHERE id = :v")
                        .param("n", next).param("v", v.id()).update();
            }
            afterRegister(actor, v, id, amount, paidWith, session);
            audit.record(entry(actor, "vehicles.expense", id, Map.of("vehicle", v.plate(), "kind", kind,
                    "amount", amount.toPlainString(), "paid_with", paidWith)));
            return view(find(id).orElseThrow());
        });
    }

    /** Dinheiro do motorista: sai do caixa dele agora (fato) e fica como adiantamento até a aprovação. */
    private void afterRegister(Actor actor, Row v, UUID id, BigDecimal amount, String paidWith, UUID session) {
        if (paidWith.equals("DRIVER_CASH")) {
            cash.movement(session, "OUT", amount, "vehicle_expense", id);
            journal.post(actor, v.branchId(), "vehicle_expense", id, "DRIVER_CASH_ADVANCE", "Gasto " + v.plate(), null,
                    List.of(Line.debit("ADIANTAMENTOS_FUNCIONARIOS", amount), Line.credit("CAIXA", amount)));
        }
    }

    // ---------------- aprovação ----------------
    @Transactional
    public ExpenseView approve(Actor actor, UUID id, String idemKey, long expectedVersion, DecisionCommand cmd) {
        ERow e = lockForDecision(actor, id);
        return idem.execute(actor, "vehicles.expense.approve", idemKey, Map.of("id", id.toString(),
                "version", expectedVersion), ExpenseView.class, () -> {
            decide(actor, e, expectedVersion, "APPROVED", cmd == null ? null : cmd.note());
            String memo = label(e.kind()) + " " + e.plate();
            if (e.paidWith().equals("DRIVER_CASH")) {
                journal.post(actor, e.branchId(), "vehicle_expense", e.id(), "EXPENSE_APPROVED", memo, null,
                        List.of(Line.debit("DESPESAS_VEICULOS", e.amount()),
                                Line.credit("ADIANTAMENTOS_FUNCIONARIOS", e.amount())));
            } else {
                journal.post(actor, e.branchId(), "vehicle_expense", e.id(), "EXPENSE_APPROVED", memo, null,
                        List.of(Line.debit("DESPESAS_VEICULOS", e.amount()), Line.credit("FORNECEDORES", e.amount())));
                payables.createForSource(actor, e.branchId(), "vehicle_expense", e.id(), memo
                        + (e.station() != null ? " — " + e.station() : e.workshop() != null ? " — " + e.workshop() : ""),
                        e.amount(), cmd == null ? null : cmd.due_date());
            }
            audit.record(entry(actor, "vehicles.expense.approve", e.id(), Map.of("amount", e.amount().toPlainString())));
            return view(find(e.id()).orElseThrow());
        });
    }

    @Transactional
    public ExpenseView reject(Actor actor, UUID id, long expectedVersion, DecisionCommand cmd) {
        ERow e = lockForDecision(actor, id);
        String note = cmd == null ? null : Text.trim(cmd.note());
        if (note == null || note.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo da recusa.",
                    List.of(new FieldError("note", "Motivo obrigatório")));
        }
        decide(actor, e, expectedVersion, "REJECTED", note);
        audit.record(entry(actor, "vehicles.expense.reject", e.id(), Map.of("reason", note,
                "driver_cash", e.paidWith().equals("DRIVER_CASH"))));
        return view(find(e.id()).orElseThrow());
    }

    private ERow lockForDecision(Actor actor, UUID id) {
        jdbc.sql("SELECT id FROM vehicle_expenses WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        ERow e = find(id).orElse(null);
        String resource = e != null && !e.kind().equals("FUEL") ? "expenses" : "fuel";
        if (e == null || !e.organizationId().equals(actor.organizationId())) {
            authz.require(actor, resource, "approve");
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, resource, "approve", "vehicle_expense", id.toString(),
                new RecordRef(e.organizationId(), e.branchId(), Sector.ENTREGAS, e.createdBy(), null));
        if (e.createdBy().equals(actor.userId()) && !actor.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Quem registrou o gasto não aprova o próprio gasto.");
        }
        return e;
    }

    private void decide(Actor actor, ERow e, long expectedVersion, String state, String note) {
        if (!e.state().equals("PENDING")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Gasto já decidido (" + e.state() + ").");
        }
        int n = jdbc.sql("""
                        UPDATE vehicle_expenses SET state = :s, decided_by = :by, decided_at = now(), decided_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("s", state).param("by", actor.userId()).param("n", Text.trim(note))
                .param("id", e.id()).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O gasto foi alterado por outra pessoa. Recarregue.");
        }
    }

    // ---------------- consultas e anexos ----------------
    /** Gastos visíveis: combustível pelo escopo de `fuel.view`, demais pelo de `expenses.view`. */
    @Transactional(readOnly = true)
    public List<ExpenseView> list(Actor actor, UUID vehicleId, UUID tripId, String state) {
        Decision fuel = authz.decide(actor, "fuel", "view");
        Decision other = authz.decide(actor, "expenses", "view");
        if (!fuel.allowed() && !other.allowed()) {
            authz.require(actor, "fuel", "view");
        }
        if (state != null && !List.of("PENDING", "APPROVED", "REJECTED").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (fuel.allowed()) {
            ScopeSql.Predicate p = ScopeSql.of(actor, fuel.scopes(), COLUMNS);
            parts.add("(e.kind = 'FUEL' AND " + p.sql() + ")");
            params.putAll(p.params());
        }
        if (other.allowed()) {
            ScopeSql.Predicate p = ScopeSql.of(actor, other.scopes(), COLUMNS);
            parts.add("(e.kind <> 'FUEL' AND " + p.sql() + ")");
            params.putAll(p.params());
        }
        var spec = jdbc.sql(SELECT + " WHERE (" + String.join(" OR ", parts) + ")"
                        + " AND (CAST(:veh AS uuid) IS NULL OR e.vehicle_id = CAST(:veh AS uuid))"
                        + " AND (CAST(:trip AS uuid) IS NULL OR e.trip_id = CAST(:trip AS uuid))"
                        + " AND (CAST(:st AS text) IS NULL OR e.state = CAST(:st AS text))"
                        + " ORDER BY e.occurred_on DESC, e.created_at DESC LIMIT 200")
                .param("veh", vehicleId).param("trip", tripId).param("st", state);
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        return spec.query(ERow.class).list().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ExpenseView get(Actor actor, UUID id) {
        return view(requireVisible(actor, id));
    }

    /** Visível a quem vê gastos daquele tipo no escopo (motorista: o próprio). */
    ERow requireVisible(Actor actor, UUID id) {
        ERow e = find(id).orElse(null);
        String resource = e != null && !e.kind().equals("FUEL") ? "expenses" : "fuel";
        if (e == null || !e.organizationId().equals(actor.organizationId())) {
            authz.require(actor, resource, "view");
            throw ApiException.notFound();
        }
        UUID driver = e.tripId() == null ? null : jdbc.sql("SELECT driver_id FROM trips WHERE id = :t")
                .param("t", e.tripId()).query(UUID.class).optional().orElse(null);
        authz.requireRecord(actor, resource, "view", "vehicle_expense", id.toString(),
                new RecordRef(e.organizationId(), e.branchId(), Sector.ENTREGAS, e.createdBy(), driver));
        return e;
    }

    @Transactional
    public DocumentView attach(Actor actor, UUID id, String fileName, byte[] bytes) {
        ERow e = requireVisible(actor, id);
        if (!e.createdBy().equals(actor.userId()) && !actor.admin()) {
            String resource = e.kind().equals("FUEL") ? "fuel" : "expenses";
            authz.require(actor, resource, "approve"); // conferente pode anexar; demais só o autor
        }
        return documents.attach(actor, e.branchId(), "vehicle_expense", e.id(), fileName, bytes);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> attachments(Actor actor, UUID id) {
        ERow e = requireVisible(actor, id);
        return documents.list("vehicle_expense", e.id());
    }

    // ---------------- apoio ----------------
    private Row vehicleFor(Actor actor, UUID vehicleId, String resource, String action) {
        Row v = vehicles.lock(vehicleId).orElse(null);
        if (v == null || !v.organizationId().equals(actor.organizationId())) {
            authz.require(actor, resource, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, resource, action, "vehicle", vehicleId.toString(), VehicleService.ref(v));
        if (v.status().equals("INACTIVE")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Veículo inativo não recebe gastos.");
        }
        return v;
    }

    /** Viagem informada precisa ser deste veículo e não conciliada; sem informar, usa a viagem ativa do veículo. */
    private UUID tripFor(Row v, UUID tripId, List<FieldError> errors) {
        if (tripId == null) {
            return v.currentTripId();
        }
        Optional<String> st = jdbc.sql("SELECT state FROM trips WHERE id = :t AND vehicle_id = :v")
                .param("t", tripId).param("v", v.id()).query(String.class).optional();
        if (st.isEmpty() || !List.of("ASSIGNED", "DEPARTED", "RETURNED").contains(st.get())) {
            errors.add(new FieldError("trip_id", "Viagem não é deste veículo ou já foi conciliada"));
            return null;
        }
        return tripId;
    }

    private static String paidWith(String raw, List<FieldError> errors) {
        String p = raw == null ? "COMPANY" : raw;
        if (!p.equals("COMPANY") && !p.equals("DRIVER_CASH")) {
            errors.add(new FieldError("paid_with", "Use COMPANY ou DRIVER_CASH"));
        }
        return p;
    }

    private static LocalDate occurredOn(LocalDate on, List<FieldError> errors) {
        LocalDate today = LocalDate.now(VehicleService.COMMERCIAL);
        LocalDate d = on == null ? today : on;
        if (d.isAfter(today) || d.isBefore(today.minusDays(400))) {
            errors.add(new FieldError("occurred_on", "Data fora do intervalo permitido"));
        }
        return d;
    }

    private static String limit(String raw, int max, String field, List<FieldError> errors) {
        String t = Text.trim(raw);
        if (t != null && t.length() > max) {
            errors.add(new FieldError(field, "Até " + max + " caracteres"));
        }
        return t;
    }

    static String label(String kind) {
        return switch (kind) {
            case "FUEL" -> "Combustível";
            case "MAINTENANCE" -> "Manutenção";
            case "TIRES" -> "Pneus";
            case "INSURANCE" -> "Seguro";
            case "DOCUMENTS" -> "Documentos";
            case "TOLL" -> "Pedágio";
            default -> "Outro gasto";
        };
    }

    Optional<ERow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id").param("id", id).query(ERow.class).optional();
    }

    ExpenseView view(ERow e) {
        return new ExpenseView(e.id(), e.vehicleId(), e.plate(), e.tripId(), e.tripNumber(), e.kind(), e.occurredOn(),
                Decimals.moneyStr(e.amount()), e.paidWith(), e.odometer() == null ? null : e.odometer().toPlainString(),
                e.liters() == null ? null : Decimals.str(e.liters()), e.fuelType(), e.fullTank(), e.station(),
                e.maintenanceType(), e.workshop(), e.parts(),
                e.nextServiceKm() == null ? null : e.nextServiceKm().toPlainString(), e.description(), e.state(),
                e.createdByName(), e.createdAt(), e.decidedByName(), e.decidedAt(), e.decidedNote(), e.version());
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "vehicle_expense", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }

    static boolean inScope(Actor actor, Decision d, RecordRef ref) {
        return d.allowed() && AccessPolicy.recordInScope(actor, d.scopes(), ref);
    }
}
