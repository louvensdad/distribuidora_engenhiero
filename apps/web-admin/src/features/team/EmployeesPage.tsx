import { useState, type FormEvent } from "react";
import { ApiError, api } from "../../api/http";
import { SECTOR_LABELS } from "../../api/labels";
import type { Absence, PerformanceReport, TeamCalendar } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDecimal, formatMoney } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

const KIND: Record<string, string> = { VACATION: "Férias", DAY_OFF: "Folga", LEAVE: "Afastamento" };
const STATE: Record<string, string> = { REQUESTED: "Aguardando aprovação", APPROVED: "Aprovada", REJECTED: "Recusada", CANCELLED: "Cancelada" };
const br = (d: string) => d.split("-").reverse().join("/");
const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const monthStart = (offset: number) => {
  const d = new Date();
  return new Date(Date.UTC(d.getFullYear(), d.getMonth() + offset, 1)).toISOString().slice(0, 10);
};
const WEEKDAY = ["dom", "seg", "ter", "qua", "qui", "sex", "sáb"];

/** Equipe › Funcionários: folgas e férias no calendário (quem está disponível em cada dia e setor) e desempenho por função. */
export function EmployeesPage() {
  const { can } = useSession();
  const [offset, setOffset] = useState(0);
  const from = monthStart(offset);
  const to = monthStart(offset + 1);
  const [cal, reload] = useResource<TeamCalendar>(`/api/v1/team/calendar?from=${from}&to=${to}`);
  const [perf] = useResource<PerformanceReport>(can("reports.view") ? `/api/v1/team/performance?from=${from}&to=${to}` : null);
  const [notice, setNotice] = useState<string[] | null>(null);
  if (cal.status === "loading") return <LoadingState />;
  if (cal.status === "forbidden") return <ForbiddenState />;
  if (cal.status === "error") return <ErrorState error={cal.error} onRetry={reload} />;
  const c = cal.data;
  const sectors = Array.from(new Set(c.members.map((m) => m.sector)));
  const monthName = new Intl.DateTimeFormat("pt-BR", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(from + "T00:00:00Z"));
  return (
    <section className="page" aria-labelledby="titulo-func">
      <header className="page-head">
        <div>
          <h1 id="titulo-func">Funcionários: folgas, férias e desempenho</h1>
          <p className="muted">A escala mostra quem está disponível em cada dia por setor. Aprovar uma ausência que deixa um setor sem ninguém gera aviso. Regras de férias da CLT e pagamento ficam com o contador e a folha.</p>
        </div>
        <div className="actions">
          <button className="btn btn-small" onClick={() => setOffset(offset - 1)} aria-label="Mês anterior">‹</button>
          <strong>{monthName}</strong>
          <button className="btn btn-small" onClick={() => setOffset(offset + 1)} aria-label="Próximo mês">›</button>
        </div>
      </header>
      {notice && <div className="notice" role="status">{notice.map((n) => <p key={n} style={{ margin: 0 }}>{n}</p>)}</div>}
      <section className="card" aria-labelledby="titulo-escala">
        <h2 id="titulo-escala" className="h3">Disponíveis por dia</h2>
        <div className="table-wrap">
          <table className="calendar">
            <thead><tr><th scope="col">Dia</th>{sectors.map((s) => <th key={s} scope="col">{SECTOR_LABELS[s] ?? s}</th>)}</tr></thead>
            <tbody>{c.days.map((d) => {
              const wd = WEEKDAY[new Date(d.date + "T12:00:00Z").getUTCDay()];
              return (
                <tr key={d.date}>
                  <th scope="row">{br(d.date).slice(0, 5)} <span className="muted small">{wd}</span></th>
                  {sectors.map((s) => {
                    const x = d.sectors.find((y) => y.sector === s);
                    if (!x) return <td key={s} />;
                    return (
                      <td key={s} className={x.uncovered ? "cell-alert" : x.absent.length ? "cell-warn" : ""}>
                        <strong>{x.available}/{x.total}</strong>{x.uncovered && <span className="badge badge-error"> sem ninguém</span>}
                        {x.absent.length > 0 && <span className="muted small"><br />{x.absent.join(", ")}</span>}
                      </td>
                    );
                  })}
                </tr>
              );
            })}</tbody>
          </table>
        </div>
      </section>
      <div className="grid-2">
        {can("users.edit_draft") && <AbsenceForm members={c.members} onDone={() => { setNotice(["Ausência registrada — aguardando aprovação."]); reload(); }} />}
        <section className="card" aria-labelledby="titulo-equipe-ferias">
          <h2 id="titulo-equipe-ferias" className="h3">Equipe</h2>
          <ul className="timeline">{c.members.map((m) => (
            <li key={m.membership_id}><strong>{m.person}</strong><span>{SECTOR_LABELS[m.sector] ?? m.sector} · {m.branch}</span>
              <span className="muted small">Férias aprovadas nos últimos 12 meses: {m.vacation_days_last_12m} dia(s)</span></li>
          ))}</ul>
        </section>
      </div>
      <section className="card" aria-labelledby="titulo-ausencias">
        <h2 id="titulo-ausencias" className="h3">Ausências do mês</h2>
        {c.absences.length === 0 ? <p className="muted">Nenhuma ausência neste mês.</p> : (
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Pessoa</th><th scope="col">Tipo</th><th scope="col">Período</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{c.absences.map((a) => (
              <tr key={a.id}>
                <th scope="row">{a.person}<span className="muted small"><br />{SECTOR_LABELS[a.sector] ?? a.sector}</span></th>
                <td data-label="Tipo">{KIND[a.kind]}</td>
                <td data-label="Período">{br(a.start_date)} a {br(a.end_date)} ({a.days} dia{a.days > 1 ? "s" : ""})</td>
                <td data-label="Situação">{STATE[a.state]}{a.decided_by_name && <span className="muted small"><br />{a.decided_by_name}</span>}</td>
                <td>{can("users.edit_draft") && <AbsenceActions a={a} onDone={(w) => { setNotice(w); reload(); }} />}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      {perf.status === "ready" && <PerformanceCard r={perf.data} />}
    </section>
  );
}

function AbsenceForm({ members, onDone }: { members: TeamCalendar["members"]; onDone: () => void }) {
  const [f, setF] = useState({ membership_id: "", kind: "VACATION", start_date: "", end_date: "", notes: "" });
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post("/api/v1/team/absences", { membership_id: f.membership_id || null, kind: f.kind, start_date: f.start_date || null,
        end_date: f.end_date || f.start_date || null, notes: f.notes.trim() || null });
      setF({ ...f, start_date: "", end_date: "", notes: "" });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Registrar folga ou férias">
      <h2 className="h3 span-2">Registrar folga ou férias</h2>
      <Field id="au-pessoa" label="Funcionário *" error={fieldError(error, "membership_id")}>
        <select id="au-pessoa" value={f.membership_id} onChange={(e) => setF({ ...f, membership_id: e.target.value })}>
          <option value="">Selecione</option>{members.map((m) => <option key={m.membership_id} value={m.membership_id}>{m.person}</option>)}
        </select>
      </Field>
      <Field id="au-tipo" label="Tipo *" error={fieldError(error, "kind")}>
        <select id="au-tipo" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
          {Object.entries(KIND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
      <Field id="au-inicio" label="Início *" error={fieldError(error, "start_date")}>
        <input id="au-inicio" type="date" value={f.start_date} onChange={(e) => setF({ ...f, start_date: e.target.value })} />
      </Field>
      <Field id="au-fim" label="Fim (inclusive)" hint="Vazio = só um dia" error={fieldError(error, "end_date")}>
        <input id="au-fim" type="date" value={f.end_date} onChange={(e) => setF({ ...f, end_date: e.target.value })} />
      </Field>
      <Field id="au-obs" label="Observação" error={fieldError(error, "notes")}>
        <input id="au-obs" maxLength={500} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Registrar</button></div>
    </form>
  );
}

function AbsenceActions({ a, onDone }: { a: Absence; onDone: (warnings: string[]) => void }) {
  const { me } = useSession();
  const [mode, setMode] = useState<"idle" | "reject" | "cancel">("idle");
  const [note, setNote] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const run = async (action: "approve" | "reject" | "cancel") => {
    setError(null);
    try {
      const { data } = await api.post<Absence>(`/api/v1/team/absences/${a.id}/${action}`, action === "approve" ? {} : { note }, `"${a.version}"`);
      onDone(action === "approve" ? [`${a.person}: ausência aprovada.`, ...data.warnings.map((w) => `Atenção — ${w}`)] : [`${a.person}: ${action === "reject" ? "recusada" : "cancelada"}.`]);
    } catch (err) {
      setError(asError(err));
    }
  };
  const own = a.user_id === me?.user.id && !me?.admin;
  return (
    <div className="actions">
      {mode === "idle" && a.state === "REQUESTED" && !own && <button className="btn btn-small btn-primary" onClick={() => void run("approve")}>Aprovar</button>}
      {mode === "idle" && a.state === "REQUESTED" && <button className="btn btn-small btn-ghost" onClick={() => setMode("reject")}>Recusar</button>}
      {mode === "idle" && ["REQUESTED", "APPROVED"].includes(a.state) && <button className="btn btn-small btn-ghost" onClick={() => setMode("cancel")}>Cancelar</button>}
      {mode !== "idle" && <>
        <label className="visually-hidden" htmlFor={`au-n-${a.id}`}>Motivo</label>
        <input id={`au-n-${a.id}`} placeholder="Motivo" value={note} onChange={(e) => setNote(e.target.value)} />
        <button className="btn btn-small" onClick={() => void run(mode)}>Confirmar</button>
      </>}
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}

function PerformanceCard({ r }: { r: PerformanceReport }) {
  const fmt = (v: string, unit: string) => (unit === "R$" ? formatMoney(v) : `${formatDecimal(v)} ${unit}`);
  return (
    <section className="card" aria-labelledby="titulo-desempenho">
      <h2 id="titulo-desempenho" className="h3">Desempenho por função (melhor funcionário por medida)</h2>
      <p className="muted small">{r.rule}</p>
      <div className="grid-2">{r.measures.map((m) => (
        <article key={m.code} className="card" aria-label={m.label}>
          <h3 className="h3">{m.label} <span className="muted small">· {m.function} · {m.higher_is_better ? "maior é melhor" : "menor é melhor"}</span></h3>
          <p className="muted small">Fórmula: {m.formula}</p>
          {m.leader && <p><span className="badge badge-ok">Destaque: {m.leader}</span></p>}
          {m.note && <p className="muted small">{m.note}</p>}
          {m.values.length > 0 && <ol>{m.values.map((v) => <li key={v.user_id}>{v.person} — {fmt(v.value, m.unit)}</li>)}</ol>}
        </article>
      ))}</div>
    </section>
  );
}
