// "Abrir origem" (W16): do movimento do histórico para a tela do registro. Só caminhos internos fixos — o id vem do
// servidor e é validado como UUID; a tela de destino aplica de novo a permissão (404/403 se não puder ver).
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const DETAIL: Record<string, string> = {
  order: "/sales/orders/",
  trip: "/operation/trips/",
  vehicle: "/operation/fleet/",
  inventory_receipt: "/operation/receipts/",
  employee: "/team/employees/",
};

const LIST: Record<string, string> = {
  customer: "/sales/customers",
  supplier: "/operation/suppliers",
  product: "/operation/products",
  inventory_loss: "/operation/stock",
  inventory_adjustment: "/operation/stock",
  vehicle_expense: "/operation/fleet",
  tax_obligation: "/finance/taxes",
  payroll_run: "/finance/payroll",
  expense: "/finance/expenses",
  payable: "/finance/payables",
  receivable: "/finance/receivables",
  document: "/finance/documents",
  cash_session: "/finance/my-cash",
  goal: "/reports/goals",
  employee_absence: "/team/absences",
  job_position: "/team/positions",
  membership: "/team/people",
  branch: "/team/branches",
  bank_transaction: "/finance/open-finance",
  bank_connection: "/finance/open-finance",
};

export function originPath(entityType: string | null, entityId: string | null): string | null {
  if (!entityType) return null;
  if (DETAIL[entityType] && entityId && UUID_RE.test(entityId)) return DETAIL[entityType] + entityId;
  return LIST[entityType] ?? null;
}

export const ENTITY_LABELS: Record<string, string> = {
  order: "Pedido", trip: "Viagem", vehicle: "Veículo", inventory_receipt: "Recebimento", customer: "Cliente",
  supplier: "Fornecedor", product: "Produto", inventory_loss: "Perda", inventory_adjustment: "Ajuste de estoque",
  vehicle_expense: "Gasto de veículo", tax_obligation: "Guia", payroll_run: "Folha", expense: "Despesa",
  payable: "Conta a pagar", receivable: "Conta a receber", document: "Documento", cash_session: "Caixa",
  goal: "Meta", employee_absence: "Ausência", employee: "Funcionário", job_position: "Cargo", membership: "Acesso", branch: "Unidade", payment: "Pagamento",
  bank_transaction: "Lançamento bancário", bank_connection: "Conexão bancária", invoice: "Nota fiscal",
};

export const CENTRAL_SECTORS = [
  { id: "COUNTER", label: "Retirada, balcão e fornecedores", icon: "box", app: "App Engenheiro Retirada",
    enter: [["Pedidos de retirada", "/sales/orders?type=PICKUP"], ["Recebimentos", "/operation/receipts"], ["Estoque", "/operation/stock"], ["Caixas", "/finance/my-cash"]] },
  { id: "DELIVERY", label: "Entregas, motoristas e veículos", icon: "truck", app: "App Engenheiro Entregas",
    enter: [["Pedidos de entrega", "/sales/orders?type=DELIVERY"], ["Viagens", "/operation/trips"], ["Frota", "/operation/fleet"]] },
  { id: "ACCOUNTING", label: "Contabilidade e documentos", icon: "tax", app: "App Engenheiro Contábil",
    enter: [["Guias", "/finance/taxes"], ["Folha", "/finance/payroll"], ["Notas fiscais", "/finance/invoices"], ["Documentos", "/finance/documents"]] },
] as const;

export const SECTOR_NAMES: Record<string, string> = {
  COUNTER: "Retirada", DELIVERY: "Entregas", ACCOUNTING: "Contabilidade", FINANCE: "Financeiro", TEAM: "Equipe",
};
