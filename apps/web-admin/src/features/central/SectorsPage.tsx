import { Link } from "react-router";
import { AUDIT_ACTION_LABELS, CLIENT_LABELS, label } from "../../api/labels";
import type { ApprovalCenter, AuditEvent, Page } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime } from "../../app/format";
import { CENTRAL_SECTORS, originPath } from "../../app/origin";
import { Icon } from "../../components/Icon";
import { ErrorState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/**
 * P03 no site: os três setores (Retirada, Entregas, Contabilidade) numa tela, com a identidade do patrão. Cada setor
 * mostra o que espera decisão e os últimos movimentos com autor e app; "Entrar" abre os módulos do setor no site.
 */
export function SectorsPage() {
  const { can } = useSession();
  const [approvals] = useResource<ApprovalCenter>("/api/v1/approvals");
  return (
    <section className="page" aria-labelledby="titulo-setores">
      <header className="page-head">
        <div>
          <h1 id="titulo-setores">Central dos setores</h1>
          <p className="muted">Você acompanha e age em cada setor com a sua própria conta: o que fizer aqui fica registrado em seu nome, pelo site.</p>
        </div>
        {can("audit.view") && <Link className="btn" to="/team/audit">Histórico completo</Link>}
      </header>
      <div className="sectors">
        {CENTRAL_SECTORS.map((s) => (
          <SectorCard key={s.id} sector={s} pending={approvals.status === "ready"
            ? approvals.data.sectors.find((x) => x.sector === s.id)?.pending ?? 0 : null} />
        ))}
      </div>
    </section>
  );
}

function SectorCard({ sector, pending }: { sector: (typeof CENTRAL_SECTORS)[number]; pending: number | null }) {
  const { can } = useSession();
  const [audit, reload] = useResource<Page<AuditEvent>>(can("audit.view") ? `/api/v1/audit-events?sector=${sector.id}&limit=6&result=SUCCESS` : null);
  return (
    <article className="card sector-card" aria-labelledby={`setor-${sector.id}`}>
      <h2 className="h3" id={`setor-${sector.id}`}><Icon name={sector.icon} size={20} /> {sector.label}</h2>
      <p className="muted small">Movimentos do {sector.app} e do site sobre este setor.</p>
      {pending !== null && (
        <p>{pending > 0
          ? <Link to={`/overview/approvals#setor-${sector.id}`}><span className="badge badge-warn">{pending}</span><span>esperando sua decisão</span></Link>
          : <span className="muted">Nada esperando sua decisão</span>}</p>
      )}
      {can("audit.view") ? (
        <>
          {audit.status === "loading" && <LoadingState />}
          {audit.status === "error" && <ErrorState error={audit.error} onRetry={reload} />}
          {audit.status === "ready" && (audit.data.items.length === 0 ? <p className="muted">Sem movimentos ainda.</p> : (
            <ul className="mini-list">
              {audit.data.items.map((e) => {
                const to = originPath(e.entity_type, e.entity_id);
                return (
                  <li key={e.id}>
                    <time dateTime={e.occurred_at} className="muted small">{formatDateTime(e.occurred_at)}</time>{" "}
                    <strong>{e.actor_name ?? "Sistema"}</strong> {label(AUDIT_ACTION_LABELS, e.action)}
                    {e.client_id && <span className="muted small"> · {label(CLIENT_LABELS, e.client_id)}</span>}
                    {to && <> · <Link to={to}>abrir</Link></>}
                  </li>
                );
              })}
            </ul>
          ))}
          <Link to={`/team/audit?sector=${sector.id}`}>Histórico do setor</Link>
        </>
      ) : <p className="muted small">Histórico de movimentos exige a permissão de histórico.</p>}
      <nav aria-label={`Entrar em ${sector.label}`} className="sector-links">
        {sector.enter.map(([t, path]) => <Link key={path} className="btn btn-small" to={path}>{t}</Link>)}
      </nav>
    </article>
  );
}
