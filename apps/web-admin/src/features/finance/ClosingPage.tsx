import { useState } from "react";
import { ApiError, api } from "../../api/http";
import type { FinanceCategories, FinanceSummary, LedgerLines, Period, Reconciliation, ReportItem } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { CATEGORY, EVENT, PERIOD_EVENT, brDate, monthLabel } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const monthStart = (offset: number) => {
  const d = new Date();
  const m = new Date(Date.UTC(d.getFullYear(), d.getMonth() + offset, 1));
  return m.toISOString().slice(0, 10);
};

/** FIN-04/FIN-07 — resultado por competência com drilldown, caixa à parte, categorias, conciliação e fechamento. */
export function ClosingPage() {
  const { can } = useSession();
  const [offset, setOffset] = useState(0);
  const from = monthStart(offset);
  const to = monthStart(offset + 1);
  const [sum, reloadSum] = useResource<FinanceSummary>(`/api/v1/reports/summary?from=${from}&to=${to}`);
  const [cats] = useResource<FinanceCategories>(`/api/v1/reports/categories?from=${from}&to=${to}`);
  const [rec, reloadRec] = useResource<Reconciliation>("/api/v1/finance/reconciliation");
  const [per, reloadPer] = useResource<{ items: Period[] }>("/api/v1/periods");
  const [drill, setDrill] = useState<ReportItem | null>(null);
  if (sum.status === "loading") return <LoadingState />;
  if (sum.status === "forbidden") return <ForbiddenState />;
  if (sum.status === "error") return <ErrorState error={sum.error} onRetry={reloadSum} />;
  const s = sum.data;
  const row = (label: string, amount: string, item?: ReportItem, strong = false) => (
    <tr>
      <th scope="row">{strong ? <strong>{label}</strong> : label}</th>
      <td className="num">{strong ? <strong>{formatMoney(amount)}</strong> : formatMoney(amount)}</td>
      <td>{item && <button className="btn btn-small btn-ghost" onClick={() => setDrill(item)} aria-label={`Abrir lançamentos de ${label}`}>Abrir lançamentos</button>}</td>
    </tr>
  );
  const d = (key: string) => s.drill.find((x) => x.key === key);
  return (
    <section className="page" aria-labelledby="titulo-resultado">
      <header className="page-head">
        <div>
          <h1 id="titulo-resultado">Resultado e fechamento</h1>
          <p className="muted">{s.note}</p>
        </div>
        <div className="actions">
          <button className="btn btn-small" onClick={() => setOffset(offset - 1)} aria-label="Mês anterior">‹</button>
          <strong>{monthLabel(from)}</strong>
          <button className="btn btn-small" onClick={() => setOffset(offset + 1)} disabled={offset >= 0} aria-label="Próximo mês">›</button>
        </div>
      </header>
      <div className="grid-2">
        <section className="card" aria-labelledby="titulo-dre">
          <h2 id="titulo-dre" className="h3">Resultado gerencial (competência)</h2>
          <div className="table-wrap">
            <table>
              <tbody>
                {row("Vendas cumpridas (bruto)", s.gross_revenue, d("gross_revenue"))}
                {row("(−) Descontos", s.discounts, d("discounts"))}
                {row("(−) Devoluções", s.returns, d("returns"))}
                {row("Receita líquida", s.net_revenue, undefined, true)}
                {row("(−) CMV", s.cogs, d("cogs"))}
                {s.expenses.map((e) => <tr key={e.key}><th scope="row">(−) {e.label}</th><td className="num">{formatMoney(e.amount)}</td>
                  <td><button className="btn btn-small btn-ghost" onClick={() => setDrill(e)} aria-label={`Abrir lançamentos de ${e.label}`}>Abrir lançamentos</button></td></tr>)}
                {row("(−) Perdas e ajustes de estoque", s.losses, d("losses"))}
                {row(s.profit.startsWith("-") ? "Prejuízo do período" : "Lucro do período", s.profit, undefined, true)}
              </tbody>
            </table>
          </div>
          <p className="muted small">Margem sobre a receita líquida: {s.margin_percent ? `${s.margin_percent.replace("-", "−").replace(".", ",")}%` : "—"} · Retiradas do sócio (fora do resultado): {formatMoney(s.owner_withdrawals)}</p>
        </section>
        <section className="card" aria-labelledby="titulo-caixa-pos">
          <h2 id="titulo-caixa-pos" className="h3">Posição de caixa (até o fim do mês)</h2>
          <p className="kpi-value">{formatMoney(s.cash.available)}</p>
          <ul className="timeline">{s.cash.accounts.map((a) => <li key={a.key}><strong>{a.label}</strong><span>{formatMoney(a.amount)}</span></li>)}</ul>
          <p className="muted small">{s.cash.note} Adiantamentos de clientes: {formatMoney(s.cash.customer_advances)}.</p>
        </section>
      </div>
      {drill && <DrillCard item={drill} from={from} to={to} onClose={() => setDrill(null)} />}
      {cats.status === "ready" && cats.data.subcategories.length > 0 && (
        <section className="card" aria-labelledby="titulo-subcat">
          <h2 id="titulo-subcat" className="h3">Despesas por categoria e subcategoria</h2>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Categoria</th><th scope="col">Subcategoria</th><th scope="col">Lançamentos</th><th scope="col" className="num">Valor</th></tr></thead>
            <tbody>{cats.data.subcategories.map((x, i) => (
              <tr key={i}><th scope="row">{CATEGORY[x.category] ?? x.category}</th><td data-label="Subcategoria">{x.subcategory}</td>
                <td data-label="Lançamentos">{x.entries}</td><td data-label="Valor" className="num">{formatMoney(x.amount)}</td></tr>
            ))}</tbody>
          </table></div>
        </section>
      )}
      {rec.status === "ready" && (
        <section className="card" aria-labelledby="titulo-conc">
          <h2 id="titulo-conc" className="h3">Conciliação {rec.data.status === "OK" ? "— tudo confere" : "— há divergência"}</h2>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Verificação</th><th scope="col" className="num">Esperado</th><th scope="col" className="num">No livro</th><th scope="col" className="num">Diferença</th><th scope="col">Situação</th></tr></thead>
            <tbody>{rec.data.checks.map((c) => (
              <tr key={c.code}><th scope="row">{c.label}<span className="muted small"><br />{c.explanation}</span></th>
                <td data-label="Esperado" className="num">{formatMoney(c.expected)}</td><td data-label="No livro" className="num">{formatMoney(c.actual)}</td>
                <td data-label="Diferença" className="num">{formatMoney(c.difference)}</td>
                <td data-label="Situação"><span className={"badge " + (c.status === "DIVERGENT" ? "badge-error" : "badge-ok")}>
                  {c.status === "OK" ? "Confere" : c.status === "ROUNDING" ? "Arredondamento" : "Divergente"}</span></td></tr>
            ))}</tbody>
          </table></div>
        </section>
      )}
      {per.status === "ready" && (
        <section className="card" aria-labelledby="titulo-periodos">
          <h2 id="titulo-periodos" className="h3">Fechamento mensal</h2>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Mês</th><th scope="col">Situação</th><th scope="col">Histórico</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{per.data.items.slice(0, 6).map((p) => (
              <tr key={p.id}>
                <th scope="row">{monthLabel(p.month)}</th>
                <td data-label="Situação">{p.state === "CLOSED" ? `Fechado${p.profit_at_close ? ` · resultado ${formatMoney(p.profit_at_close)}` : ""}` : "Aberto"}
                  {p.reopen_pending && <span className="muted small"><br />reabertura pedida por {p.reopen_requested_by}</span>}</td>
                <td data-label="Histórico">{p.events.length === 0 ? "—" : p.events.map((e, i) => (
                  <span key={i} className="muted small">{PERIOD_EVENT[e.action]} — {e.actor_name}{e.reason ? ` (${e.reason})` : ""} · {formatDateTime(e.occurred_at)}<br /></span>))}</td>
                <td><PeriodActions p={p} canClose={can("reports.close_period")} canReopen={can("reports.reopen_period")} onDone={() => { reloadPer(); reloadRec(); }} /></td>
              </tr>
            ))}</tbody>
          </table></div>
        </section>
      )}
    </section>
  );
}

function DrillCard({ item, from, to, onClose }: { item: ReportItem; from: string; to: string; onClose: () => void }) {
  const [res] = useResource<LedgerLines>(`/api/v1/finance/ledger-lines?accounts=${item.accounts.join(",")}&from=${from}&to=${to}`);
  return (
    <section className="card" aria-labelledby="titulo-drill">
      <div className="page-head">
        <h2 id="titulo-drill" className="h3">Lançamentos de {item.label}: {formatMoney(item.amount)}</h2>
        <button className="btn btn-small" onClick={onClose}>Fechar</button>
      </div>
      {res.status === "loading" && <LoadingState />}
      {res.status === "ready" && (
        <>
          <p className="muted small">Débitos {formatMoney(res.data.total_debit)} · créditos {formatMoney(res.data.total_credit)} · líquido {formatMoney(res.data.net)} ({res.data.items.length} linhas)</p>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Data</th><th scope="col">Competência</th><th scope="col">Evento</th><th scope="col" className="num">Débito</th><th scope="col" className="num">Crédito</th><th scope="col">Autor</th></tr></thead>
            <tbody>{res.data.items.map((l, i) => (
              <tr key={l.entry_id + i}><th scope="row">{brDate(l.entry_date)}</th><td data-label="Competência">{brDate(l.competence ?? l.entry_date)}</td>
                <td data-label="Evento">{EVENT[l.event_kind] ?? l.event_kind}{l.memo ? ` — ${l.memo}` : ""}{l.reversal_of ? " (estorno)" : ""}</td>
                <td data-label="Débito" className="num">{l.debit !== "0.00" ? formatMoney(l.debit) : ""}</td>
                <td data-label="Crédito" className="num">{l.credit !== "0.00" ? formatMoney(l.credit) : ""}</td>
                <td data-label="Autor">{l.created_by_name}</td></tr>
            ))}</tbody>
          </table></div>
        </>
      )}
    </section>
  );
}

function PeriodActions({ p, canClose, canReopen, onDone }: { p: Period; canClose: boolean; canReopen: boolean; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const current = p.month >= new Date().toISOString().slice(0, 8) + "01";
  const run = async (path: string) => {
    setError(null);
    try {
      await api.post(`/api/v1/periods/${p.id}/${path}`, { reason: reason.trim() || null });
      setReason("");
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  const input = <><label className="visually-hidden" htmlFor={`pm-${p.id}`}>Motivo</label>
    <input id={`pm-${p.id}`} placeholder="Motivo" value={reason} onChange={(e) => setReason(e.target.value)} /></>;
  return (
    <div className="actions">
      {p.state === "OPEN" && canClose && !current && <>{input}<button className="btn btn-small" onClick={() => void run("close")}>Fechar mês</button></>}
      {p.state === "CLOSED" && !p.reopen_pending && <>{input}<button className="btn btn-small" onClick={() => void run("reopen-requests")}>Pedir reabertura</button></>}
      {p.state === "CLOSED" && canReopen && <>
        <button className="btn btn-small" onClick={() => void run("reopen")}>{p.reopen_pending ? "Aprovar reabertura" : "Reabrir (com motivo)"}</button>
        {p.reopen_pending && <button className="btn btn-small btn-ghost" onClick={() => void run("reopen-rejections")}>Recusar</button>}</>}
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}
