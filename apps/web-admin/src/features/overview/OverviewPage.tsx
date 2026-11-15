import { Link } from "react-router";
import { AUDIT_ACTION_LABELS, label } from "../../api/labels";
import type { AuditEvent, Page, UserView } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime } from "../../app/format";
import { Icon } from "../../components/Icon";
import { LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/**
 * W02 — visão geral. Só mostra dados reais existentes. Indicadores de vendas, CMV, despesas e lucro dependem
 * do financeiro reconciliado (F06/F09) e aparecem como pendentes, nunca com números de exemplo.
 */
export function OverviewPage() {
  const { me, can } = useSession();
  const [users] = useResource<Page<UserView>>(can("users.view") ? "/api/v1/users" : null);
  const [audit] = useResource<Page<AuditEvent>>(can("audit.view") ? "/api/v1/audit-events?limit=6" : null);

  return (
    <section className="page" aria-labelledby="titulo-visao">
      <header className="page-head">
        <div>
          <h1 id="titulo-visao">Visão geral</h1>
          <p className="muted">{me?.organization.name} · {me?.branch.name}</p>
        </div>
      </header>

      <div className="kpis">
        {["Vendas realizadas", "CMV", "Despesas", "Perdas", "Lucro gerencial"].map((k) => (
          <div key={k} className="kpi card kpi-pending">
            <span className="kpi-label">{k}</span>
            <span className="kpi-value" aria-label="Ainda não disponível">—</span>
            <span className="muted small">Disponível com o financeiro reconciliado (F06/F09)</span>
          </div>
        ))}
      </div>

      <div className="grid-2">
        <div className="card">
          <h2 className="h3"><Icon name="users" size={18} /> Equipe</h2>
          {users.status === "loading" && <LoadingState />}
          {users.status === "ready" && (
            <p>
              <strong className="big">{users.data.items.filter((u) => u.status === "ACTIVE").length}</strong> pessoas ativas
              {" · "}{users.data.items.filter((u) => u.status !== "ACTIVE").length} bloqueadas
            </p>
          )}
          {can("permissions.view") && <Link to="/team/access">Abrir mapa de acessos</Link>}
        </div>
        <div className="card">
          <h2 className="h3"><Icon name="history" size={18} /> Últimos movimentos</h2>
          {audit.status === "loading" && <LoadingState />}
          {audit.status === "ready" && (audit.data.items.length === 0 ? <p className="muted">Sem movimentos ainda.</p> : (
            <ul className="mini-list">
              {audit.data.items.map((e) => (
                <li key={e.id}>
                  <time dateTime={e.occurred_at} className="muted small">{formatDateTime(e.occurred_at)}</time>{" "}
                  <strong>{e.actor_name ?? "Sistema"}</strong> {label(AUDIT_ACTION_LABELS, e.action)}
                </li>
              ))}
            </ul>
          ))}
          {can("audit.view") && <Link to="/team/audit">Ver histórico completo</Link>}
        </div>
      </div>
    </section>
  );
}
