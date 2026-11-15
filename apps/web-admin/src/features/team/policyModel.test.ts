import { describe, expect, it } from "vitest";
import type { Policy } from "../../api/types";
import { describeChanges, fromPolicy, grantableMatrix, toPayload, withCell } from "./policyModel";

const policy: Policy = {
  user: { id: "u", display_name: "Balcão", status: "ACTIVE", sector: "RETIRADA", role_template: "BALCAO", admin: false, branch: "Matriz", version: 3 },
  clients: ["engenheiro-retirada"],
  grants: [
    { resource: "orders.pickup", action: "view", scope: "OWN", effect: "ALLOW" },
    { resource: "orders.pickup", action: "view", scope: "BRANCH", effect: "ALLOW" },
    { resource: "expenses", action: "approve", scope: "BRANCH", effect: "ALLOW", max_amount: "150.00" },
    { resource: "dashboard", action: "view", scope: "ORGANIZATION", effect: "DENY" },
  ],
  fields: [{ field: "operational_contact", effect: "ALLOW" }],
};

describe("modelo do mapa de acessos", () => {
  it("preserva escopos múltiplos, alçada e negação ao ida-e-volta", () => {
    const payload = toPayload(fromPolicy(policy), " revisão ");
    expect(payload.reason).toBe("revisão");
    expect(payload.grants).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ resource: "orders.pickup", action: "view", scope: "OWN", effect: "ALLOW" }),
        expect.objectContaining({ resource: "orders.pickup", action: "view", scope: "BRANCH", effect: "ALLOW" }),
        expect.objectContaining({ resource: "expenses", action: "approve", max_amount: "150.00" }),
        expect.objectContaining({ resource: "dashboard", action: "view", effect: "DENY" }),
      ]),
    );
    expect(payload.grants).toHaveLength(4);
    expect(payload.fields).toEqual([{ field: "operational_contact", effect: "ALLOW" }]);
  });

  it("aceita vírgula decimal na alçada e omite células sem acesso", () => {
    let d = fromPolicy(policy);
    d = withCell(d, "expenses", "approve", { maxAmount: "99,90" });
    d = withCell(d, "dashboard", "view", { effect: "NONE" });
    const p = toPayload(d, "x");
    expect(p.grants.find((g) => g.resource === "expenses")?.max_amount).toBe("99.90");
    expect(p.grants.some((g) => g.resource === "dashboard")).toBe(false);
  });

  it("descreve as mudanças antes de salvar", () => {
    const before = fromPolicy(policy);
    let after = withCell(before, "dashboard", "view", { effect: "NONE" });
    after = withCell(after, "customers", "view", { effect: "ALLOW", scope: "BRANCH" });
    after = { ...after, fields: { ...after.fields, personal_document: "ALLOW" }, clients: [] };
    expect(describeChanges(before, after)).toEqual([
      "Clientes · Ver: sem acesso → permitido (Da unidade)",
      "Visão geral (dashboard) · Ver: bloqueado → sem acesso",
      "Campo CPF/CNPJ e documentos pessoais: oculto (padrão) → visível",
      "App Engenheiro Retirada: removido",
    ]);
  });

  it("só oferece o que o editor possui", () => {
    const m = grantableMatrix([
      { capability: "users.view", resource: "users", action: "view", scopes: ["ORGANIZATION"], max_amount: null },
      { capability: "customers.view", resource: "customers", action: "view", scopes: ["BRANCH"], max_amount: null },
      { capability: "customers.create", resource: "customers", action: "create", scopes: ["BRANCH"], max_amount: null },
    ]);
    expect(m).toEqual([
      { resource: "customers", actions: ["view", "create"] },
      { resource: "users", actions: ["view"] },
    ]);
  });
});
