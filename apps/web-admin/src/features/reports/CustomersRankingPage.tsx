import { useState } from "react";
import type { CustomerRanking } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDecimal, formatMoney } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { ReportFilters, useReportFilter } from "./ReportFilters";

/** REP-08 — melhor cliente por medidas separadas e públicas; ordena por UMA medida, sem nota única. */
export function CustomersRankingPage() {
  const [filter, setFilter, qs] = useReportFilter();
  const [sort, setSort] = useState("net_revenue");
  const [res, reload] = useResource<CustomerRanking>(`/api/v1/reports/customers?${qs}&sort=${sort}`);
  const measures = res.status === "ready" ? res.data.measures : null;
  return (
    <section className="page" aria-labelledby="titulo-ranking">
      <header className="page-head">
        <div>
          <h1 id="titulo-ranking">Clientes por medida</h1>
          <p className="muted">Cada coluna é uma medida própria com fórmula pública. Não existe nota única: a ordem segue só a medida escolhida.
            O desempenho por função dos funcionários fica em Equipe › Funcionários.</p>
        </div>
      </header>
      <ReportFilters filter={filter} onChange={setFilter} />
      <label className="inline-field">Ordenar por
        <select value={sort} onChange={(e) => setSort(e.target.value)}>
          {(measures ?? [{ key: "net_revenue", label: "Receita líquida", formula: "" }]).map((m) => <option key={m.key} value={m.key}>{m.label}</option>)}
        </select>
      </label>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Sem vendas cumpridas no período" /> : (
        <section className="card" aria-labelledby="titulo-tab-rank">
          <h2 id="titulo-tab-rank" className="h3">{res.data.label} — ordenado por {res.data.measures.find((m) => m.key === res.data.sort)?.label}</h2>
          <div className="table-wrap"><table className="stack">
            <thead><tr>
              <th scope="col">#</th><th scope="col">Cliente</th><th scope="col" className="num">Receita líquida</th>
              <th scope="col" className="num">Volume{res.data.unit ? ` (${res.data.unit})` : ""}</th>
              {res.data.hidden.length === 0 && <th scope="col" className="num">Margem</th>}
              <th scope="col" className="num">Pedidos</th><th scope="col" className="num">Semanas com compra</th>
              <th scope="col" className="num">Pontualidade</th><th scope="col" className="num">Devoluções</th>
            </tr></thead>
            <tbody>{res.data.items.map((c) => (
              <tr key={c.customer_id}>
                <td data-label="#">{c.position}</td><th scope="row">{c.customer_name}</th>
                <td data-label="Receita líquida" className="num">{formatMoney(c.net_revenue)}</td>
                <td data-label="Volume" className="num">{c.quantity == null ? "—" : formatDecimal(c.quantity)}</td>
                {res.data.hidden.length === 0 && <td data-label="Margem" className="num">{formatMoney(c.margin)}{c.margin_percent ? ` (${formatDecimal(c.margin_percent)}%)` : ""}</td>}
                <td data-label="Pedidos" className="num">{c.orders}</td><td data-label="Semanas com compra" className="num">{c.active_weeks}</td>
                <td data-label="Pontualidade" className="num">{c.on_time_percent == null ? "sem parcelas vencidas" : `${formatDecimal(c.on_time_percent)}% (${c.receivables_on_time}/${c.receivables_due})`}</td>
                <td data-label="Devoluções" className="num">{c.returns}</td>
              </tr>
            ))}</tbody>
          </table></div>
          <h3 className="h4">Como cada medida é calculada</h3>
          <dl className="kv">{res.data.measures.map((m) => <div key={m.key}><dt>{m.label}</dt><dd>{m.formula}</dd></div>)}</dl>
          <p className="muted small">{res.data.note}</p>
        </section>
      ))}
    </section>
  );
}
