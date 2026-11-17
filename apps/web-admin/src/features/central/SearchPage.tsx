import { Link, useSearchParams } from "react-router";
import type { SearchHit, SearchResult } from "../../api/types";
import { useResource } from "../../api/useResource";
import { SearchBox } from "../../components/Lists";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";

const GROUP: Record<SearchHit["type"], string> = {
  order: "Pedidos", trip: "Viagens", vehicle: "Veículos", customer: "Clientes", supplier: "Fornecedores", product: "Produtos",
};
const SEARCHED: Record<string, string> = {
  orders: "pedidos", trips: "viagens", vehicles: "veículos", customers: "clientes", suppliers: "fornecedores", products: "produtos",
};

/** Busca geral (F10): número, nome, SKU ou placa nos módulos que a sua conta pode ver. Nunca em campo sensível. */
export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const q = (params.get("q") ?? "").trim();
  const [res, reload] = useResource<SearchResult>(q.length >= 2 ? `/api/v1/search?q=${encodeURIComponent(q)}` : null);
  return (
    <section className="page" aria-labelledby="titulo-busca">
      <header className="page-head">
        <div>
          <h1 id="titulo-busca">Buscar registro</h1>
          <p className="muted">Por número do pedido ou da viagem, nome do cliente, fornecedor ou produto, código e placa.</p>
        </div>
      </header>
      <SearchBox id="busca-geral-pagina" label="O que você procura?" value={q} onSearch={(v) => setParams(v ? { q: v } : {})} />
      {q.length < 2 ? <EmptyState title="Digite pelo menos 2 caracteres" /> : (
        <>
          {res.status === "loading" && <LoadingState />}
          {res.status === "forbidden" && <ForbiddenState />}
          {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
          {res.status === "ready" && (
            <>
              <p className="muted small" aria-live="polite">
                Procurado em: {res.data.searched.map((s) => SEARCHED[s] ?? s).join(", ")}.
              </p>
              {res.data.items.length === 0 ? <EmptyState title={`Nada encontrado para “${q}”`} /> : (
                (Object.keys(GROUP) as SearchHit["type"][]).filter((t) => res.data.items.some((h) => h.type === t)).map((t) => (
                  <section key={t} className="card" aria-labelledby={`grupo-${t}`}>
                    <h2 className="h3" id={`grupo-${t}`}>{GROUP[t]}</h2>
                    <ul className="mini-list">
                      {res.data.items.filter((h) => h.type === t).map((h) => (
                        <li key={h.id}><Link to={h.path}>{h.title}</Link>{h.subtitle && <span className="muted small"> · {h.subtitle}</span>}</li>
                      ))}
                    </ul>
                  </section>
                ))
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
