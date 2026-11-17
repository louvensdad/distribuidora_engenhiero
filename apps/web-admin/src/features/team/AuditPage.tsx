import { Link } from "react-router";
import { AUDIT_ACTION_LABELS, CLIENT_LABELS, label } from "../../api/labels";
import type { AuditEvent, Page, UserView } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { useResource } from "../../api/useResource";
import { formatDateTime } from "../../app/format";
import { ENTITY_LABELS, originPath } from "../../app/origin";
import { PagedList } from "../../components/Lists";
import { EmptyState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/** Dia seguinte (o servidor usa fim exclusivo; a tela mostra "até" inclusivo). */
const nextDay = (iso: string) => {
  const d = new Date(iso + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
};

/**
 * W16 — histórico com ator real e horário do servidor, paginado por cursor. Filtros por pessoa, setor, registro,
 * resultado e período (dia civil de São Paulo) ficam na URL; "Abrir origem" leva ao registro (com a permissão de lá).
 */
export function AuditPage() {
  const { can } = useSession();
  const [f, setF] = useUrlFilters(["actor", "sector", "entity_type", "entity_id", "from", "until", "result"] as const);
  const [users] = useResource<Page<UserView>>(can("users.view") ? "/api/v1/users" : null);
  const list = usePaged<AuditEvent>(`/api/v1/audit-events?${qs({
    limit: 25, actor_user_id: f.actor, sector: f.sector, entity_type: f.entity_type, entity_id: f.entity_id,
    from: f.from, to: f.until ? nextDay(f.until) : "", result: f.result,
  })}`);
  const filtered = Object.values(f).some(Boolean);

  return (
    <section className="page" aria-labelledby="titulo-historico">
      <header className="page-head">
        <div>
          <h1 id="titulo-historico">Histórico</h1>
          <p className="muted">Cada ação com quem fez, quando (horário do servidor) e por qual aplicativo. Ação do patrão fica no nome do patrão.</p>
        </div>
      </header>
      <form className="filters card" aria-label="Filtros do histórico" onSubmit={(e) => e.preventDefault()}>
        {users.status === "ready" && (
          <label className="field"><span>Pessoa</span>
            <select value={f.actor} onChange={(e) => setF({ actor: e.target.value })}>
              <option value="">Todas</option>
              {users.data.items.map((u) => <option key={u.id} value={u.id}>{u.display_name}</option>)}
            </select>
          </label>
        )}
        <label className="field"><span>Setor</span>
          <select value={f.sector} onChange={(e) => setF({ sector: e.target.value })}>
            <option value="">Todos</option>
            <option value="COUNTER">Retirada, balcão e fornecedores</option>
            <option value="DELIVERY">Entregas, motoristas e veículos</option>
            <option value="ACCOUNTING">Contabilidade e documentos</option>
          </select>
        </label>
        <label className="field"><span>Resultado</span>
          <select value={f.result} onChange={(e) => setF({ result: e.target.value })}>
            <option value="">Todos</option><option value="SUCCESS">Concluídos</option><option value="DENIED">Negados</option><option value="FAILURE">Falhas</option>
          </select>
        </label>
        <label className="field"><span>De</span><input type="date" value={f.from} onChange={(e) => setF({ from: e.target.value })} /></label>
        <label className="field"><span>Até</span><input type="date" value={f.until} onChange={(e) => setF({ until: e.target.value })} /></label>
        {f.entity_id && (
          <p className="span-2">Só o registro: <strong>{ENTITY_LABELS[f.entity_type] ?? f.entity_type} {f.entity_id.slice(0, 8)}</strong>{" "}
            <button type="button" className="btn btn-small btn-ghost" onClick={() => setF({ entity_type: "", entity_id: "" })}>Mostrar todos</button></p>
        )}
        {filtered && <button type="button" className="btn btn-small btn-ghost"
          onClick={() => setF({ actor: "", sector: "", entity_type: "", entity_id: "", from: "", until: "", result: "" })}>Limpar filtros</button>}
      </form>
      <PagedList list={list} empty={<EmptyState title={filtered ? "Nenhum movimento com estes filtros" : "Nenhum movimento registrado"} />}>{(items) => (
        <ol className="timeline">
          {items.map((ev) => {
            const to = originPath(ev.entity_type, ev.entity_id);
            return (
              <li key={ev.id} className={"event event-" + ev.result.toLowerCase()}>
                <time dateTime={ev.occurred_at}>{formatDateTime(ev.occurred_at)}</time>
                <div>
                  <strong>{ev.actor_name ?? (ev.actor_kind === "SERVICE" ? "Serviço do sistema" : "Não identificado")}</strong>{" "}
                  {label(AUDIT_ACTION_LABELS, ev.action)}
                  {ev.result !== "SUCCESS" && <span className="badge badge-error">{ev.result === "DENIED" ? "negado" : "falhou"}</span>}
                  <div className="muted small">
                    {ev.client_id && <>{label(CLIENT_LABELS, ev.client_id)} · </>}
                    {ev.reason && <>Motivo: {ev.reason} · </>}
                    {ev.entity_type && <>{ENTITY_LABELS[ev.entity_type] ?? ev.entity_type} · </>}
                    {ev.request_id && <code title="Código para suporte">{ev.request_id.slice(0, 8)}</code>}
                  </div>
                  {ev.entity_type && ev.entity_id && (
                    <div className="row-actions">
                      {to && <Link className="btn btn-small" to={to}>Abrir origem</Link>}
                      {f.entity_id !== ev.entity_id && (
                        <button className="btn btn-small btn-ghost" onClick={() => setF({ entity_type: ev.entity_type ?? "", entity_id: ev.entity_id ?? "" })}>
                          Só este registro
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
      )}</PagedList>
    </section>
  );
}
