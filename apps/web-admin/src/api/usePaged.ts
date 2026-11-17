import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { ApiError, api } from "./http";
import { useResource, type Resource } from "./useResource";

export interface Paged<T> {
  first: Resource<{ items: T[]; next_cursor?: string | null }>;
  reload: () => void;
  items: T[];
  cursor: string | null;
  busy: boolean;
  error: ApiError | null;
  loadMore: () => Promise<void>;
}

/**
 * Lista paginada por cursor do servidor (WEB-02): primeira página com estados completos; "Carregar mais" acrescenta,
 * com erro próprio e nova tentativa sem perder o que já foi carregado.
 */
export function usePaged<T>(path: string | null): Paged<T> {
  const [first, reload] = useResource<{ items: T[]; next_cursor?: string | null }>(path);
  const [items, setItems] = useState<T[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (first.status === "ready") {
      setItems(first.data.items);
      setCursor(first.data.next_cursor ?? null);
      setError(null);
    }
  }, [first]);

  const loadMore = useCallback(async () => {
    if (!cursor || !path) return;
    setBusy(true);
    setError(null);
    try {
      const sep = path.includes("?") ? "&" : "?";
      const { data } = await api.get<{ items: T[]; next_cursor?: string | null }>(`${path}${sep}cursor=${encodeURIComponent(cursor)}`);
      setItems((prev) => [...prev, ...data.items]);
      setCursor(data.next_cursor ?? null);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setBusy(false);
    }
  }, [cursor, path]);

  return { first, reload, items, cursor, busy, error, loadMore };
}

/**
 * Filtros na URL: o endereço da tela já é o link profundo (copiar, recarregar e voltar mantêm o filtro).
 * Trocar filtro substitui a entrada do histórico (não enche o "voltar").
 */
export function useUrlFilters<K extends string>(keys: readonly K[]): [Record<K, string>, (patch: Partial<Record<K, string>>) => void] {
  const [params, setParams] = useSearchParams();
  const values = Object.fromEntries(keys.map((k) => [k, params.get(k) ?? ""])) as Record<K, string>;
  const set = useCallback((patch: Partial<Record<K, string>>) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      for (const [k, v] of Object.entries(patch) as [string, string | undefined][]) {
        if (v) next.set(k, v);
        else next.delete(k);
      }
      return next;
    }, { replace: true });
  }, [setParams]);
  return [values, set];
}

/** Monta a query string só com os valores preenchidos (codificados). */
export function qs(values: Record<string, string | number | null | undefined>): string {
  return Object.entries(values)
    .filter(([, v]) => v !== "" && v !== null && v !== undefined)
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join("&");
}
