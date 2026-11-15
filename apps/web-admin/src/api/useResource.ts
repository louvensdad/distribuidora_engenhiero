import { useCallback, useEffect, useState } from "react";
import { ApiError, api } from "./http";

export type Resource<T> =
  | { status: "loading" }
  | { status: "forbidden" }
  | { status: "error"; error: ApiError }
  | { status: "ready"; data: T; etag: string | null };

/** GET com estados explícitos de carregando, erro e sem permissão. Cancela ao trocar de URL. */
export function useResource<T>(path: string | null): [Resource<T>, () => void] {
  const [state, setState] = useState<Resource<T>>({ status: "loading" });
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!path) return;
    const ctl = new AbortController();
    setState({ status: "loading" });
    api
      .get<T>(path, ctl.signal)
      .then((r) => setState({ status: "ready", data: r.data, etag: r.etag }))
      .catch((e: unknown) => {
        if (ctl.signal.aborted) return;
        if (e instanceof ApiError && e.status === 403) setState({ status: "forbidden" });
        else setState({ status: "error", error: e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }) });
      });
    return () => ctl.abort();
  }, [path, tick]);

  return [state, reload];
}
