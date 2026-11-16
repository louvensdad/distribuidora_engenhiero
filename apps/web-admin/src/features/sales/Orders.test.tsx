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
const order = {
  id: "o1", number: 1047, type: "PICKUP", state: "CONFIRMED", payment_state: "UNPAID", customer_id: "c1", customer_name: "Ana Santos",
  branch: "Matriz", gross_total: "75.00", discount_total: "0.00", total: "75.00", effective_total: "75.00", paid_amount: "0.00",
  pending_amount: "0.00", on_account: "0.00", balance_due: "75.00", notes: null, created_by_name: "Balcão", created_at: "2026-11-16T12:00:00Z",
  cancel_reason: null, version: 3, lines: [{ line_no: 1, product_id: "p1", sku: "CANA", product_name: "Cana caiana", unit: "DUZIA", factor: "1",
    quantity: "10", base_quantity: "10", base_unit: "DUZIA", unit_price: "7.50", gross: "75.00", discount: "0.00", net: "75.00",
    fulfilled_quantity: "0", cancelled_quantity: "0", returned_quantity: "0", remaining_quantity: "10" }],
};

describe("W04 pedido", () => {
  it("dinheiro: envia valor apresentado e mostra o troco calculado pelo servidor", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("orders.pickup.view", "orders.pickup.confirm", "payments.confirm", "payments.view"), fields: {} }) },
      "GET /api/v1/orders/o1": { body: order },
      "GET /api/v1/orders/o1/payments": { body: { items: [] } },
      "GET /api/v1/orders/o1/history": { body: { items: [{ occurred_at: "2026-11-16T12:00:00Z", action: "orders.create", actor_name: "Balcão", client_id: "engenheiro-retirada", entity_type: "order" }] } },
      "POST /api/v1/orders/o1/payments": { body: { payments: [{ id: "p1", order_id: "o1", method: "CASH", state: "CONFIRMED", confirmation_kind: "MANUAL_AUTHORIZED",
        amount: "75.00", fee: "0.00", net: "75.00", presented_cash: "100.00", change_given: "25.00", installments: null, provider_reference: null,
        document_id: null, due_date: null, created_by_name: "Balcão", created_at: "2026-11-16T12:01:00Z", confirmed_by_name: "Balcão",
        confirmed_at: "2026-11-16T12:01:00Z", decided_note: null, version: 1 }], order: { ...order, payment_state: "PAID" } } },
    });
    renderAt("/sales/orders/o1");
    expect(await screen.findByRole("heading", { name: "Pedido #1047 · Ana Santos" })).toBeInTheDocument();
    expect(screen.getByText("Cadastrou pedido")).toBeInTheDocument();
    expect(screen.getByText(/App Engenheiro Retirada/)).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "A prazo" })).not.toBeInTheDocument(); // só administrador
    await userEvent.type(screen.getByLabelText("Dinheiro apresentado (R$) *"), "100");
    await userEvent.click(screen.getByRole("button", { name: "Registrar pagamento" }));
    expect(await screen.findByText(/Troco a entregar: R\$ 25,00/)).toBeInTheDocument();
    const post = calls.find((c) => c.url === "/api/v1/orders/o1/payments" && c.init.method === "POST")!;
    expect(JSON.parse(String(post.init.body)).parts[0]).toMatchObject({ method: "CASH", amount: "75.00", presented_cash: "100" });
    expect((post.init.headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^web-/);
    // Sem capacidade de cancelar/devolver: botões não aparecem.
    const etapas = screen.getByRole("region", { name: "Etapas" });
    expect(within(etapas).queryByRole("button", { name: "Cancelar pedido" })).not.toBeInTheDocument();
  });
});
