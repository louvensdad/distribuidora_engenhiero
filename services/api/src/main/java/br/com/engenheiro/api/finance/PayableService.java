package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Cursor;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Obrigações a pagar. Nesta etapa nascem da confirmação do recebimento (compra de estoque: passivo, não despesa).
 * Liquidação é da F06. Sem recurso `payables` no contrato de permissões: só administrador consulta (ADR-017).
 */
@Service
public class PayableService {
    private static final ZoneId COMMERCIAL = ZoneId.of("America/Sao_Paulo");
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public PayableService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    public record PayableView(UUID id, String source_type, UUID source_id, UUID supplier_id, String supplier_name,
                              String description, LocalDate competence, String amount, String balance,
                              LocalDate due_date, String state, String branch, OffsetDateTime created_at) {
    }

    public record Page(List<PayableView> items, String next_cursor) {
    }

    /** Uma obrigação por origem (unicidade no banco). Valor zero (bonificação) não gera obrigação. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForReceipt(Actor actor, UUID branchId, UUID receiptId, UUID supplierId, String supplierName,
                                 String documentNumber, BigDecimal amount, LocalDate dueDate) {
        if (amount.signum() <= 0) {
            return null;
        }
        UUID id = Ids.newId();
        String desc = "Compra de mercadoria — " + supplierName + (documentNumber == null ? "" : " — " + documentNumber);
        jdbc.sql("""
                        INSERT INTO payables (id, organization_id, branch_id, source_type, source_id, supplier_id,
                                              description, competence, amount, balance, due_date, created_by)
                        VALUES (:id, :o, :b, 'inventory_receipt', :src, :sup, :d, :comp, :a, :a, :due, :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", branchId).param("src", receiptId)
                .param("sup", supplierId).param("d", desc.length() > 200 ? desc.substring(0, 200) : desc)
                .param("comp", LocalDate.now(COMMERCIAL)).param("a", Decimals.money(amount)).param("due", dueDate)
                .param("by", actor.userId()).update();
        audit.record(new AuditEntry(actor.organizationId(), branchId, actor.userId(), "USER", actor.clientId(),
                "payables.create", "payable", id.toString(), "SUCCESS", null,
                Map.of("source_type", "inventory_receipt", "source_id", receiptId.toString()), "api"));
        return id;
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String state, int limit, String cursor) {
        authz.requireAdmin(actor, "payables.view");
        Cursor.checkLimit(limit);
        if (state != null && !List.of("OPEN", "PARTIALLY_PAID", "PAID", "CANCELLED").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        Cursor.Key c = Cursor.decode(cursor);
        List<PayableView> rows = jdbc.sql("""
                        SELECT p.id, p.source_type, p.source_id, p.supplier_id, s.name AS supplier_name, p.description,
                               p.competence, CAST(p.amount AS text) AS amount, CAST(p.balance AS text) AS balance,
                               p.due_date, p.state, b.name AS branch, p.created_at
                          FROM payables p JOIN branches b ON b.id = p.branch_id
                          LEFT JOIN suppliers s ON s.id = p.supplier_id
                         WHERE p.organization_id = :o
                           AND (CAST(:st AS text) IS NULL OR p.state = CAST(:st AS text))
                           AND (CAST(:cts AS text) IS NULL OR (p.created_at, p.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))
                         ORDER BY p.created_at DESC, p.id DESC LIMIT :lim
                        """)
                .param("o", actor.organizationId()).param("st", state).param("cts", c == null ? null : c.sort())
                .param("cid", c == null ? null : c.id()).param("lim", limit + 1).query(PayableView.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().created_at().toString(), rows.getLast().id());
        }
        return new Page(rows, next);
    }
}
