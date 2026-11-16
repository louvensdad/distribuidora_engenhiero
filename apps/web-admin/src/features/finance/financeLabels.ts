export const CATEGORY: Record<string, string> = {
  FUNCIONARIOS: "Funcionários", VEICULOS: "Veículos", ESTRUTURA: "Estrutura", IMPOSTOS_TAXAS: "Impostos e taxas",
  OUTRAS: "Outras aprovadas", RETIRADA_SOCIO: "Retirada do sócio (fora do resultado)",
};
export const EXPENSE_STATE: Record<string, string> = { PENDING: "Aguardando aprovação", APPROVED: "Aprovada", REJECTED: "Recusada", REVERSED: "Estornada" };
export const PAYABLE_STATE: Record<string, string> = { OPEN: "Em aberto", PARTIALLY_PAID: "Pago em parte", PAID: "Pago", CANCELLED: "Cancelado" };
export const RECEIVABLE_STATE: Record<string, string> = { OPEN: "Em aberto", PARTIALLY_SETTLED: "Recebido em parte", SETTLED: "Recebido", CANCELLED: "Cancelado" };
export const RECEIVABLE_KIND: Record<string, string> = { CUSTOMER: "Cliente (a prazo)", CARD: "Cartão (adquirente)", SUPPLIER_CREDIT: "Crédito com fornecedor" };
export const ACCOUNT_KIND: Record<string, string> = { CASH_DRAWERS: "Caixas de turno", COMPANY_CASH: "Caixa da empresa", BANK: "Banco", CARD_ACQUIRER: "Adquirente (a receber)" };
export const EVENT: Record<string, string> = {
  PAYMENT_CONFIRMED: "Recebimento de cliente", PAYMENT_REVERSED: "Estorno de recebimento", SALE_RECOGNIZED: "Venda cumprida",
  SALE_RETURNED: "Devolução de venda", RECEIPT_CONFIRMED: "Entrada de mercadoria", SUPPLIER_RETURN: "Devolução ao fornecedor",
  LOSS_APPROVED: "Perda aprovada", LOSS_REVERSED: "Perda estornada", ADJUSTMENT_APPROVED: "Ajuste de contagem",
  EXPENSE_APPROVED: "Despesa aprovada", EXPENSE_REVERSED: "Despesa estornada", DRIVER_CASH_ADVANCE: "Gasto com dinheiro do motorista",
  PAYABLE_PAYMENT: "Pagamento de conta", PAYABLE_PAYMENT_REVERSED: "Estorno de pagamento", RECEIVABLE_SETTLED: "Recebimento/repasse",
  RECEIVABLE_SETTLEMENT_REVERSED: "Estorno de recebimento", TRANSFER: "Transferência interna", TRANSFER_REVERSED: "Estorno de transferência",
  OPENING_BALANCE: "Saldo inicial",
};
export const PERIOD_EVENT: Record<string, string> = { CLOSE: "Fechou", REOPEN_REQUEST: "Pediu reabertura", REOPEN_APPROVE: "Aprovou reabertura", REOPEN_REJECT: "Recusou reabertura" };
export const brDate = (d: string | null | undefined) => (d ? d.split("-").reverse().join("/") : "—");
export const monthLabel = (iso: string) => new Intl.DateTimeFormat("pt-BR", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(iso + "T00:00:00Z"));
