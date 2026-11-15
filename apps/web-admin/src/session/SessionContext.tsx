import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiError, UNAUTHENTICATED_EVENT, api } from "../api/http";
import type { BffSession, Me } from "../api/types";

export type SessionState =
  | { kind: "loading" }
  | { kind: "not-configured" }
  | { kind: "anonymous"; loginUrl: string; expired: boolean }
  | { kind: "forbidden"; requestId?: string }
  | { kind: "error"; error: ApiError }
  | { kind: "ready"; me: Me };

interface SessionApi {
  state: SessionState;
  me: Me | null;
  /** Conveniência de interface. O servidor recalcula a permissão em toda chamada. */
  can: (capability: string) => boolean;
  canSeeField: (field: string) => boolean;
  reload: () => Promise<void>;
  logout: () => Promise<void>;
}

const Ctx = createContext<SessionApi | null>(null);
const LOGIN_FALLBACK = "/oauth2/authorization/engenheiro";

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ kind: "loading" });

  const load = useCallback(async (expired = false) => {
    try {
      const { data: s } = await api.get<BffSession>("/bff/session");
      if (s.web_login === "NAO_CONFIGURADO") return setState({ kind: "not-configured" });
      if (!s.authenticated) return setState({ kind: "anonymous", loginUrl: s.login_url ?? LOGIN_FALLBACK, expired });
      const { data: me } = await api.get<Me>("/api/v1/me/capabilities");
      setState({ kind: "ready", me });
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) return setState({ kind: "forbidden", requestId: e.requestId });
      if (e instanceof ApiError && e.status === 401)
        return setState({ kind: "anonymous", loginUrl: LOGIN_FALLBACK, expired: true });
      setState({ kind: "error", error: e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }) });
    }
  }, []);

  useEffect(() => {
    void load();
    const onUnauth = () => setState({ kind: "anonymous", loginUrl: LOGIN_FALLBACK, expired: true });
    window.addEventListener(UNAUTHENTICATED_EVENT, onUnauth);
    return () => window.removeEventListener(UNAUTHENTICATED_EVENT, onUnauth);
  }, [load]);

  const value = useMemo<SessionApi>(() => {
    const me = state.kind === "ready" ? state.me : null;
    const caps = new Set(me?.capabilities.map((c) => c.capability) ?? []);
    return {
      state,
      me,
      can: (c) => caps.has(c),
      canSeeField: (f) => me?.fields[f] === true,
      reload: () => load(),
      logout: async () => {
        const { data } = await api.post<{ logout_url: string }>("/bff/logout", {});
        window.location.assign(data.logout_url);
      },
    };
  }, [state, load]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): SessionApi {
  const v = useContext(Ctx);
  if (!v) throw new Error("useSession fora de SessionProvider");
  return v;
}
