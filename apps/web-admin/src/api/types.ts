// Tipos espelhando packages/contracts/openapi.json (endpoints implementados).
export type Scope = "OWN" | "ASSIGNED" | "SECTOR" | "BRANCH" | "ORGANIZATION";
export type Effect = "ALLOW" | "DENY";

export interface CapabilityView {
  capability: string;
  resource: string;
  action: string;
  scopes: Scope[];
  max_amount: string | null;
}

export interface Me {
  user: { id: string; display_name: string };
  organization: { id: string; name: string };
  branch: { id: string; name: string };
  client_id: string;
  admin: boolean;
  role_template: string;
  sector: string;
  policy_version: number;
  capabilities: CapabilityView[];
  fields: Record<string, boolean>;
}

export interface BffSession {
  web_login: "CONFIGURADO" | "NAO_CONFIGURADO";
  authenticated: boolean;
  login_url?: string;
  display_name?: string;
}

export interface UserView {
  id: string;
  display_name: string;
  status: "ACTIVE" | "DISABLED";
  sector: string;
  role_template: string;
  admin: boolean;
  branch: string;
  version: number;
}

export interface Grant {
  resource: string;
  action: string;
  scope: Scope;
  effect: Effect;
  max_amount?: string | null;
  expires_at?: string | null;
}

export interface FieldPolicy {
  field: string;
  effect: Effect;
}

export interface Policy {
  user: UserView;
  clients: string[];
  grants: Grant[];
  fields: FieldPolicy[];
}

export interface RoleTemplate {
  name: string;
  sector: string;
  admin: boolean;
  clients: string[];
  grants: string[];
  fields: Record<string, Effect>;
}

export interface AuditEvent {
  id: string;
  occurred_at: string;
  actor_user_id: string | null;
  actor_name: string | null;
  actor_kind: string;
  client_id: string | null;
  action: string;
  entity_type: string | null;
  entity_id: string | null;
  result: "SUCCESS" | "DENIED" | "FAILURE";
  reason: string | null;
  request_id: string | null;
}

export interface Customer {
  id: string;
  name: string;
  branch: string;
  contact: string | null;
  address: string | null;
  personal_document: string | null;
  status: string;
  version: number;
  created_at: string;
  masked_fields: string[];
}

export interface Page<T> {
  items: T[];
  next_cursor: string | null;
}

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  code: string;
  request_id?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

// ---- F03: catálogo, fornecedores e estoque (decimais chegam como texto) ----
export type Unit = "UNIDADE" | "DUZIA" | "FEIXE" | "MACO" | "CAIXA" | "SACO" | "KG" | "TONELADA";

export interface Conversion { unit: Unit; factor: string; valid_from: string }

export interface Product {
  id: string;
  sku: string;
  name: string;
  base_unit: Unit;
  base_unit_confirmed: boolean;
  unit_confirmed_at: string | null;
  status: string;
  version: number;
  conversions: Conversion[];
  prices?: { unit: Unit; price: string; valid_from: string }[];
  created_at: string;
}

export interface Supplier {
  id: string;
  name: string;
  contact: string | null;
  personal_document: string | null;
  status: string;
  version: number;
  created_at: string;
  masked_fields: string[];
}

export interface ReceiptLine {
  line_no: number;
  product_id: string;
  sku: string;
  product_name: string;
  unit: Unit;
  factor: string;
  quantity_expected: string | null;
  quantity_received: string;
  base_quantity: string;
  base_unit: Unit;
  difference: string | null;
  lot: string | null;
  unit_cost: string | null;
  goods_cost: string | null;
  freight_share: string | null;
  returned_base_quantity: string;
}

export interface Receipt {
  id: string;
  status: "DRAFT" | "CONFIRMED";
  supplier_id: string;
  supplier_name: string;
  branch: string;
  document_number: string | null;
  notes: string | null;
  freight_amount: string | null;
  total_cost: string | null;
  received_at: string;
  created_by_name: string;
  confirmed_at: string | null;
  confirmed_by_name: string | null;
  version: number;
  lines: ReceiptLine[];
  masked_fields: string[];
}

export interface Balance {
  product_id: string;
  sku: string;
  product_name: string;
  base_unit: Unit;
  branch_id: string;
  branch: string;
  physical: string;
  reserved: string;
  available: string;
  lost: string;
  value: string | null;
  average_cost: string | null;
  version: number;
  updated_at: string;
  masked_fields: string[];
}

export type MovementKind = "RECEIPT" | "SUPPLIER_RETURN" | "LOSS" | "LOSS_REVERSAL" | "SALE_ISSUE" | "SALE_RETURN" | "ADJUSTMENT_IN" | "ADJUSTMENT_OUT";

export interface StockMovement {
  id: string;
  kind: MovementKind;
  product_id: string;
  sku: string;
  product_name: string;
  base_unit: Unit;
  branch: string;
  quantity: string;
  balance_after: string;
  unit_cost: string | null;
  total_cost: string | null;
  source_type: string;
  source_id: string;
  source_line: number;
  reversal_of: string | null;
  reason: string | null;
  created_by_name: string;
  occurred_at: string;
  masked_fields: string[];
}

export type LossReason = "QUEBRA" | "DETERIORACAO" | "AVARIA_TRANSPORTE" | "FURTO" | "ERRO_CONTAGEM" | "OUTRO";

export interface Loss {
  id: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "REVERSED";
  product_id: string;
  sku: string;
  product_name: string;
  unit: Unit;
  quantity: string;
  base_quantity: string;
  base_unit: Unit;
  reason: LossReason;
  notes: string | null;
  total_cost: string | null;
  branch: string;
  created_by_name: string;
  created_at: string;
  decided_by_name: string | null;
  decided_at: string | null;
  decision_reason: string | null;
  movement_id: string | null;
  version: number;
  masked_fields: string[];
}

export interface Payable {
  id: string; source_type: string; source_id: string; supplier_id: string | null; supplier_name: string | null;
  description: string; competence: string; amount: string; balance: string; due_date: string | null;
  state: "OPEN" | "PARTIALLY_PAID" | "PAID" | "CANCELLED"; branch: string; created_at: string;
}

export interface BranchView { id: string; name: string; timezone: string; status: "ACTIVE" | "INACTIVE"; active_people: number; created_at: string }

export interface Adjustment {
  id: string; status: "PENDING" | "APPROVED" | "REJECTED"; product_id: string; sku: string; product_name: string; base_unit: Unit;
  counted_quantity: string; system_quantity: string; difference: string; unit_cost: string | null; total_cost: string | null;
  notes: string; branch: string; created_by_name: string; created_at: string; decided_by_name: string | null;
  decided_at: string | null; decision_reason: string | null; movement_id: string | null; version: number; masked_fields: string[];
}

export interface DocumentView {
  id: string; file_name: string; mime: string; size_bytes: number; sha256: string;
  state: "UPLOADING" | "QUARANTINED" | "AVAILABLE" | "REJECTED" | "SUPERSEDED" | "ARCHIVED";
  scan_state: string; rejection: string | null; created_by_name: string; created_at: string;
}

// ---- F04: pedidos, pagamentos e caixa ----
export interface OrderLine {
  line_no: number; product_id: string; sku: string; product_name: string; unit: Unit; factor: string; quantity: string;
  base_quantity: string; base_unit: Unit; unit_price: string; gross: string; discount: string; net: string;
  fulfilled_quantity: string; cancelled_quantity: string; returned_quantity: string; remaining_quantity: string;
}
export type OrderState = "CONFIRMED" | "READY" | "PARTIALLY_FULFILLED" | "FULFILLED" | "CANCELLED";
export type PaymentState = "UNPAID" | "PARTIALLY_PAID" | "PAID" | "ON_ACCOUNT" | "REFUND_DUE";
export interface Order {
  id: string; number: number; type: "PICKUP" | "DELIVERY"; state: OrderState; payment_state: PaymentState;
  customer_id: string; customer_name: string; branch: string; gross_total: string; discount_total: string; total: string;
  effective_total: string; paid_amount: string; pending_amount: string; on_account: string; balance_due: string;
  notes: string | null; created_by_name: string; created_at: string; cancel_reason: string | null; version: number; lines: OrderLine[];
}
export type PaymentMethod = "CASH" | "PIX" | "DEBIT" | "CREDIT" | "BANK_TRANSFER" | "ON_ACCOUNT";
export interface Payment {
  id: string; order_id: string; method: PaymentMethod; state: "PENDING" | "CONFIRMED" | "REJECTED" | "REVERSED";
  confirmation_kind: string; amount: string; fee: string; net: string; presented_cash: string | null; change_given: string | null;
  installments: number | null; provider_reference: string | null; document_id: string | null; due_date: string | null;
  created_by_name: string; created_at: string; confirmed_by_name: string | null; confirmed_at: string | null;
  decided_note: string | null; version: number;
}
export interface CashSession {
  id: string; state: "OPEN" | "CLOSED" | "REVIEWED"; operator_name: string; branch: string; opening_float: string;
  cash_in: string; cash_out: string; expected: string; counted: string | null; difference: string | null;
  justification: string | null; opened_at: string; closed_at: string | null; reviewed_by_name: string | null;
  reviewed_at: string | null; review_note: string | null; version: number;
}
export interface OrderHistoryItem { occurred_at: string; action: string; actor_name: string | null; client_id: string | null; entity_type: string | null }
