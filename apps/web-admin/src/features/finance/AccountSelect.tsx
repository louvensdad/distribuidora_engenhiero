import type { FinAccount } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatMoney } from "../../app/format";
import { Field } from "../../components/Form";

/** Caixa da empresa ou banco para pagar/receber. Saldo só aparece para quem vê o caixa da empresa. */
export function AccountSelect({ id, value, onChange, error, bankOnly }: { id: string; value: string; onChange: (v: string) => void; error?: string; bankOnly?: boolean }) {
  const [res] = useResource<{ items: FinAccount[] }>("/api/v1/finance/accounts");
  const items = res.status === "ready"
    ? res.data.items.filter((a) => a.status === "ACTIVE" && a.kind !== "CASH_DRAWERS" && a.kind !== "CARD_ACQUIRER" && (!bankOnly || a.kind === "BANK"))
    : [];
  return (
    <Field id={id} label={bankOnly ? "Conta bancária *" : "Conta (caixa da empresa ou banco) *"} error={error}>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Selecione</option>
        {items.map((a) => <option key={a.id} value={a.id}>{a.name}{a.balance != null ? ` — saldo ${formatMoney(a.balance)}` : ""}</option>)}
      </select>
    </Field>
  );
}
