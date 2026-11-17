import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Payable, PayableDetail } from "../../api/types";
import { useResource } from "../../api/useResource";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { PagedList } from "../../components/Lists";
import { formatDateTime, formatMoney, toDecimal, todayBr } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { EmptyState, ErrorState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { AccountSelect } from "./AccountSelect";
import { PAYABLE_STATE, brDate } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const SOURCE: Record<string, string> = { inventory_receipt: "Compra de mercadoria", vehicle_expense: "Gasto de veículo", expense: "Despesa" };

/** Contas a pagar (FIN-02/FIN-06): pagar baixa o passivo contra caixa/banco; nunca gera nova despesa. */
export function PayablesPage() {
  const [f, setF] = useUrlFilters(["state"] as const);
  const state = f.state || "PENDING"; // sem filtro na URL = a pagar; "ALL" = todas
  const [open, setOpen] = useState<string | null>(null);
  const list = usePaged<Payable>(`/api/v1/payables?${qs({ limit: 50, state: state === "ALL" ? "" : state })}`);
  const reload = list.reload;
  return (
    <section className="page" aria-labelledby="titulo-pagar">
      <header className="page-head">
        <div>
          <h1 id="titulo-pagar">Contas a pagar</h1>
          <p className="muted">Nascem do fato reconhecido (compra de mercadoria vira estoque; despesa e gasto de veículo já estão no resultado). Pagar só baixa a obrigação.</p>
        </div>
        <div className="field inline">
          <label htmlFor="pagar-filtro">Situação</label>
          <select id="pagar-filtro" value={state} onChange={(e) => setF({ state: e.target.value === "PENDING" ? "" : e.target.value })}>
            <option value="PENDING">A pagar</option><option value="PAID">Pagas</option><option value="CANCELLED">Canceladas</option><option value="ALL">Todas</option>
          </select>
        </div>
      </header>
      <PagedList list={list} empty={<EmptyState title="Nenhuma conta nesta situação" />}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Descrição</th><th scope="col">Origem</th><th scope="col">Competência</th><th scope="col">Vencimento</th>
              <th scope="col" className="num">Valor</th><th scope="col" className="num">Saldo</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{items.map((p) => (
              <tr key={p.id}>
                <th scope="row">{p.source_type === "inventory_receipt" ? <Link to={`/operation/receipts/${p.source_id}`}>{p.description}</Link> : p.description}</th>
                <td data-label="Origem">{SOURCE[p.source_type] ?? p.source_type}</td>
                <td data-label="Competência">{brDate(p.competence)}</td>
                <td data-label="Vencimento">{brDate(p.due_date)}</td>
                <td data-label="Valor" className="num">{formatMoney(p.amount)}</td>
                <td data-label="Saldo" className="num"><strong>{formatMoney(p.balance)}</strong></td>
                <td data-label="Situação">{PAYABLE_STATE[p.state]}</td>
                <td><button className="btn btn-small" aria-expanded={open === p.id} onClick={() => setOpen(open === p.id ? null : p.id)}>
                  {open === p.id ? "Fechar" : "Abrir"}</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}</PagedList>
      {open && <PayableCard id={open} onChange={reload} />}
    </section>
  );
}

function PayableCard({ id, onChange }: { id: string; onChange: () => void }) {
  const { can } = useSession();
  const [res, reload] = useResource<PayableDetail>(`/api/v1/payables/${id}`);
  const refresh = () => { reload(); onChange(); };
  if (res.status !== "ready") return res.status === "error" ? <ErrorState error={res.error} onRetry={reload} /> : <LoadingState />;
  const { payable: p, settlements } = res.data;
  return (
    <section className="card" aria-labelledby="titulo-conta">
      <h2 id="titulo-conta" className="h3">{p.description}</h2>
      <p>Valor {formatMoney(p.amount)} · saldo <strong>{formatMoney(p.balance)}</strong> · {PAYABLE_STATE[p.state]}</p>
      {settlements.length > 0 && (
        <ul className="timeline">{settlements.map((s) => (
          <li key={s.id}><strong>{s.kind === "PAYMENT" ? "Pagamento" : s.kind === "SUPPLIER_RETURN" ? "Abatimento por devolução" : "Cancelamento"} · {formatMoney(s.amount)}</strong>
            <span>{s.account_name ?? ""} {brDate(s.paid_on)}{s.note ? ` — ${s.note}` : ""}{s.state === "REVERSED" ? ` · estornado: ${s.reverse_reason}` : ""}</span>
            <span className="muted small">{s.created_by_name} · {formatDateTime(s.created_at)}</span>
            {s.kind === "PAYMENT" && s.state === "CONFIRMED" && can("expenses.reverse") && <ReverseSettlement id={s.id} onDone={refresh} />}</li>
        ))}</ul>
      )}
      {["OPEN", "PARTIALLY_PAID"].includes(p.state) && can("expenses.confirm") && <PayForm p={p} onDone={refresh} />}
    </section>
  );
}

function PayForm({ p, onDone }: { p: Payable; onDone: () => void }) {
  const [amount, setAmount] = useState(p.balance);
  const [account, setAccount] = useState("");
  const [paidOn, setPaidOn] = useState(todayBr);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command(`/api/v1/payables/${p.id}/payments`, { amount: toDecimal(amount), fin_account_id: account || null, paid_on: paidOn }, key);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label="Registrar pagamento da conta">
      <Field id="pg-valor" label="Valor pago (R$) *" error={fieldError(error, "amount")}>
        <input id="pg-valor" className="money" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </Field>
      <AccountSelect id="pg-conta" value={account} onChange={setAccount} error={fieldError(error, "fin_account_id")} />
      <Field id="pg-data" label="Data do pagamento" error={fieldError(error, "paid_on")}>
        <input id="pg-data" type="date" value={paidOn} onChange={(e) => setPaidOn(e.target.value)} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Registrar pagamento</button></div>
    </form>
  );
}

export function ReverseSettlement({ id, onDone, path = "payable-settlements" }: { id: string; onDone: () => void; path?: string }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const run = async () => {
    setError(null);
    try {
      await api.post(`/api/v1/${path}/${id}/reverse`, { reason });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <span className="actions">
      <label className="visually-hidden" htmlFor={`rs-${id}`}>Motivo do estorno</label>
      <input id={`rs-${id}`} placeholder="Motivo do estorno" value={reason} onChange={(e) => setReason(e.target.value)} />
      <button className="btn btn-small" onClick={() => void run()}>Estornar</button>
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </span>
  );
}
