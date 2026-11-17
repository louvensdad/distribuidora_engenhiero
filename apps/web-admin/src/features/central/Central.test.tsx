import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../../session/SessionContext";
import { App } from "../../app/App";
import { isSafeReturn, rememberReturn, takeReturn } from "../../session/returnPath";
import { caps, me, mockFetch } from "../../test/fixtures";

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

const session = { body: { web_login: "CONFIGURADO", authenticated: true, login_url: "/oauth2/authorization/engenheiro" } };

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider><App /></SessionProvider>
    </MemoryRouter>,
  );
}

const ORDER_ID = "0b6f3c1e-1111-4a2b-9c3d-000000000001";
const ev = (over: Record<string, unknown>) => ({ id: crypto.randomUUID(), occurred_at: "2026-11-17T12:00:00Z", actor_user_id: "u1",
  actor_name: "Paula", actor_kind: "USER", client_id: "engenheiro-retirada", action: "orders.create", entity_type: "order",
  entity_id: ORDER_ID, result: "SUCCESS", reason: null, request_id: "req-1234567", ...over });

describe("F10 — central do patrão no site", () => {
  it("aprovações: agrupa por setor, mostra quem pediu e leva à tela de decisão (sem aprovar sozinha)", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/approvals": { body: {
        items: [
          { kind: "TAX", sector: "ACCOUNTING", id: "t1", title: "Guia DAS: Guia fictícia", detail: "Competência 2026-11", amount: "850.00",
            requested_by: "Fernanda", requested_at: "2026-11-17T12:00:00Z", path: "/finance/taxes?state=SUBMITTED", note: "Aprovar não paga: o pagamento é outra etapa." },
          { kind: "LOSS", sector: "COUNTER", id: "l1", title: "Perda: Cana caiana", detail: "5 dúzias", amount: null,
            requested_by: "Paula", requested_at: "2026-11-17T11:00:00Z", path: "/operation/stock", note: null },
        ],
        sectors: [{ sector: "COUNTER", pending: 1 }, { sector: "DELIVERY", pending: 0 }, { sector: "ACCOUNTING", pending: 1 },
          { sector: "FINANCE", pending: 0 }, { sector: "TEAM", pending: 0 }] } },
    });
    renderAt("/overview/approvals");
    const contab = await screen.findByRole("region", { name: /Contabilidade/ });
    expect(within(contab).getByText("Guia DAS: Guia fictícia")).toBeInTheDocument();
    expect(within(contab).getByText(/R\$\s*850,00/)).toBeInTheDocument();
    expect(within(contab).getByText(/Pedido por Fernanda/)).toBeInTheDocument();
    expect(within(contab).getByText(/não paga/)).toBeInTheDocument();
    expect(within(contab).getByRole("link", { name: "Abrir para decidir" })).toHaveAttribute("href", "/finance/taxes?state=SUBMITTED");
    expect(screen.queryByRole("region", { name: /Entregas/ })).not.toBeInTheDocument();
    // Nenhum botão "Aprovar" aqui: a decisão é feita no módulo.
    expect(screen.queryByRole("button", { name: /^aprovar/i })).not.toBeInTheDocument();
  });

  it("setores: três setores com pendências e últimos movimentos com autor, app e link de origem", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/approvals": { body: { items: [], sectors: [{ sector: "COUNTER", pending: 2 }, { sector: "DELIVERY", pending: 0 }, { sector: "ACCOUNTING", pending: 0 }] } },
      "GET /api/v1/audit-events?sector=COUNTER&limit=6&result=SUCCESS": { body: { items: [ev({})], next_cursor: null } },
      "GET /api/v1/audit-events?sector=DELIVERY&limit=6&result=SUCCESS": { body: { items: [], next_cursor: null } },
      "GET /api/v1/audit-events?sector=ACCOUNTING&limit=6&result=SUCCESS": { body: { items: [ev({ actor_name: "Fernanda", client_id: "engenheiro-contabil",
        action: "taxes.create", entity_type: "tax_obligation", entity_id: "x" })], next_cursor: null } },
    });
    renderAt("/overview/sectors");
    const counter = await screen.findByRole("article", { name: /Retirada, balcão e fornecedores/ });
    const li = (await within(counter).findByText("Paula")).closest("li")!;
    expect(within(li).getByText(/App Engenheiro Retirada/)).toBeInTheDocument();
    expect(within(counter).getByRole("link", { name: "abrir" })).toHaveAttribute("href", `/sales/orders/${ORDER_ID}`);
    expect(within(counter).getByRole("link", { name: /2\s*esperando sua decisão/ })).toBeInTheDocument();
    expect(within(counter).getByRole("link", { name: "Histórico do setor" })).toHaveAttribute("href", "/team/audit?sector=COUNTER");
    const acc = screen.getByRole("article", { name: /Contabilidade e documentos/ });
    expect(await within(acc).findByText("Fernanda")).toBeInTheDocument();
    expect(within(acc).getByRole("link", { name: "abrir" })).toHaveAttribute("href", "/finance/taxes");
    // As três consultas usam a MESMA sessão (sem trocar de conta) e o filtro de setor do servidor.
    expect(calls.filter((c) => c.url.includes("sector=")).length).toBe(3);
  });

  it("histórico: filtros vêm da URL (link profundo), 'até' inclusivo vira fim exclusivo, e há 'Abrir origem'", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/users": { body: { items: [], next_cursor: null } },
      "GET /api/v1/audit-events": { body: { items: [ev({ actor_name: "Patrão Teste", client_id: "engenheiro-web", action: "orders.cancel" })], next_cursor: null } },
    });
    renderAt("/team/audit?sector=COUNTER&from=2026-11-01&until=2026-11-30");
    const item = (await screen.findByRole("link", { name: "Abrir origem" })).closest("li")!;
    expect(within(item).getByText("Patrão Teste")).toBeInTheDocument();
    expect(within(item).getByText(/Site do patrão/)).toBeInTheDocument();
    expect(within(item).getByRole("link", { name: "Abrir origem" })).toHaveAttribute("href", `/sales/orders/${ORDER_ID}`);
    const q = calls.find((c) => c.url.startsWith("/api/v1/audit-events"))!.url;
    expect(q).toContain("sector=COUNTER");
    expect(q).toContain("from=2026-11-01");
    expect(q).toContain("to=2026-12-01");
    expect(screen.getByLabelText("Setor")).toHaveValue("COUNTER");
  });

  it("busca geral: topo leva a /search, resultados agrupados com link; menos de 2 letras não consulta", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/reports/overview": { body: null, status: 404 },
      "GET /api/v1/search": { body: { query: "mercado", searched: ["orders", "customers"], items: [
        { type: "customer", id: "c1", title: "Mercado Fictício", subtitle: "Matriz", path: "/sales/customers?q=Mercado%20Fict%C3%ADcio" },
        { type: "order", id: ORDER_ID, title: "Pedido #1048 · Mercado Fictício", subtitle: "Entrega", path: `/sales/orders/${ORDER_ID}` }] } },
    });
    renderAt("/search?q=m");
    expect(await screen.findByText("Digite pelo menos 2 caracteres")).toBeInTheDocument();
    expect(calls.some((c) => c.url.startsWith("/api/v1/search"))).toBe(false);
    const top = screen.getByRole("searchbox", { name: /Buscar pedido, cliente, placa/ });
    await userEvent.clear(top);
    await userEvent.type(top, "mercado{Enter}");
    expect(await screen.findByRole("link", { name: "Pedido #1048 · Mercado Fictício" })).toHaveAttribute("href", `/sales/orders/${ORDER_ID}`);
    expect(screen.getByRole("region", { name: "Clientes" })).toBeInTheDocument();
    expect(screen.getByText(/Procurado em: pedidos, clientes/)).toBeInTheDocument();
    expect(calls.some((c) => c.url === "/api/v1/search?q=mercado")).toBe(true);
  });

  it("endereço inexistente mostra 'página não encontrada' (não 'sem permissão')", async () => {
    mockFetch({ "GET /bff/session": session, "GET /api/v1/me/capabilities": { body: me() } });
    renderAt("/nao/existe");
    expect(await screen.findByRole("heading", { name: "Página não encontrada" })).toBeInTheDocument();
  });
});

describe("F10 — listas: paginação, filtros na URL e erro de rede", () => {
  const order = (n: number) => ({ id: `o${n}`, number: n, type: "PICKUP", state: "CONFIRMED", payment_state: "UNPAID", customer_id: "c1",
    customer_name: `Cliente ${n}`, branch: "Matriz", gross_total: "10.00", discount_total: "0.00", total: "10.00", effective_total: "10.00",
    paid_amount: "0.00", pending_amount: "0.00", on_account: "0.00", balance_due: "10.00", notes: null, created_by_name: "Paula",
    created_at: "2026-11-17T12:00:00Z", cancel_reason: null, version: 1, lines: [], delivery: null, dispatch: null, masked_fields: [] });

  it("pedidos: busca e filtro ficam na URL; 'Carregar mais' acrescenta; falha de rede mantém o que já foi carregado", async () => {
    let page2Fails = true;
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { "Content-Type": "application/json" } });
      if (url === "/bff/session") return json(session.body);
      if (url === "/api/v1/me/capabilities") return json(me({ capabilities: caps("orders.pickup.view") }));
      if (url.startsWith("/api/v1/orders") && url.includes("cursor=")) {
        if (page2Fails) { page2Fails = false; throw new TypeError("Failed to fetch"); }
        return json({ items: [order(2)], next_cursor: null });
      }
      if (url.startsWith("/api/v1/orders")) return json({ items: [order(3)], next_cursor: "c2" });
      return new Response("{}", { status: 404 });
    }));
    renderAt("/sales/orders?type=PICKUP&q=cliente");
    expect(await screen.findByRole("rowheader", { name: "#3" })).toBeInTheDocument();
    const first = calls.find((u) => u.startsWith("/api/v1/orders"))!;
    expect(first).toContain("type=PICKUP");
    expect(first).toContain("q=cliente");
    expect(screen.getByLabelText("Setor")).toHaveValue("PICKUP");
    expect(screen.getByRole("searchbox", { name: /Buscar pedido por número ou cliente/ })).toHaveValue("cliente");
    await userEvent.click(screen.getByRole("button", { name: "Carregar mais" }));
    expect(await screen.findByText("Sem conexão com o servidor")).toBeInTheDocument();
    expect(screen.getByRole("rowheader", { name: "#3" })).toBeInTheDocument(); // nada perdido
    await userEvent.click(screen.getByRole("button", { name: /Tentar novamente/ }));
    expect(await screen.findByRole("rowheader", { name: "#2" })).toBeInTheDocument();
    expect(screen.getByText(/2 registro\(s\) — fim da lista/)).toBeInTheDocument();
    expect(calls.filter((u) => u.includes("cursor=c2")).length).toBe(2);
  });
});

describe("F10 — sessão expirada e volta ao endereço", () => {
  it("401 com a tela aberta: aviso por cima, o texto digitado continua; 'Já entrei' com a mesma conta segue", async () => {
    let expired = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = String(input);
      const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/json" } });
      if (url === "/bff/session") return json(session.body);
      if (url === "/api/v1/me/capabilities") return json(me());
      if (url.startsWith("/api/v1/customers") && (init.method ?? "GET") === "POST") {
        if (!expired) { expired = true; return json({ status: 401, code: "UNAUTHENTICATED" }, 401); }
        return json({ id: "c9" }, 201);
      }
      if (url.startsWith("/api/v1/customers")) return json({ items: [], next_cursor: null });
      return new Response("{}", { status: 404 });
    }));
    renderAt("/sales/customers");
    await userEvent.click(await screen.findByRole("button", { name: /novo cliente/i }));
    await userEvent.type(screen.getByLabelText("Nome *"), "Cliente digitado antes de expirar");
    await userEvent.click(screen.getByRole("button", { name: "Cadastrar cliente" }));
    const dialog = await screen.findByRole("alertdialog", { name: /sessão terminou/ });
    expect(within(dialog).getByRole("link", { name: /Entrar em outra aba/ })).toHaveAttribute("target", "_blank");
    expect(screen.getByLabelText("Nome *")).toHaveValue("Cliente digitado antes de expirar"); // nada perdido
    await userEvent.click(within(dialog).getByRole("button", { name: "Já entrei, continuar" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(screen.getByLabelText("Nome *")).toHaveValue("Cliente digitado antes de expirar");
  });

  it("se outra conta entrou, recarrega do zero (não mistura identidades)", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign, pathname: "/sales/customers", search: "", hash: "" });
    let calls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/json" } });
      if (url === "/bff/session") return json(session.body);
      if (url === "/api/v1/me/capabilities") {
        calls++;
        return json(calls === 1 ? me() : me({ user: { id: "u-outro", display_name: "Outra Pessoa" } }));
      }
      if (url.startsWith("/api/v1/customers")) return json({ status: 401, code: "UNAUTHENTICATED" }, 401);
      return new Response("{}", { status: 404 });
    }));
    renderAt("/sales/customers");
    const dialog = await screen.findByRole("alertdialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Já entrei, continuar" }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/"));
  });

  it("volta só para caminho interno do site", () => {
    expect(isSafeReturn("/sales/orders/abc?x=1#y")).toBe(true);
    for (const bad of ["//evil.example/x", "https://evil.example", "/\\evil", "/bff/logout", "/oauth2/authorization/x", "/api/v1/me", ""]) {
      expect(isSafeReturn(bad)).toBe(false);
    }
    rememberReturn("/team/audit?sector=COUNTER");
    expect(takeReturn()).toBe("/team/audit?sector=COUNTER");
    expect(takeReturn()).toBeNull(); // usado uma vez
    rememberReturn("//evil.example");
    expect(takeReturn()).toBeNull();
  });

  it("depois de entrar, abre o endereço guardado (link profundo preservado)", async () => {
    sessionStorage.setItem("engenheiro:voltar", "/team/audit?sector=DELIVERY");
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/users": { body: { items: [], next_cursor: null } },
      "GET /api/v1/audit-events": { body: { items: [], next_cursor: null } },
      "GET /api/v1/reports/overview": { status: 404, body: null },
    });
    renderAt("/");
    expect(await screen.findByRole("heading", { name: "Histórico" })).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.url.includes("sector=DELIVERY"))).toBe(true));
  });
});
