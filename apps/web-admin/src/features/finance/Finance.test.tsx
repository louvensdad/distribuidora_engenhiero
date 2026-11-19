import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../../session/SessionContext";
import { App } from "../../app/App";
import { caps, me, mockFetch } from "../../test/fixtures";

afterEach(() => vi.unstubAllGlobals());
const session = { body: { web_login: "CONFIGURADO", authenticated: true, login_url: "/oauth2/authorization/engenheiro" } };
const renderAt = (path: string) => render(<MemoryRouter initialEntries={[path]}><SessionProvider><App /></SessionProvider></MemoryRouter>);
const item = (key: string, label: string, amount: string, accounts: string[]) => ({ key, label, amount, accounts });
const summary = {
  from: "2026-11-01", to: "2026-12-01", basis: "COMPETENCE", gross_revenue: "40000.00", discounts: "0.00", returns: "0.00",
  net_revenue: "40000.00", cogs: "15000.00",
  expenses: [item("FUNCIONARIOS", "Funcionários", "8000.00", ["DESPESAS_FUNCIONARIOS"]), item("VEICULOS", "Veículos", "3000.00", ["DESPESAS_VEICULOS"]),
    item("ESTRUTURA", "Estrutura", "2000.00", ["DESPESAS_ESTRUTURA"]), item("IMPOSTOS_TAXAS", "Impostos e taxas", "2000.00", ["DESPESAS_IMPOSTOS", "TAXAS_CARTAO"]),
    item("OUTRAS", "Outras aprovadas", "0.00", ["DESPESAS_OUTRAS"])],
  expenses_total: "15000.00", losses: "500.00", costs_expenses_losses: "30500.00", profit: "9500.00", margin_percent: "23.75",
  owner_withdrawals: "0.00",
  cash: { available: "11000.00", accounts: [item("a1", "Banco principal", "11000.00", ["BANCO"])], receivables_open: "4000.00",
    card_receivables_open: "97.00", payables_open: "1510.00", customer_advances: "0.00", note: "Disponível = caixas + bancos." },
  drill: [item("gross_revenue", "Vendas cumpridas (bruto)", "40000.00", ["RECEITA_VENDAS"]), item("discounts", "Descontos", "0.00", ["DESCONTOS_CONCEDIDOS"]),
    item("returns", "Devoluções", "0.00", ["DEVOLUCOES_VENDAS"]), item("cogs", "CMV", "15000.00", ["CMV"]), item("losses", "Perdas", "500.00", ["PERDAS_ESTOQUE", "AJUSTES_ESTOQUE"])],
  note: "Resultado gerencial por competência; saldo em caixa não é lucro.",
};

describe("F06 financeiro no site", () => {
  it("resultado (FECHAMENTO-01) separado do caixa; cada total abre os lançamentos; conciliação divergente aparece", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("reports.view", "reports.close_period") }) },
      "GET /api/v1/reports/summary": { body: summary },
      "GET /api/v1/reports/categories": { body: { from: "2026-11-01", to: "2026-12-01", categories: [], total: "15000.00",
        subcategories: [{ category: "IMPOSTOS_TAXAS", subcategory: "Taxas de cartão", amount: "3.00", entries: 1 }] } },
      "GET /api/v1/finance/reconciliation": { body: { checked_at: "2026-11-16T12:00:00Z", status: "DIVERGENT", checks: [
        { code: "PAYABLES", label: "Contas a pagar × FORNECEDORES", expected: "10.00", actual: "0.00", difference: "-10.00", status: "DIVERGENT", explanation: "x" }] } },
      "GET /api/v1/periods": { body: { items: [] } },
      "GET /api/v1/finance/ledger-lines": { body: { accounts: ["DESPESAS_IMPOSTOS", "TAXAS_CARTAO"], from: "2026-11-01", to: "2026-12-01", basis: "COMPETENCE",
        total_debit: "2000.00", total_credit: "0.00", net: "2000.00", items: [{ entry_id: "e1", entry_date: "2026-11-10", competence: "2026-11-05",
          event_kind: "EXPENSE_APPROVED", memo: "Guia mensal", source_type: "expense", source_id: "x1", account: "DESPESAS_IMPOSTOS", debit: "2000.00",
          credit: "0.00", fin_account: null, created_by_name: "Aprovador", created_at: "2026-11-10T12:00:00Z", reversal_of: null }] } },
    });
    renderAt("/finance/closing");
    const dre = await screen.findByRole("region", { name: "Resultado gerencial (competência)" });
    expect(within(dre).getByText("Lucro do período")).toBeInTheDocument();
    expect(within(dre).getByText("R$ 9.500,00")).toBeInTheDocument();
    expect(within(dre).getByText(/23,75%/)).toBeInTheDocument();
    const caixa = screen.getByRole("region", { name: "Posição de caixa (até o fim do mês)" });
    expect(within(caixa).getAllByText("R$ 11.000,00").length).toBeGreaterThan(0);
    await userEvent.click(within(dre).getByRole("button", { name: "Abrir lançamentos de Impostos e taxas" }));
    const drill = await screen.findByRole("region", { name: /Lançamentos de Impostos e taxas/ });
    expect(within(drill).getByText(/Despesa aprovada — Guia mensal/)).toBeInTheDocument();
    const req = calls.find((c) => c.url.startsWith("/api/v1/finance/ledger-lines"))!;
    expect(req.url).toContain("accounts=DESPESAS_IMPOSTOS,TAXAS_CARTAO");
    expect(screen.getByText(/há divergência/)).toBeInTheDocument();
    expect(screen.getByText("Divergente")).toBeInTheDocument();
    expect(screen.getByText("Taxas de cartão")).toBeInTheDocument();
  });

  it("F17: conferência do mês com a operação mostra cada verificação; divergência aparece; sem custo (403) o cartão some", async () => {
    const pilot = { from: "2026-11-01", to: "2026-12-01", label: "novembro de 2026", timezone: "America/Sao_Paulo", status: "DIVERGENT",
      report: { net_revenue: "40000.00", cogs: "15000.00", expenses_total: "2000.00", losses: "500.00", profit: "22500.00", fulfillments: 1, payments: 2, cash_sessions_closed: 0 },
      checks: [
        { code: "SALES_NET", area: "Vendas", label: "Vendas líquidas × relatório", unit: "BRL", expected: "40000.00", actual: "40000.00", difference: "0.00", status: "OK", explanation: "Pedido só pago não é venda." },
        { code: "SALES_STOCK_QTY", area: "Estoque", label: "Quantidade entregue × saída de estoque", unit: "base", expected: "2000", actual: "2000", difference: "0", status: "OK", explanation: "x" },
        { code: "CASH_PAYMENTS", area: "Caixa", label: "Dinheiro confirmado × entradas nos caixas", unit: "BRL", expected: "41000.00", actual: "40990.00", difference: "-10.00", status: "DIVERGENT", explanation: "O troco não é receita." },
        { code: "LEDGER_SOURCES", area: "Livro", label: "Lançamentos com documento de origem", unit: "documentos", expected: "0", actual: "0", difference: "0", status: "OK", explanation: "7 documento(s)." }],
      note: "Conferência de leitura: não altera nada.", checked_at: "2026-11-19T12:00:00Z" };
    const base = {
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("reports.view") }) },
      "GET /api/v1/reports/summary": { body: summary },
      "GET /api/v1/reports/categories": { body: { from: "2026-11-01", to: "2026-12-01", categories: [], total: "0.00", subcategories: [] } },
      "GET /api/v1/finance/reconciliation": { body: { checked_at: "2026-11-16T12:00:00Z", status: "OK", checks: [] } },
      "GET /api/v1/periods": { body: { items: [] } },
    };
    const calls = mockFetch({ ...base, "GET /api/v1/reports/reconciliation": { body: pilot } });
    const { unmount } = renderAt("/finance/closing");
    const card = await screen.findByRole("region", { name: /Conferência do mês com a operação — 1 divergência/ });
    expect(calls.some((c) => c.url.startsWith("/api/v1/reports/reconciliation?period=MONTH&date="))).toBe(true);
    const linha = within(card).getByRole("rowheader", { name: /Dinheiro confirmado × entradas nos caixas/ }).closest("tr")!;
    expect(within(linha).getByText("Divergente")).toBeInTheDocument();
    expect(within(linha).getByText("−R$ 10,00", { exact: false })).toBeInTheDocument();
    expect(within(card).getAllByText("2000 un. base").length).toBe(2);
    expect(within(card).getByText(/1 entrega\(s\), 2 pagamento\(s\) confirmado\(s\)/)).toBeInTheDocument();
    unmount();

    mockFetch({ ...base, "GET /api/v1/reports/reconciliation": { status: 403, body: { status: 403, code: "FORBIDDEN" } } });
    renderAt("/finance/closing");
    await screen.findByRole("region", { name: "Resultado gerencial (competência)" });
    expect(screen.queryByRole("region", { name: /Conferência do mês/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Acesso negado|sem permissão/i)).not.toBeInTheDocument();
  });

  it("pagar conta: escolhe caixa/banco (sem caixa de turno, sem saldo se não vê o caixa) e envia com chave idempotente", async () => {
    const payable = { id: "pg1", source_type: "expense", source_id: "x1", supplier_id: null, supplier_name: null, description: "Guia mensal",
      competence: "2026-11-05", amount: "850.00", balance: "850.00", due_date: "2026-11-20", state: "OPEN", branch: "Matriz",
      created_at: "2026-11-16T12:00:00Z", version: 1 };
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("expenses.view", "expenses.confirm") }) },
      "GET /api/v1/payables": { body: { items: [payable], next_cursor: null } },
      "GET /api/v1/payables/pg1": { body: { payable, settlements: [] } },
      "GET /api/v1/finance/accounts": { body: { items: [
        { id: "safe", code: "COFRE_EMPRESA", name: "Caixa da empresa (cofre)", kind: "COMPANY_CASH", ledger_account: "CAIXA", is_default: false, status: "ACTIVE", balance: null, version: 1 },
        { id: "bank", code: "BANCO_PRINCIPAL", name: "Banco principal", kind: "BANK", ledger_account: "BANCO", is_default: true, status: "ACTIVE", balance: null, version: 1 }] } },
      "POST /api/v1/payables/pg1/payments": { body: { payable: { ...payable, balance: "0.00", state: "PAID" }, settlements: [] } },
    });
    renderAt("/finance/payables");
    await userEvent.click(await screen.findByRole("button", { name: "Abrir" }));
    const form = await screen.findByRole("form", { name: "Registrar pagamento da conta" });
    const select = within(form).getByLabelText("Conta (caixa da empresa ou banco) *");
    expect(within(select).queryByText(/saldo/)).not.toBeInTheDocument();
    expect(within(select).queryByText(/Caixas de turno/)).not.toBeInTheDocument();
    await userEvent.selectOptions(select, "bank");
    await userEvent.click(within(form).getByRole("button", { name: "Registrar pagamento" }));
    const post = calls.find((c) => c.url === "/api/v1/payables/pg1/payments")!;
    expect(JSON.parse(String(post.init.body))).toMatchObject({ amount: "850.00", fin_account_id: "bank" });
    expect((post.init.headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^web-/);
  });

  it("despesa: quem cadastrou não vê aprovar; competência vai no cadastro", async () => {
    const mine = { id: "x1", category: "IMPOSTOS_TAXAS", subcategory: "Guia mensal", description: "Simples", payee: null, supplier_id: null,
      supplier_name: null, amount: "850.00", competence: "2026-11-05", due_date: null, state: "PENDING", payable_id: null, payable_state: null,
      payable_balance: null, branch: "Matriz", created_by_name: "Financeiro", created_at: "2026-11-16T12:00:00Z", decided_by_name: null,
      decided_at: null, decided_note: null, reverse_reason: null, version: 1 };
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, user: { id: "u-fin", display_name: "Financeiro" },
        capabilities: caps("expenses.view", "expenses.create", "expenses.approve") }) },
      "GET /api/v1/expenses": { body: { items: [mine], next_cursor: null } },
      "POST /api/v1/expenses": { status: 201, body: mine },
    });
    renderAt("/finance/expenses");
    const row = (await screen.findByRole("rowheader", { name: /Simples/ })).closest("tr")!;
    expect(within(row).queryByRole("button", { name: "Aprovar" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Cadastrar despesa/ }));
    const form = screen.getByRole("form", { name: "Nova despesa" });
    await userEvent.type(within(form).getByLabelText("Subcategoria *"), "Aluguel");
    await userEvent.type(within(form).getByLabelText("Descrição *"), "Galpão");
    await userEvent.type(within(form).getByLabelText("Valor (R$) *"), "2.000,00");
    await userEvent.clear(within(form).getByLabelText("Competência *"));
    await userEvent.type(within(form).getByLabelText("Competência *"), "2026-10-31");
    await userEvent.click(within(form).getByRole("button", { name: "Cadastrar despesa" }));
    const body = JSON.parse(String(calls.find((c) => c.url === "/api/v1/expenses" && c.init.method === "POST")!.init.body));
    expect(body).toMatchObject({ category: "ESTRUTURA", subcategory: "Aluguel", amount: "2000.00", competence: "2026-10-31" });
  });
});
