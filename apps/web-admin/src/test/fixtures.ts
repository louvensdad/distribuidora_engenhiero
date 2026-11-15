import type { CapabilityView, Me } from "../api/types";

export function caps(...keys: string[]): CapabilityView[] {
  return keys.map((k) => {
    const dot = k.lastIndexOf(".");
    return { capability: k, resource: k.slice(0, dot), action: k.slice(dot + 1), scopes: ["BRANCH"], max_amount: null };
  });
}

export function me(over: Partial<Me> = {}): Me {
  return {
    user: { id: "u-patrao", display_name: "Patrão Teste" },
    organization: { id: "org", name: "Distribuidora de Cana Engenheiro Ltda" },
    branch: { id: "b1", name: "Matriz" },
    client_id: "engenheiro-web",
    admin: true,
    role_template: "ADMIN",
    sector: "ADMINISTRACAO",
    policy_version: 1,
    capabilities: caps("dashboard.view", "customers.view", "customers.create", "users.view", "users.edit_draft",
      "permissions.view", "permissions.edit_draft", "audit.view", "orders.pickup.view", "reports.view"),
    fields: { operational_contact: true, delivery_address: true, personal_document: true },
    ...over,
  };
}

/** fetch falso por rota: devolve JSON/status e registra as chamadas. */
export function mockFetch(routes: Record<string, { status?: number; body?: unknown; etag?: string }>) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({ url, init });
    const method = (init.method ?? "GET").toUpperCase();
    const route = routes[`${method} ${url}`] ?? routes[`${method} ${url.split("?")[0]}`];
    if (!route) return new Response(JSON.stringify({ status: 404, code: "NOT_FOUND" }), { status: 404 });
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (route.etag) headers.ETag = route.etag;
    return new Response(route.body === undefined ? "" : JSON.stringify(route.body), { status: route.status ?? 200, headers });
  });
  vi.stubGlobal("fetch", fn);
  return calls;
}
