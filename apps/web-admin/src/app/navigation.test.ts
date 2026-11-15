import { describe, expect, it } from "vitest";
import { firstAllowedPath, visibleTabs } from "./navigation";

const can = (list: string[]) => (c: string) => list.includes(c);

describe("abas por capacidade", () => {
  it("patrão vê as sete abas na ordem do cérebro", () => {
    const all = ["dashboard.view", "customers.view", "inventory.stock.view", "company_cash.view", "reports.view", "ai.view", "users.view"];
    expect(visibleTabs(can(all)).map((t) => t.label)).toEqual([
      "Visão geral", "Vendas", "Operação", "Financeiro", "Relatórios", "Inteligência", "Equipe"]);
  });

  it("perfil operacional não vê visão geral, relatórios nem equipe", () => {
    const balcao = ["orders.pickup.view", "customers.view", "inventory.stock.view"];
    const labels = visibleTabs(can(balcao)).map((t) => t.label);
    expect(labels).toEqual(["Vendas", "Operação"]);
    expect(firstAllowedPath(can(balcao))).toBe("/sales/customers");
  });

  it("sem capacidades não há destino inicial", () => {
    expect(firstAllowedPath(can([]))).toBeNull();
  });
});
