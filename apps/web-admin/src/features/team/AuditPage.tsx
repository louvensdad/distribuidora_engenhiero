import { useEffect, useState } from "react";
import { ApiError, api } from "../../api/http";
import { AUDIT_ACTION_LABELS, CLIENT_LABELS, label } from "../../api/labels";
import type { AuditEvent, Page, UserView } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/** W16 — histórico com ator real e horário do servidor. Paginação por cursor no servidor. */
export function AuditPage() {
  const { can } = useSession();
  const [actor, setActor] = useState("");
  const [users] = useResource<Page<UserView>>(can("users.view") ? "/api/v1/users" : null);
  const query = `/api/v1/audit-events?limit=25${actor ? `&actor_user_id=${actor}` : ""}`;
  const [first, reload] = useResource<Page<AuditEvent>>(query);
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [more, setMore] = useState<{ busy: boolean; error: ApiError | null }>({ busy: false, error: null });

  useEffect(() => {
    if (first.status === "ready") {
      setItems(first.data.items);
      setCursor(first.data.next_cursor);
    }
  }, [first]);

  const loadMore = async () => {
    if (!cursor) return;
    setMore({ busy: true, error: null });
    try {
      const { data } = await api.get<Page<AuditEvent>>(`${query}&cursor=${encodeURIComponent(cursor)}`);
      setItems((prev) => [...prev, ...data.items]);
      setCursor(data.next_cursor);
      setMore({ busy: false, error: null });
    } catch (e) {
      setMore({ busy: false, error: e instanceof ApiError ? e : null });
    }
  };

  return (
    <section className="page" aria-labelledby="titulo-historico">
      <header className="page-head">
        <div>
          <h1 id="titulo-historico">Histórico</h1>
          <p className="muted">Cada ação com quem fez, quando (horário do servidor) e por qual aplicativo.</p>
        </div>
        {users.status === "ready" && (
          <label className="field inline">
            <span>Filtrar pessoa</span>
            <select value={actor} onChange={(e) => setActor(e.target.value)}>
              <option value="">Todas</option>
              {users.data.items.map((u) => <option key={u.id} value={u.id}>{u.display_name}</option>)}
            </select>
          </label>
        )}
      </header>
      {first.status === "loading" && <LoadingState />}
      {first.status === "forbidden" && <ForbiddenState />}
      {first.status === "error" && <ErrorState error={first.error} onRetry={reload} />}
      {first.status === "ready" && (items.length === 0 ? <EmptyState title="Nenhum movimento registrado" /> : (
        <>
          <ol className="timeline">
            {items.map((ev) => (
              <li key={ev.id} className={"event event-" + ev.result.toLowerCase()}>
                <time dateTime={ev.occurred_at}>{formatDateTime(ev.occurred_at)}</time>
                <div>
                  <strong>{ev.actor_name ?? (ev.actor_kind === "SERVICE" ? "Serviço do sistema" : "Não identificado")}</strong>{" "}
                  {label(AUDIT_ACTION_LABELS, ev.action)}
                  {ev.result !== "SUCCESS" && <span className="badge badge-error">{ev.result === "DENIED" ? "negado" : "falhou"}</span>}
                  <div className="muted small">
                    {ev.client_id && <>{label(CLIENT_LABELS, ev.client_id)} · </>}
                    {ev.reason && <>Motivo: {ev.reason} · </>}
                    {ev.entity_type && <>{ev.entity_type} </>}
                    {ev.request_id && <code title="Código para suporte">{ev.request_id.slice(0, 8)}</code>}
                  </div>
                </div>
              </li>
            ))}
          </ol>
          {more.error && <ErrorState error={more.error} onRetry={() => void loadMore()} />}
          {cursor && (
            <button className="btn" disabled={more.busy} onClick={() => void loadMore()}>
              {more.busy ? "Carregando…" : "Carregar mais"}
            </button>
          )}
        </>
      ))}
    </section>
  );
}
