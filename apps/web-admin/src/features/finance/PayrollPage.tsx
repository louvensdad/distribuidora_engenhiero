import { useState } from "react";
import { ApiError, api } from "../../api/http";
import type { PayrollRun } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { AccountSelect } from "./AccountSelect";
import { RUBRIC_KIND, STEP, monthBr } from "./accountingLabels";
import { brDate } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** W10 — folha por rubricas: totais para quem vê a folha; valor por pessoa só com a permissão de salário individual. */
export function PayrollPage() {
  const [res, reload] = useResource<{ items: PayrollRun[] }>("/api/v1/payroll-runs");
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-folha">
      <header className="page-head">
        <div>
          <h1 id="titulo-folha">Folha de pagamento e encargos</h1>
          <p className="muted">O contador prepara ou importa a folha no app Contábil; o patrão confere e aprova; o pagamento dos salários é outra etapa. Encargos e retenções viram conta a pagar, paga pelas guias (sem despesa em dobro).</p>
        </div>
      </header>
      {notice && <p className="notice" role="status">{notice}</p>}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhuma folha" /> : res.data.items.map((r) => (
        <RunCard key={r.id} r={r} onDone={(m) => { setNotice(m); reload(); }} />
      )))}
    </section>
  );
}

function RunCard({ r, onDone }: { r: PayrollRun; onDone: (m: string) => void }) {
  const { can, me } = useSession();
  const [note, setNote] = useState("");
  const [account, setAccount] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const run = async (fn: () => Promise<unknown>, done: string) => {
    setError(null);
    try {
      await fn();
      onDone(done);
    } catch (err) {
      setError(asError(err));
    }
  };
  const t = r.totals;
  return (
    <article className="card" aria-label={`Folha ${monthBr(r.competence)}`}>
      <div className="page-head">
        <div>
          <h2 className="h3">{r.description} · {monthBr(r.competence)} · {t.people} pessoa(s)</h2>
          <p className="muted small">{r.source === "IMPORT" ? "Importada do arquivo do contador" : "Lançada manualmente"} por {r.created_by_name} em {formatDateTime(r.created_at)} · pagamento em {brDate(r.pay_date)}</p>
        </div>
        <span className={"badge " + (r.state === "PAID" ? "badge-ok" : r.state === "RETURNED" ? "badge-error" : "badge-violet")}>{STEP[r.state]}</span>
      </div>
      <div className="kpis kpis-4">
        <div className="card kpi"><span className="kpi-label">Proventos</span><span className="kpi-value">{formatMoney(t.earnings)}</span></div>
        <div className="card kpi"><span className="kpi-label">Benefícios</span><span className="kpi-value">{formatMoney(t.benefits)}</span></div>
        <div className="card kpi"><span className="kpi-label">Encargos</span><span className="kpi-value">{formatMoney(t.employer_charges)}</span></div>
        <div className="card kpi kpi-highlight"><span className="kpi-label">Custo total</span><span className="kpi-value">{formatMoney(t.company_cost)}</span>
          <span className="muted small">líquido a pagar {formatMoney(t.net_pay)}</span></div>
      </div>
      <div className="table-wrap"><table className="stack">
        <thead><tr><th scope="col">Rubrica</th><th scope="col">Tipo</th><th scope="col">Pessoas</th><th scope="col" className="num">Total</th></tr></thead>
        <tbody>{r.rubrics.map((x) => (
          <tr key={x.kind + x.rubric}><th scope="row">{x.rubric}</th><td data-label="Tipo">{RUBRIC_KIND[x.kind]}</td><td data-label="Pessoas">{x.people}</td>
            <td data-label="Total" className="num">{formatMoney(x.amount)}</td></tr>
        ))}</tbody>
      </table></div>
      {r.people ? (
        <details>
          <summary>Valores por pessoa (dado individual)</summary>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Pessoa</th><th scope="col" className="num">Proventos</th><th scope="col" className="num">Descontos</th><th scope="col" className="num">Adiant.</th><th scope="col" className="num">Líquido</th></tr></thead>
            <tbody>{r.people.map((p) => (
              <tr key={p.membership_id}><th scope="row">{p.person}</th><td data-label="Proventos" className="num">{formatMoney(p.earnings)}</td>
                <td data-label="Descontos" className="num">{formatMoney(p.deductions)}</td><td data-label="Adiant." className="num">{formatMoney(p.advances)}</td>
                <td data-label="Líquido" className="num"><strong>{formatMoney(p.net)}</strong></td></tr>
            ))}</tbody>
          </table></div>
        </details>
      ) : <p className="muted small">Valores por pessoa ocultos: exigem a permissão de salário individual.</p>}
      {r.decided_at && <p className="muted small">{r.state === "RETURNED" ? "Devolvida" : "Aprovada"} por {r.decided_by_name}{r.decided_note ? ` — ${r.decided_note}` : ""} em {formatDateTime(r.decided_at)}</p>}
      {r.state === "SUBMITTED" && (me?.admin || can("payroll.approve")) && (
        <div className="actions">
          <label className="visually-hidden" htmlFor={`fn-${r.id}`}>Observação ou motivo</label>
          <input id={`fn-${r.id}`} placeholder="Observação / motivo da devolução" value={note} onChange={(e) => setNote(e.target.value)} />
          <button className="btn btn-small btn-primary" onClick={() => void run(() => api.post(`/api/v1/payroll-runs/${r.id}/approval`, { note: note || null }, `"${r.version}"`), "Folha aprovada — salários ainda a pagar.")}>Aprovar folha</button>
          <button className="btn btn-small btn-ghost" onClick={() => void run(() => api.post(`/api/v1/payroll-runs/${r.id}/return`, { note }, `"${r.version}"`), "Folha devolvida ao contador.")}>Devolver</button>
        </div>
      )}
      {r.state === "APPROVED" && (me?.admin || can("payroll.confirm")) && (
        <div className="form-grid" role="group" aria-label="Pagamento dos salários">
          <AccountSelect id={`fp-${r.id}`} value={account} onChange={setAccount} />
          <div className="actions span-2"><button className="btn btn-primary" onClick={() => void run(() => api.post(`/api/v1/payroll-runs/${r.id}/payments`, { fin_account_id: account || null }), "Salários líquidos pagos.")}>Pagar salários líquidos ({formatMoney(t.net_pay)})</button></div>
        </div>
      )}
      {(me?.admin || can("payroll.export")) && <p><a href={`/api/v1/payroll-runs/${r.id}/export`}>Exportar CSV</a> <span className="muted small">(por pessoa só com a permissão individual)</span></p>}
      {error && <p className="notice notice-error" role="alert">{error.message}</p>}
    </article>
  );
}
