package br.com.engenheiro.api.logistics;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.catalogparties.products.Units;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frota (LOG-02, LOG-05, LOG-06): veículo com placa, capacidade e validade de documentos; odômetro somente inclusão e
 * monotônico (correção só por administrador, com motivo); consumo km/l apenas entre abastecimentos de tanque cheio.
 * Escopo ASSIGNED de veículo = motorista da viagem ativa do veículo.
 */
@Service
public class VehicleService {
    static final ZoneId COMMERCIAL = ZoneId.of("America/Sao_Paulo");
    static final String RESOURCE = "vehicles";
    /** Motorista atual: viagem atribuída ou em rota do veículo. */
    static final String CURRENT_DRIVER_SQL =
            "(SELECT t.driver_id FROM trips t WHERE t.vehicle_id = v.id AND t.state IN ('ASSIGNED', 'DEPARTED'))";
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("v.organization_id", "v.branch_id", "'ENTREGAS'",
            "v.created_by", CURRENT_DRIVER_SQL);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;

    public VehicleService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
    }

    public record CreateCommand(String plate, String model, String capacity_quantity, String capacity_unit,
                                String odometer_km, String next_service_km, LocalDate registration_valid_until,
                                LocalDate insurance_valid_until, String notes) {
    }

    public record StatusCommand(String status, String reason) {
    }

    public record OdometerCorrection(String reading, String reason) {
    }

    public record Consumption(String state, String km_per_liter, String km, String liters, int full_tank_fills,
                              String message) {
    }

    public record Alert(String code, String message) {
    }

    public record VehicleView(UUID id, String plate, String model, String capacity_quantity, String capacity_unit,
                              String status, String odometer_km, String next_service_km,
                              LocalDate registration_valid_until, LocalDate insurance_valid_until, String notes,
                              String branch, UUID current_trip_id, Long current_trip_number, String current_driver_name,
                              long version, OffsetDateTime created_at, List<Alert> alerts, Consumption consumption) {
    }

    public record Page(List<VehicleView> items) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, String plate, String model,
               BigDecimal capacityQuantity, String capacityUnit, String status, BigDecimal odometerKm,
               BigDecimal nextServiceKm, LocalDate registrationValidUntil, LocalDate insuranceValidUntil, String notes,
               UUID createdBy, UUID currentDriver, String currentDriverName, UUID currentTripId,
               Long currentTripNumber, long version, OffsetDateTime createdAt) {
    }

    static final String SELECT = """
            SELECT v.id, v.organization_id, v.branch_id, b.name AS branch_name, v.plate, v.model, v.capacity_quantity,
                   v.capacity_unit, v.status, v.odometer_km, v.next_service_km, v.registration_valid_until,
                   v.insurance_valid_until, v.notes, v.created_by, t.driver_id AS current_driver,
                   u.display_name AS current_driver_name, t.id AS current_trip_id, t.number AS current_trip_number,
                   v.version, v.created_at
              FROM vehicles v JOIN branches b ON b.id = v.branch_id
              LEFT JOIN trips t ON t.vehicle_id = v.id AND t.state IN ('ASSIGNED', 'DEPARTED')
              LEFT JOIN app_users u ON u.id = t.driver_id
            """;

    // ---------------- cadastro ----------------
    @Transactional
    public VehicleView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        return idem.execute(actor, "vehicles.create", idemKey, cmd, VehicleView.class, () -> doCreate(actor, cmd));
    }

    private VehicleView doCreate(Actor actor, CreateCommand cmd) {
        List<FieldError> errors = new ArrayList<>();
        String plate = normalizePlate(cmd.plate());
        if (plate == null) {
            errors.add(new FieldError("plate", "Placa inválida (ABC1D23 ou ABC1234)"));
        }
        String model = Text.trim(cmd.model());
        if (model == null || model.length() > 80) {
            errors.add(new FieldError("model", "Informe o modelo (até 80 caracteres)"));
        }
        BigDecimal cap = Decimals.parse(cmd.capacity_quantity(), Decimals.QTY_SCALE, false, "capacity_quantity", errors);
        if (!Units.valid(cmd.capacity_unit())) {
            errors.add(new FieldError("capacity_unit", "Medida de capacidade inválida"));
        }
        BigDecimal odo = parseKm(cmd.odometer_km(), "odometer_km", true, errors);
        BigDecimal next = cmd.next_service_km() == null || cmd.next_service_km().isBlank() ? null
                : parseKm(cmd.next_service_km(), "next_service_km", true, errors);
        String notes = Text.trim(cmd.notes());
        if (notes != null && notes.length() > 500) {
            errors.add(new FieldError("notes", "Até 500 caracteres"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO vehicles (id, organization_id, branch_id, plate, model, capacity_quantity,
                                                  capacity_unit, odometer_km, next_service_km, registration_valid_until,
                                                  insurance_valid_until, notes, created_by)
                            VALUES (:id, :o, :b, :p, :m, :c, :u, :odo, :n, :reg, :ins, :notes, :by)
                            """)
                    .param("id", id).param("o", actor.organizationId()).param("b", actor.branchId()).param("p", plate)
                    .param("m", model).param("c", cap).param("u", cmd.capacity_unit()).param("odo", odo)
                    .param("n", next).param("reg", cmd.registration_valid_until())
                    .param("ins", cmd.insurance_valid_until()).param("notes", notes).param("by", actor.userId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Placa já cadastrada.",
                    List.of(new FieldError("plate", "Já existe veículo com esta placa")));
        }
        jdbc.sql("INSERT INTO odometer_readings (id, vehicle_id, reading, kind, recorded_by) VALUES (:id, :v, :r, 'INITIAL', :by)")
                .param("id", Ids.newId()).param("v", id).param("r", odo).param("by", actor.userId()).update();
        audit.record(entry(actor, "vehicles.create", id, Map.of("plate", plate, "model", model)));
        return view(find(id).orElseThrow());
    }

    @Transactional
    public VehicleView changeStatus(Actor actor, UUID id, long expectedVersion, StatusCommand cmd) {
        Row v = requireRow(actor, id, "edit_draft");
        if (cmd == null || cmd.status() == null || !List.of("ACTIVE", "MAINTENANCE", "INACTIVE").contains(cmd.status())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Situação inválida.",
                    List.of(new FieldError("status", "Use ACTIVE, MAINTENANCE ou INACTIVE")));
        }
        String reason = Text.trim(cmd.reason());
        if (reason == null || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório")));
        }
        if (!cmd.status().equals("ACTIVE") && v.currentTripId() != null) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "Veículo está na viagem V" + v.currentTripNumber() + "; troque o veículo da viagem antes.");
        }
        int n = jdbc.sql("UPDATE vehicles SET status = :s, version = version + 1 WHERE id = :id AND version = :ver")
                .param("s", cmd.status()).param("id", id).param("ver", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O veículo foi alterado por outra pessoa. Recarregue.");
        }
        audit.record(entry(actor, "vehicles.status", id, Map.of("from", v.status(), "to", cmd.status(), "reason", reason)));
        return view(find(id).orElseThrow());
    }

    /** Correção de odômetro: procedimento aprovado = administrador, com motivo, auditado (cerebro/02). */
    @Transactional
    public VehicleView correctOdometer(Actor actor, UUID id, OdometerCorrection cmd) {
        authz.requireAdmin(actor, "vehicles.odometer_correction");
        Row v = requireRow(actor, id, "view");
        List<FieldError> errors = new ArrayList<>();
        BigDecimal reading = parseKm(cmd == null ? null : cmd.reading(), "reading", true, errors);
        String reason = cmd == null ? null : Text.trim(cmd.reason());
        if (reason == null || reason.length() > 500) {
            errors.add(new FieldError("reason", "Motivo obrigatório"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        jdbc.sql("""
                        INSERT INTO odometer_readings (id, vehicle_id, reading, kind, reason, recorded_by)
                        VALUES (:id, :v, :r, 'CORRECTION', :reason, :by)
                        """).param("id", Ids.newId()).param("v", id).param("r", reading).param("reason", reason)
                .param("by", actor.userId()).update();
        jdbc.sql("UPDATE vehicles SET version = version + 1 WHERE id = :id").param("id", id).update();
        audit.record(entry(actor, "vehicles.odometer_correction", id, Map.of("from", v.odometerKm().toPlainString(),
                "to", reading.toPlainString(), "reason", reason)));
        return view(find(id).orElseThrow());
    }

    /**
     * Registra leitura (saída, retorno, abastecimento, manutenção). Regressiva → 422; o banco também recusa
     * (gatilho com bloqueio da linha do veículo).
     */
    void recordOdometer(Actor actor, UUID vehicleId, BigDecimal reading, String kind, String sourceType, UUID sourceId,
                        String field) {
        BigDecimal current = jdbc.sql("SELECT odometer_km FROM vehicles WHERE id = :id FOR UPDATE")
                .param("id", vehicleId).query(BigDecimal.class).single();
        if (reading.compareTo(current) < 0) {
            throw regressive(field, reading, current);
        }
        try {
            jdbc.sql("""
                            INSERT INTO odometer_readings (id, vehicle_id, reading, kind, source_type, source_id, recorded_by)
                            VALUES (:id, :v, :r, :k, :st, :sid, :by)
                            """).param("id", Ids.newId()).param("v", vehicleId).param("r", reading).param("k", kind)
                    .param("st", sourceType).param("sid", sourceId).param("by", actor.userId()).update();
        } catch (DataIntegrityViolationException e) {
            throw regressive(field, reading, current);
        }
    }

    static ApiException regressive(String field, BigDecimal reading, BigDecimal current) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Odômetro regressivo: " + reading.toPlainString()
                + " km é menor que o último registrado (" + current.toPlainString() + " km). Peça correção ao administrador.",
                List.of(new FieldError(field, "Menor que o último odômetro (" + current.toPlainString() + " km)")));
    }

    // ---------------- consultas ----------------
    @Transactional(readOnly = true)
    public Page list(Actor actor, String status) {
        Decision d = authz.require(actor, RESOURCE, "view");
        if (status != null && !List.of("ACTIVE", "MAINTENANCE", "INACTIVE").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status inválido.");
        }
        ScopeSql.Predicate p = ScopeSql.of(actor, d.scopes(), COLUMNS);
        var spec = jdbc.sql(SELECT + " WHERE " + p.sql() + " AND (CAST(:st AS text) IS NULL OR v.status = CAST(:st AS text))"
                + " ORDER BY v.plate LIMIT 200").param("st", status);
        for (Map.Entry<String, Object> e : p.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return new Page(spec.query(Row.class).list().stream().map(this::view).toList());
    }

    @Transactional(readOnly = true)
    public VehicleView get(Actor actor, UUID id) {
        return view(requireRow(actor, id, "view"));
    }

    public record HistoryItem(OffsetDateTime occurred_at, String action, String actor_name, String client_id,
                              String entity_type, String entity_id) {
    }

    /** Histórico do veículo: cadastro, situação, viagens com ele e gastos (com autor e app de origem). */
    @Transactional(readOnly = true)
    public List<HistoryItem> history(Actor actor, UUID id) {
        requireRow(actor, id, "view");
        return jdbc.sql("""
                        SELECT a.occurred_at, a.action, u.display_name AS actor_name, a.client_id, a.entity_type, a.entity_id
                          FROM audit_events a LEFT JOIN app_users u ON u.id = a.actor_user_id
                         WHERE a.organization_id = :org AND a.result = 'SUCCESS'
                           AND ((a.entity_type = 'vehicle' AND a.entity_id = :vid)
                             OR (a.entity_type = 'trip' AND a.entity_id IN (SELECT id::text FROM trips WHERE vehicle_id = :v))
                             OR (a.entity_type = 'vehicle_expense'
                                 AND a.entity_id IN (SELECT id::text FROM vehicle_expenses WHERE vehicle_id = :v)))
                         ORDER BY a.occurred_at DESC, a.id DESC LIMIT 200
                        """).param("org", actor.organizationId()).param("vid", id.toString()).param("v", id)
                .query(HistoryItem.class).list();
    }

    public record OdometerView(String reading, String kind, String reason, OffsetDateTime recorded_at,
                               String recorded_by_name) {
    }

    @Transactional(readOnly = true)
    public List<OdometerView> odometer(Actor actor, UUID id) {
        requireRow(actor, id, "view");
        return jdbc.sql("""
                        SELECT r.reading::text AS reading, r.kind, r.reason, r.recorded_at, u.display_name AS recorded_by_name
                          FROM odometer_readings r JOIN app_users u ON u.id = r.recorded_by
                         WHERE r.vehicle_id = :v ORDER BY r.recorded_at DESC, r.id DESC LIMIT 100
                        """).param("v", id).query(OdometerView.class).list();
    }

    // ---------------- apoio ----------------
    Row requireRow(Actor actor, UUID id, String action) {
        Row v = find(id).orElse(null);
        if (v == null || !v.organizationId().equals(actor.organizationId())) {
            authz.require(actor, RESOURCE, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, action, "vehicle", id.toString(), ref(v));
        return v;
    }

    static RecordRef ref(Row v) {
        return new RecordRef(v.organizationId(), v.branchId(), Sector.ENTREGAS, v.createdBy(), v.currentDriver());
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE v.id = :id").param("id", id).query(Row.class).optional();
    }

    Optional<Row> lock(UUID id) {
        jdbc.sql("SELECT id FROM vehicles WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return find(id);
    }

    VehicleView view(Row v) {
        LocalDate today = LocalDate.now(COMMERCIAL);
        List<Alert> alerts = new ArrayList<>();
        if (v.registrationValidUntil() != null && v.registrationValidUntil().isBefore(today)) {
            alerts.add(new Alert("REGISTRATION_EXPIRED", "Licenciamento vencido em " + v.registrationValidUntil()));
        }
        if (v.insuranceValidUntil() != null && v.insuranceValidUntil().isBefore(today)) {
            alerts.add(new Alert("INSURANCE_EXPIRED", "Seguro vencido em " + v.insuranceValidUntil()));
        }
        if (v.nextServiceKm() != null && v.odometerKm().compareTo(v.nextServiceKm()) >= 0) {
            alerts.add(new Alert("SERVICE_DUE", "Revisão prevista para " + v.nextServiceKm().toPlainString()
                    + " km (aviso; não bloqueia entrega)"));
        }
        return new VehicleView(v.id(), v.plate(), v.model(), Decimals.str(v.capacityQuantity()), v.capacityUnit(),
                v.status(), v.odometerKm().toPlainString(), v.nextServiceKm() == null ? null : v.nextServiceKm().toPlainString(),
                v.registrationValidUntil(), v.insuranceValidUntil(), v.notes(), v.branchName(), v.currentTripId(),
                v.currentTripNumber(), v.currentDriverName(), v.version(), v.createdAt(), alerts, consumption(v.id()));
    }

    /**
     * km/l só entre abastecimentos de tanque cheio: do primeiro cheio ao último cheio, soma os litros abastecidos
     * depois do primeiro (inclusive parciais). Menos de dois cheios → dados insuficientes, sem estimativa.
     */
    Consumption consumption(UUID vehicleId) {
        record Fill(BigDecimal odometer, BigDecimal liters, boolean fullTank) {
        }
        List<Fill> fills = jdbc.sql("""
                        SELECT odometer, liters, full_tank FROM vehicle_expenses
                         WHERE vehicle_id = :v AND kind = 'FUEL' AND state <> 'REJECTED'
                         ORDER BY odometer, created_at
                        """).param("v", vehicleId).query(Fill.class).list();
        int firstFull = -1;
        int lastFull = -1;
        int fullCount = 0;
        for (int i = 0; i < fills.size(); i++) {
            if (fills.get(i).fullTank()) {
                fullCount++;
                if (firstFull < 0) {
                    firstFull = i;
                }
                lastFull = i;
            }
        }
        if (fullCount < 2) {
            return new Consumption("INSUFFICIENT_DATA", null, null, null, fullCount,
                    "Consumo exige ao menos dois abastecimentos de tanque cheio.");
        }
        BigDecimal km = fills.get(lastFull).odometer().subtract(fills.get(firstFull).odometer());
        BigDecimal liters = BigDecimal.ZERO;
        for (int i = firstFull + 1; i <= lastFull; i++) {
            liters = liters.add(fills.get(i).liters());
        }
        if (km.signum() <= 0 || liters.signum() <= 0) {
            return new Consumption("INSUFFICIENT_DATA", null, null, null, fullCount,
                    "Abastecimentos sem quilometragem percorrida entre eles.");
        }
        return new Consumption("OK", km.divide(liters, 2, RoundingMode.HALF_UP).toPlainString(), km.toPlainString(),
                liters.stripTrailingZeros().toPlainString(), fullCount, null);
    }

    static String normalizePlate(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        return p.matches("^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$") ? p : null;
    }

    static BigDecimal parseKm(String raw, String field, boolean allowZero, List<FieldError> errors) {
        BigDecimal v = Decimals.parse(raw, 1, allowZero, field, errors);
        if (v != null && v.compareTo(new BigDecimal("99999999")) > 0) {
            errors.add(new FieldError(field, "Valor fora do limite"));
            return null;
        }
        return v;
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "vehicle", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
