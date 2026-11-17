// Estrutura de abas do site (cerebro/01: Visão geral, Vendas, Operação, Financeiro, Relatórios, Inteligência, Equipe).
// Cada subaba declara a capacidade exigida e se já está implementada. Abas sem nenhuma subaba permitida não aparecem.

export interface SubTab {
  id: string;
  label: string;
  path: string;
  /** Basta uma destas capacidades. */
  anyOf: string[];
  /** Fase do roteiro que entrega a função; ausente = implementada. */
  pendingPhase?: string;
}

export interface Tab {
  id: string;
  label: string;
  icon: string;
  path: string;
  subtabs: SubTab[];
}

export const TABS: Tab[] = [
  {
    id: "overview", label: "Visão geral", icon: "dashboard", path: "/overview",
    subtabs: [{ id: "summary", label: "Resumo", path: "/overview", anyOf: ["dashboard.view"] }],
  },
  {
    id: "sales", label: "Vendas", icon: "order", path: "/sales",
    subtabs: [
      { id: "customers", label: "Clientes", path: "/sales/customers", anyOf: ["customers.view"] },
      { id: "orders", label: "Pedidos", path: "/sales/orders", anyOf: ["orders.pickup.view", "orders.delivery.view"] },
    ],
  },
  {
    id: "operation", label: "Operação", icon: "inventory", path: "/operation",
    subtabs: [
      { id: "stock", label: "Estoque", path: "/operation/stock", anyOf: ["inventory.stock.view"] },
      { id: "receipts", label: "Recebimentos", path: "/operation/receipts", anyOf: ["inventory.receipts.view"] },
      { id: "products", label: "Produtos", path: "/operation/products", anyOf: ["catalog.view"] },
      { id: "suppliers", label: "Fornecedores", path: "/operation/suppliers", anyOf: ["suppliers.view"] },
      { id: "fleet", label: "Frota", path: "/operation/fleet", anyOf: ["vehicles.view"] },
      { id: "trips", label: "Viagens", path: "/operation/trips", anyOf: ["trips.view"] },
    ],
  },
  {
    id: "finance", label: "Financeiro", icon: "wallet", path: "/finance",
    subtabs: [
      { id: "cash", label: "Caixa e contas", path: "/finance/cash", anyOf: ["company_cash.view"] },
      { id: "closing", label: "Resultado e fechamento", path: "/finance/closing", anyOf: ["reports.view"] },
      { id: "expenses", label: "Despesas", path: "/finance/expenses", anyOf: ["expenses.view"] },
      { id: "payables", label: "Contas a pagar", path: "/finance/payables", anyOf: ["expenses.view"] },
      { id: "receivables", label: "Contas a receber", path: "/finance/receivables", anyOf: ["payments.view", "expenses.view"] },
      { id: "openfinance", label: "Open Finance", path: "/finance/open-finance", anyOf: ["company_cash.view"] },
      { id: "mycash", label: "Caixa do turno", path: "/finance/my-cash", anyOf: ["own_cash.view", "admin"] },
      { id: "taxes", label: "Guias", path: "/finance/taxes", anyOf: ["taxes.view"] },
      { id: "payroll", label: "Folha", path: "/finance/payroll", anyOf: ["payroll.view"] },
      { id: "invoices", label: "Notas fiscais", path: "/finance/invoices", anyOf: ["invoices.view"] },
      { id: "documents", label: "Documentos", path: "/finance/documents", anyOf: ["documents.view"] },
      { id: "integrations", label: "Integrações", path: "/finance/integrations", anyOf: ["invoices.view", "payments.confirm", "admin"] },
    ],
  },
  {
    id: "reports", label: "Relatórios", icon: "report", path: "/reports",
    subtabs: [{ id: "reports", label: "Relatórios", path: "/reports", anyOf: ["reports.view"], pendingPhase: "F09" }],
  },
  {
    id: "intelligence", label: "Inteligência", icon: "ai", path: "/intelligence",
    subtabs: [{ id: "ai", label: "IA da gestão", path: "/intelligence", anyOf: ["ai.view"], pendingPhase: "F15" }],
  },
  {
    id: "team", label: "Equipe", icon: "users", path: "/team",
    subtabs: [
      { id: "people", label: "Pessoas", path: "/team/people", anyOf: ["users.view"] },
      { id: "employees", label: "Funcionários", path: "/team/employees", anyOf: ["users.view"] },
      { id: "access", label: "Mapa de acessos", path: "/team/access", anyOf: ["permissions.view"] },
      { id: "audit", label: "Histórico", path: "/team/audit", anyOf: ["audit.view"] },
      { id: "branches", label: "Unidades", path: "/team/branches", anyOf: ["admin"] },
    ],
  },
];

export function visibleTabs(can: (c: string) => boolean): Tab[] {
  return TABS.map((t) => ({ ...t, subtabs: t.subtabs.filter((s) => s.anyOf.some(can)) })).filter(
    (t) => t.subtabs.length > 0,
  );
}

export function firstAllowedPath(can: (c: string) => boolean): string | null {
  return visibleTabs(can)[0]?.subtabs[0]?.path ?? null;
}
