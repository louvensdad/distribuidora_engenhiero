import type { OrderState, PaymentMethod, PaymentState } from "../../api/types";

export const ORDER_STATE: Record<OrderState, string> = {
  CONFIRMED: "Reservado", READY: "Pronto para retirada", PARTIALLY_FULFILLED: "Entregue em parte", FULFILLED: "Concluído", CANCELLED: "Cancelado",
};
export const PAYMENT_STATE: Record<PaymentState, string> = {
  UNPAID: "Não pago", PARTIALLY_PAID: "Pago em parte", PAID: "Pago", ON_ACCOUNT: "A prazo autorizado", REFUND_DUE: "Devolver valor ao cliente",
};
export const METHOD: Record<PaymentMethod, string> = {
  CASH: "Dinheiro", PIX: "Pix", DEBIT: "Débito", CREDIT: "Crédito", BANK_TRANSFER: "Transferência", ON_ACCOUNT: "A prazo",
};
export const PAY_STATE: Record<string, string> = { PENDING: "Aguardando conferência", CONFIRMED: "Confirmado", REJECTED: "Recusado", REVERSED: "Estornado" };
export const TYPE: Record<string, string> = { PICKUP: "Retirada", DELIVERY: "Entrega" };
