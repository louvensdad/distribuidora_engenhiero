import { useState } from "react";
import { ApiError, api } from "../../api/http";
import type { Goal, GoalMetric } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDecimal, formatMoney, formatQty, monthStartBr, toDecimal, todayBr } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { brDate } from "../finance/financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const STATUS: Record<string, [string, string]> = {
  NOT_STARTED: ["Não começou", "badge-muted"], ON_PACE: ["No ritmo", "badge-ok"], BEHIND_PACE: ["Abaixo do ritmo", "badge-warn"],
  ACHIEVED: ["Atingida", "badge-ok"], MISSED: ["Não atingida", "badge-error"], HIDDEN: ["Realizado oculto pela sua permissão", "badge-muted"],
};
const STATE: Record<string, string> = { DRAFT: "Rascunho", ACTIVE: "Ativa", CANCELLED: "Cancelada" };
const isQty = (g: { metric: GoalMetric }) => g.metric === "SOLD_QUANTITY";
const trim = (v: string) => (v.includes(".") ? v.replace(/\.?0+$/, "") : v);
const fmt = (g: Goal, v: string | null) => (v == null ? "—" : isQty(g) ? formatQty(trim(v), g.unit ?? "") : formatMoney(v));

/** Segunda-feira da semana de uma data civil (AAAA-MM-DD). */
const mondayOf = (iso: string) => {
  const d = new Date(iso + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
};

/** REP-07 — metas semanais/mensais: realizado (do relatório), faltante, % e ritmo, com a fórmula à vista. */
export function GoalsPage() {
  const { can } = useSession();
  const [offset, setOffset] = useState(0);
  const from = monthStartBr(offset);
  const to = monthStartBr(offset + 1);
  const [res, reload] = useResource<{ items: Goal[] }>(`/api/v1/goals?from=${from}&to=${to}`);
  const [editing, setEditing] = useState<Goal | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-metas">
      <header className="page-head">
        <div>
          <h1 id="titulo-metas">Metas</h1>
          <p className="muted">O realizado não é digitado: vem do mesmo cálculo do relatório para o período da meta. Meta ativa não muda — para rever, cancele com motivo e crie outra.</p>
        </div>
        <div className="actions">
          <button className="btn btn-small" onClick={() => setOffset(offset - 1)} aria-label="Mês anterior">‹</button>
          <strong>{new Intl.DateTimeFormat("pt-BR", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(from + "T00:00:00Z"))}</strong>
          <button className="btn btn-small" onClick={() => setOffset(offset + 1)} aria-label="Próximo mês">›</button>
        </div>
      </header>
      {can("goals.create") && <GoalForm key={editing?.id ?? "nova"} editing={editing} onDone={() => { setEditing(null); reload(); }} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (() => {
        const live = res.data.items.filter((g) => g.state !== "CANCELLED");
        const cancelled = res.data.items.filter((g) => g.state === "CANCELLED");
        return (
          <>
            {live.length === 0 ? <EmptyState title="Nenhuma meta ativa ou em rascunho neste mês" /> : (
              <div className="cards-grid">
                {live.map((g) => <GoalCard key={g.id} g={g} onEdit={() => setEditing(g)} onDone={reload}
                  canEdit={can("goals.edit_draft")} canManage={can("goals.create")} />)}
              </div>
            )}
            {cancelled.length > 0 && (
              <details className="card">
                <summary>Canceladas neste mês ({cancelled.length})</summary>
                <ul className="timeline">{cancelled.map((g) => <li key={g.id}><strong>{g.title}</strong>
                  <span className="muted small">{g.metric_label} · meta {fmt(g, g.target)} · cancelada por {g.cancelled_by_name}: {g.cancel_reason}</span></li>)}</ul>
              </details>
            )}
          </>
        );
      })()}
    </section>
  );
}

function GoalCard({ g, onEdit, onDone, canEdit, canManage }: { g: Goal; onEdit: () => void; onDone: () => void; canEdit: boolean; canManage: boolean }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const p = g.progress;
  const [label, cls] = STATUS[p.status] ?? [p.status, "badge-muted"];
  const pctNum = p.percent == null ? 0 : Math.min(100, Math.max(0, Number(p.percent)));
  const run = async (path: string, body: unknown) => {
    setError(null);
    try { await api.post(`/api/v1/goals/${g.id}/${path}`, body, `"${g.version}"`); onDone(); } catch (e) { setError(asError(e)); }
  };
  return (
    <article className="card" aria-label={g.title}>
      <h2 className="h3">{g.title}</h2>
      <p className="small">
        <span className={"badge " + (g.state === "ACTIVE" ? "badge-ok" : g.state === "DRAFT" ? "badge-violet" : "badge-muted")}>{STATE[g.state]}</span>
        {g.state !== "CANCELLED" && <span className={"badge " + cls}>{label}</span>}
      </p>
      <p className="muted small">{g.metric_label} · {g.period_label} ({brDate(g.period_start)} a {brDate(prevDay(g.period_end))}) · {g.branch_name ?? "Empresa toda"}</p>
      <dl className="kv">
        <dt>Meta</dt><dd>{fmt(g, g.target)}</dd>
        <dt>Realizado</dt><dd>{p.realized == null ? "oculto" : fmt(g, p.realized)}</dd>
        <dt>Falta</dt><dd><strong>{p.remaining == null ? "—" : fmt(g, p.remaining)}</strong></dd>
        <dt>Ritmo esperado hoje</dt><dd>{fmt(g, p.expected_by_now)} (dia {p.days_elapsed} de {p.days_total})</dd>
      </dl>
      {p.percent != null && (
        <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pctNum} aria-label={`${formatDecimal(p.percent)}% da meta`}>
          <span style={{ width: `${pctNum}%` }} /><b>{formatDecimal(p.percent)}%</b>
        </div>
      )}
      <details><summary>Fórmula</summary><p className="small">{p.formula}</p></details>
      {g.state === "CANCELLED" && <p className="muted small">Cancelada por {g.cancelled_by_name}: {g.cancel_reason}</p>}
      {g.state === "ACTIVE" && <p className="muted small">Ativada por {g.activated_by_name}.</p>}
      <div className="actions">
        {g.state === "DRAFT" && canEdit && <button className="btn btn-small" onClick={onEdit}>Editar</button>}
        {g.state === "DRAFT" && canManage && <button className="btn btn-small btn-primary" onClick={() => void run("activation", null)}>Ativar</button>}
        {g.state !== "CANCELLED" && canManage && <>
          <label className="visually-hidden" htmlFor={`gm-${g.id}`}>Motivo do cancelamento</label>
          <input id={`gm-${g.id}`} placeholder="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} />
          <button className="btn btn-small btn-ghost" onClick={() => void run("cancellation", { reason })}>Cancelar meta</button></>}
      </div>
      {error && <p className="field-error" role="alert">{error.message}</p>}
    </article>
  );
}

const prevDay = (iso: string) => {
  const d = new Date(iso + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
};

function GoalForm({ editing, onDone }: { editing: Goal | null; onDone: () => void }) {
  const [metric, setMetric] = useState<GoalMetric>(editing?.metric ?? "NET_REVENUE");
  const [kind, setKind] = useState<"WEEK" | "MONTH">(editing?.period_kind ?? "MONTH");
  const [date, setDate] = useState(editing?.period_start ?? todayBr());
  const [target, setTarget] = useState(editing ? formatDecimal(isQty(editing) ? trim(editing.target) : editing.target) : "");
  const [unit, setUnit] = useState(editing?.unit ?? "DUZIA");
  const [title, setTitle] = useState(editing?.title ?? "");
  const [error, setError] = useState<ApiError | null>(null);
  const start = kind === "WEEK" ? mondayOf(date) : date.slice(0, 8) + "01";
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    const body = { metric, unit: metric === "SOLD_QUANTITY" ? unit : null, period_kind: kind, period_start: start,
      target: toDecimal(target), title: title.trim() };
    try {
      if (editing) await api.put(`/api/v1/goals/${editing.id}`, body, `"${editing.version}"`);
      else await api.post("/api/v1/goals", body);
      setTarget(""); setTitle("");
      onDone();
    } catch (err) { setError(asError(err)); }
  };
  return (
    <form className="card form-grid" onSubmit={(e) => void submit(e)} aria-label={editing ? "Editar meta" : "Nova meta"}>
      <h2 className="h3 span-2">{editing ? "Editar rascunho" : "Nova meta (rascunho)"}</h2>
      <Field id="g-titulo" label="Título" error={fieldError(error, "title")}>
        <input id="g-titulo" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Ex.: vendas de novembro" />
      </Field>
      <Field id="g-ind" label="Indicador" error={fieldError(error, "metric")}>
        <select id="g-ind" value={metric} onChange={(e) => setMetric(e.target.value as GoalMetric)}>
          <option value="NET_REVENUE">Vendas (receita líquida)</option>
          <option value="SOLD_QUANTITY">Quantidade vendida</option>
          <option value="PROFIT">Lucro gerencial</option>
        </select>
      </Field>
      <Field id="g-per" label="Período">
        <select id="g-per" value={kind} onChange={(e) => setKind(e.target.value as "WEEK" | "MONTH")}>
          <option value="MONTH">Mês</option><option value="WEEK">Semana (segunda a domingo)</option>
        </select>
      </Field>
      <Field id="g-data" label={kind === "WEEK" ? "Uma data da semana" : "Uma data do mês"} hint={`Começa em ${brDate(start)}`} error={fieldError(error, "period_start")}>
        <input id="g-data" type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />
      </Field>
      {metric === "SOLD_QUANTITY" && (
        <Field id="g-un" label="Unidade-base" error={fieldError(error, "unit")}>
          <input id="g-un" value={unit} onChange={(e) => setUnit(e.target.value.toUpperCase())} />
        </Field>
      )}
      <Field id="g-alvo" label={metric === "SOLD_QUANTITY" ? "Meta (quantidade)" : "Meta (R$)"} error={fieldError(error, "target")}>
        <input id="g-alvo" inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="Ex.: 50.000,00" />
      </Field>
      <FormError error={error} />
      <div className="actions span-2">
        <button className="btn btn-primary" type="submit">{editing ? "Salvar rascunho" : "Criar rascunho"}</button>
        {editing && <button className="btn btn-ghost" type="button" onClick={onDone}>Cancelar edição</button>}
      </div>
    </form>
  );
}
