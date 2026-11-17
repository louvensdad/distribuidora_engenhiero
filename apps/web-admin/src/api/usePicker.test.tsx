import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { SessionProvider } from "../session/SessionContext";
import { App } from "../app/App";
import { caps, me } from "../test/fixtures";

afterEach(() => vi.unstubAllGlobals());

describe("seletor com busca (listas acima de 100)", () => {
  it("novo pedido: cliente fora dos primeiros resultados é achado pela busca no servidor e continua selecionado", async () => {
    const urls: string[] = [];
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { "Content-Type": "application/json" } });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      urls.push(url);
      if (url === "/bff/session") return json({ web_login: "CONFIGURADO", authenticated: true, login_url: "/x" });
      if (url === "/api/v1/me/capabilities") return json(me({ capabilities: caps("orders.pickup.create", "orders.pickup.view") }));
      if (url.startsWith("/api/v1/customers")) {
        const q = new URL(url, "http://x").searchParams.get("q");
        return json({ items: q ? [{ id: "c999", name: "Zulmira Fictícia", branch: "Matriz", masked_fields: [] }]
          : [{ id: "c1", name: "Ana Fictícia", branch: "Matriz", masked_fields: [] }], next_cursor: "mais" });
      }
      if (url.startsWith("/api/v1/products")) return json({ items: [], next_cursor: null });
      return new Response("{}", { status: 404 });
    }));
    render(<MemoryRouter initialEntries={["/sales/orders/new"]}><SessionProvider><App /></SessionProvider></MemoryRouter>);
    const select = await screen.findByLabelText("Cliente *");
    expect(screen.queryByRole("option", { name: "Zulmira Fictícia" })).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Buscar cliente"), "zul");
    const opt = await screen.findByRole("option", { name: "Zulmira Fictícia" });
    await userEvent.selectOptions(select, opt);
    expect(select).toHaveValue("c999");
    await waitFor(() => expect(urls.some((u) => u.includes("/api/v1/customers?limit=20&q=zul"))).toBe(true));
    // Nova busca sem o selecionado no resultado: ele continua na lista e selecionado.
    await userEvent.clear(screen.getByLabelText("Buscar cliente"));
    await userEvent.type(screen.getByLabelText("Buscar cliente"), "ana");
    await waitFor(() => expect(urls.some((u) => u.includes("q=ana"))).toBe(true));
    expect(select).toHaveValue("c999");
  });
});
