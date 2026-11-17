import { useEffect, useState, type ReactNode } from "react";
import type { Paged } from "../api/usePaged";
import { Icon } from "./Icon";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "./States";

/**
 * Estados completos de uma lista paginada: carregando, sem permissão, erro com nova tentativa, vazio e
 * "Carregar mais" (com erro próprio, sem perder as páginas já exibidas).
 */
export function PagedList<T>({ list, empty, children }: { list: Paged<T>; empty: ReactNode; children: (items: T[]) => ReactNode }) {
  const { first } = list;
  if (first.status === "loading") return <LoadingState />;
  if (first.status === "forbidden") return <ForbiddenState />;
  if (first.status === "error") return <ErrorState error={first.error} onRetry={list.reload} />;
  if (list.items.length === 0) return <>{empty}</>;
  return (
    <>
      {children(list.items)}
      <LoadMore list={list} />
    </>
  );
}

export function LoadMore<T>({ list }: { list: Paged<T> }) {
  return (
    <div className="load-more" aria-live="polite">
      {list.error && <ErrorState error={list.error} onRetry={() => void list.loadMore()} />}
      {list.cursor ? (
        <button className="btn" disabled={list.busy} onClick={() => void list.loadMore()}>
          {list.busy ? "Carregando…" : "Carregar mais"}
        </button>
      ) : list.items.length > 0 && <p className="muted small">{list.items.length} registro(s) — fim da lista.</p>}
    </div>
  );
}

/** Busca ligada à URL (?q=): Enter aplica; limpar volta à lista completa. */
export function SearchBox({ id, label, value, onSearch, placeholder }: { id: string; label: string; value: string; onSearch: (q: string) => void; placeholder?: string }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  return (
    <form className="search" role="search" onSubmit={(e) => { e.preventDefault(); onSearch(text.trim()); }}>
      <label className="visually-hidden" htmlFor={id}>{label}</label>
      <input id={id} type="search" placeholder={placeholder ?? label} value={text} maxLength={80}
        onChange={(e) => { setText(e.target.value); if (e.target.value === "") onSearch(""); }} />
      <button className="btn"><Icon name="search" size={16} /> Buscar</button>
    </form>
  );
}

export function EmptyResult({ q, what }: { q: string; what: string }) {
  return <EmptyState title={q ? `Nada encontrado para “${q}”` : `Nenhum ${what}`} />;
}
