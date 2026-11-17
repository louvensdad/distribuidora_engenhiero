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
const absence = { id: "a1", membership_id: "m3", user_id: "u3", person: "Carlos Motorista", sector: "ENTREGAS", branch: "Matriz", kind: "DAY_OFF",
  start_date: "2026-11-20", end_date: "2026-11-20", days: 1, state: "REQUESTED", notes: null, created_by_name: "Gerente",
  created_at: "2026-11-16T12:00:00Z", decided_by_name: null, decided_note: null, version: 1, warnings: [] };
const calendar = {
  from: "2026-11-01", to: "2026-12-01",
  members: [{ membership_id: "m1", user_id: "u1", person: "Ana Balcão", sector: "RETIRADA", role_template: "BALCAO", branch: "Matriz", vacation_days_last_12m: 10 },
    { membership_id: "m3", user_id: "u3", person: "Carlos Motorista", sector: "ENTREGAS", role_template: "MOTORISTA", branch: "Matriz", vacation_days_last_12m: 0 }],
  days: [{ date: "2026-11-20", sectors: [{ sector: "RETIRADA", total: 1, available: 0, absent: ["Ana Balcão (férias)"], uncovered: true },
    { sector: "ENTREGAS", total: 1, available: 1, absent: [], uncovered: false }] }],
  absences: [absence],
};

describe("Equipe › Funcionários", () => {
  it("calendário mostra setor sem ninguém; aprovar devolve o aviso; desempenho sem nota única", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("users.view", "users.edit_draft", "reports.view") }) },
      "GET /api/v1/team/calendar": { body: calendar },
      "GET /api/v1/team/performance": { body: { from: "2026-11-01", to: "2026-12-01", rule: "Sem nota única: cada medida é independente.",
        measures: [{ code: "DELIVERIES", function: "Motorista", label: "Entregas concluídas", formula: "Paradas entregues por completo.", unit: "entregas",
          higher_is_better: true, values: [{ user_id: "u3", person: "Carlos Motorista", value: "12" }, { user_id: "u4", person: "Roberto", value: "9" }],
          leader: "Carlos Motorista", note: null }] } },
      "POST /api/v1/team/absences/a1/approve": { body: { ...absence, state: "APPROVED", warnings: ["2026-11-20: setor ENTREGAS sem ninguém disponível"] } },
    });
    renderAt("/team/absences");
    expect(await screen.findByText("sem ninguém")).toBeInTheDocument();
    expect(screen.getByText("Ana Balcão (férias)")).toBeInTheDocument();
    expect(screen.getByText(/Férias aprovadas nos últimos 12 meses: 10 dia/)).toBeInTheDocument();
    const row = screen.getByRole("rowheader", { name: /Carlos Motorista/ }).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: "Aprovar" }));
    expect(await screen.findByText(/Atenção — 2026-11-20: setor ENTREGAS sem ninguém disponível/)).toBeInTheDocument();
    const post = calls.find((c) => c.url === "/api/v1/team/absences/a1/approve")!;
    expect(new Headers(post.init.headers as HeadersInit).get("If-Match")).toBe('"1"');
    const perf = screen.getByRole("region", { name: /Desempenho por função/ });
    expect(within(perf).getByText("Destaque: Carlos Motorista")).toBeInTheDocument();
    expect(within(perf).getByText(/Fórmula: Paradas entregues por completo/)).toBeInTheDocument();
    expect(within(perf).getByText("Sem nota única: cada medida é independente.")).toBeInTheDocument();
  });

  it("a própria ausência não mostra o botão aprovar", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, user: { id: "u3", display_name: "Carlos Motorista" }, capabilities: caps("users.view", "users.edit_draft") }) },
      "GET /api/v1/team/calendar": { body: calendar },
    });
    renderAt("/team/absences");
    const row = (await screen.findByRole("rowheader", { name: /Carlos Motorista/ })).closest("tr")!;
    expect(within(row).queryByRole("button", { name: "Aprovar" })).not.toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Recusar" })).toBeInTheDocument();
  });
});

describe("Financeiro › Open Finance", () => {
  it("sem agregador: NÃO CONFIGURADO explícito e a conexão devolve o erro do servidor (nada simulado)", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("company_cash.view", "expenses.confirm") }) },
      "GET /api/v1/finance/open-finance": { body: { state: "NAO_CONFIGURADO", provider: null, connections: [],
        message: "Open Finance NÃO CONFIGURADO: contrate um agregador autorizado e informe as credenciais no servidor." } },
      "GET /api/v1/finance/open-finance/transactions": { body: { items: [] } },
      "GET /api/v1/finance/accounts": { body: { items: [{ id: "b1", code: "BANCO_PRINCIPAL", name: "Banco principal", kind: "BANK", ledger_account: "BANCO", is_default: true, status: "ACTIVE", balance: "0.00", version: 1 }] } },
      "POST /api/v1/finance/open-finance/connections": { status: 503, body: { status: 503, code: "INTEGRATION_UNAVAILABLE",
        detail: "Open Finance NÃO CONFIGURADO: contrate um agregador autorizado. Nada foi conectado nem importado." } },
    });
    renderAt("/finance/open-finance");
    expect(await screen.findByText("NÃO CONFIGURADO")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("contrate um agregador");
    const form = screen.getByRole("form", { name: "Conectar conta bancária" });
    await within(form).findByRole("option", { name: "Banco principal" }); // contas carregam à parte
    await userEvent.selectOptions(within(form).getByLabelText("Conta no sistema *"), "b1");
    await userEvent.type(within(form).getByLabelText("Banco *"), "Banco Fictício");
    await userEvent.type(within(form).getByLabelText("CNPJ da empresa *"), "11.222.333/0001-81");
    await userEvent.click(within(form).getByRole("button", { name: "Conectar (requer agregador)" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("Nada foi conectado nem importado");
    expect(screen.getByText("Nada nesta situação")).toBeInTheDocument();
  });
});
