import { useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { CashSession } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney, toDecimal } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { ConflictState, EmptyState, ErrorState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

const STATE: Record<CashSession["state"], string> = { OPEN: "Aberto", CLOSED: "Fechado — aguardando conferência", REVIEWED: "Conferido" };

/** Caixa do turno: fundo, entradas em dinheiro (sem troco), esperado × contado, diferença justificada e conferente. */
export function MyCashPage() {
  const { can, me } = useSession();
  const [cur, reload] = useResource<{ session: CashSession | null }>(can("own_cash.view") ? "/api/v1/cash-sessions/current" : null);
  const [all, reloadAll] = useResource<{ items: CashSession[] }>("/api/v1/cash-sessions");
  const refresh = () => { reload(); reloadAll(); };
  return (
    <section className="page" aria-labelledby="titulo-caixa">
      <header className="page-head">
        <div>
          <h1 id="titulo-caixa">Caixa do turno</h1>
          <p className="muted">Entra no caixa só o valor dos pedidos pagos em dinheiro; o troco não é receita nem despesa. O saldo da empresa não aparece aqui.</p>
        </div>
      </header>
      {can("own_cash.view") && (cur.status === "loading" ? <LoadingState /> : cur.status === "error" ? <ErrorState error={cur.error} onRetry={reload} /> :
        cur.status === "ready" && (cur.data.session ? <CurrentSession s={cur.data.session} onDone={refresh} /> : can("own_cash.create") ? <OpenForm onDone={refresh} /> : <EmptyState title="Nenhum caixa aberto" />))}
      <h2 className="h3">{me?.admin ? "Fechamentos da equipe" : "Meus caixas"}</h2>
      {all.status === "ready" && (all.data.items.length === 0 ? <EmptyState title="Nenhum caixa registrado" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Operador</th><th scope="col">Abertura</th><th scope="col" className="num">Esperado</th><th scope="col" className="num">Contado</th>
              <th scope="col" className="num">Diferença</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{all.data.items.map((s) => (
              <tr key={s.id}>
                <th scope="row">{s.operator_name}<br /><span className="muted small">{s.branch}</span></th>
                <td data-label="Abertura">{formatDateTime(s.opened_at)}</td>
                <td data-label="Esperado" className="num">{formatMoney(s.expected)}</td>
                <td data-label="Contado" className="num">{formatMoney(s.counted)}</td>
                <td data-label="Diferença" className={"num" + (s.difference && s.difference !== "0.00" ? " text-warning" : "")}>{formatMoney(s.difference)}
                  {s.justification && <span className="muted small"><br />“{s.justification}”</span>}</td>
                <td data-label="Situação">{STATE[s.state]}{s.reviewed_by_name && <span className="muted small"><br />por {s.reviewed_by_name}</span>}</td>
                <td>{me?.admin && s.state === "CLOSED" && s.operator_name !== me.user.display_name && <ReviewButton s={s} onDone={refresh} />}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function OpenForm({ onDone }: { onDone: () => void }) {
  const [fund, setFund] = useState("");
  const [key] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command("/api/v1/cash-sessions", { opening_float: toDecimal(fund) }, key);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    }
  };
  return (
    <form className="card row-form" onSubmit={submit} noValidate>
      <h2 className="h3">Abrir caixa</h2>
      <Field id="cx-fundo" label="Fundo de troco (R$) *" error={fieldError(error, "opening_float")}>
        <input id="cx-fundo" className="money" inputMode="decimal" value={fund} onChange={(e) => setFund(e.target.value)} />
      </Field>
      <span />
      <button className="btn btn-primary">Abrir caixa</button>
      <FormError error={error?.fieldErrors.length ? null : error} />
    </form>
  );
}

function CurrentSession({ s, onDone }: { s: CashSession; onDone: () => void }) {
  const { can } = useSession();
  const [counted, setCounted] = useState("");
  const [just, setJust] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command(`/api/v1/cash-sessions/${s.id}/close`, { counted: toDecimal(counted), justification: just.trim() || null }, key, `"${s.version}"`);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    }
  };
  if (error?.code === "VERSION_CONFLICT") return <ConflictState onReload={() => { setError(null); onDone(); }} />;
  return (
    <>
      <div className="kpis kpis-4" aria-label="Caixa aberto">
        <div className="card kpi"><span className="kpi-label">Fundo</span><span className="kpi-value">{formatMoney(s.opening_float)}</span></div>
        <div className="card kpi"><span className="kpi-label">Entradas em dinheiro</span><span className="kpi-value">{formatMoney(s.cash_in)}</span></div>
        <div className="card kpi"><span className="kpi-label">Saídas (estornos e gastos)</span><span className="kpi-value">{formatMoney(s.cash_out)}</span></div>
        <div className="card kpi kpi-highlight"><span className="kpi-label">Esperado na gaveta</span><span className="kpi-value">{formatMoney(s.expected)}</span></div>
      </div>
      {can("own_cash.close") && (
        <form className="card form-grid" onSubmit={submit} noValidate aria-labelledby="titulo-fechar">
          <h2 id="titulo-fechar" className="h3 span-2">Fechar caixa (aberto em {formatDateTime(s.opened_at)})</h2>
          <Field id="cx-contado" label="Valor contado (R$) *" error={fieldError(error, "counted")}>
            <input id="cx-contado" className="money" inputMode="decimal" value={counted} onChange={(e) => setCounted(e.target.value)} />
          </Field>
          <Field id="cx-just" label="Justificativa (obrigatória se houver diferença)" error={fieldError(error, "justification")}>
            <input id="cx-just" maxLength={500} value={just} onChange={(e) => setJust(e.target.value)} />
          </Field>
          <FormError error={error} />
          <div className="actions span-2"><button className="btn btn-primary">Fechar e enviar para conferência</button></div>
        </form>
      )}
    </>
  );
}

function ReviewButton({ s, onDone }: { s: CashSession; onDone: () => void }) {
  const [err, setErr] = useState<string | null>(null);
  const review = async () => {
    const note = window.prompt("Observação da conferência (opcional)") ?? "";
    try {
      await api.post(`/api/v1/cash-sessions/${s.id}/review`, { note: note.trim() || null }, `"${s.version}"`);
      onDone();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Erro inesperado");
    }
  };
  return <div className="row-actions"><button className="btn btn-small btn-primary" onClick={() => void review()}>Conferir</button>{err && <span className="field-error">{err}</span>}</div>;
}
