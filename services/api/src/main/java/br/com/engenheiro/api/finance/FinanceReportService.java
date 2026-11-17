package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resultado gerencial por competência (cerebro/03 "Fechamento por categoria"), separado da posição de caixa (FIN-04);
 * cada total abre as linhas do livro que o formam (drilldown) e a conciliação confere subledgers contra o livro.
 * Ver: `reports.view`. Os números vêm só do livro — tela, gráfico e arquivo usam este mesmo cálculo.
 */
@Service
public class FinanceReportService {
    /** Categoria de despesa do resultado → contas do livro (ordem de exibição). */
    public static final Map<String, List<String>> EXPENSE_CATEGORIES = new LinkedHashMap<>();

    static {
        EXPENSE_CATEGORIES.put("FUNCIONARIOS", List.of("DESPESAS_FUNCIONARIOS"));
        EXPENSE_CATEGORIES.put("VEICULOS", List.of("DESPESAS_VEICULOS"));
        EXPENSE_CATEGORIES.put("ESTRUTURA", List.of("DESPESAS_ESTRUTURA"));
        EXPENSE_CATEGORIES.put("IMPOSTOS_TAXAS", List.of("DESPESAS_IMPOSTOS", "TAXAS_CARTAO"));
        EXPENSE_CATEGORIES.put("OUTRAS", List.of("DESPESAS_OUTRAS", "DIFERENCAS_CAIXA"));
    }

    static final List<String> LOSS_ACCOUNTS = List.of("PERDAS_ESTOQUE", "AJUSTES_ESTOQUE");

    /**
     * Versão da política do resultado gerencial (ordem/composição das linhas, categorias, perdas e base por competência).
     * Gravada no retrato do período fechado; mudar a regra exige nova versão, sem reescrever meses fechados (ADR-024).
     */
    public static final String POLICY_VERSION = "RESULTADO-GERENCIAL-1";

    /** Contas valoradas a custo de compra: abrir as linhas exige o campo `purchase_cost`. */
    public static final Set<String> COST_ACCOUNTS = Set.of("CMV", "ESTOQUE", "PERDAS_ESTOQUE", "AJUSTES_ESTOQUE");

    /** Campos do fechamento ocultados juntos (ver {@link #closingVisible}). */
    public static final List<String> CLOSING_FIELDS = List.of("cogs", "losses", "costs_expenses_losses", "profit",
            "margin_percent");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final FinAccountService accounts;

    public FinanceReportService(JdbcClient jdbc, AuthorizationService authz, FinAccountService accounts) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.accounts = accounts;
    }

    // ---------------- cálculo puro (testado contra testes/dados-financeiros.json) ----------------
    public record Result(BigDecimal grossRevenue, BigDecimal discounts, BigDecimal returns, BigDecimal netRevenue,
                         BigDecimal cogs, Map<String, BigDecimal> expenses, BigDecimal expensesTotal, BigDecimal losses,
                         BigDecimal costsExpensesLosses, BigDecimal profit, BigDecimal marginPercent) {
    }

    /** Receita líquida − CMV − despesas por categoria − perdas = resultado; margem sobre a receita líquida. */
    public static Result compute(BigDecimal gross, BigDecimal discounts, BigDecimal returns, BigDecimal cogs,
                                 Map<String, BigDecimal> expenses, BigDecimal losses) {
        BigDecimal net = gross.subtract(discounts).subtract(returns);
        BigDecimal exp = expenses.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = cogs.add(exp).add(losses);
        BigDecimal profit = net.subtract(total);
        BigDecimal margin = net.signum() == 0 ? null
                : profit.multiply(BigDecimal.valueOf(100)).divide(net, 2, RoundingMode.HALF_UP);
        return new Result(gross, discounts, returns, net, cogs, expenses, exp, losses, total, profit, margin);
    }

    // ---------------- contrato ----------------
    public record Item(String key, String label, String amount, List<String> accounts) {
    }

    public record Summary(LocalDate from, LocalDate to, String basis, String policy_version, List<String> hidden,
                          String gross_revenue, String discounts,
                          String returns, String net_revenue, String cogs, List<Item> expenses, String expenses_total,
                          String losses, String costs_expenses_losses, String profit, String margin_percent,
                          String owner_withdrawals, CashPosition cash, List<Item> drill, String note) {
    }

    public record CashPosition(String available, List<Item> accounts, String receivables_open, String card_receivables_open,
                               String payables_open, String customer_advances, String note) {
    }

    public record LedgerLine(UUID entry_id, LocalDate entry_date, LocalDate competence, String event_kind, String memo,
                             String source_type, UUID source_id, String account, String debit, String credit,
                             String fin_account, String created_by_name, OffsetDateTime created_at, UUID reversal_of,
                             String origin_type, UUID origin_id, Long origin_number, int documents, UUID document_id) {
    }

    public record Lines(List<String> accounts, LocalDate from, LocalDate to, String basis, String total_debit,
                        String total_credit, String net, List<LedgerLine> items) {
    }

    public record Sub(String category, String subcategory, String amount, int entries) {
    }

    public record Categories(LocalDate from, LocalDate to, List<Item> categories, List<Sub> subcategories, String total) {
    }

    public record Check(String code, String label, String expected, String actual, String difference, String status,
                        String explanation) {
    }

    public record Reconciliation(OffsetDateTime checked_at, String status, List<Check> checks) {
    }

    // ---------------- resultado ----------------
    @Transactional
    public Summary summary(Actor actor, LocalDate from, LocalDate to) {
        requireOrganization(actor);
        Summary s = summaryUnchecked(actor.organizationId(), range(from, to)[0], range(from, to)[1]);
        return closingVisible(actor) ? s : masked(s);
    }

    /**
     * O fechamento (CMV, perdas, total de custos e lucro/margem) só aparece a quem vê custo de compra E margem: com a
     * receita, as despesas e qualquer um desses números, os outros saem por subtração (ADR-024).
     */
    public boolean closingVisible(Actor actor) {
        return authz.canSeeField(actor, FieldClass.PURCHASE_COST) && authz.canSeeField(actor, FieldClass.MARGIN);
    }

    /** Resumo consolidado (com a posição de caixa da empresa) exige `reports.view` no escopo da organização. */
    void requireOrganization(Actor actor) {
        if (!authz.require(actor, "reports", "view").scopes().contains(Scope.ORGANIZATION)) {
            throw ApiException.forbidden();
        }
    }

    static Summary masked(Summary s) {
        return new Summary(s.from(), s.to(), s.basis(), s.policy_version(), CLOSING_FIELDS, s.gross_revenue(), s.discounts(),
                s.returns(), s.net_revenue(), null, s.expenses(), s.expenses_total(), null, null, null, null,
                s.owner_withdrawals(), s.cash(),
                s.drill().stream().filter(i -> !i.key().equals("cogs") && !i.key().equals("losses")).toList(), s.note());
    }

    public Summary summaryUnchecked(UUID org, LocalDate f, LocalDate t) {
        Map<String, BigDecimal> bal = balancesByCompetence(org, f, t, null);
        BigDecimal gross = bal.getOrDefault("RECEITA_VENDAS", BigDecimal.ZERO).negate();
        BigDecimal disc = bal.getOrDefault("DESCONTOS_CONCEDIDOS", BigDecimal.ZERO);
        BigDecimal ret = bal.getOrDefault("DEVOLUCOES_VENDAS", BigDecimal.ZERO);
        BigDecimal cogs = bal.getOrDefault("CMV", BigDecimal.ZERO);
        Map<String, BigDecimal> exp = new LinkedHashMap<>();
        List<Item> expItems = new ArrayList<>();
        for (Map.Entry<String, List<String>> c : EXPENSE_CATEGORIES.entrySet()) {
            BigDecimal v = c.getValue().stream().map(a -> bal.getOrDefault(a, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            exp.put(c.getKey(), v);
            expItems.add(new Item(c.getKey(), label(c.getKey()), Decimals.moneyStr(v), c.getValue()));
        }
        BigDecimal losses = LOSS_ACCOUNTS.stream().map(a -> bal.getOrDefault(a, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        Result r = compute(gross, disc, ret, cogs, exp, losses);
        List<Item> drill = List.of(
                new Item("gross_revenue", "Vendas cumpridas (bruto)", Decimals.moneyStr(gross), List.of("RECEITA_VENDAS")),
                new Item("discounts", "Descontos concedidos", Decimals.moneyStr(disc), List.of("DESCONTOS_CONCEDIDOS")),
                new Item("returns", "Devoluções de vendas", Decimals.moneyStr(ret), List.of("DEVOLUCOES_VENDAS")),
                new Item("cogs", "Custo da mercadoria vendida (CMV)", Decimals.moneyStr(cogs), List.of("CMV")),
                new Item("losses", "Perdas e ajustes de estoque", Decimals.moneyStr(losses), LOSS_ACCOUNTS),
                new Item("owner_withdrawals", "Retiradas do sócio (fora do resultado)",
                        Decimals.moneyStr(bal.getOrDefault("RETIRADAS_SOCIOS", BigDecimal.ZERO)), List.of("RETIRADAS_SOCIOS")));
        return new Summary(f, t, "COMPETENCE", POLICY_VERSION, List.of(), Decimals.moneyStr(gross), Decimals.moneyStr(disc), Decimals.moneyStr(ret),
                Decimals.moneyStr(r.netRevenue()), Decimals.moneyStr(cogs), expItems, Decimals.moneyStr(r.expensesTotal()),
                Decimals.moneyStr(losses), Decimals.moneyStr(r.costsExpensesLosses()), Decimals.moneyStr(r.profit()),
                r.marginPercent() == null ? null : r.marginPercent().toPlainString(),
                Decimals.moneyStr(bal.getOrDefault("RETIRADAS_SOCIOS", BigDecimal.ZERO)), cash(org, t), drill,
                "Resultado gerencial por competência; não é a demonstração contábil do contador. Saldo em caixa não é lucro.");
    }

    /** Posição de caixa até o fim (exclusivo) do período: saldos por conta, separados do resultado. */
    public CashPosition cash(UUID org, LocalDate to) {
        accounts.ensureDefaults(org);
        List<Item> items = new ArrayList<>();
        BigDecimal available = BigDecimal.ZERO;
        for (FinAccountService.Acc a : accounts.accounts(org)) {
            BigDecimal b = accounts.balance(a, to);
            items.add(new Item(a.id().toString(), a.name(), Decimals.moneyStr(b), List.of(a.ledgerAccount())));
            if (!a.ledgerAccount().equals("RECEBIVEL_CARTAO")) {
                available = available.add(b);
            }
        }
        record Open(BigDecimal customer, BigDecimal card, BigDecimal payables) {
        }
        Open o = jdbc.sql("""
                        SELECT (SELECT coalesce(sum(balance), 0) FROM receivables WHERE organization_id = :o AND kind = 'CUSTOMER'
                                   AND state IN ('OPEN', 'PARTIALLY_SETTLED')) AS customer,
                               (SELECT coalesce(sum(balance), 0) FROM receivables WHERE organization_id = :o AND kind = 'CARD'
                                   AND state IN ('OPEN', 'PARTIALLY_SETTLED')) AS card,
                               (SELECT coalesce(sum(balance), 0) FROM payables WHERE organization_id = :o
                                   AND state IN ('OPEN', 'PARTIALLY_PAID')) AS payables
                        """).param("o", org).query(Open.class).single();
        BigDecimal clientes = ledgerBalance(org, "CLIENTES");
        BigDecimal advances = clientes.signum() < 0 ? clientes.negate() : BigDecimal.ZERO;
        return new CashPosition(Decimals.moneyStr(available), items, Decimals.moneyStr(o.customer()), Decimals.moneyStr(o.card()),
                Decimals.moneyStr(o.payables()), Decimals.moneyStr(advances),
                "Disponível = caixas + bancos. Cartão a receber, contas a receber e a pagar ficam à parte.");
    }

    // ---------------- categorias ----------------
    @Transactional(readOnly = true)
    public Categories categories(Actor actor, LocalDate from, LocalDate to) {
        requireOrganization(actor);
        LocalDate[] r = range(from, to);
        Map<String, BigDecimal> bal = balancesByCompetence(actor.organizationId(), r[0], r[1], null);
        List<Item> cats = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, List<String>> c : EXPENSE_CATEGORIES.entrySet()) {
            BigDecimal v = c.getValue().stream().map(a -> bal.getOrDefault(a, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            total = total.add(v);
            cats.add(new Item(c.getKey(), label(c.getKey()), Decimals.moneyStr(v), c.getValue()));
        }
        // Subcategorias: despesas cadastradas, gastos de veículo por tipo e taxas de cartão (cada uma do seu lançamento).
        List<Sub> subs = subcategories(actor.organizationId(), r[0], r[1], null);
        return new Categories(r[0], r[1], cats, subs, Decimals.moneyStr(total));
    }

    /** Subcategorias de despesa do período (de uma unidade, se informada): a mesma consulta da tela de categorias. */
    public List<Sub> subcategories(UUID org, LocalDate f, LocalDate t, UUID branch) {
        return jdbc.sql(SUBCATEGORIES).param("o", org).param("f", f).param("t", t).param("b", branch).query(Sub.class).list()
                .stream().map(x -> new Sub(x.category(), x.subcategory(), Decimals.moneyStr(new BigDecimal(x.amount())),
                        x.entries())).toList();
    }

    private static final String SUBCATEGORIES = """
                        WITH l AS (
                          SELECT e.source_type, e.source_id, l.account, l.debit - l.credit AS v
                            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                           WHERE e.organization_id = :o AND coalesce(e.competence, e.entry_date) >= :f
                             AND coalesce(e.competence, e.entry_date) < :t
                             AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))
                             AND l.account IN ('DESPESAS_FUNCIONARIOS', 'DESPESAS_VEICULOS', 'DESPESAS_ESTRUTURA',
                                               'DESPESAS_IMPOSTOS', 'TAXAS_CARTAO', 'DESPESAS_OUTRAS', 'DIFERENCAS_CAIXA'))
                        SELECT CASE l.account WHEN 'DESPESAS_FUNCIONARIOS' THEN 'FUNCIONARIOS' WHEN 'DESPESAS_VEICULOS' THEN 'VEICULOS'
                                    WHEN 'DESPESAS_ESTRUTURA' THEN 'ESTRUTURA' WHEN 'DESPESAS_IMPOSTOS' THEN 'IMPOSTOS_TAXAS'
                                    WHEN 'TAXAS_CARTAO' THEN 'IMPOSTOS_TAXAS' ELSE 'OUTRAS' END AS category,
                               coalesce(x.subcategory,
                                        CASE ve.kind WHEN 'FUEL' THEN 'Combustível' WHEN 'MAINTENANCE' THEN 'Manutenção'
                                             WHEN 'TIRES' THEN 'Pneus' WHEN 'INSURANCE' THEN 'Seguro' WHEN 'DOCUMENTS' THEN 'Documentos'
                                             WHEN 'TOLL' THEN 'Pedágio' WHEN 'OTHER' THEN 'Outros do veículo' END,
                                        CASE WHEN l.account = 'TAXAS_CARTAO' THEN 'Taxas de cartão' END, 'Outros') AS subcategory,
                               CAST(sum(l.v) AS text) AS amount, CAST(count(*) AS int) AS entries
                          FROM l LEFT JOIN expenses x ON l.source_type = 'expense' AND x.id = l.source_id
                          LEFT JOIN vehicle_expenses ve ON l.source_type = 'vehicle_expense' AND ve.id = l.source_id
                         GROUP BY 1, 2 HAVING sum(l.v) <> 0 ORDER BY 1, 3 DESC
                        """;

    // ---------------- drilldown ----------------
    /** Linhas do livro da organização inteira (tela de fechamento). */
    @Transactional(readOnly = true)
    public Lines lines(Actor actor, List<String> accountsFilter, LocalDate from, LocalDate to, String basis, UUID finAccountId) {
        requireOrganization(actor);
        return linesUnchecked(actor, accountsFilter, from, to, basis, finAccountId, null);
    }

    /**
     * Drilldown (REP-05): cada linha aponta o registro de origem (pedido, despesa, gasto, guia...) e o comprovante anexado;
     * abrir o comprovante passa pela política do próprio registro. Contas a custo exigem `purchase_cost`. O chamador já
     * autorizou o relatório e resolveu a unidade permitida.
     */
    public Lines linesUnchecked(Actor actor, List<String> accountsFilter, LocalDate from, LocalDate to, String basis,
                                UUID finAccountId, UUID branch) {
        LocalDate[] r = range(from, to);
        if (accountsFilter == null || accountsFilter.isEmpty() || accountsFilter.size() > 10) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe de 1 a 10 contas do livro.");
        }
        if (accountsFilter.stream().anyMatch(COST_ACCOUNTS::contains) && !closingVisible(actor)) {
            throw ApiException.forbidden();
        }
        boolean cash = "CASH".equals(basis);
        List<LedgerLine> items = jdbc.sql("""
                        WITH x AS (
                          SELECT e.id AS entry_id, e.entry_date, e.competence, e.event_kind, e.memo, e.source_type, e.source_id,
                                 l.account, CAST(l.debit AS text) AS debit, CAST(l.credit AS text) AS credit,
                                 fa.name AS fin_account, u.display_name AS created_by_name, e.created_at, e.reversal_of,
                                 CASE WHEN e.source_type IN ('order_fulfillment', 'order_return') THEN 'order'
                                      WHEN e.source_type = 'payment' THEN 'order' ELSE e.source_type END AS origin_type,
                                 CASE WHEN e.source_type IN ('order_fulfillment', 'order_return') THEN f.order_id
                                      WHEN e.source_type = 'payment' THEN p.order_id ELSE e.source_id END AS origin_id
                            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id JOIN app_users u ON u.id = e.created_by
                            LEFT JOIN fin_accounts fa ON fa.id = l.fin_account_id
                            LEFT JOIN fulfillments f ON e.source_type IN ('order_fulfillment', 'order_return') AND f.id = e.source_id
                            LEFT JOIN payments p ON e.source_type = 'payment' AND p.id = e.source_id
                           WHERE e.organization_id = :o AND l.account IN (:accs)
                             AND (CASE WHEN :cash THEN e.entry_date ELSE coalesce(e.competence, e.entry_date) END) >= :f
                             AND (CASE WHEN :cash THEN e.entry_date ELSE coalesce(e.competence, e.entry_date) END) < :t
                             AND (CAST(:fa AS uuid) IS NULL OR l.fin_account_id = CAST(:fa AS uuid))
                             AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))
                           ORDER BY e.entry_date, e.created_at, e.id LIMIT 2000)
                        SELECT x.*, CASE WHEN x.origin_type = 'order' THEN o.number END AS origin_number,
                               CAST((SELECT count(*) FROM documents d WHERE d.organization_id = :o AND d.owner_type = x.origin_type
                                        AND d.owner_id = x.origin_id AND d.state = 'AVAILABLE') AS int) AS documents,
                               (SELECT d.id FROM documents d WHERE d.organization_id = :o AND d.owner_type = x.origin_type
                                   AND d.owner_id = x.origin_id AND d.state = 'AVAILABLE' ORDER BY d.created_at LIMIT 1) AS document_id
                          FROM x LEFT JOIN orders o ON x.origin_type = 'order' AND o.id = x.origin_id
                         ORDER BY x.entry_date, x.created_at, x.entry_id
                        """).param("o", actor.organizationId()).param("accs", accountsFilter).param("cash", cash)
                .param("f", r[0]).param("t", r[1]).param("fa", finAccountId).param("b", branch).query(LedgerLine.class).list();
        BigDecimal d = BigDecimal.ZERO;
        BigDecimal c = BigDecimal.ZERO;
        for (LedgerLine l : items) {
            d = d.add(new BigDecimal(l.debit()));
            c = c.add(new BigDecimal(l.credit()));
        }
        return new Lines(accountsFilter, r[0], r[1], cash ? "CASH" : "COMPETENCE", Decimals.moneyStr(d), Decimals.moneyStr(c),
                Decimals.moneyStr(d.subtract(c)), items);
    }

    // ---------------- conciliação ----------------
    @Transactional
    public Reconciliation reconciliation(Actor actor) {
        requireOrganization(actor);
        return reconcile(actor.organizationId());
    }

    Reconciliation reconcile(UUID org) {
        accounts.ensureDefaults(org);
        List<Check> checks = new ArrayList<>();
        record DC(BigDecimal debit, BigDecimal credit) {
        }
        DC dc = jdbc.sql("""
                        SELECT coalesce(sum(l.debit), 0) AS debit, coalesce(sum(l.credit), 0) AS credit
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.organization_id = :o
                        """).param("o", org).query(DC.class).single();
        checks.add(check("JOURNAL_BALANCED", "Livro balanceado (débitos = créditos)", dc.debit(), dc.credit(),
                "Cada lançamento é balanceado pelo banco; a soma geral também precisa fechar."));
        BigDecimal payables = jdbc.sql("SELECT coalesce(sum(balance), 0) FROM payables WHERE organization_id = :o AND state IN ('OPEN', 'PARTIALLY_PAID')")
                .param("o", org).query(BigDecimal.class).single();
        BigDecimal supplierCredit = jdbc.sql("""
                        SELECT coalesce(sum(balance), 0) FROM receivables WHERE organization_id = :o AND kind = 'SUPPLIER_CREDIT'
                           AND state IN ('OPEN', 'PARTIALLY_SETTLED')
                        """).param("o", org).query(BigDecimal.class).single();
        checks.add(check("PAYABLES", "Contas a pagar − créditos de fornecedor × conta FORNECEDORES",
                payables.subtract(supplierCredit), ledgerBalance(org, "FORNECEDORES").negate(),
                "Toda obrigação nasce e baixa junto com o lançamento do livro."));
        BigDecimal cards = jdbc.sql("""
                        SELECT coalesce(sum(balance), 0) FROM receivables WHERE organization_id = :o AND kind = 'CARD'
                           AND state IN ('OPEN', 'PARTIALLY_SETTLED')
                        """).param("o", org).query(BigDecimal.class).single();
        checks.add(check("CARD_RECEIVABLES", "Parcelas de cartão a receber × conta RECEBIVEL_CARTAO", cards,
                ledgerBalance(org, "RECEBIVEL_CARTAO"), "Líquido do adquirente; a taxa já foi despesa na venda."));
        BigDecimal stock = jdbc.sql("""
                        SELECT coalesce(sum(round(b.value, 2)), 0) FROM inventory_balances b JOIN branches br ON br.id = b.branch_id
                         WHERE br.organization_id = :o
                        """).param("o", org).query(BigDecimal.class).single();
        Check st = check("INVENTORY", "Valor do estoque (custo médio) × conta ESTOQUE", stock, ledgerBalance(org, "ESTOQUE"),
                "Diferença de poucos centavos vem do arredondamento do custo unitário (6 casas) para centavos.");
        BigDecimal stDiff = new BigDecimal(st.difference()).abs();
        if (stDiff.signum() != 0 && stDiff.compareTo(new BigDecimal("0.05")) <= 0) {
            st = new Check(st.code(), st.label(), st.expected(), st.actual(), st.difference(), "ROUNDING", st.explanation());
        }
        checks.add(st);
        UUID drawers = accounts.idOf(org, FinAccountService.DRAWERS);
        BigDecimal movements = jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN m.direction = 'IN' THEN m.amount ELSE -m.amount END), 0)
                          FROM cash_movements m JOIN cash_sessions s ON s.id = m.cash_session_id WHERE s.organization_id = :o
                        """).param("o", org).query(BigDecimal.class).single();
        BigDecimal transfers = jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN to_account_id = :d THEN amount ELSE -amount END), 0) FROM fin_transfers
                         WHERE organization_id = :o AND state = 'CONFIRMED' AND (from_account_id = :d OR to_account_id = :d)
                        """).param("o", org).param("d", drawers).query(BigDecimal.class).single();
        FinAccountService.Acc dr = accounts.find(org, drawers).orElseThrow();
        checks.add(check("CASH_DRAWERS", "Caixas de turno: movimentos dos caixas ± transferências × conta", movements.add(transfers),
                accounts.balance(dr, null), "Fundo de troco não é lançamento (é o mesmo dinheiro do turno)."));
        // Open Finance: saldo informado pelo banco × saldo da conta no livro (só conexões ativas com saldo lido).
        record Bank(String name, BigDecimal providerBalance, UUID accountId) {
        }
        for (Bank b : jdbc.sql("""
                        SELECT a.name, c.provider_balance, a.id AS account_id FROM bank_connections c
                          JOIN fin_accounts a ON a.id = c.fin_account_id
                         WHERE c.organization_id = :o AND c.state = 'ACTIVE' AND c.provider_balance IS NOT NULL
                        """).param("o", org).query(Bank.class).list()) {
            BigDecimal ledger = accounts.balance(accounts.find(org, b.accountId()).orElseThrow(), null);
            int pending = jdbc.sql("SELECT count(*) FROM bank_transactions WHERE fin_account_id = :a AND state IN ('SUGGESTED', 'UNMATCHED')")
                    .param("a", b.accountId()).query(Integer.class).single();
            checks.add(check("BANK_" + b.accountId().toString().substring(0, 8).toUpperCase(),
                    "Saldo no banco (Open Finance) × livro — " + b.name(), b.providerBalance(), ledger,
                    pending == 0 ? "Extrato importado e conciliado." : pending + " transação(ões) do extrato aguardando conferência."));
        }
        String status = checks.stream().allMatch(c -> Set.of("OK", "ROUNDING").contains(c.status())) ? "OK" : "DIVERGENT";
        return new Reconciliation(OffsetDateTime.now(), status, checks);
    }

    // ---------------- apoio ----------------
    public Map<String, BigDecimal> balancesByCompetence(UUID org, LocalDate f, LocalDate t, UUID branch) {
        record AB(String account, BigDecimal balance) {
        }
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (AB ab : jdbc.sql("""
                        SELECT l.account, sum(l.debit - l.credit) AS balance
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND coalesce(e.competence, e.entry_date) >= :f
                           AND coalesce(e.competence, e.entry_date) < :t
                           AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))
                         GROUP BY l.account
                        """).param("o", org).param("f", f).param("t", t).param("b", branch).query(AB.class).list()) {
            m.put(ab.account(), ab.balance());
        }
        return m;
    }

    BigDecimal ledgerBalance(UUID org, String account) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND l.account = :a
                        """).param("o", org).param("a", account).query(BigDecimal.class).single();
    }

    static Check check(String code, String label, BigDecimal expected, BigDecimal actual, String explanation) {
        BigDecimal diff = actual.subtract(expected);
        return new Check(code, label, Decimals.moneyStr(expected), Decimals.moneyStr(actual), Decimals.moneyStr(diff),
                diff.signum() == 0 ? "OK" : "DIVERGENT", explanation);
    }

    public static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now(Journal.COMMERCIAL).withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1) : to;
        if (!t.isAfter(f) || f.plusYears(3).isBefore(t)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Intervalo inválido (início inclusivo, fim exclusivo, até 3 anos).");
        }
        return new LocalDate[] {f, t};
    }

    public static String label(String category) {
        return switch (category) {
            case "FUNCIONARIOS" -> "Funcionários";
            case "VEICULOS" -> "Veículos";
            case "ESTRUTURA" -> "Estrutura";
            case "IMPOSTOS_TAXAS" -> "Impostos e taxas";
            case "RETIRADA_SOCIO" -> "Retirada do sócio";
            default -> "Outras aprovadas";
        };
    }
}
