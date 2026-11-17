import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../../session/SessionContext";
import { App } from "../../app/App";
import { caps, me, mockFetch } from "../../test/fixtures";

afterEach(() => vi.unstubAllGlobals());

const session = { body: { web_login: "CONFIGURADO", authenticated: true, login_url: "/oauth2/authorization/engenheiro" } };
const renderAt = (path: string) => render(<MemoryRouter initialEntries={[path]}><SessionProvider><App /></SessionProvider></MemoryRouter>);

const POS = { id: "p1", name: "Balconista", sector: "RETIRADA", suggested_template: "BALCAO", description: null, status: "ACTIVE",
  employees: 1, version: 1, created_at: "2026-11-17T12:00:00Z" };
const EMP = { id: "e1", full_name: "Carlos Fictício", branch_id: "b1", branch: "Matriz", job_position_id: "p1", job_position: "Balconista",
  sector: "RETIRADA", duties: "Atende o balcão", admission_date: "2026-03-02", termination_date: null, status: "ACTIVE",
  work_schedule: null, phone: null, cpf: null, birth_date: null, address: null, base_salary: null, notes: null, account: null,
  version: 1, created_at: "2026-11-17T12:00:00Z", created_by_name: "Patrão", masked_fields: ["cpf", "birth_date", "address", "base_salary"] };

describe("Equipe › Funcionários e cargos (ADR-027)", () => {
  it("lista com cargo, situação e conta; filtros na URL", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me() },
      "GET /api/v1/job-positions": { body: { items: [POS] } },
      "GET /api/v1/employees": { body: { items: [EMP, { ...EMP, id: "e2", full_name: "Paula Fictícia", account: { user_id: "u2",
        display_name: "Paula", status: "ACTIVE", role_template: "BALCAO", sector: "RETIRADA" } }], next_cursor: null } },
    });
    renderAt("/team/employees?status=ACTIVE&position=p1");
    const row = (await screen.findByRole("link", { name: "Carlos Fictício" })).closest("tr")!;
    expect(within(row).getByText("sem conta")).toBeInTheDocument();
    expect(within(row).getByText("Ativo")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Paula Fictícia" }).closest("tr")!).toHaveTextContent("Balcão");
    const q = calls.find((c) => c.url.startsWith("/api/v1/employees"))!.url;
    expect(q).toContain("status=ACTIVE");
    expect(q).toContain("job_position_id=p1");
  });

  it("ficha sem permissão de dados pessoais: mostra 'oculto' e o formulário não envia CPF nem salário", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("users.view", "users.edit_draft"), fields: {} }) },
      "GET /api/v1/employees/e1": { body: EMP, etag: '"1"' },
      "GET /api/v1/job-positions": { body: { items: [POS] } },
      "GET /api/v1/branches": { body: { items: [{ id: "b1", name: "Matriz", status: "ACTIVE" }] } },
      "GET /api/v1/employees/unlinked-accounts": { body: { items: [], account_creation: "NOT_CONFIGURED" } },
      "PUT /api/v1/employees/e1": { body: { ...EMP, version: 2, duties: "Caixa" } },
    });
    renderAt("/team/employees/e1");
    expect(await screen.findByRole("heading", { name: "Carlos Fictício" })).toBeInTheDocument();
    expect(screen.getAllByText("oculto").length).toBeGreaterThanOrEqual(3);
    expect(await screen.findByText("Criação de conta NÃO CONFIGURADA")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Editar" }));
    expect(screen.queryByLabelText("CPF")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Salário base (R$)")).not.toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText("Função / atividades"));
    await userEvent.type(screen.getByLabelText("Função / atividades"), "Caixa");
    await userEvent.click(screen.getByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(calls.some((c) => c.init.method === "PUT")).toBe(true));
    const put = calls.find((c) => c.init.method === "PUT")!;
    const body = JSON.parse(String(put.init.body));
    expect(body.duties).toBe("Caixa");
    expect(body).not.toHaveProperty("cpf");
    expect(body).not.toHaveProperty("base_salary");
    expect(body).not.toHaveProperty("phone");
    expect((put.init.headers as Record<string, string>)["If-Match"]).toBe('"1"');
  });

  it("dar acesso: modelo sugerido pelo cargo e senha temporária mostrada uma vez", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("users.view", "users.edit_draft", "permissions.edit_draft", "permissions.view") }) },
      "GET /api/v1/employees/e1": { body: { ...EMP, masked_fields: [] }, etag: '"1"' },
      "GET /api/v1/job-positions": { body: { items: [POS] } },
      "GET /api/v1/employees/unlinked-accounts": { body: { items: [], account_creation: "CONFIGURED" } },
      "POST /api/v1/employees/e1/access": { body: { employee: { ...EMP, version: 2 }, username: "carlos.ficticio", temporary_password: "TempAbc12345",
        provider: "keycloak", note: "Senha temporária mostrada só agora." } },
    });
    renderAt("/team/employees/e1");
    const user = await screen.findByLabelText("Nome de usuário *");
    expect(user).toHaveValue("carlos.ficticio");
    await waitFor(() => expect(screen.getByLabelText("Modelo de acesso *")).toHaveValue("BALCAO"));
    await userEvent.click(screen.getByRole("button", { name: "Criar conta e dar acesso" }));
    expect(await screen.findByText("TempAbc12345")).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === "POST")!;
    expect(JSON.parse(String(post.init.body))).toEqual({ username: "carlos.ficticio", role_template: "BALCAO" });
  });

  it("cargos: cria com setor e acesso sugerido", async () => {
    const calls = mockFetch({
      "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("users.view", "users.edit_draft") }) },
      "GET /api/v1/job-positions": { body: { items: [] } },
      "POST /api/v1/job-positions": { status: 201, body: { ...POS, id: "p2", name: "Motorista" } },
    });
    renderAt("/team/positions");
    await userEvent.click(await screen.findByRole("button", { name: /Novo cargo/ }));
    await userEvent.type(screen.getByLabelText("Nome do cargo *"), "Motorista");
    await userEvent.selectOptions(screen.getByLabelText("Setor *"), "ENTREGAS");
    await userEvent.selectOptions(screen.getByLabelText(/Acesso sugerido/), "MOTORISTA");
    await userEvent.click(screen.getByRole("button", { name: "Salvar cargo" }));
    await waitFor(() => expect(calls.some((c) => c.init.method === "POST")).toBe(true));
    expect(JSON.parse(String(calls.find((c) => c.init.method === "POST")!.init.body))).toMatchObject({
      name: "Motorista", sector: "ENTREGAS", suggested_template: "MOTORISTA", status: "ACTIVE" });
  });
});
