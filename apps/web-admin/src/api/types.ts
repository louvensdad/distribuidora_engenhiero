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
  id: string; source_type: string; source_id: string; supplier_id: string | null; supplier_name: string | null; description: string;
  competence: string; amount: string; balance: string; due_date: string | null; state: "OPEN" | "PARTIALLY_PAID" | "PAID" | "CANCELLED";
  branch: string; created_at: string; version: number;
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
  delivery: Delivery | null; dispatch: Dispatch | null; masked_fields: string[];
}
export interface Delivery {
  address: string | null; reference: string | null; contact: string | null; window_start: string | null; window_end: string | null;
  agreed_payment: PaymentMethod | null;
}
export interface Dispatch { trip_id: string; trip_number: number; trip_state: TripState; stop_state: StopState; driver_name: string | null; plate: string | null }
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

// ---- F05: frota, viagens e entregas ----
export type VehicleStatus = "ACTIVE" | "MAINTENANCE" | "INACTIVE";
export interface Vehicle {
  id: string; plate: string; model: string; capacity_quantity: string; capacity_unit: Unit; status: VehicleStatus;
  odometer_km: string; next_service_km: string | null; registration_valid_until: string | null; insurance_valid_until: string | null;
  notes: string | null; branch: string; current_trip_id: string | null; current_trip_number: number | null;
  current_driver_name: string | null; version: number; created_at: string;
  alerts: { code: string; message: string }[];
  consumption: { state: "OK" | "INSUFFICIENT_DATA"; km_per_liter: string | null; km: string | null; liters: string | null; full_tank_fills: number; message: string | null };
}
export type ExpenseKind = "FUEL" | "MAINTENANCE" | "TIRES" | "INSURANCE" | "DOCUMENTS" | "TOLL" | "OTHER";
export interface VehicleExpense {
  id: string; vehicle_id: string; plate: string; trip_id: string | null; trip_number: number | null; kind: ExpenseKind;
  occurred_on: string; amount: string; paid_with: "COMPANY" | "DRIVER_CASH"; odometer: string | null; liters: string | null;
  fuel_type: string | null; full_tank: boolean | null; station: string | null; maintenance_type: string | null; workshop: string | null;
  parts: string | null; next_service_km: string | null; description: string | null; state: "PENDING" | "APPROVED" | "REJECTED";
  created_by_name: string; created_at: string; decided_by_name: string | null; decided_at: string | null; decided_note: string | null;
  version: number;
}
export interface OdometerReading { reading: string; kind: string; reason: string | null; recorded_at: string; recorded_by_name: string }
export interface EntityHistoryItem { occurred_at: string; action: string; actor_name: string | null; client_id: string | null; entity_type?: string | null; entity_id?: string | null }
export type TripState = "DRAFT" | "ASSIGNED" | "DEPARTED" | "RETURNED" | "RECONCILED" | "CANCELLED";
export type StopState = "PENDING" | "DELIVERED" | "PARTIAL" | "FAILED" | "REMOVED";
export interface TripIncident { id: string; stop_id: string | null; kind: string; requested_action: string; description: string; created_by_name: string; created_at: string }
export interface StopOrder {
  id: string; number: number; customer_name: string; state: OrderState; payment_state: PaymentState; effective_total: string;
  paid_amount: string; pending_amount: string; on_account: string; balance_due: string; lines: OrderLine[];
  delivery: Delivery | null; masked_fields: string[];
}
export interface TripStop {
  id: string; seq: number; state: StopState; recipient_name: string | null; delivered_at: string | null; delivered_by_name: string | null;
  order: StopOrder; load: string | null; incidents: TripIncident[];
}
export interface Trip {
  id: string; number: number; state: TripState; planned_date: string; notes: string | null; vehicle_id: string | null; plate: string | null;
  vehicle_model: string | null; capacity_quantity: string | null; capacity_unit: Unit | null; load: string | null; driver_id: string | null;
  driver_name: string | null; departed_at: string | null; departure_odometer: string | null; returned_at: string | null;
  return_odometer: string | null; distance_km: string | null; returned_by_name: string | null; cash_collected: string | null;
  cash_expenses: string | null; cash_expected: string | null; cash_declared: string | null; cash_difference: string | null;
  return_note: string | null; reconciled_at: string | null; reconciled_by_name: string | null; reconcile_note: string | null;
  cancel_reason: string | null; created_by_name: string; created_at: string; version: number; stops: TripStop[]; incidents: TripIncident[];
}
export interface TripSummary { id: string; number: number; state: TripState; planned_date: string; plate: string | null; driver_name: string | null; stops: number; delivered: number; created_at: string; version: number }
export interface DriverOption { id: string; name: string; busy: boolean }
export interface TripCosts {
  trip_id: string; rule_code: string; rule_version: number; rule_description: string; capacity_unit: Unit | null; total_cost: string;
  allocated_total: string; unallocated: string; pending_cost: string; distance_km: string | null; cost_per_km: string | null;
  lines: { stop_id: string; order_number: number; customer_name: string; delivered_quantity: string; share: string; allocated_cost: string }[];
  expenses: { id: string; kind: ExpenseKind; amount: string; state: string; paid_with: string }[]; message: string | null;
}

// ---- F06: financeiro ----
export interface FinAccount { id: string; code: string; name: string; kind: "CASH_DRAWERS" | "COMPANY_CASH" | "BANK" | "CARD_ACQUIRER"; ledger_account: string; is_default: boolean; status: string; balance: string | null; version: number }
export interface StatementLine { entry_id: string; entry_date: string; competence: string | null; event_kind: string; memo: string | null; source_type: string; source_id: string; debit: string; credit: string; balance: string; created_by_name: string; reversal: boolean }
export interface Statement { account: FinAccount; from: string; to: string; opening_balance: string; inflows: string; outflows: string; closing_balance: string; lines: StatementLine[] }
export interface Transfer { id: string; from_account_id: string; from_name: string; to_account_id: string; to_name: string; amount: string; transfer_date: string; note: string | null; state: "CONFIRMED" | "REVERSED"; created_by_name: string; created_at: string; reverse_reason: string | null }
export type ExpenseCategory = "FUNCIONARIOS" | "VEICULOS" | "ESTRUTURA" | "IMPOSTOS_TAXAS" | "OUTRAS" | "RETIRADA_SOCIO";
export interface Expense {
  id: string; category: ExpenseCategory; subcategory: string; description: string; payee: string | null; supplier_id: string | null;
  supplier_name: string | null; amount: string; competence: string; due_date: string | null; state: "PENDING" | "APPROVED" | "REJECTED" | "REVERSED";
  payable_id: string | null; payable_state: string | null; payable_balance: string | null; branch: string; created_by_name: string; created_at: string;
  decided_by_name: string | null; decided_at: string | null; decided_note: string | null; reverse_reason: string | null; version: number;
}
export interface PayableSettlement { id: string; payable_id: string; kind: "PAYMENT" | "SUPPLIER_RETURN" | "CANCELLATION"; amount: string; fin_account_id: string | null; account_name: string | null; paid_on: string; note: string | null; state: "CONFIRMED" | "REVERSED"; created_by_name: string; created_at: string; reverse_reason: string | null }
export interface PayableDetail { payable: Payable; settlements: PayableSettlement[] }
export interface Receivable {
  id: string; kind: "CUSTOMER" | "CARD" | "SUPPLIER_CREDIT"; payment_id: string | null; order_id: string | null; order_number: number | null;
  customer_name: string | null; supplier_id: string | null; supplier_name: string | null; installment_no: number; installments: number; due_date: string;
  gross: string; fee: string; amount: string; balance: string; state: "OPEN" | "PARTIALLY_SETTLED" | "SETTLED" | "CANCELLED"; description: string | null;
  branch: string; version: number;
}
export interface ReportItem { key: string; label: string; amount: string; accounts: string[] }
export interface CashPosition { available: string; accounts: ReportItem[]; receivables_open: string; card_receivables_open: string; payables_open: string; customer_advances: string; note: string }
export interface FinanceSummary {
  from: string; to: string; basis: string; policy_version: string; hidden: string[]; gross_revenue: string; discounts: string; returns: string; net_revenue: string; cogs: string | null;
  expenses: ReportItem[]; expenses_total: string; losses: string | null; costs_expenses_losses: string | null; profit: string | null; margin_percent: string | null;
  owner_withdrawals: string; cash: CashPosition; drill: ReportItem[]; note: string;
}
export interface LedgerLines {
  accounts: string[]; from: string; to: string; basis: string; total_debit: string; total_credit: string; net: string;
  items: { entry_id: string; entry_date: string; competence: string | null; event_kind: string; memo: string | null; source_type: string; source_id: string; account: string; debit: string; credit: string; fin_account: string | null; created_by_name: string; created_at: string; reversal_of: string | null;
    origin_type: string | null; origin_id: string | null; origin_number: number | null; documents: number; document_id: string | null }[];
}
export interface FinanceCategories { from: string; to: string; categories: ReportItem[]; subcategories: { category: string; subcategory: string; amount: string; entries: number }[]; total: string }
export interface Reconciliation { checked_at: string; status: "OK" | "DIVERGENT"; checks: { code: string; label: string; expected: string; actual: string; difference: string; status: "OK" | "ROUNDING" | "DIVERGENT"; explanation: string }[] }
export interface Period {
  id: string; month: string; state: "OPEN" | "CLOSED"; closed_at: string | null; closed_by_name: string | null; reopen_pending: boolean;
  reopen_requested_by: string | null; profit_at_close: string | null; reconciliation_at_close: string | null;
  events: { action: string; actor_name: string; reason: string | null; state: string | null; occurred_at: string }[]; version: number;
}

// ---- ADR-021: funcionários (folgas/férias, desempenho) e Open Finance ----
export interface Absence {
  id: string; membership_id: string; user_id: string; person: string; sector: string; branch: string; kind: "VACATION" | "DAY_OFF" | "LEAVE";
  start_date: string; end_date: string; days: number; state: "REQUESTED" | "APPROVED" | "REJECTED" | "CANCELLED"; notes: string | null;
  created_by_name: string; created_at: string; decided_by_name: string | null; decided_note: string | null; version: number; warnings: string[];
}
export interface TeamCalendar {
  from: string; to: string;
  members: { membership_id: string; user_id: string; person: string; sector: string; role_template: string; branch: string; vacation_days_last_12m: number }[];
  days: { date: string; sectors: { sector: string; total: number; available: number; absent: string[]; uncovered: boolean }[] }[];
  absences: Absence[];
}
export interface PerformanceReport {
  from: string; to: string; rule: string;
  measures: { code: string; function: string; label: string; formula: string; unit: string; higher_is_better: boolean; values: { user_id: string; person: string; value: string }[]; leader: string | null; note: string | null }[];
}
export interface BankConnection { id: string; fin_account_id: string; account_name: string; provider: string; institution: string; company_document: string; state: string; consent_url: string | null; last_sync_at: string | null; last_error: string | null; provider_balance: string | null; ledger_balance: string | null; created_by_name: string; created_at: string }
export interface OpenFinanceStatus { state: "CONFIGURADO" | "NAO_CONFIGURADO"; provider: string | null; message: string; connections: BankConnection[] }
export interface BankTransaction { id: string; fin_account_id: string; account_name: string; booked_on: string; amount: string; direction: "IN" | "OUT"; description: string; counterpart: string | null; state: "MATCHED" | "SUGGESTED" | "UNMATCHED" | "IGNORED"; matched_type: string | null; matched_id: string | null; suggested_expense_id: string | null; suggested_category: string | null; expense_state: string | null; decided_note: string | null }

// ---- F07: guias, folha e documentos ----
export interface TaxObligation {
  id: string; kind: string; description: string; competence: string; due_date: string; amount: string; notes: string | null;
  payroll_run_id: string | null; state: "DRAFT" | "SUBMITTED" | "RETURNED" | "APPROVED" | "PAID" | "CANCELLED"; payable_id: string | null;
  payable_state: string | null; created_by_name: string; created_at: string; submitted_by_name: string | null; submitted_at: string | null;
  decided_by_name: string | null; decided_at: string | null; decided_note: string | null; attachments: DocumentView[]; version: number;
}
export interface RubricTotal { kind: string; rubric: string; amount: string; people: number }
export interface PayrollRun {
  id: string; competence: string; description: string; pay_date: string; state: TaxObligation["state"]; source: "MANUAL" | "IMPORT";
  totals: { earnings: string; benefits: string; deductions: string; advances: string; employer_charges: string; net_pay: string; company_cost: string; people: number };
  rubrics: RubricTotal[];
  people: { membership_id: string; person: string; earnings: string; benefits: string; deductions: string; advances: string; employer_charges: string; net: string; rubrics: RubricTotal[] }[] | null;
  masked_fields: string[]; net_payable_id: string | null; net_payable_state: string | null; created_by_name: string; created_at: string;
  submitted_by_name: string | null; decided_by_name: string | null; decided_at: string | null; decided_note: string | null; version: number;
}
export interface AccountingDocument {
  id: string; kind: string; title: string; competence: string | null; related_type: string | null; related_id: string | null;
  current_version: number; state: string; created_by_name: string; created_at: string;
  versions: { version_no: number; reason: string | null; created_by_name: string; created_at: string; file: DocumentView }[]; version: number;
}
export interface DocumentRequest {
  id: string; title: string; description: string | null; kind: string; competence: string | null; requested_from_name: string | null;
  due_date: string | null; state: "OPEN" | "FULFILLED" | "CANCELLED"; fulfilled_document_id: string | null; fulfilled_by_name: string | null;
  fulfilled_at: string | null; created_by_name: string; created_at: string; version: number;
}

// ---- F08: notas fiscais e integrações ----
export type IntegrationOutcome = "BLOCKED" | "SENT" | "SUCCESS" | "REJECTED" | "TIMEOUT" | "ERROR";
export interface IntegrationAttempt {
  id: string; integration: string; operation: string; entity_type: string; entity_id: string; outcome: IntegrationOutcome;
  detail: string | null; actor_name: string | null; started_at: string; finished_at: string | null;
}
export interface FiscalSettings {
  document_type: "NFE" | "NFCE" | "BOTH" | null; environment: "HOMOLOGACAO" | "PRODUCAO"; series: number | null;
  default_cfop: string | null; issuer_document: string | null; state_registration: string | null; tax_regime: string | null;
  notes: string | null; provider_state: "CONFIGURED" | "NOT_CONFIGURED"; provider: string | null; missing: string[]; version: number;
}
export type InvoiceState = "DRAFT" | "READY" | "TRANSMITTING" | "UNCERTAIN" | "AUTHORIZED" | "REJECTED" | "CANCEL_PENDING"
  | "CANCELLED" | "DISCARDED";
export interface InvoiceLine {
  line_no: number; product_id: string; description: string; ncm: string | null; cfop: string | null; unit: string;
  quantity: string; unit_price: string; total: string;
}
export interface Invoice {
  id: string; order_id: string; order_number: number; customer_name: string; document_type: "NFE" | "NFCE";
  environment: string; state: InvoiceState; total: string; provider: string | null; number: number | null;
  series: number | null; access_key: string | null; protocol: string | null; xml_document_id: string | null;
  rejection_code: string | null; rejection_reason: string | null; cancel_reason: string | null; cancel_protocol: string | null;
  attempts: number; reviewed_by_name: string | null; reviewed_at: string | null; transmitted_at: string | null;
  authorized_at: string | null; created_by_name: string; created_at: string; lines: InvoiceLine[]; issues: string[];
  provider_state: "CONFIGURED" | "NOT_CONFIGURED"; history: IntegrationAttempt[]; version: number;
}
export interface IntegrationStatusItem {
  integration: "FISCAL" | "PAYMENTS" | "OPEN_FINANCE"; label: string; state: "CONFIGURED" | "NOT_CONFIGURED";
  provider: string | null; message: string; pending: number; pending_label: string;
}
export interface IntegrationStatus { items: IntegrationStatusItem[]; recent: IntegrationAttempt[] }
export interface WebhookEvent {
  id: string; provider: string; event_id: string | null; signature_valid: boolean; event_type: string | null;
  reference: string | null; amount: string | null; status: string; detail: string | null; payment_id: string | null;
  received_at: string;
}

// ---- F09: relatórios, metas e ranking ----
export type ReportPeriod = "DAY" | "WEEK" | "MONTH" | "CUSTOM";
export interface ReportResult {
  gross_revenue: string; discounts: string; returns: string; net_revenue: string; cogs: string | null; expenses_total: string;
  losses: string | null; costs_expenses_losses: string | null; profit: string | null; margin_percent: string | null;
  outcome: "PROFIT" | "LOSS" | "ZERO" | null;
}
export interface ReportColumn { key: string; label: string; amount: string }
export interface ReportPoint { from: string; to: string; label: string; net_revenue: string; expenses_total: string; cogs: string | null; losses: string | null; profit: string | null; sold_quantity: string | null }
export interface ReportSub { category: string; subcategory: string; amount: string; entries: number }
export interface Report {
  policy_version: string; timezone: string; period: ReportPeriod; from: string; to: string; label: string;
  branch_id: string | null; branch_name: string | null; basis: string; source: "LIVE" | "SNAPSHOT";
  closed: { closed_at: string | null; closed_by_name: string | null; policy_version: string; matches_live: boolean } | null;
  hidden: string[]; result: ReportResult; expenses: ReportItem[]; subcategories: ReportSub[];
  sold: { quantity_total: string | null; unit: string | null; by_unit: { unit: string; sold: string; backlog: string }[]; orders: number; note: string };
  chart_unit: string; chart: ReportColumn[]; timeline_bucket: "DAY" | "WEEK" | "MONTH"; timeline: ReportPoint[];
  comparison: { from: string; to: string; label: string; items: { key: string; label: string; current: string; previous: string; difference: string; percent: string | null }[]; formula: string };
  drill: ReportItem[]; cash: CashPosition | null; formula: string; note: string; generated_by: string; generated_at: string;
}
export interface CustomerRankingRow {
  position: number; customer_id: string; customer_name: string; net_revenue: string; quantity: string | null; margin: string | null;
  margin_percent: string | null; orders: number; active_weeks: number; returns: number; receivables_due: number;
  receivables_on_time: number; on_time_percent: string | null;
}
export interface CustomerRanking {
  from: string; to: string; label: string; branch_id: string | null; sort: string; unit: string | null;
  measures: { key: string; label: string; formula: string }[]; hidden: string[]; items: CustomerRankingRow[]; note: string;
}
export type GoalMetric = "NET_REVENUE" | "SOLD_QUANTITY" | "PROFIT";
export interface Goal {
  id: string; metric: GoalMetric; metric_label: string; unit: string | null; period_kind: "WEEK" | "MONTH"; period_start: string;
  period_end: string; period_label: string; target: string; title: string; notes: string | null; branch_id: string | null;
  branch_name: string | null; state: "DRAFT" | "ACTIVE" | "CANCELLED"; cancel_reason: string | null; created_by_name: string;
  created_at: string; activated_by_name: string | null; activated_at: string | null; cancelled_by_name: string | null;
  cancelled_at: string | null;
  progress: { realized: string | null; remaining: string | null; percent: string | null; expected_by_now: string | null; days_elapsed: number;
    days_total: number; status: "NOT_STARTED" | "ON_PACE" | "BEHIND_PACE" | "ACHIEVED" | "MISSED" | "HIDDEN"; formula: string };
  version: number;
}

// ---------- F10: central do patrão ----------
export type CentralSector = "COUNTER" | "DELIVERY" | "ACCOUNTING" | "FINANCE" | "TEAM";

export interface ApprovalItem {
  kind: "TAX" | "PAYROLL" | "LOSS" | "ADJUSTMENT" | "EXPENSE" | "VEHICLE_EXPENSE" | "CASH_SESSION" | "PAYMENT" | "PERIOD_REOPEN" | "ABSENCE";
  sector: CentralSector;
  id: string | null;
  title: string;
  detail: string | null;
  amount: string | null;
  requested_by: string | null;
  requested_at: string | null;
  path: string;
  note: string | null;
}

export interface ApprovalCenter {
  items: ApprovalItem[];
  sectors: { sector: CentralSector; pending: number }[];
}

export interface SearchHit {
  type: "order" | "trip" | "vehicle" | "customer" | "supplier" | "product";
  id: string;
  title: string;
  subtitle: string | null;
  path: string;
}

export interface SearchResult {
  query: string;
  searched: string[];
  items: SearchHit[];
}

// ---------- ADR-027: funcionários e cargos ----------
export type TemplateKey = "BALCAO" | "RECEBIMENTO" | "COORDENADOR_ENTREGAS" | "MOTORISTA" | "CONTADOR";

export interface JobPosition {
  id: string;
  name: string;
  sector: "ADMINISTRACAO" | "RETIRADA" | "ENTREGAS" | "CONTABILIDADE";
  suggested_template: TemplateKey | null;
  description: string | null;
  status: "ACTIVE" | "ARCHIVED";
  employees: number;
  version: number;
  created_at: string;
}

export interface EmployeeAccount {
  user_id: string;
  display_name: string;
  status: "ACTIVE" | "DISABLED";
  role_template: string;
  sector: string;
}

export interface Employee {
  id: string;
  full_name: string;
  branch_id: string;
  branch: string;
  job_position_id: string;
  job_position: string;
  sector: string;
  duties: string | null;
  admission_date: string;
  termination_date: string | null;
  status: "ACTIVE" | "ON_LEAVE" | "TERMINATED";
  work_schedule: string | null;
  phone: string | null;
  cpf: string | null;
  birth_date: string | null;
  address: string | null;
  base_salary: string | null;
  notes: string | null;
  account: EmployeeAccount | null;
  version: number;
  created_at: string;
  created_by_name: string;
  masked_fields: string[];
}

export interface EmployeeAccessResult {
  employee: Employee;
  username: string;
  temporary_password: string;
  provider: string;
  note: string;
}

// ---- F15: IA da gestão (/api/v1/ai) ----
export type AiState = "NOT_CONFIGURED" | "BUDGET_NOT_SET" | "BUDGET_EXHAUSTED" | "CONFIGURED";
export interface AiStatus {
  state: AiState; message: string; provider: string | null; model: string | null; budget_usd: string; spent_usd: string;
  reserved_usd: string; remaining_usd: string; daily_limit: number; used_today: number; tools: string[];
}
export interface AiSource { tool: string; label: string; href: string }
export interface AiToolUse { name: string; label: string; input: Record<string, string>; ok: boolean; error: string | null }
export interface AiQuestion {
  id: string; question: string; from: string; to: string;
  state: "RUNNING" | "ANSWERED" | "FAILED" | "REFUSED" | "LIMIT_REACHED";
  answer: string | null; limitations: string[]; tools: AiToolUse[]; sources: AiSource[]; provider: string; model: string | null;
  input_tokens: number; output_tokens: number; cache_read_tokens: number; cost_usd: string; latency_ms: number | null;
  error_code: string | null; cutoff_at: string; created_at: string; client_id: string;
}
