import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../session/SessionContext";
import { App } from "./App";
import { caps, me, mockFetch } from "../test/fixtures";

afterEach(() => vi.unstubAllGlobals());

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider><App /></SessionProvider>
    </MemoryRouter>,
  );
}

const session = { body: { web_login: "CONFIGURADO", authenticated: true, login_url: "/oauth2/authorization/engenheiro" } };

describe("site do patrão", () => {
  it("sem sessão mostra a entrada segura apontando para o login do servidor", async () => {
    mockFetch({ "GET /bff/session": { body: { web_login: "CONFIGURADO", authenticated: false, login_url: "/oauth2/authorization/engenheiro" } } });
    renderAt("/");
    const link = await screen.findByRole("link", { name: /entrar com segurança/i });
    expect(link).toHaveAttribute("href", "/oauth2/authorization/engenheiro");
    expect(screen.getByRole("heading", { name: "Distribuidora de Cana Engenheiro Ltda" })).toBeInTheDocument();
  });

  it("login web não configurado aparece explicitamente", async () => {
    mockFetch({ "GET /bff/session": { body: { web_login: "NAO_CONFIGURADO", authenticated: false } } });
    renderAt("/");
    expect(await screen.findByText(/NÃO CONFIGURADO/)).toBeInTheDocument();
  });

  it("conta sem acesso ao site (403) não vê o painel", async () => {
    mockFetch({ "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { status: 403, body: { status: 403, code: "FORBIDDEN", request_id: "r-9" } } });
    renderAt("/");
    expect(await screen.findByText(/não tem acesso ao site do patrão/)).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Seções principais" })).not.toBeInTheDocument();
  });

  it("clientes: campos ocultos pela política aparecem como ocultos, sem valor", async () => {
    mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("customers.view"), fields: { operational_contact: true } }) },
      "GET /api/v1/customers": { body: { items: [{ id: "c1", name: "Mercado Exemplo", branch: "Matriz", contact: "(11) 9", address: null,
        personal_document: null, status: "ACTIVE", version: 1, created_at: "2026-11-14T12:00:00Z", masked_fields: ["address", "personal_document"] }], next_cursor: null } },
    });
    renderAt("/sales/customers");
    const row = (await screen.findByRole("rowheader", { name: "Mercado Exemplo" })).closest("tr")!;
    expect(within(row).getByText("(11) 9")).toBeInTheDocument();
    expect(within(row).getAllByText("oculto")).toHaveLength(2);
    expect(screen.queryByRole("button", { name: /novo cliente/i })).not.toBeInTheDocument();
    const tabs = screen.getByRole("navigation", { name: "Seções principais" });
    expect(within(tabs).queryByText("Equipe")).not.toBeInTheDocument();
  });

  it("rota sem capacidade mostra 'sem permissão' e não chama a API daquele recurso", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("customers.view") }) },
      "GET /api/v1/customers": { body: { items: [], next_cursor: null } },
    });
    renderAt("/team/audit");
    expect(await screen.findByRole("heading", { name: "Sem permissão" })).toBeInTheDocument();
    expect(calls.some((c) => c.url.startsWith("/api/v1/audit-events"))).toBe(false);
  });

  it("módulo de fase futura é rotulado como pendente, sem números de exemplo", async () => {
    mockFetch({ "GET /bff/session": session, "GET /api/v1/me/capabilities": { body: me() } });
    renderAt("/sales/orders");
    expect(await screen.findByText(/Pedidos: em implementação/)).toBeInTheDocument();
    expect(screen.queryByText(/R\$/)).not.toBeInTheDocument();
  });

  it("mapa de acessos impede editar o próprio acesso", async () => {
    mockFetch({ "GET /bff/session": session, "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/users": { body: { items: [], next_cursor: null } } });
    renderAt("/team/access?user=u-patrao");
    expect(await screen.findByText(/não pode alterar o próprio acesso/)).toBeInTheDocument();
  });

  it("mapa de acessos: versão desatualizada (412) mostra conflito sem sobrescrever", async () => {
    const policy = { user: { id: "u2", display_name: "Balcão", status: "ACTIVE", sector: "RETIRADA", role_template: "BALCAO", admin: false, branch: "Matriz", version: 2 },
      clients: ["engenheiro-retirada"], grants: [], fields: [] };
    const calls = mockFetch({
      "GET /bff/session": session, "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/users": { body: { items: [policy.user], next_cursor: null } },
      "GET /api/v1/role-templates": { body: [] },
      "GET /api/v1/users/u2/access-policy": { body: policy, etag: '"2"' },
      "POST /api/v1/users/u2/access-policy": { status: 412, body: { status: 412, code: "VERSION_CONFLICT" } },
    });
    const user = userEvent.setup();
    renderAt("/team/access?user=u2");
    await user.click(await screen.findByText("Clientes", { selector: ".group-name" }));
    const select = screen.getByLabelText("Ver", { selector: "#ef-customers-view" });
    await user.selectOptions(select, "ALLOW");
    expect(screen.getByText("Clientes · Ver: sem acesso → permitido (Próprios registros)")).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Motivo/, { selector: "#motivo-salvar" }), "teste");
    await user.click(screen.getByRole("button", { name: "Salvar permissões" }));
    expect(await screen.findByText(/mudou enquanto você editava/)).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === "POST")!;
    expect(new Headers(post.init.headers as HeadersInit).get("If-Match")).toBe('"2"');
    expect(JSON.parse(String(post.init.body))).toMatchObject({ reason: "teste",
      grants: [{ resource: "customers", action: "view", scope: "OWN", effect: "ALLOW" }] });
  });
});
