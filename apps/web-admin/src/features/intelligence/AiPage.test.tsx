import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../../session/SessionContext";
import { App } from "../../app/App";
import { caps, me, mockFetch } from "../../test/fixtures";

afterEach(() => vi.unstubAllGlobals());

const session = { body: { web_login: "CONFIGURADO", authenticated: true, login_url: "/oauth2/authorization/engenheiro" } };
const status = { state: "CONFIGURED", message: "IA ativa: respostas com período, fontes e custo registrados.", provider: "anthropic",
  model: "claude-opus-5-5", budget_usd: "20.00", spent_usd: "0.10", reserved_usd: "0.00", remaining_usd: "19.90", daily_limit: 30,
  used_today: 2, tools: ["consultar_resumo"] };
const answered = { id: "q1", question: "Quanto gastei este mês?", from: "2026-11-01", to: "2026-11-30", state: "ANSWERED",
  answer: "Despesas do período: R$ 1.234,56.", limitations: ["Sem permissão para folha."],
  tools: [{ name: "consultar_resumo", label: "resultado do período", input: {}, ok: true, error: null },
    { name: "consultar_pendencias_contador", label: "pendências do contador", input: {}, ok: false, error: "SEM_PERMISSAO" }],
  sources: [{ tool: "consultar_resumo", label: "Relatório 01/11/2026 a 30/11/2026", href: "/reports" }], provider: "anthropic",
  model: "claude-opus-5-5", input_tokens: 2200, output_tokens: 350, cache_read_tokens: 0, cost_usd: "0.015800", latency_ms: 2300,
  error_code: null, cutoff_at: "2026-11-19T18:00:00Z", created_at: "2026-11-19T18:00:01Z", client_id: "engenheiro-web" };

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><SessionProvider><App /></SessionProvider></MemoryRouter>);
}

describe("IA da gestão (F15)", () => {
  it("pergunta com período e mostra resposta, fontes autorizadas, limitações, modelo e custo", async () => {
    const calls = mockFetch({ "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("ai.view", "reports.view") }) },
      "GET /api/v1/ai/status": { body: status }, "GET /api/v1/ai/questions": { body: { items: [] } },
      "POST /api/v1/ai/questions": { status: 201, body: answered } });
    renderAt("/intelligence");
    expect(await screen.findByText(/Modelo claude-opus-5-5/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Pergunta"), "Quanto gastei este mês?");
    await userEvent.click(screen.getByRole("button", { name: "Perguntar" }));
    const card = await screen.findByRole("article", { name: "Resposta da IA" });
    expect(within(card).getByText("Despesas do período: R$ 1.234,56.")).toBeInTheDocument();
    expect(within(card).getByText(/01\/11\/2026 a 30\/11\/2026 \(fuso/)).toBeInTheDocument();
    expect(within(card).getByText(/pendências do contador \(sem permissão\)/)).toBeInTheDocument();
    expect(within(card).getByText("Sem permissão para folha.")).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: /Relatório 01\/11\/2026/ })).toHaveAttribute("href", "/reports");
    expect(within(card).getByText(/US\$ 0.015800/)).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === "POST" && c.url === "/api/v1/ai/questions")!;
    const body = JSON.parse(String(post.init.body));
    expect(body.question).toBe("Quanto gastei este mês?");
    expect(body.from).toMatch(/^\d{4}-\d{2}-01$/);
    expect(body.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("orçamento esgotado bloqueia o formulário e explica o motivo", async () => {
    mockFetch({ "GET /bff/session": session, "GET /api/v1/me/capabilities": { body: me({ capabilities: caps("ai.view") }) },
      "GET /api/v1/ai/status": { body: { ...status, state: "BUDGET_EXHAUSTED", message: "Orçamento mensal da IA esgotado." } },
      "GET /api/v1/ai/questions": { body: { items: [answered] } } });
    renderAt("/intelligence");
    expect(await screen.findByText("Orçamento mensal da IA esgotado.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Perguntar" })).not.toBeInTheDocument();
    // O histórico continua disponível, com a resposta registrada.
    await userEvent.click(await screen.findByRole("button", { name: "Quanto gastei este mês?" }));
    expect(await screen.findByText("Despesas do período: R$ 1.234,56.")).toBeInTheDocument();
  });

  it("sem ai.view a rota não consulta a IA", async () => {
    const calls = mockFetch({ "GET /bff/session": session,
      "GET /api/v1/me/capabilities": { body: me({ admin: false, capabilities: caps("reports.view") }) } });
    renderAt("/intelligence");
    expect(await screen.findByRole("heading", { name: "Sem permissão" })).toBeInTheDocument();
    expect(calls.some((c) => c.url.startsWith("/api/v1/ai"))).toBe(false);
  });
});
