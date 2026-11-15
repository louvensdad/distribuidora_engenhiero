import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, UNAUTHENTICATED_EVENT, api } from "./http";
import { mockFetch } from "../test/fixtures";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("cliente HTTP", () => {
  it("envia o token de CSRF do cookie só em mutações e usa a sessão da mesma origem", async () => {
    document.cookie = "XSRF-TOKEN=abc%2D123";
    const calls = mockFetch({ "GET /x": { body: {} }, "POST /y": { body: {} } });
    await api.get("/x");
    await api.post("/y", { a: 1 }, '"3"');
    const get = new Headers(calls[0]!.init.headers as HeadersInit);
    const post = new Headers(calls[1]!.init.headers as HeadersInit);
    expect(get.get("X-XSRF-TOKEN")).toBeNull();
    expect(post.get("X-XSRF-TOKEN")).toBe("abc-123");
    expect(post.get("If-Match")).toBe('"3"');
    expect(calls[1]!.init.credentials).toBe("same-origin");
    expect(post.get("Authorization")).toBeNull(); // nenhum token no navegador
  });

  it("converte erro padronizado e preserva request_id e erros de campo", async () => {
    mockFetch({ "POST /z": { status: 422, body: { status: 422, code: "VALIDATION_ERROR", detail: "Dados inválidos",
      request_id: "rid-1", errors: [{ field: "name", message: "obrigatório" }] } } });
    const err = await api.post("/z", {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 422, code: "VALIDATION_ERROR", requestId: "rid-1", message: "Dados inválidos" });
    expect((err as ApiError).fieldErrors).toEqual([{ field: "name", message: "obrigatório" }]);
  });

  it("avisa a aplicação quando a sessão expira (401)", async () => {
    mockFetch({ "GET /me": { status: 401, body: { status: 401, code: "UNAUTHENTICATED" } } });
    const seen = vi.fn();
    window.addEventListener(UNAUTHENTICATED_EVENT, seen);
    await api.get("/me").catch(() => undefined);
    expect(seen).toHaveBeenCalledOnce();
    window.removeEventListener(UNAUTHENTICATED_EVENT, seen);
  });

  it("falha de rede vira erro explícito, não sucesso", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("rede"); }));
    await expect(api.get("/x")).rejects.toMatchObject({ code: "NETWORK_ERROR", status: 0 });
  });
});
