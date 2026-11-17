import { useState } from "react";
import { ApiError, api, newIdempotencyKey, uploadFile } from "../../api/http";
import type { TaxObligation } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { AccountSelect } from "./AccountSelect";
import { DocLink } from "./DocLink";
import { STEP, monthBr } from "./accountingLabels";
import { brDate } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** W09 — guias: o contador prepara no app; aqui o patrão aprova ou devolve e, à parte, registra o pagamento. */
export function TaxesPage() {
  const [state, setState] = useState("");
  const [res, reload] = useResource<{ items: TaxObligation[] }>(`/api/v1/tax-obligations${state ? `?state=${state}` : ""}`);
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-guias">
      <header className="page-head">
        <div>
          <h1 id="titulo-guias">Guias, vencimentos e aprovações</h1>
          <p className="muted">Preparar, aprovar e pagar são etapas separadas. Guia aprovada continua <strong>não paga</strong> até alguém registrar o pagamento. A despesa entra uma vez, na aprovação.</p>
        </div>
        <div className="field inline"><label htmlFor="g-sit">Situação</label>
          <select id="g-sit" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">Todas</option>{Object.entries(STEP).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></div>
      </header>
      {notice && <p className="notice" role="status">{notice}</p>}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhuma guia" /> : res.data.items.map((g) => (
        <TaxCard key={g.id} g={g} onDone={(m) => { setNotice(m); reload(); }} />
      )))}
    </section>
  );
}

function TaxCard({ g, onDone }: { g: TaxObligation; onDone: (m: string) => void }) {
  const { can, me } = useSession();
  const [note, setNote] = useState("");
  const [account, setAccount] = useState("");
  const [proof, setProof] = useState<string>("");
  const [key] = useState(newIdempotencyKey);
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
  const mayDecide = g.state === "SUBMITTED" && (me?.admin || can("taxes.approve"));
  const mayPay = g.state === "APPROVED" && (me?.admin || can("taxes.confirm"));
  return (
    <article className="card" aria-label={`Guia ${g.kind} ${monthBr(g.competence)}`}>
      <div className="page-head">
        <div>
          <h2 className="h3">{g.kind} · competência {monthBr(g.competence)} · {formatMoney(g.amount)}</h2>
          <p className="muted small">{g.description} · vence {brDate(g.due_date)}{g.payroll_run_id ? " · encargos da folha (sem nova despesa)" : ""}</p>
        </div>
        <span className={"badge " + (g.state === "PAID" ? "badge-ok" : g.state === "RETURNED" ? "badge-error" : "badge-violet")}>{STEP[g.state]}</span>
      </div>
      <ul className="timeline">
        <li><strong>Preparada</strong><span>{g.created_by_name}</span><span className="muted small">{formatDateTime(g.created_at)}</span></li>
        {g.submitted_at && <li><strong>Enviada para aprovação</strong><span>{g.submitted_by_name}</span><span className="muted small">{formatDateTime(g.submitted_at)}</span></li>}
        {g.decided_at && <li><strong>{g.state === "RETURNED" ? "Devolvida" : "Aprovada"}</strong><span>{g.decided_by_name}{g.decided_note ? ` — ${g.decided_note}` : ""}</span><span className="muted small">{formatDateTime(g.decided_at)}</span></li>}
        {g.state === "PAID" && <li><strong>Paga</strong><span>conta a pagar baixada</span></li>}
      </ul>
      <p>Arquivos: {g.attachments.length === 0 ? "nenhum" : g.attachments.map((d, i) => <span key={d.id}>{i > 0 && " · "}<DocLink d={d} /></span>)}</p>
      {mayDecide && (
        <div className="actions">
          <label className="visually-hidden" htmlFor={`gn-${g.id}`}>Observação ou motivo</label>
          <input id={`gn-${g.id}`} placeholder="Observação / motivo da devolução" value={note} onChange={(e) => setNote(e.target.value)} />
          <button className="btn btn-small btn-primary" onClick={() => void run(() => api.command(`/api/v1/tax-obligations/${g.id}/approval`, { note: note || null }, key, `"${g.version}"`), "Guia aprovada — o pagamento continua pendente.")}>Aprovar</button>
          <button className="btn btn-small btn-ghost" onClick={() => void run(() => api.post(`/api/v1/tax-obligations/${g.id}/return`, { note }, `"${g.version}"`), "Guia devolvida ao contador.")}>Devolver</button>
        </div>
      )}
      {mayPay && (
        <div className="form-grid" role="group" aria-label={`Pagamento da guia ${g.kind}`}>
          <AccountSelect id={`gp-${g.id}`} value={account} onChange={setAccount} />
          <div className="field">
            <label htmlFor={`gc-${g.id}`}>Comprovante (opcional)</label>
            <input id={`gc-${g.id}`} type="file" accept="application/pdf,image/png,image/jpeg" onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) void uploadFile<{ id: string; state: string; rejection: string | null }>(`/api/v1/tax-obligations/${g.id}/attachments`, f)
                .then((d) => (d.state === "AVAILABLE" ? setProof(d.id) : setError(new ApiError({ status: 422, code: "VALIDATION_ERROR", detail: `Comprovante recusado: ${d.rejection}` }))))
                .catch((err) => setError(asError(err)));
            }} />
            {proof && <span className="muted small">Comprovante anexado.</span>}
          </div>
          <div className="actions span-2">
            <button className="btn btn-primary" onClick={() => void run(() => api.command(`/api/v1/tax-obligations/${g.id}/payments`, { fin_account_id: account || null, proof_document_id: proof || null }, key), "Pagamento da guia registrado.")}>Registrar pagamento</button>
          </div>
        </div>
      )}
      {error && <p className="notice notice-error" role="alert">{error.message}</p>}
    </article>
  );
}
