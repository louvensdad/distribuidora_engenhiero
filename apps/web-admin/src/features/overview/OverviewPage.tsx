import { Link } from "react-router";
import { AUDIT_ACTION_LABELS, label } from "../../api/labels";
import type { AuditEvent, Page, Report, UserView } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { Icon } from "../../components/Icon";
import { LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/**
 * W02 — visão geral. Só mostra dados reais existentes. Vendas, CMV, despesas, perdas e lucro do mês vêm do mesmo
 * relatório canônico (F09) para quem tem `reports.view`; sem a permissão, aparecem bloqueados; campo oculto aparece como oculto.
 */
export function OverviewPage() {
  const { me, can } = useSession();
  const [users] = useResource<Page<UserView>>(can("users.view") ? "/api/v1/users" : null);
  const [audit] = useResource<Page<AuditEvent>>(can("audit.view") ? "/api/v1/audit-events?limit=6" : null);
  const [sum] = useResource<Report>(can("reports.view") ? "/api/v1/reports/overview?period=MONTH" : null);
  const r = sum.status === "ready" ? sum.data.result : undefined;
  const kpis: [string, string | null | undefined][] = [
    ["Vendas (receita líquida)", r?.net_revenue],
    ["CMV", r && r.cogs],
    ["Despesas", r?.expenses_total],
    ["Perdas", r && r.losses],
    ["Lucro gerencial", r && r.profit],
  ];

  return (
    <section className="page" aria-labelledby="titulo-visao">
      <header className="page-head">
        <div>
          <h1 id="titulo-visao">Visão geral</h1>
          <p className="muted">{me?.organization.name} · {me?.branch.name}</p>
        </div>
      </header>

      <div className="kpis">
        {kpis.map(([k, v]) => v !== undefined ? (
          <Link key={k} to="/reports" className="kpi card">
            <span className="kpi-label">{k} (mês)</span>
            <span className="kpi-value">{v === null ? "oculto" : formatMoney(v)}</span>
            <span className="muted small">{v === null ? "Oculto pela sua permissão (custo/margem)" : "Resultado gerencial por competência"}</span>
          </Link>
        ) : (
          <div key={k} className="kpi card kpi-pending">
            <span className="kpi-label">{k}</span>
            <span className="kpi-value" aria-label="Não disponível">—</span>
            <span className="muted small">{can("reports.view") ? "Carregando…" : "Sem acesso ao resultado (permissão de relatórios)"}</span>
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
