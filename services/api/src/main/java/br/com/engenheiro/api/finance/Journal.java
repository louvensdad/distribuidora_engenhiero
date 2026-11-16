package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Livro financeiro gerencial (arquitetura/02): cada evento gera um lançamento único (source + evento) e balanceado
 * (trigger diferido), somente inclusão. Conta CLIENTES: saldo credor = adiantamento de cliente; devedor = a receber.
 * F06: linha pode apontar a conta financeira (caixa/banco/adquirente); sem conta, vale a conta padrão do livro.
 * Competência (data civil) separada da data do lançamento; mês fechado recusa lançamento (aqui e no banco).
 * Correção só por reversão vinculada (uma por lançamento). Não substitui a contabilidade oficial do contador.
 */
@Service
public class Journal {
    public static final ZoneId COMMERCIAL = ZoneId.of("America/Sao_Paulo");
    private final JdbcClient jdbc;

    public Journal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Line(String account, BigDecimal debit, BigDecimal credit, UUID finAccountId) {
        public static Line debit(String account, BigDecimal v) {
            return new Line(account, v, BigDecimal.ZERO, null);
        }

        public static Line credit(String account, BigDecimal v) {
            return new Line(account, BigDecimal.ZERO, v, null);
        }

        /** Linha numa conta financeira específica (caixa da empresa, banco, adquirente). */
        public Line at(UUID finAccount) {
            return new Line(account, debit, credit, finAccount);
        }
    }

    /** Linhas de valor zero são omitidas; um lançamento sem linhas não é gravado (retorna null). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(Actor actor, UUID branchId, String sourceType, UUID sourceId, String eventKind, String memo,
                     UUID reversalOf, List<Line> lines) {
        return post(actor, branchId, sourceType, sourceId, eventKind, memo, reversalOf, null, lines);
    }

    /** Com competência explícita (data civil do fato gerador); null = data do lançamento. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(Actor actor, UUID branchId, String sourceType, UUID sourceId, String eventKind, String memo,
                     UUID reversalOf, LocalDate competence, List<Line> lines) {
        List<Line> nonZero = new ArrayList<>();
        for (Line l : lines) {
            if (l.debit().signum() != 0 || l.credit().signum() != 0) {
                nonZero.add(l);
            }
        }
        if (nonZero.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now(COMMERCIAL);
        requireOpen(actor.organizationId(), today);
        if (competence != null) {
            requireOpen(actor.organizationId(), competence);
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO journal_entries (id, organization_id, branch_id, source_type, source_id, event_kind,
                                                         entry_date, competence, reversal_of, memo, created_by)
                            VALUES (:id, :o, :b, :st, :sid, :ek, :d, :comp, :rev, :memo, :by)
                            """)
                    .param("id", id).param("o", actor.organizationId()).param("b", branchId).param("st", sourceType)
                    .param("sid", sourceId).param("ek", eventKind).param("d", today).param("comp", competence)
                    .param("rev", reversalOf).param("memo", memo).param("by", actor.userId()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Lançamento já registrado ou já revertido.");
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("período fechado")) {
                throw new ApiException(ErrorCode.PERIOD_CLOSED, "Período fechado: peça a reabertura aprovada.");
            }
            throw e;
        }
        for (Line l : nonZero) {
            jdbc.sql("""
                            INSERT INTO journal_lines (id, entry_id, account, debit, credit, fin_account_id)
                            VALUES (:id, :e, :a, :d, :c, :f)
                            """)
                    .param("id", Ids.newId()).param("e", id).param("a", l.account())
                    .param("d", l.debit().setScale(2, java.math.RoundingMode.UNNECESSARY))
                    .param("c", l.credit().setScale(2, java.math.RoundingMode.UNNECESSARY))
                    .param("f", l.finAccountId()).update();
        }
        return id;
    }

    /**
     * Reversão exata do lançamento de origem (débito ↔ crédito, mesmas contas financeiras e competência do estorno =
     * hoje). Só uma reversão por lançamento (índice único). Retorna o id da reversão, ou null se não havia origem.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID reverse(Actor actor, String sourceType, UUID sourceId, String originalEvent, String reversalEvent,
                        String memo) {
        record Orig(UUID id, UUID branchId) {
        }
        Orig o = jdbc.sql("""
                        SELECT id, branch_id FROM journal_entries
                         WHERE source_type = :st AND source_id = :s AND event_kind = :ek AND organization_id = :org
                        """).param("st", sourceType).param("s", sourceId).param("ek", originalEvent)
                .param("org", actor.organizationId()).query(Orig.class).optional().orElse(null);
        if (o == null) {
            return null;
        }
        List<Line> mirrored = jdbc.sql("""
                        SELECT account, credit AS debit, debit AS credit, fin_account_id FROM journal_lines WHERE entry_id = :e
                        """).param("e", o.id()).query(Line.class).list();
        return post(actor, o.branchId(), sourceType, sourceId, reversalEvent, memo, o.id(), mirrored);
    }

    /** Mês fechado → 409 PERIOD_CLOSED (o gatilho do banco repete a regra). */
    public void requireOpen(UUID organizationId, LocalDate date) {
        Boolean closed = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM fin_periods WHERE organization_id = :o AND state = 'CLOSED'
                                        AND month = date_trunc('month', CAST(:d AS date))::date)
                        """).param("o", organizationId).param("d", date).query(Boolean.class).single();
        if (closed) {
            throw new ApiException(ErrorCode.PERIOD_CLOSED, "Período " + date.getMonthValue() + "/" + date.getYear()
                    + " fechado: peça a reabertura aprovada.");
        }
    }
}
