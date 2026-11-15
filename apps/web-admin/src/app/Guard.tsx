import type { ReactNode } from "react";
import { ForbiddenState } from "../components/States";
import { useSession } from "../session/SessionContext";

/** Evita carregar dados de quem não tem a capacidade. Não é fronteira de segurança: o servidor também nega. */
export function Guard({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  const { can } = useSession();
  return anyOf.some(can) ? <>{children}</> : <ForbiddenState />;
}
