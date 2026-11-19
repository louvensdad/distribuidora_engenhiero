import { useState } from "react";
import { Link } from "react-router";
import { ApiError, api } from "../../api/http";
import type { AiQuestion, AiStatus } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, monthStartBr } from "../../app/format";
import { Field, FormError } from "../../components/Form";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { brDate } from "../finance/financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const STATE_LABEL: Record<AiQuestion["state"], [string, string]> = {
  RUNNING: ["Em andamento", "badge-muted"], ANSWERED: ["Respondida", "badge-ok"], FAILED: ["Falhou", "badge-error"],
  REFUSED: ["Recusada pelo provedor", "badge-warn"], LIMIT_REACHED: ["Etapas demais", "badge-warn"],
};
const CLIENT_LABEL: Record<string, string> = { "engenheiro-web": "site", "engenheiro-gestao": "app Patrão" };
const lastDay = (iso: string) => {
  const d = new Date(iso + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
};

/** F15 — IA da gestão: pergunta sobre os dados, com período, fontes autorizadas, limitações, modelo e custo. */
export function AiPage() {
  const [status, reloadStatus] = useResource<AiStatus>("/api/v1/ai/status");
  const [history, reloadHistory] = useResource<{ items: AiQuestion[] }>("/api/v1/ai/questions");
  const [current, setCurrent] = useState<AiQuestion | null>(null);
  if (status.status === "loading") return <LoadingState />;
  if (status.status === "forbidden") return <ForbiddenState />;
  if (status.status === "error") return <ErrorState error={status.error} onRetry={reloadStatus} />;
  const s = status.data;
  const active = s.state === "CONFIGURED";
  return (
    <section className="page" aria-labelledby="titulo-ia">
      <header className="page-head">
        <div>
          <h1 id="titulo-ia">IA da gestão</h1>
          <p className="muted">Responde com os números calculados pelo sistema e só com o que você tem permissão de ver. Ela apenas consulta: não paga, não emite nota, não altera estoque nem acessos.</p>
        </div>
      </header>
      <div className={`notice ${active ? "notice-ok" : "state-warning"}`} role="status" aria-label="Situação da IA">
        <strong>{s.state === "NOT_CONFIGURED" ? "INTEGRAÇÃO NÃO CONFIGURADA" : s.state === "CONFIGURED" ? "IA ativa" : "IA bloqueada"}</strong>
        <p>{s.message}</p>
        <p className="muted small">
          {s.model ? <>Modelo {s.model} ({s.provider}) · </> : null}
          Orçamento do mês US$ {s.budget_usd} · gasto US$ {s.spent_usd} · disponível US$ {s.remaining_usd} · suas perguntas hoje {s.used_today} de {s.daily_limit}
        </p>
      </div>
      {active && <AskForm onAnswered={(q) => { setCurrent(q); reloadStatus(); reloadHistory(); }} />}
      {current && <AnswerCard q={current} />}
      <h2 className="h3">Minhas perguntas</h2>
      {history.status === "loading" && <LoadingState />}
      {history.status === "error" && <ErrorState error={history.error} onRetry={reloadHistory} />}
      {history.status === "ready" && (history.data.items.length === 0
        ? <EmptyState title="Nenhuma pergunta ainda" />
        : <ul className="timeline">{history.data.items.map((q) => (
          <li key={q.id}>
            <button type="button" className="btn btn-small" onClick={() => setCurrent(q)}>{q.question}</button>
            <span className="muted small"> {formatDateTime(q.created_at)} · {STATE_LABEL[q.state][0]} · {brDate(q.from)} a {brDate(q.to)} · pelo {CLIENT_LABEL[q.client_id] ?? q.client_id}</span>
          </li>))}</ul>)}
    </section>
  );
}

function AskForm({ onAnswered }: { onAnswered: (q: AiQuestion) => void }) {
  const [question, setQuestion] = useState("");
  const [from, setFrom] = useState(monthStartBr(0));
  const [to, setTo] = useState(lastDay(monthStartBr(1)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!question.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.post<AiQuestion>("/api/v1/ai/questions", { question: question.trim(), from, to });
      onAnswered(r.data);
      setQuestion("");
    } catch (err) {
      setError(asError(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="card" onSubmit={submit} aria-label="Perguntar à IA">
      <Field label="Pergunta" id="ia-pergunta">
        <textarea id="ia-pergunta" value={question} maxLength={2000} rows={3} onChange={(e) => setQuestion(e.target.value)}
          placeholder="Ex.: Quanto gastei com combustível este mês? Qual veículo custou mais?" />
      </Field>
      <div className="form-grid">
        <Field label="De" id="ia-de"><input id="ia-de" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="Até (inclusive)" id="ia-ate"><input id="ia-ate" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      <FormError error={error} />
      <button className="btn btn-primary" type="submit" disabled={busy || !question.trim()}>{busy ? "Consultando…" : "Perguntar"}</button>
    </form>
  );
}

export function AnswerCard({ q }: { q: AiQuestion }) {
  const [label, cls] = STATE_LABEL[q.state];
  return (
    <article className="card" aria-label="Resposta da IA">
      <p className="muted small">Pergunta: {q.question}</p>
      <span className={`badge ${cls}`}>{label}</span>
      {q.answer ? <p style={{ whiteSpace: "pre-wrap" }}>{q.answer}</p>
        : <p>Sem resposta{q.error_code ? ` (${q.error_code})` : ""}. Nenhum dado foi alterado.</p>}
      <dl className="kv small">
        <dt>Período</dt><dd>{brDate(q.from)} a {brDate(q.to)} (fuso de São Paulo)</dd>
        <dt>Dados até</dt><dd>{formatDateTime(q.cutoff_at)}</dd>
        <dt>Consultas feitas</dt><dd>{q.tools.length === 0 ? "nenhuma" : q.tools.map((t) => `${t.label}${t.ok ? "" : ` (${t.error === "SEM_PERMISSAO" ? "sem permissão" : t.error})`}`).join(" · ")}</dd>
        <dt>Modelo e custo</dt><dd>{q.model ?? "—"} · US$ {q.cost_usd} · {q.input_tokens + q.output_tokens} tokens{q.latency_ms != null ? ` · ${(q.latency_ms / 1000).toFixed(1)} s` : ""}</dd>
      </dl>
      {q.limitations.length > 0 && (
        <div className="notice" role="note" aria-label="Limitações">
          <strong>O que faltou ou ficou de fora</strong>
          <ul>{q.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
        </div>
      )}
      {q.sources.length > 0 && (
        <div aria-label="Fontes">
          <strong>Fontes (abra para conferir)</strong>
          <ul>{q.sources.map((s) => <li key={s.href + s.label}><Link to={s.href}>{s.label}</Link></li>)}</ul>
        </div>
      )}
    </article>
  );
}
