import { useState } from "react";
import { Link } from "react-router";
import type { LedgerLines, Report, ReportItem } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, formatQty } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { EVENT, brDate } from "../finance/financeLabels";
import { Columns3D, TimelineBars } from "./Columns3D";
import { ReportFilters, useReportFilter } from "./ReportFilters";

const pct = (v: string | null) => (v == null ? "—" : formatDecimal(v) + "%");

/** Estados comuns do relatório (carregando, sem permissão, unidade fora do acesso, erro). */
export function useReport(): [ReturnType<typeof useReportFilter>, ReturnType<typeof useResource<Report>>] {
  const filter = useReportFilter();
  const res = useResource<Report>(`/api/v1/reports/overview?${filter[2]}`);
  return [filter, res];
}

export function ReportStates({ res, onRetry }: { res: ReturnType<typeof useResource<Report>>[0]; onRetry: () => void }) {
  if (res.status === "loading") return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") {
    if (res.error.status === 404) return <EmptyState title="Unidade fora do seu acesso">Sua permissão de relatório vale só para a sua unidade. Escolha "Todas" ou a sua unidade.</EmptyState>;
    return <ErrorState error={res.error} onRetry={onRetry} />;
  }
  return null;
}

/** W12 — relatório completo: fechamento, colunas 3D, linha do tempo, comparação, quantidade e arquivos (REP-02..06). */
export function ReportPage() {
  const { can } = useSession();
  const [[filter, setFilter, qs], [res, reload]] = useReport();
  const [flat, setFlat] = useState(() => typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);
  const [drill, setDrill] = useState<ReportItem | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-relatorio">
      <header className="page-head">
        <div>
          <h1 id="titulo-relatorio">Relatório completo</h1>
          <p className="muted">Resultado gerencial por competência. Gráfico, tabela e arquivos usam o mesmo cálculo do servidor.</p>
        </div>
        <div className="actions">
          {can("reports.print") && <a className="btn btn-small" href={`/api/v1/reports/overview/export?format=pdf&${qs}`}>PDF</a>}
          {can("reports.export") && <a className="btn btn-small" href={`/api/v1/reports/overview/export?format=xlsx&${qs}`}>Planilha (XLSX)</a>}
          {can("reports.export") && <a className="btn btn-small" href={`/api/v1/reports/overview/export?format=csv&${qs}`}>CSV</a>}
          <button className="btn btn-small" disabled title="A IA da gestão chega na F15; sem chave configurada fica NÃO CONFIGURADA.">Analisar com a IA — não configurada</button>
        </div>
      </header>
      <ReportFilters filter={filter} onChange={setFilter} />
      <ReportStates res={res} onRetry={reload} />
      {res.status === "ready" && <ReportBody r={res.data} flat={flat} setFlat={setFlat} drill={drill} setDrill={setDrill} qs={qs} />}
    </section>
  );
}

function ReportBody({ r, flat, setFlat, drill, setDrill, qs }: {
  r: Report; flat: boolean; setFlat: (b: boolean) => void; drill: ReportItem | null; setDrill: (i: ReportItem | null) => void; qs: string;
}) {
  const x = r.result;
  const hidden = r.hidden.length > 0;
  const d = (k: string) => r.drill.find((i) => i.key === k);
  const empty = x.gross_revenue === "0.00" && x.expenses_total === "0.00" && (x.cogs ?? "0.00") === "0.00" && (x.losses ?? "0.00") === "0.00";
  const row = (label: string, amount: string | null, item?: ReportItem, strong = false) => (
    <tr>
      <th scope="row">{strong ? <strong>{label}</strong> : label}</th>
      <td className="num">{strong ? <strong>{formatMoney(amount)}</strong> : formatMoney(amount)}</td>
      <td>{item && <button className="btn btn-small btn-ghost" onClick={() => setDrill(item)} aria-label={`Abrir lançamentos de ${label}`}>Abrir lançamentos</button>}</td>
    </tr>
  );
  return (
    <>
      <p className="small" aria-live="polite">
        <strong>{r.label}</strong>{r.branch_name ? ` · ${r.branch_name}` : " · todas as unidades"} · política {r.policy_version}
        {r.source === "SNAPSHOT" && r.closed && <span className="badge badge-violet">Mês fechado — retrato de {r.closed.closed_at ? formatDateTime(r.closed.closed_at) : "—"}{r.closed.matches_live ? " · confere com o livro" : " · DIVERGE do livro"}</span>}
        {hidden && <span className="badge badge-warn">CMV, perdas e lucro ocultos pela sua permissão</span>}
      </p>
      {empty && <EmptyState title="Sem movimento no período">Nenhuma venda cumprida, despesa ou perda com competência neste período.</EmptyState>}
      <div className="kpis" aria-label="Indicadores do período">
        {r.chart.map((c) => (
          <div key={c.key} className={"card kpi" + (c.key === "profit" ? (c.amount.startsWith("-") ? " kpi-loss" : " kpi-highlight") : "")}>
            <span className="kpi-label">{c.label}</span><span className="kpi-value">{formatMoney(c.amount)}</span>
          </div>
        ))}
      </div>
      <div className="grid-2">
        <section className="card chart-card" aria-labelledby="titulo-grafico">
          <div className="page-head">
            <h2 id="titulo-grafico" className="h3">Fechamento em colunas</h2>
            <button type="button" className="btn btn-small" aria-pressed={flat} onClick={() => setFlat(!flat)}>{flat ? "Ver em 3D" : "Versão 2D"}</button>
          </div>
          <Columns3D columns={r.chart} flat={flat} title={`Fechamento — ${r.label}`}
            subtitle={`Valores em R$ (BRL), ${r.label}${r.branch_name ? ", " + r.branch_name : ""}. Altura proporcional ao valor, escala única.`} />
        </section>
        <section className="card" aria-labelledby="titulo-fech">
          <h2 id="titulo-fech" className="h3">Fechamento (tabela)</h2>
          <div className="table-wrap"><table>
            <tbody>
              {row("Vendas cumpridas (bruto)", x.gross_revenue, d("gross_revenue"))}
              {row("(−) Descontos", x.discounts, d("discounts"))}
              {row("(−) Devoluções", x.returns, d("returns"))}
              {row("Receita líquida", x.net_revenue, undefined, true)}
              {!hidden && row("(−) CMV", x.cogs, d("cogs"))}
              {r.expenses.map((e) => <tr key={e.key}><th scope="row">(−) {e.label}</th><td className="num">{formatMoney(e.amount)}</td>
                <td><button className="btn btn-small btn-ghost" onClick={() => setDrill(e)} aria-label={`Abrir lançamentos de ${e.label}`}>Abrir lançamentos</button></td></tr>)}
              {row("Despesas totais", x.expenses_total, undefined, true)}
              {!hidden && row("(−) Perdas e ajustes de estoque", x.losses, d("losses"))}
              {!hidden && row("CMV + despesas + perdas", x.costs_expenses_losses)}
              {!hidden && row(x.outcome === "LOSS" ? "Prejuízo do período" : "Lucro do período", x.profit, undefined, true)}
            </tbody>
          </table></div>
          <p className="muted small">{hidden ? "Lucro e margem ocultos pela sua permissão." : `Margem sobre a receita líquida: ${pct(x.margin_percent)}.`} {r.formula}</p>
        </section>
      </div>
      {drill && <LinesCard item={drill} qs={qs} onClose={() => setDrill(null)} />}
      <div className="grid-2">
        <section className="card" aria-labelledby="titulo-qtd">
          <h2 id="titulo-qtd" className="h3">Quantidade vendida</h2>
          {r.sold.by_unit.length === 0 ? <p className="muted">Nada vendido no período.</p> : (
            <div className="table-wrap"><table className="stack">
              <thead><tr><th scope="col">Unidade-base</th><th scope="col" className="num">Vendida (cumprida − devolvida)</th><th scope="col" className="num">Em carteira (reservada)</th></tr></thead>
              <tbody>{r.sold.by_unit.map((u) => <tr key={u.unit}><th scope="row">{u.unit}</th>
                <td data-label="Vendida" className="num">{formatQty(u.sold.replace(/\.?0+$/, ""), u.unit)}</td><td data-label="Em carteira" className="num">{formatQty(u.backlog.replace(/\.?0+$/, ""), u.unit)}</td></tr>)}</tbody>
            </table></div>
          )}
          <p className="muted small">{r.sold.orders} pedido(s) com retirada/entrega. {r.sold.note}</p>
        </section>
        <section className="card" aria-labelledby="titulo-comp">
          <h2 id="titulo-comp" className="h3">Comparação com {r.comparison.label}</h2>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Indicador</th><th scope="col" className="num">Atual</th><th scope="col" className="num">Anterior</th><th scope="col" className="num">Diferença</th><th scope="col" className="num">Variação</th></tr></thead>
            <tbody>{r.comparison.items.map((i) => {
              const f = i.key === "sold_quantity" ? formatDecimal : formatMoney;
              return <tr key={i.key}><th scope="row">{i.label}</th><td data-label="Atual" className="num">{f(i.current)}</td>
                <td data-label="Anterior" className="num">{f(i.previous)}</td><td data-label="Diferença" className="num">{f(i.difference)}</td>
                <td data-label="Variação" className="num">{pct(i.percent)}</td></tr>;
            })}</tbody>
          </table></div>
          <p className="muted small">{r.comparison.formula}</p>
        </section>
      </div>
      <section className="card" aria-labelledby="titulo-tempo">
        <h2 id="titulo-tempo" className="h3">Linha do tempo — receita líquida {r.timeline_bucket === "DAY" ? "por dia" : r.timeline_bucket === "WEEK" ? "por semana" : "por mês"}</h2>
        <TimelineBars points={r.timeline.map((p) => ({ label: p.label, value: p.net_revenue }))} label={`Receita líquida por período em R$, ${r.label}`} />
        <details>
          <summary>Tabela da linha do tempo</summary>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Período</th><th scope="col" className="num">Vendas</th><th scope="col" className="num">Despesas</th>
              {!hidden && <><th scope="col" className="num">CMV</th><th scope="col" className="num">Perdas</th><th scope="col" className="num">Resultado</th></>}</tr></thead>
            <tbody>{r.timeline.map((p) => <tr key={p.from}><th scope="row">{p.label}</th><td data-label="Vendas" className="num">{formatMoney(p.net_revenue)}</td>
              <td data-label="Despesas" className="num">{formatMoney(p.expenses_total)}</td>
              {!hidden && <><td data-label="CMV" className="num">{formatMoney(p.cogs)}</td><td data-label="Perdas" className="num">{formatMoney(p.losses)}</td>
                <td data-label="Resultado" className="num">{formatMoney(p.profit)}</td></>}</tr>)}</tbody>
          </table></div>
        </details>
      </section>
      {r.cash && (
        <section className="card" aria-labelledby="titulo-caixa-rel">
          <h2 id="titulo-caixa-rel" className="h3">Posição de caixa no fim do período (não é lucro)</h2>
          <p className="kpi-value">{formatMoney(r.cash.available)}</p>
          <p className="muted small">{r.cash.note}</p>
        </section>
      )}
      <p className="muted small">{r.note} Gerado para {r.generated_by} em {formatDateTime(r.generated_at)}. <Link to={`/reports/categories?${new URLSearchParams(window.location.search)}`}>Ver gastos por categoria</Link></p>
    </>
  );
}

const ORIGIN: Record<string, (id: string) => string> = {
  order: (id) => `/sales/orders/${id}`, expense: () => "/finance/expenses", inventory_receipt: (id) => `/operation/receipts/${id}`,
  inventory_loss: () => "/operation/stock", inventory_adjustment: () => "/operation/stock", vehicle_expense: () => "/operation/fleet",
  payroll_run: () => "/finance/payroll", tax_obligation: () => "/finance/taxes", payable_payment: () => "/finance/payables",
};

/** Drilldown (REP-05): linhas do livro → registro de origem e comprovante (o download confere a permissão do registro). */
export function LinesCard({ item, qs, onClose }: { item: ReportItem; qs: string; onClose: () => void }) {
  const [res] = useResource<LedgerLines>(`/api/v1/reports/lines?accounts=${item.accounts.join(",")}&${qs}`);
  return (
    <section className="card" aria-labelledby="titulo-linhas">
      <div className="page-head">
        <h2 id="titulo-linhas" className="h3">Lançamentos de {item.label}: {formatMoney(item.amount)}</h2>
        <button className="btn btn-small" onClick={onClose}>Fechar</button>
      </div>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "ready" && (
        <>
          <p className="muted small">Débitos {formatMoney(res.data.total_debit)} · créditos {formatMoney(res.data.total_credit)} · líquido {formatMoney(res.data.net)} ({res.data.items.length} linhas)</p>
          {res.data.items.length === 0 ? <p className="muted">Sem lançamentos.</p> : (
            <div className="table-wrap"><table className="stack">
              <thead><tr><th scope="col">Competência</th><th scope="col">Evento</th><th scope="col">Origem</th><th scope="col">Comprovante</th><th scope="col" className="num">Débito</th><th scope="col" className="num">Crédito</th><th scope="col">Autor</th></tr></thead>
              <tbody>{res.data.items.map((l, i) => {
                const link = l.origin_type && l.origin_id && ORIGIN[l.origin_type] ? ORIGIN[l.origin_type]!(l.origin_id) : null;
                return (
                  <tr key={l.entry_id + i}><th scope="row">{brDate(l.competence ?? l.entry_date)}</th>
                    <td data-label="Evento">{EVENT[l.event_kind] ?? l.event_kind}{l.memo ? ` — ${l.memo}` : ""}{l.reversal_of ? " (estorno)" : ""}</td>
                    <td data-label="Origem">{link ? <Link to={link}>{l.origin_type === "order" ? `Pedido #${l.origin_number ?? ""}` : "Abrir registro"}</Link> : "—"}</td>
                    <td data-label="Comprovante">{l.document_id ? <a href={`/api/v1/documents/${l.document_id}/download`}>Baixar{l.documents > 1 ? ` (1 de ${l.documents})` : ""}</a> : "—"}</td>
                    <td data-label="Débito" className="num">{l.debit !== "0.00" ? formatMoney(l.debit) : ""}</td>
                    <td data-label="Crédito" className="num">{l.credit !== "0.00" ? formatMoney(l.credit) : ""}</td>
                    <td data-label="Autor">{l.created_by_name}</td></tr>
                );
              })}</tbody>
            </table></div>
          )}
        </>
      )}
    </section>
  );
}
