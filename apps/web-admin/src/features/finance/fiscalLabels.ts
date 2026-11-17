import type { IntegrationOutcome, InvoiceState } from "../../api/types";

export const INVOICE_STATE: Record<InvoiceState, string> = {
  DRAFT: "Rascunho", READY: "Conferida pelo contador", TRANSMITTING: "Enviada — aguardando a SEFAZ",
  UNCERTAIN: "Resultado incerto — consultar", AUTHORIZED: "Autorizada", REJECTED: "Rejeitada",
  CANCEL_PENDING: "Cancelamento pendente", CANCELLED: "Cancelada", DISCARDED: "Descartada",
};
export const INVOICE_BADGE: Record<InvoiceState, string> = {
  DRAFT: "badge-violet", READY: "badge-violet", TRANSMITTING: "badge-warn", UNCERTAIN: "badge-warn", AUTHORIZED: "badge-ok",
  REJECTED: "badge-error", CANCEL_PENDING: "badge-warn", CANCELLED: "badge-muted", DISCARDED: "badge-muted",
};
export const DOC_TYPE: Record<string, string> = { NFE: "NF-e (modelo 55)", NFCE: "NFC-e (modelo 65)", BOTH: "NF-e e NFC-e (pelo cliente)" };
export const OUTCOME: Record<IntegrationOutcome, string> = {
  BLOCKED: "Bloqueado", SENT: "Enviado", SUCCESS: "Concluído", REJECTED: "Recusado", TIMEOUT: "Sem resposta", ERROR: "Erro",
};
export const OPERATION: Record<string, string> = {
  draft: "Rascunho", transmit: "Transmissão", query: "Consulta", cancel: "Cancelamento", charge: "Cobrança Pix",
};
export const TAX_REGIME: Record<string, string> = {
  SIMPLES_NACIONAL: "Simples Nacional", SIMPLES_EXCESSO: "Simples — excesso de sublimite", NORMAL: "Regime normal",
};
export const WEBHOOK_STATUS: Record<string, string> = {
  PROCESSED: "Processado", DUPLICATE: "Repetido (sem efeito)", REJECTED_SIGNATURE: "Assinatura inválida (sem efeito)",
  UNMATCHED: "Para conferir", IGNORED: "Ignorado", ERROR: "Erro (o provedor reenvia)",
};
