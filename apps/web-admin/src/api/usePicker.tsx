import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "./http";

export interface Picker<T extends { id: string }> {
  /** Estado da PRIMEIRA carga (o formulário só depende dela; buscas depois não piscam a tela). */
  status: "loading" | "ready" | "forbidden" | "error";
  error: ApiError | null;
  reload: () => void;
  q: string;
  setQ: (q: string) => void;
  /** Resultado atual da busca (até 20). */
  items: T[];
  /** Qualquer item já visto (o selecionado continua disponível mesmo fora do resultado atual). */
  byId: (id: string) => T | undefined;
  /** Opções para o <select>: o resultado atual + o selecionado, se não estiver nele. */
  options: (selectedId: string) => T[];
}

/**
 * Seletor com busca no servidor (WEB-02): listas de cadastro crescem além de 100 itens, então o formulário busca
 * por nome/código (q) em vez de carregar "os primeiros 100". Espera 250 ms entre teclas.
 */
export function usePicker<T extends { id: string }>(path: string, limit = 20): Picker<T> {
  const [q, setQ] = useState("");
  const [items, setItems] = useState<T[]>([]);
  const [status, setStatus] = useState<Picker<T>["status"]>("loading");
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const seen = useRef(new Map<string, T>());

  useEffect(() => {
    const ctl = new AbortController();
    const t = setTimeout(() => {
      const sep = path.includes("?") ? "&" : "?";
      api.get<{ items: T[] }>(`${path}${sep}limit=${limit}${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ""}`, ctl.signal)
        .then(({ data }) => {
          data.items.forEach((i) => seen.current.set(i.id, i));
          setItems(data.items);
          setStatus("ready");
          setError(null);
        })
        .catch((e: unknown) => {
          if (ctl.signal.aborted) return;
          const err = e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" });
          setError(err);
          setStatus((s) => (s === "ready" ? s : err.status === 403 ? "forbidden" : "error"));
        });
    }, q ? 250 : 0);
    return () => {
      clearTimeout(t);
      ctl.abort();
    };
  }, [path, q, limit, tick]);

  return {
    status, error, reload: () => setTick((x) => x + 1), q, setQ, items,
    byId: (id) => seen.current.get(id),
    options: (selectedId) => {
      const sel = selectedId ? seen.current.get(selectedId) : undefined;
      return sel && !items.some((i) => i.id === sel.id) ? [sel, ...items] : items;
    },
  };
}

/** Campo de busca que acompanha o <select> do seletor. */
export function PickerSearch({ id, label, picker, disabled }: { id: string; label: string; picker: Picker<{ id: string }>; disabled?: boolean }) {
  return (
    <input id={id} type="search" className="picker-search" aria-label={label} placeholder={label} maxLength={80} disabled={disabled}
      value={picker.q} onChange={(e) => picker.setQ(e.target.value)} />
  );
}
