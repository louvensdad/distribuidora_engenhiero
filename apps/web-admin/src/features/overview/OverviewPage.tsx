import { Link } from "react-router";
import { AUDIT_ACTION_LABELS, label } from "../../api/labels";
import type { ApprovalCenter, AuditEvent, Page, Report, UserView } from "../../api/types";
import { APPROVERS } from "../../app/navigation";
import { SECTOR_NAMES } from "../../app/origin";
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
  const [ap] = useResource<ApprovalCenter>(APPROVERS.some(can) ? "/api/v1/approvals" : null);
  // Cada total abre os registros que o compõem (drilldown do relatório canônico).
  const kpis: [string, string | null | undefined, string][] = [
    ["Vendas (receita líquida)", r?.net_revenue, "/reports?period=MONTH&abrir=gross_revenue"],
    ["CMV", r && r.cogs, "/reports?period=MONTH&abrir=cogs"],
    ["Despesas", r?.expenses_total, "/reports/categories?period=MONTH"],
    ["Perdas", r && r.losses, "/reports?period=MONTH&abrir=losses"],
    ["Lucro gerencial", r && r.profit, "/reports?period=MONTH"],
  ];

  return (
    <section className="page" aria-labelledby="titulo-visao">
      <header className="page-head">
        <div>
          <h1 id="titulo-visao">Visão geral</h1>
          <p className="muted">{me?.organization.name} · {me?.branch.name}</p>
        </div>
        <div className="actions">
          {can("reports.view") && <Link className="btn" to="/reports">Abrir relatório</Link>}
          {/* IA real só na F15: botão desligado e rotulado, nunca resposta simulada. */}
          <button className="btn" disabled title="IA da gestão ainda não configurada (fase F15)">
            <Icon name="ai" size={16} /> Perguntar à IA — não configurada
          </button>
        </div>
      </header>

      {ap.status === "ready" && (
        <Link to="/overview/approvals" className={"card approvals-strip" + (ap.data.items.length ? " has-pending" : "")}>
          <Icon name="approve" size={20} />
          {ap.data.items.length === 0 ? <span>Nada esperando a sua decisão.</span> : (
            <span><strong>{ap.data.items.length}</strong> esperando a sua decisão:{" "}
              {ap.data.sectors.filter((x) => x.pending > 0).map((x) => `${x.pending} ${SECTOR_NAMES[x.sector]}`).join(" · ")}</span>
          )}
        </Link>
      )}

      <div className="kpis">
        {kpis.map(([k, v, to]) => v !== undefined ? (
          <Link key={k} to={to} className="kpi card">
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
          {" · "}<Link to="/overview/sectors">Central dos setores</Link>
        </div>
      </div>
    </section>
  );
}
