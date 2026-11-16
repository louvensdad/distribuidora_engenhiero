package br.com.engenheiro.api.inventory;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Único ponto que altera saldo, valor e reservas (INV-03/INV-04). Sempre dentro da transação do comando,
 * com bloqueio da linha de saldo (SELECT ... FOR UPDATE) ou atualização condicional atômica.
 *
 * Custo médio móvel: valor contábil / quantidade física. Entrada soma o custo real (mercadoria + frete
 * apropriado); saída por venda ou perda usa o custo médio do momento; devolução ao fornecedor e estorno usam o
 * custo histórico do movimento de origem. Custo unitário não é arredondado antes de multiplicar.
 */
@Service
public class StockLedger {
    static final int COST_SCALE = 6;
    public static final int COST_SCALE_PUBLIC = COST_SCALE;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public StockLedger(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Balance(BigDecimal physical, BigDecimal reserved, BigDecimal value, long version) {
        public BigDecimal available() {
            return physical.subtract(reserved);
        }
    }

    public record Movement(UUID id, String kind, BigDecimal quantity, BigDecimal unitCost, BigDecimal totalCost,
                           BigDecimal balanceAfter, BigDecimal valueAfter) {
    }

    public record Source(String type, UUID id, int line) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Balance lock(UUID org, UUID branch, UUID product) {
        jdbc.sql("INSERT INTO inventory_balances (organization_id, branch_id, product_id) VALUES (:o, :b, :p) "
                        + "ON CONFLICT DO NOTHING")
                .param("o", org).param("b", branch).param("p", product).update();
        return jdbc.sql("SELECT physical, reserved, value, version FROM inventory_balances "
                        + "WHERE branch_id = :b AND product_id = :p FOR UPDATE")
                .param("b", branch).param("p", product).query(Balance.class).single();
    }

    /** Entrada com custo total real (mercadoria + frete apropriado). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Movement receive(Actor actor, UUID branch, UUID product, BigDecimal qty, BigDecimal totalCost,
                            String kind, Source src, UUID reversalOf, String reason) {
        requirePositive(qty);
        Balance b = lock(actor.organizationId(), branch, product);
        BigDecimal unit = totalCost.divide(qty, COST_SCALE, RoundingMode.HALF_UP);
        return apply(actor, branch, product, b, kind, qty, unit, totalCost, BigDecimal.ZERO, src, reversalOf, reason);
    }

    /**
     * Saída pelo custo médio (venda, perda). `fromReservation` consome reserva já feita; senão exige
     * disponível (físico − reservado) suficiente, para não tomar mercadoria reservada a outro pedido.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Movement issueAtAverage(Actor actor, UUID branch, UUID product, BigDecimal qty, String kind, Source src,
                                   BigDecimal fromReservation, String reason) {
        requirePositive(qty);
        Balance b = lock(actor.organizationId(), branch, product);
        checkAvailable(b, qty, fromReservation);
        BigDecimal cost = qty.compareTo(b.physical()) == 0 ? b.value()
                : b.value().multiply(qty).divide(b.physical(), COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal unit = b.value().divide(b.physical(), COST_SCALE, RoundingMode.HALF_UP);
        return apply(actor, branch, product, b, kind, qty.negate(), unit, cost.negate(),
                fromReservation == null ? BigDecimal.ZERO : fromReservation.negate(), src, null, reason);
    }

    /** Saída pelo custo histórico do movimento de origem (devolução ao fornecedor). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Movement issueAtCost(Actor actor, UUID branch, UUID product, BigDecimal qty, BigDecimal unitCost,
                                String kind, Source src, UUID reversalOf, String reason) {
        requirePositive(qty);
        Balance b = lock(actor.organizationId(), branch, product);
        checkAvailable(b, qty, null);
        BigDecimal cost = qty.multiply(unitCost).setScale(COST_SCALE, RoundingMode.HALF_UP);
        // O valor contábil nunca fica negativo; ao zerar o físico, sai todo o valor restante.
        if (qty.compareTo(b.physical()) == 0 || cost.compareTo(b.value()) > 0) {
            cost = b.value();
        }
        return apply(actor, branch, product, b, kind, qty.negate(), unitCost, cost.negate(), BigDecimal.ZERO, src,
                reversalOf, reason);
    }

    /** Reserva: diminui o disponível. Atualização condicional atômica: duas reservas concorrentes não negativam. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID reserve(Actor actor, UUID branch, UUID product, BigDecimal qty, String sourceType, UUID sourceId,
                        Instant expiresAt) {
        requirePositive(qty);
        record Existing(UUID id, BigDecimal quantity, String state) {
        }
        Optional<Existing> prior = jdbc.sql("SELECT id, quantity, state FROM stock_reservations "
                        + "WHERE source_type = :t AND source_id = :s")
                .param("t", sourceType).param("s", sourceId).query(Existing.class).optional();
        if (prior.isPresent()) {
            if (prior.get().quantity().compareTo(qty) == 0 && prior.get().state().equals("ACTIVE")) {
                return prior.get().id(); // mesma operação repetida
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Já existe reserva para esta operação.");
        }
        jdbc.sql("INSERT INTO inventory_balances (organization_id, branch_id, product_id) VALUES (:o, :b, :p) "
                        + "ON CONFLICT DO NOTHING")
                .param("o", actor.organizationId()).param("b", branch).param("p", product).update();
        int n = jdbc.sql("""
                        UPDATE inventory_balances SET reserved = reserved + :q, version = version + 1,
                                                      updated_at = clock_timestamp()
                         WHERE branch_id = :b AND product_id = :p AND physical - reserved >= :q
                        """)
                .param("q", qty).param("b", branch).param("p", product).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT, "Estoque disponível insuficiente para reservar.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO stock_reservations (id, organization_id, branch_id, product_id, quantity, remaining,
                                                        source_type, source_id, expires_at, created_by)
                        VALUES (:id, :o, :b, :p, :q, :q, :t, :s, :exp, :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", branch).param("p", product)
                .param("q", qty).param("t", sourceType).param("s", sourceId)
                .param("exp", expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
                .param("by", actor.userId()).update();
        return id;
    }

    /** Libera (ou expira) reserva ativa; repetir sobre reserva já encerrada não altera o saldo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(UUID reservationId, String finalState) {
        if (!finalState.equals("RELEASED") && !finalState.equals("EXPIRED")) {
            throw new IllegalArgumentException(finalState);
        }
        record Res(UUID branchId, UUID productId, BigDecimal quantity) {
        }
        Optional<Res> r = jdbc.sql("""
                        UPDATE stock_reservations r SET state = :st, closed_at = clock_timestamp(), remaining = 0
                          FROM (SELECT id, remaining FROM stock_reservations WHERE id = :id FOR UPDATE) old
                         WHERE r.id = old.id AND r.state = 'ACTIVE'
                         RETURNING r.branch_id, r.product_id, old.remaining AS quantity
                        """)
                .param("st", finalState).param("id", reservationId).query(Res.class).optional();
        r.ifPresent(x -> jdbc.sql("UPDATE inventory_balances SET reserved = reserved - :q, version = version + 1, "
                        + "updated_at = clock_timestamp() WHERE branch_id = :b AND product_id = :p")
                .param("q", x.quantity()).param("b", x.branchId()).param("p", x.productId()).update());
        return r.isPresent();
    }

    /** Cumprimento total: baixa o saldo restante da reserva pelo custo médio e encerra a reserva. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Movement fulfillReservation(Actor actor, UUID reservationId, Source src) {
        BigDecimal remaining = jdbc.sql("SELECT remaining FROM stock_reservations WHERE id = :id AND state = 'ACTIVE'")
                .param("id", reservationId).query(BigDecimal.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TRANSITION, "Reserva não está ativa."));
        return consumeReservation(actor, reservationId, remaining, src);
    }

    /**
     * Cumprimento parcial (F04): baixa [qty] da reserva pelo custo médio; a reserva fica ativa com o restante ou é
     * encerrada (CONSUMED) quando zera. Bloqueio da reserva evita consumo duplo concorrente.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Movement consumeReservation(Actor actor, UUID reservationId, BigDecimal qty, Source src) {
        requirePositive(qty);
        record Res(UUID branchId, UUID productId, BigDecimal remaining) {
        }
        Res r = jdbc.sql("""
                        SELECT branch_id, product_id, remaining FROM stock_reservations
                         WHERE id = :id AND state = 'ACTIVE' AND organization_id = :o FOR UPDATE
                        """)
                .param("id", reservationId).param("o", actor.organizationId()).query(Res.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TRANSITION, "Reserva não está ativa."));
        if (qty.compareTo(r.remaining()) > 0) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT, "Quantidade maior que a reservada para esta linha.");
        }
        BigDecimal left = r.remaining().subtract(qty);
        jdbc.sql("""
                        UPDATE stock_reservations SET remaining = :left,
                               state = CASE WHEN :left = 0 THEN 'CONSUMED' ELSE state END,
                               closed_at = CASE WHEN :left = 0 THEN clock_timestamp() ELSE closed_at END
                         WHERE id = :id
                        """)
                .param("left", left).param("id", reservationId).update();
        return issueAtAverage(actor, r.branchId(), r.productId(), qty, "SALE_ISSUE", src, qty, null);
    }

    private static void checkAvailable(Balance b, BigDecimal qty, BigDecimal fromReservation) {
        BigDecimal usable = b.available().add(fromReservation == null ? BigDecimal.ZERO : fromReservation);
        if (fromReservation != null && fromReservation.compareTo(b.reserved()) > 0 || usable.compareTo(qty) < 0) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT,
                    "Estoque disponível insuficiente (físico " + b.physical().stripTrailingZeros().toPlainString()
                            + ", reservado " + b.reserved().stripTrailingZeros().toPlainString() + ").");
        }
    }

    private static void requirePositive(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("quantidade deve ser positiva");
        }
    }

    private Movement apply(Actor actor, UUID branch, UUID product, Balance b, String kind, BigDecimal qty,
                           BigDecimal unitCost, BigDecimal totalCost, BigDecimal reservedDelta, Source src,
                           UUID reversalOf, String reason) {
        BigDecimal physical = b.physical().add(qty);
        BigDecimal value = physical.signum() == 0 ? BigDecimal.ZERO : b.value().add(totalCost);
        if (physical.signum() < 0 || value.signum() < 0) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT, "Operação deixaria o estoque negativo.");
        }
        jdbc.sql("""
                        UPDATE inventory_balances SET physical = :ph, value = :v, reserved = reserved + :rd,
                                                      version = version + 1, updated_at = clock_timestamp()
                         WHERE branch_id = :b AND product_id = :p
                        """)
                .param("ph", physical).param("v", value).param("rd", reservedDelta).param("b", branch)
                .param("p", product).update();
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO stock_movements (id, organization_id, branch_id, product_id, kind, quantity, unit_cost,
                                                     total_cost, balance_after, value_after, source_type, source_id,
                                                     source_line, reversal_of, reason, created_by)
                        VALUES (:id, :o, :b, :p, :k, :q, :uc, :tc, :ba, :va, :st, :sid, :sl, :rev, :reason, :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", branch).param("p", product)
                .param("k", kind).param("q", qty).param("uc", unitCost).param("tc", totalCost)
                .param("ba", physical).param("va", value).param("st", src.type()).param("sid", src.id())
                .param("sl", src.line()).param("rev", reversalOf).param("reason", reason)
                .param("by", actor.userId()).update();
        // Evento para as outras superfícies/consumidores; sem dado pessoal nem custo.
        jdbc.sql("""
                        INSERT INTO outbox_events (id, organization_id, aggregate_type, aggregate_id, aggregate_version,
                                                   event_type, payload)
                        VALUES (:id, :o, 'inventory_balance', :agg, :ver, 'inventory.movement.recorded', CAST(:p AS jsonb))
                        """)
                .param("id", Ids.newId()).param("o", actor.organizationId()).param("agg", branch + ":" + product)
                .param("ver", b.version() + 1)
                .param("p", json.writeValueAsString(Map.of("movement_id", id.toString(), "kind", kind,
                        "branch_id", branch.toString(), "product_id", product.toString())))
                .update();
        return new Movement(id, kind, qty, unitCost, totalCost, physical, value);
    }
}
