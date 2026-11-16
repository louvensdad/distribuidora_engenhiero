import type { StopState, TripState } from "../../api/types";

export const TRIP_STATE: Record<TripState, string> = {
  DRAFT: "Rascunho", ASSIGNED: "Atribuída", DEPARTED: "Em rota", RETURNED: "Retornou", RECONCILED: "Conferida", CANCELLED: "Cancelada",
};
export const STOP_STATE: Record<StopState, string> = {
  PENDING: "Pendente", DELIVERED: "Entregue", PARTIAL: "Entregue em parte", FAILED: "Não entregue", REMOVED: "Retirada da viagem",
};

export const VEHICLE_STATUS: Record<string, string> = { ACTIVE: "Ativo", MAINTENANCE: "Em manutenção", INACTIVE: "Inativo" };
export const EXPENSE_KIND: Record<string, string> = {
  FUEL: "Combustível", MAINTENANCE: "Manutenção", TIRES: "Pneus", INSURANCE: "Seguro", DOCUMENTS: "Documentos", TOLL: "Pedágio", OTHER: "Outro",
};
export const EXPENSE_STATE: Record<string, string> = { PENDING: "Aguardando aprovação", APPROVED: "Aprovado", REJECTED: "Recusado" };
export const PAID_WITH: Record<string, string> = { COMPANY: "Empresa (vira conta a pagar)", DRIVER_CASH: "Dinheiro do motorista" };
export const FUEL_TYPE: Record<string, string> = { DIESEL: "Diesel", GASOLINA: "Gasolina", ETANOL: "Etanol", GNV: "GNV" };
export const INCIDENT_KIND: Record<string, string> = {
  CUSTOMER_ABSENT: "Cliente ausente", WRONG_ADDRESS: "Endereço errado", REFUSED: "Recusa", PARTIAL: "Entrega parcial",
  DAMAGED: "Avaria", VEHICLE: "Problema no veículo", OTHER: "Outro",
};
export const REQUESTED_ACTION: Record<string, string> = { RESCHEDULE: "Reagendar", RETURN: "Devolver", NONE: "Sem pedido" };
export const ODOMETER_KIND: Record<string, string> = {
  INITIAL: "Cadastro", DEPARTURE: "Saída", RETURN: "Retorno", FUEL: "Abastecimento", MAINTENANCE: "Manutenção", CORRECTION: "Correção aprovada",
};
