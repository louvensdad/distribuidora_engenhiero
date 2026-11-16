package br.com.engenheiro.api.orders;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caixa próprio por turno (ORD-09): fundo, entradas/saídas em dinheiro, esperado, contado, diferença com justificativa
 * e conferente (administrador, diferente do operador — ADR-018). Troco nunca entra como receita nem despesa: só o
 * valor do pedido entra no caixa. O saldo da empresa não é exposto ao operador.
 */
@Service
public class CashService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;

    public CashService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
    }

    public record OpenCommand(String opening_float) {
    }

    public record CloseCommand(String counted, String justification) {
    }

    public record ReviewCommand(String note) {
    }

    public record SessionView(UUID id, String state, String operator_name, String branch, String opening_float,
                              String cash_in, String cash_out, String expected, String counted, String difference,
                              String justification, OffsetDateTime opened_at, OffsetDateTime closed_at,
                              String reviewed_by_name, OffsetDateTime reviewed_at, String review_note, long version) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, UUID operatorId, String operatorName,
               String state, BigDecimal openingFloat, BigDecimal expected, BigDecimal counted, BigDecimal difference,
               String justification, OffsetDateTime openedAt, OffsetDateTime closedAt, String reviewedByName,
               OffsetDateTime reviewedAt, String reviewNote, long version, BigDecimal cashIn, BigDecimal cashOut) {
    }

    @Transactional
    public SessionView open(Actor actor, String idemKey, OpenCommand cmd) {
        authz.require(actor, "own_cash", "create");
        return idem.execute(actor, "cash.open", idemKey, cmd, SessionView.class, () -> {
            List<FieldError> errors = new java.util.ArrayList<>();
            BigDecimal opening = Decimals.parse(cmd == null ? null : cmd.opening_float(), Decimals.MONEY_SCALE, true,
                    "opening_float", errors);
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o fundo de troco.", errors);
            }
            UUID id = Ids.newId();
            try {
                jdbc.sql("""
                                INSERT INTO cash_sessions (id, organization_id, branch_id, operator_id, state, opening_float)
                                VALUES (:id, :o, :b, :u, 'OPEN', :f)
                                """)
                        .param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                        .param("u", actor.userId()).param("f", opening).update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Você já tem um caixa aberto nesta unidade.");
            }
            audit.record(entry(actor, "cash.open", id, Map.of("opening_float", opening.toPlainString())));
            return view(find(id).orElseThrow());
        });
    }

    @Transactional(readOnly = true)
    public Optional<SessionView> current(Actor actor) {
        authz.require(actor, "own_cash", "view");
        return jdbc.sql(SELECT + " WHERE s.operator_id = :u AND s.branch_id = :b AND s.state = 'OPEN'")
                .param("u", actor.userId()).param("b", actor.branchId()).query(Row.class).optional().map(this::view);
    }

    /** Sessões: o operador vê as próprias; o administrador vê todas (conferência). */
    @Transactional(readOnly = true)
    public List<SessionView> list(Actor actor) {
        if (!actor.admin()) {
            authz.require(actor, "own_cash", "view");
        }
        return jdbc.sql(SELECT + " WHERE s.organization_id = :o AND (CAST(:u AS uuid) IS NULL OR s.operator_id = :u) "
                        + "ORDER BY s.opened_at DESC LIMIT 50")
                .param("o", actor.organizationId()).param("u", actor.admin() ? null : actor.userId())
                .query(Row.class).list().stream().map(this::view).toList();
    }

    @Transactional
    public SessionView close(Actor actor, UUID id, String idemKey, long expectedVersion, CloseCommand cmd) {
        authz.require(actor, "own_cash", "close");
        Row s = lock(id);
        if (!s.operatorId().equals(actor.userId()) || !s.organizationId().equals(actor.organizationId())) {
            throw ApiException.notFound(); // caixa de outra pessoa não é visível para fechamento
        }
        return idem.execute(actor, "cash.close", idemKey, Map.of("id", id.toString(), "body", cmd == null ? Map.of() : cmd,
                "version", expectedVersion), SessionView.class, () -> {
            if (!s.state().equals("OPEN")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Caixa já fechado.");
            }
            List<FieldError> errors = new java.util.ArrayList<>();
            BigDecimal counted = Decimals.parse(cmd == null ? null : cmd.counted(), Decimals.MONEY_SCALE, true, "counted", errors);
            String just = cmd == null ? null : Text.trim(cmd.justification());
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o valor contado.", errors);
            }
            BigDecimal expected = s.openingFloat().add(s.cashIn()).subtract(s.cashOut());
            BigDecimal diff = counted.subtract(expected);
            if (diff.signum() != 0 && (just == null || just.length() > 500)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Há diferença de " + diff.toPlainString() + "; justifique.",
                        List.of(new FieldError("justification", "Justificativa obrigatória quando há diferença")));
            }
            int n = jdbc.sql("""
                            UPDATE cash_sessions SET state = 'CLOSED', expected = :e, counted = :c, difference = :d,
                                   justification = :j, closed_at = now(), version = version + 1
                             WHERE id = :id AND version = :v
                            """)
                    .param("e", expected).param("c", counted).param("d", diff).param("j", just).param("id", id)
                    .param("v", expectedVersion).update();
            if (n == 0) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "O caixa mudou (novo recebimento?). Recarregue.");
            }
            Map<String, Object> ch = new HashMap<>();
            ch.put("expected", expected.toPlainString());
            ch.put("counted", counted.toPlainString());
            ch.put("difference", diff.toPlainString());
            audit.record(entry(actor, "cash.close", id, ch));
            return view(find(id).orElseThrow());
        });
    }

    @Transactional
    public SessionView review(Actor actor, UUID id, long expectedVersion, ReviewCommand cmd) {
        authz.requireAdmin(actor, "own_cash.review");
        Row s = lock(id);
        if (!s.organizationId().equals(actor.organizationId())) {
            throw ApiException.notFound();
        }
        if (!s.state().equals("CLOSED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só caixa fechado pode ser conferido.");
        }
        if (s.operatorId().equals(actor.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Quem operou o caixa não confere o próprio fechamento.");
        }
        String note = cmd == null ? null : Text.trim(cmd.note());
        int n = jdbc.sql("""
                        UPDATE cash_sessions SET state = 'REVIEWED', reviewed_by = :by, reviewed_at = now(), review_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """)
                .param("by", actor.userId()).param("n", note).param("id", id).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O caixa mudou. Recarregue.");
        }
        audit.record(entry(actor, "cash.review", id, Map.of("difference", s.difference().toPlainString())));
        return view(find(id).orElseThrow());
    }

    /** Sessão aberta do operador, bloqueada, para registrar dinheiro. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requireOpenSession(Actor actor) {
        return jdbc.sql("SELECT id FROM cash_sessions WHERE operator_id = :u AND branch_id = :b AND state = 'OPEN' FOR UPDATE")
                .param("u", actor.userId()).param("b", actor.branchId()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TRANSITION,
                        "Abra o seu caixa antes de receber ou devolver dinheiro."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void movement(UUID sessionId, String direction, BigDecimal amount, String sourceType, UUID sourceId) {
        jdbc.sql("""
                        INSERT INTO cash_movements (id, cash_session_id, direction, amount, source_type, source_id)
                        VALUES (:id, :s, :d, :a, :st, :sid)
                        """)
                .param("id", Ids.newId()).param("s", sessionId).param("d", direction).param("a", amount)
                .param("st", sourceType).param("sid", sourceId).update();
        jdbc.sql("UPDATE cash_sessions SET version = version + 1 WHERE id = :id").param("id", sessionId).update();
    }

    private static final String SELECT = """
            SELECT s.id, s.organization_id, s.branch_id, b.name AS branch_name, s.operator_id, u.display_name AS operator_name,
                   s.state, s.opening_float, s.expected, s.counted, s.difference, s.justification, s.opened_at,
                   s.closed_at, r.display_name AS reviewed_by_name, s.reviewed_at, s.review_note, s.version,
                   (SELECT coalesce(sum(amount), 0) FROM cash_movements m WHERE m.cash_session_id = s.id AND m.direction = 'IN') AS cash_in,
                   (SELECT coalesce(sum(amount), 0) FROM cash_movements m WHERE m.cash_session_id = s.id AND m.direction = 'OUT') AS cash_out
              FROM cash_sessions s JOIN branches b ON b.id = s.branch_id JOIN app_users u ON u.id = s.operator_id
              LEFT JOIN app_users r ON r.id = s.reviewed_by
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(Row.class).optional();
    }

    private Row lock(UUID id) {
        jdbc.sql("SELECT id FROM cash_sessions WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional()
                .orElseThrow(ApiException::notFound);
        return find(id).orElseThrow();
    }

    private SessionView view(Row s) {
        BigDecimal expected = s.expected() != null ? s.expected() : s.openingFloat().add(s.cashIn()).subtract(s.cashOut());
        return new SessionView(s.id(), s.state(), s.operatorName(), s.branchName(), Decimals.moneyStr(s.openingFloat()),
                Decimals.moneyStr(s.cashIn()), Decimals.moneyStr(s.cashOut()), Decimals.moneyStr(expected),
                Decimals.moneyStr(s.counted()), Decimals.moneyStr(s.difference()), s.justification(), s.openedAt(),
                s.closedAt(), s.reviewedByName(), s.reviewedAt(), s.reviewNote(), s.version());
    }

    private static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "cash_session", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
