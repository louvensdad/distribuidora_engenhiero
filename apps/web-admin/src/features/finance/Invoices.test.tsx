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
const settings = (extra: Record<string, unknown> = {}) => ({ document_type: "NFE", environment: "HOMOLOGACAO", series: 1, default_cfop: "5102",
  issuer_document: "11222333000181", state_registration: "ISENTO", tax_regime: "SIMPLES_NACIONAL", notes: null,
  provider_state: "NOT_CONFIGURED", provider: null, missing: [], version: 2, ...extra });
const nota = (state: string, extra: Record<string, unknown> = {}) => ({ id: "n1", order_id: "o1", order_number: 42, customer_name: "Mercado Exemplo",
  document_type: "NFE", environment: "HOMOLOGACAO", state, total: "75.00", provider: null, number: null, series: 1, access_key: null,
  protocol: null, xml_document_id: null, rejection_code: null, rejection_reason: null, cancel_reason: null, cancel_protocol: null,
  attempts: 0, reviewed_by_name: null, reviewed_at: null, transmitted_at: null, authorized_at: null, created_by_name: "Contador",
  created_at: "2026-11-16T12:00:00Z", lines: [{ line_no: 1, product_id: "p1", description: "Cana caiana", ncm: "12129300", cfop: "5102",
    unit: "DUZIA", quantity: "10", unit_price: "7.5", total: "75.00" }], issues: [], provider_state: "NOT_CONFIGURED", history: [],
  version: 2, ...extra });

describe("F08 — notas fiscais e integrações no site", () => {
  it("sem provedor: mostra emissão BLOQUEADA; transmitir devolve o motivo e a nota segue conferida, nunca 'Autorizada'", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("invoices.view", "invoices.confirm") }) },
      "GET /api/v1/fiscal-settings": { body: settings() },
      "GET /api/v1/orders": { body: { items: [], next_cursor: null } },
      "GET /api/v1/invoices": { body: { items: [nota("READY", { history: [{ id: "a1", integration: "FISCAL", operation: "transmit",
        entity_type: "invoice", entity_id: "n1", outcome: "BLOCKED", detail: "Provedor fiscal NÃO CONFIGURADO (D03)", actor_name: "Patrão",
        started_at: "2026-11-16T12:10:00Z", finished_at: "2026-11-16T12:10:00Z" }] })] } },
      "POST /api/v1/invoices/n1/transmission": { status: 503, body: { status: 503, code: "INTEGRATION_UNAVAILABLE",
        detail: "Emissão BLOQUEADA: Provedor fiscal NÃO CONFIGURADO (D03). Nada foi enviado nem emitido." } },
    });
    renderAt("/finance/invoices");
    const cfg = await screen.findByRole("article", { name: "Configuração fiscal" });
    expect(within(cfg).getByText("Emissão BLOQUEADA — provedor NÃO CONFIGURADO")).toBeInTheDocument();
    const card = screen.getByRole("article", { name: "Nota do pedido 42" });
    expect(within(card).getByText("Conferida pelo contador")).toBeInTheDocument();
    await userEvent.click(within(card).getByRole("button", { name: "Transmitir" }));
    expect(await within(card).findByRole("alert")).toHaveTextContent("Nada foi enviado nem emitido");
    expect(within(card).queryByText("Autorizada")).not.toBeInTheDocument();
    expect(within(card).getByText(/Transmissão: Bloqueado/)).toBeInTheDocument();
    const post = calls.find((c) => c.url === "/api/v1/invoices/n1/transmission")!;
    const h = new Headers(post.init.headers as HeadersInit);
    expect(h.get("If-Match")).toBe('"2"');
    expect(h.get("Idempotency-Key")).toMatch(/^web-/);
  });

  it("incerta: avisa que pode ter sido emitida, não oferece Transmitir, só Consultar provedor", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("invoices.view", "invoices.confirm") }) },
      "GET /api/v1/fiscal-settings": { body: settings({ provider_state: "CONFIGURED", provider: "provedor-x" }) },
      "GET /api/v1/orders": { body: { items: [], next_cursor: null } },
      "GET /api/v1/invoices": { body: { items: [nota("UNCERTAIN")] } },
    });
    renderAt("/finance/invoices");
    const card = await screen.findByRole("article", { name: "Nota do pedido 42" });
    expect(within(card).getByText("Resultado incerto — consultar")).toBeInTheDocument();
    expect(within(card).getByText(/pode ou não ter sido emitida/)).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Transmitir" })).not.toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Consultar provedor" })).toBeInTheDocument();
  });

  it("autorizada: chave, protocolo e XML só com invoices.export; cancelar é ação separada", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("invoices.view", "invoices.cancel") }) },
      "GET /api/v1/fiscal-settings": { body: settings({ provider_state: "CONFIGURED", provider: "provedor-x" }) },
      "GET /api/v1/invoices": { body: { items: [nota("AUTHORIZED", { access_key: "3".repeat(44), protocol: "135260000001",
        xml_document_id: "x1", number: 1001 })] } },
    });
    renderAt("/finance/invoices");
    const card = await screen.findByRole("article", { name: "Nota do pedido 42" });
    expect(within(card).getByText("3".repeat(44))).toBeInTheDocument();
    expect(within(card).getByText("135260000001")).toBeInTheDocument();
    expect(within(card).queryByRole("link", { name: "Baixar XML da nota" })).not.toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Cancelar nota" })).toBeInTheDocument();
  });

  it("integrações: estados honestos e, para não administrador, sem histórico nem triagem", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("invoices.view") }) },
      "GET /api/v1/integrations": { body: { items: [
        { integration: "FISCAL", label: "Notas fiscais", state: "NOT_CONFIGURED", provider: null, message: "Emissão BLOQUEADA", pending: 0, pending_label: "notas incertas" },
        { integration: "PAYMENTS", label: "Pagamentos (Pix por provedor)", state: "NOT_CONFIGURED", provider: null, message: "Conferência manual", pending: 0, pending_label: "avisos" },
        { integration: "OPEN_FINANCE", label: "Open Finance (extrato bancário)", state: "NOT_CONFIGURED", provider: null, message: "ADR-021", pending: 0, pending_label: "sem par" }],
        recent: [] } },
    });
    renderAt("/finance/integrations");
    expect(await screen.findAllByText("NÃO CONFIGURADA — bloqueada")).toHaveLength(3);
    expect(screen.queryByRole("article", { name: "Tentativas recentes" })).not.toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "Avisos de pagamento" })).not.toBeInTheDocument();
  });
});
