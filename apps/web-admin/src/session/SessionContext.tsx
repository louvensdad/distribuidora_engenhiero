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
  /** Sessão venceu com a tela aberta: a tela continua montada (nada digitado se perde) até entrar de novo. */
  expired: boolean;
  loginUrl: string;
  /** Depois de entrar de novo (em outra aba): confere a sessão. Mesma pessoa → continua; outra → recarrega tudo. */
  recheck: () => Promise<"ok" | "still-anonymous" | "other-user">;
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
  const [expired, setExpired] = useState(false);
  const [loginUrl, setLoginUrl] = useState(LOGIN_FALLBACK);

  const load = useCallback(async (expired = false) => {
    try {
      const { data: s } = await api.get<BffSession>("/bff/session");
      if (s.web_login === "NAO_CONFIGURADO") return setState({ kind: "not-configured" });
      if (s.login_url) setLoginUrl(s.login_url);
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
    // Com a tela pronta, não desmonta: marca "expirada" e mostra o aviso por cima (WEB-03). Antes disso, vai à entrada.
    const onUnauth = () => setState((prev) => {
      if (prev.kind === "ready") {
        setExpired(true);
        return prev;
      }
      return { kind: "anonymous", loginUrl: LOGIN_FALLBACK, expired: true };
    });
    window.addEventListener(UNAUTHENTICATED_EVENT, onUnauth);
    return () => window.removeEventListener(UNAUTHENTICATED_EVENT, onUnauth);
  }, [load]);

  const recheck = useCallback(async (): Promise<"ok" | "still-anonymous" | "other-user"> => {
    const before = state.kind === "ready" ? state.me.user.id : null;
    try {
      const { data: s } = await api.get<BffSession>("/bff/session");
      if (!s.authenticated) return "still-anonymous";
      const { data: me } = await api.get<Me>("/api/v1/me/capabilities");
      if (before && me.user.id !== before) {
        // Outra conta entrou nesta janela: nada da tela anterior pode continuar com a nova identidade.
        window.location.assign("/");
        return "other-user";
      }
      setState({ kind: "ready", me });
      setExpired(false);
      return "ok";
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return "still-anonymous";
      throw e;
    }
  }, [state]);

  const value = useMemo<SessionApi>(() => {
    const me = state.kind === "ready" ? state.me : null;
    const caps = new Set(me?.capabilities.map((c) => c.capability) ?? []);
    return {
      state,
      expired,
      loginUrl,
      recheck,
      me,
      // "admin": funções sem recurso no contrato (ADR-017). Só monta menu; o servidor decide.
      can: (c) => (c === "admin" ? me?.admin === true : caps.has(c)),
      canSeeField: (f) => me?.fields[f] === true,
      reload: () => load(),
      logout: async () => {
        const { data } = await api.post<{ logout_url: string }>("/bff/logout", {});
        window.location.assign(data.logout_url);
      },
    };
  }, [state, load, expired, loginUrl, recheck]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): SessionApi {
  const v = useContext(Ctx);
  if (!v) throw new Error("useSession fora de SessionProvider");
  return v;
}
