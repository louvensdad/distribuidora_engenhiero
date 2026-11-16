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

const line = { line_no: 1, product_id: "p1", sku: "CANA", product_name: "Cana caiana", unit: "DUZIA", factor: "1", quantity: "24",
  base_quantity: "24", base_unit: "DUZIA", unit_price: "7.50", gross: "180.00", discount: "0.00", net: "180.00",
  fulfilled_quantity: "0", cancelled_quantity: "0", returned_quantity: "0", remaining_quantity: "24" };
const stopOrder = (over: Record<string, unknown> = {}) => ({
  id: "o1", number: 1048, customer_name: "Mercado Central", state: "CONFIRMED", payment_state: "PAID", effective_total: "180.00",
  paid_amount: "180.00", pending_amount: "0.00", on_account: "0.00", balance_due: "0.00", lines: [line],
  delivery: { address: "Rua Exemplo, 100", reference: null, contact: "(11) 90000-0000", window_start: null, window_end: null, agreed_payment: "PIX" },
  masked_fields: [], ...over,
});
const trip = (over: Record<string, unknown> = {}) => ({
  id: "t1", number: 82, state: "DEPARTED", planned_date: "2026-11-16", notes: null, vehicle_id: "v1", plate: "ABC1D23", vehicle_model: "Fiorino",
  capacity_quantity: "100", capacity_unit: "DUZIA", load: "40", driver_id: "u-mot", driver_name: "Carlos", departed_at: "2026-11-16T12:00:00Z",
  departure_odometer: "45200.0", returned_at: null, return_odometer: null, distance_km: null, returned_by_name: null, cash_collected: null,
  cash_expenses: null, cash_expected: null, cash_declared: null, cash_difference: null, return_note: null, reconciled_at: null,
  reconciled_by_name: null, reconcile_note: null, cancel_reason: null, created_by_name: "Marcos", created_at: "2026-11-16T11:00:00Z", version: 4,
  stops: [
    { id: "s1", seq: 1, state: "PENDING", recipient_name: null, delivered_at: null, delivered_by_name: null, order: stopOrder(), load: "24", incidents: [] },
    { id: "s2", seq: 2, state: "PENDING", recipient_name: null, delivered_at: null, delivered_by_name: null, load: "16", incidents: [],
      order: stopOrder({ id: "o2", number: 1046, customer_name: "Feira do Bairro", payment_state: "UNPAID", paid_amount: "0.00", balance_due: "120.00",
        delivery: { address: null, reference: null, contact: null, window_start: null, window_end: null, agreed_payment: "CASH" },
        masked_fields: ["delivery.address", "delivery.reference", "delivery.contact"],
        lines: [{ ...line, quantity: "16", remaining_quantity: "16", net: "120.00" }] }) },
  ],
  incidents: [], ...over,
});

describe("F05 viagens no site", () => {
  it("motorista: vê só o necessário, Pix pago não é cobrado e a entrega vai com chave idempotente", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, user: { id: "u-mot", display_name: "Carlos" },
        capabilities: caps("trips.view", "trips.confirm", "payments.confirm"), fields: { delivery_address: true, operational_contact: true } }) },
      "GET /api/v1/trips/t1": { body: trip(), etag: '"4"' },
      "GET /api/v1/trips/t1/history": { body: { items: [{ occurred_at: "2026-11-16T12:00:00Z", action: "trips.depart", actor_name: "Carlos", client_id: "engenheiro-entregas" }] } },
      "POST /api/v1/trips/t1/deliveries": { body: trip() },
    });
    renderAt("/operation/trips/t1");
    expect(await screen.findByRole("heading", { name: "Viagem V82" })).toBeInTheDocument();
    expect(screen.getByText("Registrou saída da viagem")).toBeInTheDocument();
    const p1 = screen.getByRole("article", { name: "Parada 1: pedido 1048" });
    expect(within(p1).getByText(/Rua Exemplo, 100/)).toBeInTheDocument();
    expect(within(p1).getByText(/Pago — não cobrar novamente/)).toBeInTheDocument();
    const p2 = screen.getByRole("article", { name: "Parada 2: pedido 1046" });
    expect(within(p2).getAllByText("oculto").length).toBeGreaterThan(0);
    expect(within(p2).getByText(/Cobrar R\$ 120,00 \(combinado: Dinheiro\)/)).toBeInTheDocument();
    // Sem custos nem atribuição para o motorista.
    expect(screen.queryByRole("heading", { name: "Custo da viagem e rateio por entrega" })).not.toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Atribuir carro e motorista" })).not.toBeInTheDocument();
    expect(calls.some((c) => c.url.includes("/costs"))).toBe(false);

    await userEvent.click(within(p1).getByText("Confirmar entrega", { selector: "summary" }));
    const form = within(p1).getByRole("form", { name: "Entrega do pedido 1048" });
    expect(within(form).queryByText(/Recebimento na entrega/)).not.toBeInTheDocument(); // já pago
    await userEvent.type(within(form).getByLabelText("Quem recebeu *"), "Ana (gerente)");
    await userEvent.click(within(form).getByRole("button", { name: "Confirmar entrega" }));
    const post = calls.find((c) => c.url === "/api/v1/trips/t1/deliveries")!;
    expect(JSON.parse(String(post.init.body))).toMatchObject({ stop_id: "s1", recipient_name: "Ana (gerente)", lines: null, payment: null, partial_reason: null });
    expect((post.init.headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^web-/);
  });

  it("entrega parcial exige motivo e manda só as quantidades entregues, com o dinheiro apresentado", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("trips.view", "trips.confirm", "payments.confirm"), fields: {} }) },
      "GET /api/v1/trips/t1": { body: trip() },
      "GET /api/v1/trips/t1/history": { body: { items: [] } },
      "POST /api/v1/trips/t1/deliveries": { status: 422, body: { status: 422, code: "VALIDATION_ERROR", message: "Entrega parcial exige o motivo (gera ocorrência).",
        errors: [{ field: "partial_reason", message: "Informe o motivo da entrega parcial" }] } },
    });
    renderAt("/operation/trips/t1");
    const p2 = await screen.findByRole("article", { name: "Parada 2: pedido 1046" });
    await userEvent.click(within(p2).getByText("Confirmar entrega", { selector: "summary" }));
    const form = within(p2).getByRole("form", { name: "Entrega do pedido 1046" });
    await userEvent.type(within(form).getByLabelText("Quem recebeu *"), "João");
    const q = within(form).getByLabelText(/Cana caiana entregue/);
    await userEvent.clear(q);
    await userEvent.type(q, "12");
    expect(within(form).getByLabelText(/Motivo da entrega parcial/)).toBeInTheDocument();
    await userEvent.type(within(form).getByLabelText("Valor entregue pelo cliente (R$) *"), "150");
    await userEvent.click(within(form).getByRole("button", { name: "Confirmar entrega" }));
    expect(await within(form).findByText("Informe o motivo da entrega parcial")).toBeInTheDocument();
    const body = JSON.parse(String(calls.find((c) => c.url === "/api/v1/trips/t1/deliveries")!.init.body));
    expect(body.lines).toEqual([{ line_no: 1, quantity: "12" }]);
    expect(body.payment.parts[0]).toMatchObject({ method: "CASH", amount: "120.00", presented_cash: "150" });
  });

  it("coordenação: atribuição recusada mostra o motivo do servidor (capacidade) e o rateio aparece com a regra", async () => {
    const draft = trip({ state: "DRAFT", vehicle_id: null, plate: null, driver_id: null, driver_name: null, load: null, capacity_unit: null,
      departure_odometer: null, departed_at: null, stops: [] });
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("trips.view", "trips.assign", "vehicles.view", "orders.delivery.view", "expenses.view"), fields: {} }) },
      "GET /api/v1/trips/t1": { body: draft },
      "GET /api/v1/trips/t1/history": { body: { items: [] } },
      "GET /api/v1/trips/t1/costs": { body: { trip_id: "t1", rule_code: "TRIP_COST", rule_version: 1, rule_description: "Proporcional à quantidade entregue.",
        capacity_unit: "DUZIA", total_cost: "100.00", allocated_total: "100.00", unallocated: "0.00", pending_cost: "0.00", distance_km: null, cost_per_km: null,
        lines: [{ stop_id: "s1", order_number: 1, customer_name: "A", delivered_quantity: "1", share: "0.333333", allocated_cost: "33.33" },
          { stop_id: "s3", order_number: 3, customer_name: "C", delivered_quantity: "1", share: "0.333334", allocated_cost: "33.34" }], expenses: [], message: null } },
      "GET /api/v1/vehicle-expenses?trip_id=t1": { body: { items: [] } },
      "GET /api/v1/vehicles?status=ACTIVE": { body: { items: [{ id: "v1", plate: "AAA1A11", model: "Fiorino", capacity_quantity: "10", capacity_unit: "DUZIA",
        status: "ACTIVE", current_trip_id: null, current_trip_number: null }] } },
      "GET /api/v1/trips/eligible-drivers": { body: { items: [{ id: "u-mot", name: "Carlos", busy: false }] } },
      "GET /api/v1/orders?type=DELIVERY&limit=100": { body: { items: [{ id: "o9", number: 9, state: "CONFIRMED", payment_state: "UNPAID", customer_name: "Mercado",
        lines: [{ ...line, remaining_quantity: "12" }], delivery: { agreed_payment: "CASH" }, dispatch: null }], next_cursor: null } },
      "POST /api/v1/trips/t1/assignments": { status: 422, body: { status: 422, code: "VALIDATION_ERROR", message: "A atribuição não é válida.",
        errors: [{ field: "vehicle_id", message: "Carga 12 acima da capacidade do veículo (10 DUZIA)" }] } },
    });
    renderAt("/operation/trips/t1");
    const form = await screen.findByRole("form", { name: "Atribuir carro e motorista" });
    await userEvent.selectOptions(within(form).getByLabelText("Veículo e placa *"), "v1");
    await userEvent.selectOptions(within(form).getByLabelText("Motorista autorizado *"), "u-mot");
    await userEvent.click(within(form).getByRole("checkbox"));
    await userEvent.click(within(form).getByRole("button", { name: "Validar capacidade e atribuir" }));
    expect(await within(form).findByText(/acima da capacidade do veículo/)).toBeInTheDocument();
    const custos = screen.getByRole("region", { name: "Custo da viagem e rateio por entrega" });
    expect(within(custos).getByText(/Regra TRIP_COST v1/)).toBeInTheDocument();
    expect(within(custos).getByText("R$ 33,34")).toBeInTheDocument();
  });

  it("veículo: consumo sem dados suficientes não inventa km/l e o abastecimento manda o odômetro", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("vehicles.view", "fuel.create", "fuel.view"), fields: {} }) },
      "GET /api/v1/vehicles/v1": { body: { id: "v1", plate: "ABC1D23", model: "Fiorino", capacity_quantity: "100", capacity_unit: "DUZIA", status: "ACTIVE",
        odometer_km: "45200.0", next_service_km: "45000.0", registration_valid_until: null, insurance_valid_until: null, notes: null, branch: "Matriz",
        current_trip_id: null, current_trip_number: null, current_driver_name: null, version: 1, created_at: "2026-11-16T12:00:00Z",
        alerts: [{ code: "SERVICE_DUE", message: "Revisão prevista para 45000.0 km (aviso; não bloqueia entrega)" }],
        consumption: { state: "INSUFFICIENT_DATA", km_per_liter: null, km: null, liters: null, full_tank_fills: 1, message: "Consumo exige ao menos dois abastecimentos de tanque cheio." } } },
      "GET /api/v1/vehicle-expenses?vehicle_id=v1": { body: { items: [] } },
      "GET /api/v1/vehicles/v1/odometer": { body: { items: [] } },
      "GET /api/v1/vehicles/v1/history": { body: { items: [] } },
      "POST /api/v1/vehicles/v1/fuel": { status: 422, body: { status: 422, code: "VALIDATION_ERROR", message: "Corrija os campos indicados.",
        errors: [{ field: "odometer", message: "Menor que o último odômetro (45200.0 km)" }] } },
    });
    renderAt("/operation/fleet/v1");
    expect(await screen.findByText("Dados insuficientes")).toBeInTheDocument();
    expect(screen.getByText(/não bloqueia entrega/)).toBeInTheDocument();
    await userEvent.click(screen.getByText("Registrar abastecimento", { selector: "summary" }));
    const form = screen.getByRole("form", { name: "Abastecimento" });
    await userEvent.type(within(form).getByLabelText(/Odômetro \(km\)/), "45.100,5");
    await userEvent.type(within(form).getByLabelText("Litros *"), "30");
    await userEvent.type(within(form).getByLabelText("Valor pago (R$) *"), "180,00");
    await userEvent.click(within(form).getByRole("button", { name: "Registrar abastecimento" }));
    expect(await within(form).findByText(/Menor que o último odômetro/)).toBeInTheDocument();
    const body = JSON.parse(String(calls.find((c) => c.url === "/api/v1/vehicles/v1/fuel")!.init.body));
    expect(body).toMatchObject({ odometer: "45100.5", liters: "30", amount: "180.00", full_tank: true, paid_with: "COMPANY" });
  });
});
