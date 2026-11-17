import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import type { ReportItem } from "../../api/types";
import { formatMoney } from "../../app/format";
import { EmptyState } from "../../components/States";
import { LinesCard, ReportStates, useReport } from "./ReportPage";
import { ReportFilters } from "./ReportFilters";

/** W13 — gastos primeiro por categoria/subcategoria (REP-01), depois o fechamento total. */
export function CategoriesPage() {
  const [[filter, setFilter, qs], [res, reload]] = useReport();
  const [sp] = useSearchParams();
  const [drill, setDrill] = useState<ReportItem | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-categorias">
      <header className="page-head">
        <div>
          <h1 id="titulo-categorias">Gastos por categoria</h1>
          <p className="muted">Primeiro o que foi gasto em cada categoria e subcategoria; depois o fechamento com CMV e perdas.</p>
        </div>
        <div className="actions"><Link className="btn btn-small" to={`/reports?${sp.toString()}`}>Ver fechamento</Link></div>
      </header>
      <ReportFilters filter={filter} onChange={setFilter} />
      <ReportStates res={res} onRetry={reload} />
      {res.status === "ready" && (() => {
        const r = res.data;
        if (r.result.expenses_total === "0.00" && r.subcategories.length === 0) {
          return <EmptyState title="Sem despesas no período">Nenhuma despesa com competência em {r.label}.</EmptyState>;
        }
        return (
          <>
            <section className="card" aria-labelledby="titulo-cats">
              <h2 id="titulo-cats" className="h3">{r.label}{r.branch_name ? ` · ${r.branch_name}` : ""}</h2>
              <ul className="category-list">
                {r.expenses.map((c) => {
                  const subs = r.subcategories.filter((s) => s.category === c.key);
                  return (
                    <li key={c.key}>
                      <details>
                        <summary><span>{c.label}</span><strong className="num">{formatMoney(c.amount)}</strong></summary>
                        {subs.length === 0 ? <p className="muted small">Sem lançamentos.</p> : (
                          <div className="table-wrap"><table className="stack">
                            <thead><tr><th scope="col">Subcategoria</th><th scope="col">Lançamentos</th><th scope="col" className="num">Valor</th></tr></thead>
                            <tbody>{subs.map((s) => <tr key={s.subcategory}><th scope="row">{s.subcategory}</th>
                              <td data-label="Lançamentos">{s.entries}</td><td data-label="Valor" className="num">{formatMoney(s.amount)}</td></tr>)}</tbody>
                          </table></div>
                        )}
                        <button className="btn btn-small btn-ghost" onClick={() => setDrill(c)}>Abrir lançamentos de {c.label}</button>
                      </details>
                    </li>
                  );
                })}
                <li className="category-total"><span>Despesas totais</span><strong className="num">{formatMoney(r.result.expenses_total)}</strong></li>
              </ul>
            </section>
            {drill && <LinesCard item={drill} qs={qs} onClose={() => setDrill(null)} />}
            <section className="card" aria-labelledby="titulo-fech-total">
              <h2 id="titulo-fech-total" className="h3">Fechamento total</h2>
              <dl className="kv">
                <dt>Receita líquida</dt><dd>{formatMoney(r.result.net_revenue)}</dd>
                {r.hidden.length === 0 && <><dt>CMV</dt><dd>{formatMoney(r.result.cogs)}</dd></>}
                <dt>Despesas</dt><dd>{formatMoney(r.result.expenses_total)}</dd>
                {r.hidden.length === 0 && <><dt>Perdas</dt><dd>{formatMoney(r.result.losses)}</dd>
                  <dt>{r.result.outcome === "LOSS" ? "Prejuízo" : "Lucro"}</dt><dd><strong>{formatMoney(r.result.profit)}</strong></dd></>}
              </dl>
              {r.hidden.length > 0 && <p className="muted small">CMV, perdas e lucro ocultos pela sua permissão.</p>}
            </section>
          </>
        );
      })()}
    </section>
  );
}
