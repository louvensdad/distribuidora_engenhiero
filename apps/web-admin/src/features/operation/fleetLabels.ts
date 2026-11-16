import type { StopState, TripState } from "../../api/types";

export const TRIP_STATE: Record<TripState, string> = {
  DRAFT: "Rascunho", ASSIGNED: "Atribuída", DEPARTED: "Em rota", RETURNED: "Retornou", RECONCILED: "Conferida", CANCELLED: "Cancelada",
};
export const STOP_STATE: Record<StopState, string> = {
  PENDING: "Pendente", DELIVERED: "Entregue", PARTIAL: "Entregue em parte", FAILED: "Não entregue", REMOVED: "Retirada da viagem",
};
