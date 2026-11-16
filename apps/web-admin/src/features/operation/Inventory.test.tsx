import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../../session/SessionContext";
import { App } from "../../app/App";
import { caps, me, mockFetch } from "../../test/fixtures";
import { formatDecimal, formatMoney, formatQty } from "../../app/format";

afterEach(() => vi.unstubAllGlobals());

const session = { body: { web_login: "CONFIGURADO", authenticated: true, login_url: "/oauth2/authorization/engenheiro" } };
const renderAt = (path: string) =>
  render(<MemoryRouter initialEntries={[path]}><SessionProvider><App /></SessionProvider></MemoryRouter>);

const balance = (over: Record<string, unknown> = {}) => ({
  product_id: "p1", sku: "CANA-A", product_name: "Cana caiana", base_unit: "DUZIA", branch_id: "b1", branch: "Matriz",
  physical: "450", reserved: "80", available: "370", lost: "50", value: "4725.00", average_cost: "10.5", version: 3,
  updated_at: "2026-11-16T12:00:00Z", masked_fields: [], ...over,
});
const summary = { body: { totals: [{ base_unit: "DUZIA", physical: "450", reserved: "80", available: "370", lost: "50", products: 1 }] } };
const product = { id: "p1", sku: "CANA-A", name: "Cana caiana", base_unit: "DUZIA", base_unit_confirmed: true, unit_confirmed_at: null,
  status: "ACTIVE", version: 1, conversions: [{ unit: "FEIXE", factor: "3", valid_from: "2026-11-16T12:00:00Z" }], created_at: "2026-11-16T12:00:00Z" };

describe("formatação decimal sem float", () => {
  it("separa milhar e decimal no padrão brasileiro", () => {
    expect(formatMoney("1510.00")).toBe("R$ 1.510,00");
    expect(formatMoney("-44.5")).toBe("−R$ 44,50");
    expect(formatDecimal("0.083333")).toBe("0,083333");
    expect(formatQty("1", "DUZIA")).toBe("1 dúzia");
    expect(formatQty("450", "DUZIA")).toBe("450 dúzias");
  });
});

describe("W05 estoque", () => {
  it("mostra totais do servidor e esconde valor de quem não vê custo", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("inventory.stock.view"), fields: {} }) },
      "GET /api/v1/inventory/balances/summary": summary,
      "GET /api/v1/inventory/balances": { body: { items: [balance({ value: null, average_cost: null, masked_fields: ["value", "average_cost"] })], next_cursor: null } },
    });
    renderAt("/operation/stock");
    const kpis = await screen.findByLabelText("Totais em dúzias");
    expect(within(kpis).getByText("450 dúzias")).toBeInTheDocument();
    expect(within(kpis).getByText("80 dúzias")).toBeInTheDocument();
    expect(within(kpis).getByText("370 dúzias")).toBeInTheDocument();
    expect(within(kpis).getByText("50 dúzias")).toBeInTheDocument();
    const row = (await screen.findByRole("rowheader", { name: /Cana caiana/ })).closest("tr")!;
    expect(within(row).getAllByText("oculto")).toHaveLength(2);
    expect(screen.queryByText(/4\.725/)).not.toBeInTheDocument();
    // Sem capacidade: sem ações de recebimento/perda.
    expect(screen.queryByRole("link", { name: /receber mercadoria/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /registrar perda/i })).not.toBeInTheDocument();
  });

  it("aprovar perda envia If-Match e Idempotency-Key", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("inventory.stock.view", "inventory.losses.view", "inventory.losses.approve") }) },
      "GET /api/v1/inventory/balances/summary": summary,
      "GET /api/v1/inventory/balances": { body: { items: [balance()], next_cursor: null } },
      "GET /api/v1/inventory/losses": { body: { items: [{ id: "l1", status: "PENDING", product_id: "p1", sku: "CANA-A", product_name: "Cana caiana",
        unit: "DUZIA", quantity: "5", base_quantity: "5", base_unit: "DUZIA", reason: "QUEBRA", notes: null, total_cost: null, branch: "Matriz",
        created_by_name: "Balcão", created_at: "2026-11-16T12:00:00Z", decided_by_name: null, decided_at: null, decision_reason: null,
        movement_id: null, version: 4, masked_fields: [] }], next_cursor: null } },
      "POST /api/v1/inventory/losses/l1/approve": { body: {} },
    });
    renderAt("/operation/stock");
    expect(await screen.findByText("R$ 4.725,00")).toBeInTheDocument();
    await userEvent.click(await screen.findByRole("button", { name: /aprovar baixa/i }));
    const post = calls.find((c) => c.url.endsWith("/losses/l1/approve"))!;
    const headers = post.init.headers as Record<string, string>;
    expect(headers["If-Match"]).toBe('"4"');
    expect(headers["Idempotency-Key"]).toMatch(/^web-[0-9a-f-]{36}$/);
  });
});

describe("W06 recebimento", () => {
  it("quem não vê custo não recebe campos de custo nem frete", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("inventory.receipts.view", "inventory.receipts.create"), fields: {} }) },
      "GET /api/v1/suppliers": { body: { items: [{ id: "s1", name: "Fornecedor Fictício", contact: null, personal_document: null, status: "ACTIVE",
        version: 1, created_at: "2026-11-16T12:00:00Z", masked_fields: ["contact", "personal_document"] }], next_cursor: null } },
      "GET /api/v1/products": { body: { items: [product], next_cursor: null } },
    });
    renderAt("/operation/receipts/new");
    expect(await screen.findByRole("heading", { name: "Conferir recebimento de fornecedor" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/custo unitário/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/frete/i)).not.toBeInTheDocument();
    expect(screen.getByText(/Custo e frete ficam com quem tem acesso/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /confirmar entrada/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /salvar rascunho/i })).toBeInTheDocument();
  });

  it("salvar rascunho envia quantidades com chave de idempotência e mostra erro de campo do servidor", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("inventory.receipts.view", "inventory.receipts.create", "inventory.receipts.confirm"),
        fields: { purchase_cost: true } }) },
      "GET /api/v1/suppliers": { body: { items: [{ id: "s1", name: "Fornecedor Fictício", contact: null, personal_document: null, status: "ACTIVE",
        version: 1, created_at: "2026-11-16T12:00:00Z", masked_fields: [] }], next_cursor: null } },
      "GET /api/v1/products": { body: { items: [product], next_cursor: null } },
      "POST /api/v1/inventory/receipts": { status: 422, body: { status: 422, code: "VALIDATION_ERROR", detail: "Corrija os campos indicados.",
        errors: [{ field: "lines[0].quantity_received", message: "Obrigatório" }] } },
    });
    renderAt("/operation/receipts/new");
    await userEvent.selectOptions(await screen.findByLabelText("Fornecedor *"), "s1");
    await userEvent.selectOptions(screen.getByLabelText("Produto *"), "p1");
    await userEvent.selectOptions(screen.getByLabelText("Medida"), "FEIXE");
    await userEvent.type(screen.getByLabelText("Prevista"), "10");
    await userEvent.click(screen.getByRole("button", { name: /salvar rascunho/i }));
    expect(await screen.findByText("Obrigatório")).toBeInTheDocument();
    const post = calls.find((c) => c.url === "/api/v1/inventory/receipts")!;
    const body = JSON.parse(String(post.init.body));
    expect(body.lines[0]).toMatchObject({ product_id: "p1", unit: "FEIXE", quantity_expected: "10" });
    expect((post.init.headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^web-/);
    // Dados digitados preservados após o erro.
    expect(screen.getByLabelText("Prevista")).toHaveValue("10");
    // Quem vê custo tem custo, frete e “Confirmar entrada”.
    expect(screen.getByLabelText(/custo unitário/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/frete de compra/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /confirmar entrada/i })).toBeInTheDocument();
  });
});
