import { useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { FinAccount, FinanceSummary, Statement, Transfer } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney, toDecimal, monthStartBr } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { ACCOUNT_KIND, EVENT, brDate } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const firstOfMonth = () => monthStartBr(0);

/** W08 — financeiro por conta: saldos, extrato, transferências; resultado do mês ao lado (caixa ≠ lucro). */
export function CashAccountsPage() {
  const { can, me } = useSession();
  const [res, reload] = useResource<{ items: FinAccount[] }>("/api/v1/finance/accounts");
  const [sum] = useResource<FinanceSummary>(can("reports.view") ? "/api/v1/reports/summary" : null);
  const [tr, reloadTr] = useResource<{ items: Transfer[] }>("/api/v1/finance/transfers");
  const [open, setOpen] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null); // fica na página: a lista recarrega e desmonta os formulários
  const refresh = () => { reload(); reloadTr(); };
  if (res.status === "loading") return <>{notice && <p className="notice" role="status">{notice}</p>}<LoadingState /></>;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  const accounts = res.data.items;
  return (
    <section className="page" aria-labelledby="titulo-contas">
      <header className="page-head">
        <div>
          <h1 id="titulo-contas">Financeiro por conta</h1>
          <p className="muted">Saldo de cada caixa e banco é a soma dos lançamentos. Transferência entre contas não muda o total.</p>
        </div>
      </header>
      {sum.status === "ready" && (
      <div className="kpis kpis-4">
        <div className="card kpi kpi-highlight"><span className="kpi-label">Disponível (caixas + bancos)</span>
          <span className="kpi-value">{formatMoney(sum.data.cash.available)}</span></div>
        <>
          <div className="card kpi"><span className="kpi-label">Cartões a receber</span><span className="kpi-value">{formatMoney(sum.data.cash.card_receivables_open)}</span></div>
          <div className="card kpi"><span className="kpi-label">A receber de clientes</span><span className="kpi-value">{formatMoney(sum.data.cash.receivables_open)}</span></div>
          <div className="card kpi"><span className="kpi-label">A pagar</span><span className="kpi-value">{formatMoney(sum.data.cash.payables_open)}</span></div>
        </>
      </div>
      )}
      {sum.status === "ready" && (
        <p className="notice">Resultado gerencial do mês (competência): <strong>{formatMoney(sum.data.profit)}</strong>. Saldo em caixa não é lucro — veja Financeiro › Resultado e fechamento.</p>
      )}
      <div className="table-wrap card">
        <table className="stack">
          <thead><tr><th scope="col">Conta</th><th scope="col">Tipo</th><th scope="col" className="num">Saldo</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
          <tbody>{accounts.map((a) => (
            <tr key={a.id}>
              <th scope="row">{a.name}</th>
              <td data-label="Tipo">{ACCOUNT_KIND[a.kind]}</td>
              <td data-label="Saldo" className="num"><strong>{formatMoney(a.balance)}</strong></td>
              <td><button className="btn btn-small" aria-expanded={open === a.id} onClick={() => setOpen(open === a.id ? null : a.id)}>
                {open === a.id ? "Fechar extrato" : "Ver extrato"}</button></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {notice && <p className="notice" role="status">{notice}</p>}
      {open && <StatementCard id={open} />}
      {me?.admin && (
        <div className="grid-2">
          <TransferForm accounts={accounts} onDone={() => { setNotice("Transferência registrada."); refresh(); }} />
          <NewAccountForm onDone={() => { setNotice("Conta criada."); refresh(); }} />
        </div>
      )}
      {tr.status === "ready" && tr.data.items.length > 0 && (
        <section className="card" aria-labelledby="titulo-transf">
          <h2 id="titulo-transf" className="h3">Transferências internas</h2>
          <ul className="timeline">{tr.data.items.map((t) => (
            <li key={t.id}><strong>{t.from_name} → {t.to_name} · {formatMoney(t.amount)}</strong>
              <span>{t.note ?? ""}{t.state === "REVERSED" ? ` · estornada: ${t.reverse_reason}` : ""}</span>
              <span className="muted small">{t.created_by_name} · {formatDateTime(t.created_at)}</span>
              {me?.admin && t.state === "CONFIRMED" && <ReverseTransfer t={t} onDone={refresh} />}</li>
          ))}</ul>
        </section>
      )}
    </section>
  );
}

function StatementCard({ id }: { id: string }) {
  const [from, setFrom] = useState(firstOfMonth());
  const [res] = useResource<Statement>(`/api/v1/finance/accounts/${id}/statement?from=${from}`);
  return (
    <section className="card" aria-labelledby="titulo-extrato">
      <div className="page-head">
        <h2 id="titulo-extrato" className="h3">Extrato {res.status === "ready" ? `— ${res.data.account.name}` : ""}</h2>
        <div className="field inline"><label htmlFor="ext-de">De</label>
          <input id="ext-de" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
      </div>
      {res.status === "loading" && <LoadingState />}
      {res.status === "error" && <p className="notice notice-error">{res.error.message}</p>}
      {res.status === "ready" && (
        <>
          <p>Saldo anterior <strong>{formatMoney(res.data.opening_balance)}</strong> · entradas {formatMoney(res.data.inflows)} · saídas {formatMoney(res.data.outflows)} · saldo final <strong>{formatMoney(res.data.closing_balance)}</strong> <span className="muted small">(até {brDate(res.data.to)}, exclusivo)</span></p>
          {res.data.lines.length === 0 ? <EmptyState title="Sem movimentos no período" /> : (
            <div className="table-wrap">
              <table className="stack">
                <thead><tr><th scope="col">Data</th><th scope="col">Movimento</th><th scope="col" className="num">Entrada</th><th scope="col" className="num">Saída</th><th scope="col" className="num">Saldo</th></tr></thead>
                <tbody>{res.data.lines.map((l) => (
                  <tr key={l.entry_id}>
                    <th scope="row">{brDate(l.entry_date)}</th>
                    <td data-label="Movimento">{EVENT[l.event_kind] ?? l.event_kind}{l.memo ? ` — ${l.memo}` : ""}<span className="muted small"><br />{l.created_by_name}</span></td>
                    <td data-label="Entrada" className="num">{l.debit !== "0.00" ? formatMoney(l.debit) : ""}</td>
                    <td data-label="Saída" className="num">{l.credit !== "0.00" ? formatMoney(l.credit) : ""}</td>
                    <td data-label="Saldo" className="num">{formatMoney(l.balance)}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}

function TransferForm({ accounts, onDone }: { accounts: FinAccount[]; onDone: () => void }) {
  const usable = accounts.filter((a) => a.status === "ACTIVE" && a.kind !== "CARD_ACQUIRER");
  const [f, setF] = useState({ from: "", to: "", amount: "", note: "" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command("/api/v1/finance/transfers", { from_account_id: f.from || null, to_account_id: f.to || null, amount: toDecimal(f.amount), note: f.note.trim() || null }, key);
      setKey(newIdempotencyKey());
      setF({ from: "", to: "", amount: "", note: "" });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Transferência interna">
      <h2 className="h3 span-2">Transferência entre contas</h2>
      <Field id="tr-de" label="De *" error={fieldError(error, "from_account_id")}>
        <select id="tr-de" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })}>
          <option value="">Selecione</option>{usable.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
      </Field>
      <Field id="tr-para" label="Para *" error={fieldError(error, "to_account_id")}>
        <select id="tr-para" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })}>
          <option value="">Selecione</option>{usable.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
      </Field>
      <Field id="tr-valor" label="Valor (R$) *" error={fieldError(error, "amount")}>
        <input id="tr-valor" className="money" inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
      </Field>
      <Field id="tr-obs" label="Observação" error={fieldError(error, "note")}>
        <input id="tr-obs" maxLength={500} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Transferir</button></div>
    </form>
  );
}

function NewAccountForm({ onDone }: { onDone: () => void }) {
  const [f, setF] = useState({ name: "", kind: "BANK", opening: "" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command("/api/v1/finance/accounts", { name: f.name, kind: f.kind, opening_balance: toDecimal(f.opening) }, key);
      setKey(newIdempotencyKey());
      setF({ name: "", kind: "BANK", opening: "" });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Nova conta">
      <h2 className="h3 span-2">Nova conta</h2>
      <Field id="nc-nome" label="Nome *" error={fieldError(error, "name")}>
        <input id="nc-nome" maxLength={80} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
      </Field>
      <Field id="nc-tipo" label="Tipo *" error={fieldError(error, "kind")}>
        <select id="nc-tipo" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
          <option value="BANK">Banco</option><option value="COMPANY_CASH">Caixa da empresa</option>
        </select>
      </Field>
      <Field id="nc-saldo" label="Saldo inicial (R$)" hint="Lançado como saldo inicial, não como receita" error={fieldError(error, "opening_balance")}>
        <input id="nc-saldo" className="money" inputMode="decimal" value={f.opening} onChange={(e) => setF({ ...f, opening: e.target.value })} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn">Criar conta</button></div>
    </form>
  );
}

function ReverseTransfer({ t, onDone }: { t: Transfer; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const run = async () => {
    setError(null);
    try {
      await api.post(`/api/v1/finance/transfers/${t.id}/reverse`, { reason });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <span className="actions">
      <label className="visually-hidden" htmlFor={`rt-${t.id}`}>Motivo do estorno</label>
      <input id={`rt-${t.id}`} placeholder="Motivo do estorno" value={reason} onChange={(e) => setReason(e.target.value)} />
      <button className="btn btn-small" onClick={() => void run()}>Estornar</button>
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </span>
  );
}
