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

/** FECHAMENTO-01 (docs/especificacao/testes/dados-financeiros.json) como o servidor devolve. */
function report(hidden = false) {
  const h = <T,>(v: T) => (hidden ? null : v);
  return {
    policy_version: "RESULTADO-GERENCIAL-1", timezone: "America/Sao_Paulo", period: "MONTH", from: "2026-11-01", to: "2026-12-01",
    label: "novembro de 2026", branch_id: null, branch_name: null, basis: "COMPETENCE", source: "LIVE", closed: null,
    hidden: hidden ? ["cogs", "losses", "costs_expenses_losses", "profit", "margin_percent"] : [],
    result: { gross_revenue: "40000.00", discounts: "0.00", returns: "0.00", net_revenue: "40000.00", cogs: h("15000.00"),
      expenses_total: "15000.00", losses: h("500.00"), costs_expenses_losses: h("30500.00"), profit: h("9500.00"),
      margin_percent: h("23.75"), outcome: h("PROFIT") },
    expenses: [item("FUNCIONARIOS", "Funcionários", "8000.00", ["DESPESAS_FUNCIONARIOS"]), item("VEICULOS", "Veículos", "3000.00", ["DESPESAS_VEICULOS"]),
      item("ESTRUTURA", "Estrutura", "2000.00", ["DESPESAS_ESTRUTURA"]), item("IMPOSTOS_TAXAS", "Impostos e taxas", "2000.00", ["DESPESAS_IMPOSTOS", "TAXAS_CARTAO"]),
      item("OUTRAS", "Outras aprovadas", "0.00", ["DESPESAS_OUTRAS"])],
    subcategories: [{ category: "VEICULOS", subcategory: "Combustível", amount: "3000.00", entries: 4 },
      { category: "FUNCIONARIOS", subcategory: "Folha", amount: "8000.00", entries: 1 }],
    sold: { quantity_total: "2000.000000", unit: "DUZIA", by_unit: [{ unit: "DUZIA", sold: "2000.000000", backlog: "40.000000" }], orders: 12, note: "Quantidade efetivamente vendida." },
    chart_unit: "BRL",
    chart: [{ key: "net_revenue", label: "Vendas (receita líquida)", amount: "40000.00" },
      ...(hidden ? [] : [{ key: "cogs", label: "CMV", amount: "15000.00" }]),
      { key: "expenses_total", label: "Despesas", amount: "15000.00" },
      ...(hidden ? [] : [{ key: "losses", label: "Perdas", amount: "500.00" }, { key: "profit", label: "Lucro", amount: "9500.00" }])],
    timeline_bucket: "DAY",
    timeline: [{ from: "2026-11-01", to: "2026-11-02", label: "01/11", net_revenue: "40000.00", expenses_total: "15000.00", cogs: h("15000.00"), losses: h("500.00"), profit: h("9500.00"), sold_quantity: "2000.000000" }],
    comparison: { from: "2026-10-01", to: "2026-11-01", label: "outubro de 2026", formula: "Diferença = atual − anterior",
      items: [{ key: "net_revenue", label: "Vendas (receita líquida)", current: "40000.00", previous: "32000.00", difference: "8000.00", percent: "25.00" }] },
    drill: [item("gross_revenue", "Vendas cumpridas (bruto)", "40000.00", ["RECEITA_VENDAS"]), ...(hidden ? [] : [item("cogs", "CMV", "15000.00", ["CMV"])])],
    cash: null, formula: "Lucro = receita líquida − CMV − despesas − perdas", note: "Resultado gerencial.", generated_by: "Patrão", generated_at: "2026-11-17T12:00:00Z",
  };
}
const R = "GET /api/v1/reports/overview";

describe("F09 — relatórios no site", () => {
  it("fixture 40.000 − 15.000 − 15.000 − 500 = 9.500 igual nos indicadores, no gráfico 3D e na tabela; arquivos apontam o mesmo filtro", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("reports.view", "reports.export", "reports.print") }) },
      [R]: { body: report() },
      "GET /api/v1/branches": { body: { items: [{ id: "b1", name: "Matriz", status: "ACTIVE" }] } },
    });
    renderAt("/reports?period=MONTH&date=2026-11-17");
    const kpis = await screen.findByLabelText("Indicadores do período");
    expect(within(kpis).getByText("R$ 9.500,00")).toBeInTheDocument();
    // Gráfico: um grupo por coluna, com o valor exato do servidor e rótulo direto; altura proporcional na mesma escala.
    const svg = screen.getByRole("img", { name: /Fechamento — novembro de 2026/ });
    const bars = Array.from(svg.querySelectorAll("g[data-key]"));
    expect(bars.map((g) => g.getAttribute("data-amount"))).toEqual(["40000.00", "15000.00", "15000.00", "500.00", "9500.00"]);
    const heights = bars.map((g) => Number(g.querySelector("rect")!.getAttribute("height")));
    expect(heights[4]! / heights[0]!).toBeCloseTo(9500 / 40000, 5);
    expect(heights[1]).toBeCloseTo(heights[2]!, 5);
    expect(within(svg as unknown as HTMLElement).getByText("R$ 9.500,00")).toBeInTheDocument();
    // Tabela acessível com os mesmos números.
    const table = screen.getByRole("heading", { name: "Fechamento (tabela)" }).closest("section")!;
    expect(within(table).getByRole("row", { name: /Lucro do período R\$ 9\.500,00/ })).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: /\(−\) CMV R\$ 15\.000,00/ })).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: /CMV \+ despesas \+ perdas R\$ 30\.500,00/ })).toBeInTheDocument();
    expect(screen.getByText(/Margem sobre a receita líquida: 23,75%/)).toBeInTheDocument();
    // Versão 2D sem faces de profundidade.
    expect(svg.querySelectorAll("polygon").length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole("button", { name: "Versão 2D" }));
    expect(screen.getByRole("img", { name: /Fechamento/ }).querySelectorAll("polygon").length).toBe(0);
    // Arquivos: PDF/XLSX/CSV do mesmo filtro; IA sem chave = não configurada.
    expect(screen.getByRole("link", { name: "PDF" })).toHaveAttribute("href", "/api/v1/reports/overview/export?format=pdf&period=MONTH&date=2026-11-17");
    expect(screen.getByRole("link", { name: "CSV" })).toHaveAttribute("href", expect.stringContaining("format=csv&period=MONTH&date=2026-11-17"));
    expect(screen.getByRole("button", { name: /Analisar com a IA — não configurada/ })).toBeDisabled();
    expect(calls.some((c) => c.url === "/api/v1/reports/overview?period=MONTH&date=2026-11-17")).toBe(true);
  });

  it("período livre manda fim exclusivo (até + 1 dia) e dia usa a data civil escolhida", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("reports.view") }) },
      [R]: { body: report() },
    });
    renderAt("/reports?period=CUSTOM&from=2026-11-01&until=2026-11-30");
    await screen.findByLabelText("Indicadores do período");
    expect(calls.some((c) => c.url === "/api/v1/reports/overview?period=CUSTOM&from=2026-11-01&to=2026-12-01")).toBe(true);
    await userEvent.click(screen.getByRole("radio", { name: "Dia" }));
    expect(calls.some((c) => c.url.startsWith("/api/v1/reports/overview?period=DAY&date="))).toBe(true);
    // Sem reports.export/print: sem botões de arquivo.
    expect(screen.queryByRole("link", { name: "CSV" })).not.toBeInTheDocument();
  });

  it("sem custo/margem: CMV, perdas e lucro não aparecem em indicador, gráfico nem tabela", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("reports.view") }) },
      [R]: { body: report(true) },
    });
    renderAt("/reports");
    await screen.findByLabelText("Indicadores do período");
    expect(screen.getByText("CMV, perdas e lucro ocultos pela sua permissão")).toBeInTheDocument();
    expect(screen.queryByText("R$ 9.500,00")).not.toBeInTheDocument();
    expect(screen.queryByText("R$ 500,00")).not.toBeInTheDocument();
    const svg = screen.getByRole("img", { name: /Fechamento/ });
    expect(Array.from(svg.querySelectorAll("g[data-key]")).map((g) => g.getAttribute("data-key"))).toEqual(["net_revenue", "expenses_total"]);
  });

  it("gastos primeiro por categoria, com subcategorias e o total; depois o fechamento", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("reports.view") }) },
      [R]: { body: report() },
    });
    renderAt("/reports/categories");
    expect(await screen.findByText("Despesas totais")).toBeInTheDocument();
    const veic = screen.getByText("Veículos").closest("details")!;
    await userEvent.click(within(veic).getByText("Veículos"));
    expect(within(veic).getByText("Combustível")).toBeInTheDocument();
    const fech = screen.getByRole("heading", { name: "Fechamento total" }).closest("section")!;
    expect(within(fech).getByText("R$ 9.500,00")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ver fechamento" })).toBeInTheDocument();
  });

  it("metas: realizado, faltante, % e fórmula; ativar manda a versão", async () => {
    const goal = { id: "g1", metric: "NET_REVENUE", metric_label: "Vendas (receita líquida)", unit: null, period_kind: "MONTH",
      period_start: "2026-11-01", period_end: "2026-12-01", period_label: "novembro de 2026", target: "50000.00", title: "Vendas de novembro",
      notes: null, branch_id: null, branch_name: null, state: "DRAFT", cancel_reason: null, created_by_name: "Patrão", created_at: "2026-11-17T12:00:00Z",
      activated_by_name: null, activated_at: null, cancelled_by_name: null, cancelled_at: null, version: 3,
      progress: { realized: "40000.00", remaining: "10000.00", percent: "80.00", expected_by_now: "28333.33", days_elapsed: 17, days_total: 30,
        status: "ON_PACE", formula: "Faltante = meta − realizado (mínimo 0)" } };
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("goals.view", "goals.create", "goals.edit_draft") }) },
      "GET /api/v1/goals": { body: { items: [goal] } },
      "POST /api/v1/goals/g1/activation": { body: { ...goal, state: "ACTIVE", version: 4 } },
    });
    renderAt("/reports/goals");
    const card = await screen.findByRole("article", { name: "Vendas de novembro" });
    expect(within(card).getByText("R$ 10.000,00")).toBeInTheDocument();
    expect(within(card).getByRole("progressbar")).toHaveAttribute("aria-valuenow", "80");
    expect(within(card).getByText(/Faltante = meta − realizado/)).toBeInTheDocument();
    await userEvent.click(within(card).getByRole("button", { name: "Ativar" }));
    const post = calls.find((c) => c.url === "/api/v1/goals/g1/activation")!;
    expect(new Headers(post.init.headers as HeadersInit).get("If-Match")).toBe('"3"');
  });

  it("clientes: medidas separadas com fórmula, sem nota única; margem some sem permissão", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("reports.view") }) },
      "GET /api/v1/branches": { body: { items: [] } },
      "GET /api/v1/reports/customers": { body: { from: "2026-11-01", to: "2026-12-01", label: "novembro de 2026", branch_id: null,
        sort: "net_revenue", unit: "DUZIA", hidden: ["margin", "margin_percent"], note: "Sem nota única.",
        measures: [{ key: "net_revenue", label: "Receita líquida", formula: "Soma da receita líquida." }, { key: "returns", label: "Devoluções", formula: "Menos é melhor." }],
        items: [{ position: 1, customer_id: "c1", customer_name: "Mercado Exemplo", net_revenue: "40000.00", quantity: "2000.000000", margin: null,
          margin_percent: null, orders: 3, active_weeks: 2, returns: 0, receivables_due: 2, receivables_on_time: 1, on_time_percent: "50.00" }] } },
    });
    renderAt("/reports/customers");
    expect(await screen.findByRole("rowheader", { name: "Mercado Exemplo" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Margem" })).not.toBeInTheDocument();
    expect(screen.getByText("50,00% (1/2)")).toBeInTheDocument();
    expect(screen.getByText("Soma da receita líquida.")).toBeInTheDocument();
    expect(screen.queryByText(/pontuação|nota final/i)).not.toBeInTheDocument();
  });
});
