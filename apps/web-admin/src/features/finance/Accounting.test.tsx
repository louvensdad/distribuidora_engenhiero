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
const pdf = { id: "f1", file_name: "guia-das.pdf", mime: "application/pdf", size_bytes: 30, sha256: "0".repeat(64), state: "AVAILABLE", scan_state: "NAO_CONFIGURADO", rejection: null, created_by_name: "Contador", created_at: "2026-11-16T12:00:00Z" };
const guia = (state: string, extra: Record<string, unknown> = {}) => ({ id: "g1", kind: "DAS", description: "Simples Nacional", competence: "2026-11", due_date: "2026-12-20",
  amount: "850.00", notes: null, payroll_run_id: null, state, payable_id: state === "APPROVED" ? "p1" : null, payable_state: state === "APPROVED" ? "OPEN" : null,
  created_by_name: "Contador", created_at: "2026-11-16T12:00:00Z", submitted_by_name: "Contador", submitted_at: "2026-11-16T12:05:00Z",
  decided_by_name: state === "APPROVED" ? "Patrão" : null, decided_at: state === "APPROVED" ? "2026-11-16T13:00:00Z" : null, decided_note: null,
  attachments: [pdf], version: 3, ...extra });

describe("F07 no site do patrão", () => {
  it("guia enviada: aprovar envia If-Match e chave; guia aprovada mostra pagamento pendente e o arquivo para baixar", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("taxes.view", "taxes.approve", "taxes.confirm") }) },
      "GET /api/v1/tax-obligations": { body: { items: [guia("SUBMITTED")] } },
      "POST /api/v1/tax-obligations/g1/approval": { body: guia("APPROVED") },
    });
    renderAt("/finance/taxes");
    const card = await screen.findByRole("article", { name: "Guia DAS 11/2026" });
    expect(within(card).getByText("Aguardando aprovação")).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: "guia-das.pdf" })).toHaveAttribute("href", "/api/v1/documents/f1/download");
    await userEvent.click(within(card).getByRole("button", { name: "Aprovar" }));
    expect(await screen.findByText("Guia aprovada — o pagamento continua pendente.")).toBeInTheDocument();
    const post = calls.find((c) => c.url === "/api/v1/tax-obligations/g1/approval")!;
    const h = new Headers(post.init.headers as HeadersInit);
    expect(h.get("If-Match")).toBe('"3"');
    expect(h.get("Idempotency-Key")).toMatch(/^web-/);
  });

  it("guia aprovada: status 'pagamento pendente' e formulário de pagamento separado", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("taxes.view", "taxes.approve", "taxes.confirm") }) },
      "GET /api/v1/tax-obligations": { body: { items: [guia("APPROVED")] } },
      "GET /api/v1/finance/accounts": { body: { items: [] } },
    });
    renderAt("/finance/taxes");
    const card = await screen.findByRole("article", { name: "Guia DAS 11/2026" });
    expect(within(card).getByText("Aprovada — pagamento pendente")).toBeInTheDocument();
    expect(within(card).getByRole("group", { name: "Pagamento da guia DAS" })).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Aprovar" })).not.toBeInTheDocument();
  });

  it("folha sem permissão individual: totais por rubrica e aviso, sem tabela por pessoa", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("payroll.view") }) },
      "GET /api/v1/payroll-runs": { body: { items: [{ id: "r1", competence: "2026-11", description: "Folha mensal", pay_date: "2026-12-05", state: "SUBMITTED", source: "IMPORT",
        totals: { earnings: "6000.00", benefits: "800.00", deductions: "540.00", advances: "1000.00", employer_charges: "1200.00", net_pay: "4460.00", company_cost: "8000.00", people: 2 },
        rubrics: [{ kind: "EARNING", rubric: "Salário base", amount: "6000.00", people: 2 }], people: null, masked_fields: ["people"],
        net_payable_id: null, net_payable_state: null, created_by_name: "Contador", created_at: "2026-11-16T12:00:00Z", submitted_by_name: "Contador",
        decided_by_name: null, decided_at: null, decided_note: null, version: 2 }] } },
    });
    renderAt("/finance/payroll");
    const card = await screen.findByRole("article", { name: "Folha 11/2026" });
    expect(within(card).getByText("R$ 8.000,00")).toBeInTheDocument();
    expect(within(card).getByText(/Valores por pessoa ocultos/)).toBeInTheDocument();
    expect(within(card).queryByText("Valores por pessoa (dado individual)")).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Aprovar folha" })).not.toBeInTheDocument(); // sem payroll.approve
    expect(within(card).queryByRole("link", { name: "Exportar CSV" })).not.toBeInTheDocument();
  });

  it("documentos: enviar sem arquivo é barrado na tela (só o nome não basta)", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("documents.view", "documents.attach") }) },
      "GET /api/v1/documents": { body: { items: [] } },
      "GET /api/v1/document-requests": { body: { items: [] } },
    });
    renderAt("/finance/documents");
    const form = await screen.findByRole("form", { name: "Enviar documento" });
    await userEvent.type(within(form).getByLabelText("Título *"), "Contrato social");
    await userEvent.click(within(form).getByRole("button", { name: "Enviar" }));
    expect(within(form).getByText("Obrigatório")).toBeInTheDocument();
    expect(calls.some((c) => c.url === "/api/v1/documents/uploads")).toBe(false);
  });
});
