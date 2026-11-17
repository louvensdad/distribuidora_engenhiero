export const STEP: Record<string, string> = { DRAFT: "Em preparação", SUBMITTED: "Aguardando aprovação", RETURNED: "Devolvida ao contador", APPROVED: "Aprovada — pagamento pendente", PAID: "Paga", CANCELLED: "Cancelada" };
export const RUBRIC_KIND: Record<string, string> = { EARNING: "Proventos", BENEFIT: "Benefícios", DEDUCTION: "Descontos e retenções", ADVANCE: "Adiantamentos compensados", EMPLOYER_CHARGE: "Encargos" };
export const DOC_KIND: Record<string, string> = { GUIA: "Guia", COMPROVANTE: "Comprovante", FOLHA: "Folha", NOTA_FISCAL: "Nota fiscal", CONTRATO: "Contrato", EXTRATO: "Extrato", OUTRO: "Outro" };
export const monthBr = (ym: string | null) => (ym ? ym.split("-").reverse().join("/") : "—");
