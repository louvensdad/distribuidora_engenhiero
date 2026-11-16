import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Receivable } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney, toDecimal } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { AccountSelect } from "./AccountSelect";
import { RECEIVABLE_KIND, RECEIVABLE_STATE, brDate } from "./financeLabels";
import { ReverseSettlement } from "./PayablesPage";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
interface Detail { receivable: Receivable; settlements: { id: string; amount: string; received: string; account_name: string; received_on: string; note: string | null; state: string; created_by_name: string; created_at: string; reverse_reason: string | null }[] }

/** FIN-02/FIN-03 — a receber: vendas a prazo, parcelas líquidas do cartão (conciliação do repasse) e créditos com fornecedor. */
export function ReceivablesPage() {
  const [kind, setKind] = useState("");
  const [state, setState] = useState("PENDING");
  const [open, setOpen] = useState<string | null>(null);
  const [res, reload] = useResource<{ items: Receivable[] }>(`/api/v1/receivables?state=${state}${kind ? `&kind=${kind}` : ""}`);
  return (
    <section className="page" aria-labelledby="titulo-receber">
      <header className="page-head">
        <div>
          <h1 id="titulo-receber">Contas a receber</h1>
          <p className="muted">Cartão: o adquirente repassa o líquido em parcelas; a taxa já é despesa na venda e não é cobrada de novo na conciliação.</p>
        </div>
      </header>
      <div className="actions" style={{ justifyContent: "flex-start" }}>
        <div className="field inline"><label htmlFor="rc-tipo">Tipo</label>
          <select id="rc-tipo" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">Todos</option>{Object.entries(RECEIVABLE_KIND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></div>
        <div className="field inline"><label htmlFor="rc-sit">Situação</label>
          <select id="rc-sit" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="PENDING">A receber</option><option value="SETTLED">Recebidos</option><option value="CANCELLED">Cancelados</option>
          </select></div>
      </div>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nada nesta situação" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Origem</th><th scope="col">Tipo</th><th scope="col">Vencimento</th><th scope="col" className="num">Bruto</th>
              <th scope="col" className="num">Taxa</th><th scope="col" className="num">A receber</th><th scope="col" className="num">Saldo</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{res.data.items.map((r) => (
              <tr key={r.id}>
                <th scope="row">{r.order_id ? <Link to={`/sales/orders/${r.order_id}`}>Pedido #{r.order_number} · {r.customer_name}</Link> : r.supplier_name ?? r.description}
                  {r.installments > 1 && <span className="muted small"> · parcela {r.installment_no}/{r.installments}</span>}</th>
                <td data-label="Tipo">{RECEIVABLE_KIND[r.kind]}</td>
                <td data-label="Vencimento">{brDate(r.due_date)}</td>
                <td data-label="Bruto" className="num">{formatMoney(r.gross)}</td>
                <td data-label="Taxa" className="num">{formatMoney(r.fee)}</td>
                <td data-label="A receber" className="num">{formatMoney(r.amount)}</td>
                <td data-label="Saldo" className="num"><strong>{formatMoney(r.balance)}</strong></td>
                <td data-label="Situação">{RECEIVABLE_STATE[r.state]}</td>
                <td><button className="btn btn-small" aria-expanded={open === r.id} onClick={() => setOpen(open === r.id ? null : r.id)}>{open === r.id ? "Fechar" : "Abrir"}</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
      {open && <ReceivableCard id={open} onChange={reload} />}
    </section>
  );
}

function ReceivableCard({ id, onChange }: { id: string; onChange: () => void }) {
  const { can } = useSession();
  const [res, reload] = useResource<Detail>(`/api/v1/receivables/${id}`);
  const refresh = () => { reload(); onChange(); };
  if (res.status !== "ready") return res.status === "error" ? <ErrorState error={res.error} onRetry={reload} /> : <LoadingState />;
  const r = res.data.receivable;
  const cap = r.kind === "SUPPLIER_CREDIT" ? "expenses.confirm" : "payments.confirm";
  return (
    <section className="card" aria-labelledby="titulo-rec">
      <h2 id="titulo-rec" className="h3">{RECEIVABLE_KIND[r.kind]} · {r.description}</h2>
      <p>A receber {formatMoney(r.amount)} · saldo <strong>{formatMoney(r.balance)}</strong> · vence {brDate(r.due_date)}</p>
      {res.data.settlements.length > 0 && (
        <ul className="timeline">{res.data.settlements.map((s) => (
          <li key={s.id}><strong>{formatMoney(s.amount)} baixados · {formatMoney(s.received)} creditados em {s.account_name}</strong>
            <span>{brDate(s.received_on)}{s.note ? ` — ${s.note}` : ""}{s.state === "REVERSED" ? ` · estornado: ${s.reverse_reason}` : ""}</span>
            <span className="muted small">{s.created_by_name} · {formatDateTime(s.created_at)}</span>
            {s.state === "CONFIRMED" && can(cap) && <ReverseSettlement id={s.id} path="receivable-settlements" onDone={refresh} />}</li>
        ))}</ul>
      )}
      {["OPEN", "PARTIALLY_SETTLED"].includes(r.state) && can(cap) && <SettleForm r={r} onDone={refresh} />}
    </section>
  );
}

function SettleForm({ r, onDone }: { r: Receivable; onDone: () => void }) {
  const [amount, setAmount] = useState(r.balance);
  const [received, setReceived] = useState(r.balance);
  const [account, setAccount] = useState("");
  const [note, setNote] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command(`/api/v1/receivables/${r.id}/settlements`, { amount: toDecimal(amount), received: toDecimal(received),
        fin_account_id: account || null, note: note.trim() || null }, key);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label="Registrar recebimento">
      <Field id="rc-baixa" label="Baixar do saldo (R$) *" error={fieldError(error, "amount")}>
        <input id="rc-baixa" className="money" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </Field>
      <Field id="rc-credito" label="Creditado na conta (R$) *" hint={r.kind === "CARD" ? "Menor que a baixa só com motivo (ajuste de taxa)" : "Igual à baixa"} error={fieldError(error, "received")}>
        <input id="rc-credito" className="money" inputMode="decimal" value={received} onChange={(e) => setReceived(e.target.value)} />
      </Field>
      <AccountSelect id="rc-conta" value={account} onChange={setAccount} bankOnly={r.kind === "CARD"} error={fieldError(error, "fin_account_id")} />
      <Field id="rc-motivo" label="Observação / motivo da diferença" error={fieldError(error, "note")}>
        <input id="rc-motivo" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Registrar recebimento</button></div>
    </form>
  );
}
