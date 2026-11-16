package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Livro financeiro gerencial (arquitetura/02): cada evento gera um lançamento único (source + evento) e balanceado
 * (trigger diferido). Conta CLIENTES: saldo credor = adiantamento de cliente; devedor = a receber.
 * Não substitui a contabilidade oficial do contador.
 */
@Service
public class Journal {
    private static final ZoneId COMMERCIAL = ZoneId.of("America/Sao_Paulo");
    private final JdbcClient jdbc;

    public Journal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Line(String account, BigDecimal debit, BigDecimal credit) {
        public static Line debit(String account, BigDecimal v) {
            return new Line(account, v, BigDecimal.ZERO);
        }

        public static Line credit(String account, BigDecimal v) {
            return new Line(account, BigDecimal.ZERO, v);
        }
    }

    /** Linhas de valor zero são omitidas; um lançamento sem linhas não é gravado (retorna null). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(Actor actor, UUID branchId, String sourceType, UUID sourceId, String eventKind, String memo,
                     UUID reversalOf, List<Line> lines) {
        List<Line> nonZero = new ArrayList<>();
        for (Line l : lines) {
            if (l.debit().signum() != 0 || l.credit().signum() != 0) {
                nonZero.add(l);
            }
        }
        if (nonZero.isEmpty()) {
            return null;
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO journal_entries (id, organization_id, branch_id, source_type, source_id, event_kind,
                                                     entry_date, reversal_of, memo, created_by)
                        VALUES (:id, :o, :b, :st, :sid, :ek, :d, :rev, :memo, :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", branchId).param("st", sourceType)
                .param("sid", sourceId).param("ek", eventKind).param("d", LocalDate.now(COMMERCIAL))
                .param("rev", reversalOf).param("memo", memo).param("by", actor.userId()).update();
        for (Line l : nonZero) {
            jdbc.sql("INSERT INTO journal_lines (id, entry_id, account, debit, credit) VALUES (:id, :e, :a, :d, :c)")
                    .param("id", Ids.newId()).param("e", id).param("a", l.account())
                    .param("d", l.debit().setScale(2, java.math.RoundingMode.UNNECESSARY))
                    .param("c", l.credit().setScale(2, java.math.RoundingMode.UNNECESSARY)).update();
        }
        return id;
    }
}
