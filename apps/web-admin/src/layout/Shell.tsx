import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate, useSearchParams } from "react-router";
import { visibleTabs } from "../app/navigation";
import { Icon } from "../components/Icon";
import { OfflineBanner } from "../components/States";
import { SessionExpiredDialog } from "../components/SessionExpiredDialog";
import { useSession } from "../session/SessionContext";
import marca from "../assets/marca.svg";

export function Shell() {
  const { me, can, logout } = useSession();
  const location = useLocation();
  const [leaving, setLeaving] = useState(false);
  const tabs = visibleTabs(can);
  const active = tabs.find((t) => location.pathname.startsWith(t.path));
  const searchable = ["orders.pickup.view", "orders.delivery.view", "trips.view", "vehicles.view", "customers.view",
    "suppliers.view", "catalog.view"].some(can);

  return (
    <div className="app">
      <a className="skip-link" href="#conteudo">Pular para o conteúdo</a>
      <header className="topbar">
        <img className="brand" src={marca} alt="Distribuidora de Cana Engenheiro Ltda" />
        <div className="context">
          <span className="org">{me?.organization.name}</span>
          <span className="muted small">Unidade: {me?.branch.name}</span>
        </div>
        {searchable && <GlobalSearch />}
        <div className="user">
          <span className="user-name">{me?.user.display_name}</span>
          <button
            className="btn btn-ghost"
            disabled={leaving}
            onClick={() => {
              setLeaving(true);
              logout().catch(() => setLeaving(false));
            }}
          >
            {leaving ? "Saindo…" : "Sair"}
          </button>
        </div>
      </header>

      <nav className="tabs" aria-label="Seções principais">
        <ul>
          {tabs.map((t) => (
            <li key={t.id}>
              <NavLink to={t.subtabs[0]!.path} className={() => "tab" + (active?.id === t.id ? " tab-active" : "")}
                aria-current={active?.id === t.id ? "page" : undefined}>
                <Icon name={t.icon} size={18} />
                <span>{t.label}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      {active && active.subtabs.length > 1 && (
        <nav className="subtabs" aria-label={`Seções de ${active.label}`}>
          <ul>
            {active.subtabs.map((s) => (
              <li key={s.id}>
                <NavLink to={s.path} className={({ isActive }) => "subtab" + (isActive ? " subtab-active" : "")}>
                  {s.label}
                  {s.pendingPhase && <span className="badge">{s.pendingPhase}</span>}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      )}

      <OfflineBanner />
      <SessionExpiredDialog />
      <main id="conteudo" tabIndex={-1} className="content">
        <Outlet />
      </main>
      <footer className="footer muted small">
        Distribuidora de Cana Engenheiro Ltda · as permissões são verificadas pelo servidor a cada ação
      </footer>
    </div>
  );
}

/** Busca geral no topo: leva a /search?q= (o servidor decide em quais módulos procurar). */
function GlobalSearch() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const location = useLocation();
  const current = location.pathname === "/search" ? params.get("q") ?? "" : "";
  const [q, setQ] = useState(current);
  useEffect(() => setQ(current), [current]);
  return (
    <form className="global-search" role="search" onSubmit={(e) => {
      e.preventDefault();
      if (q.trim().length >= 2) navigate(`/search?q=${encodeURIComponent(q.trim())}`);
    }}>
      <label className="visually-hidden" htmlFor="busca-geral">Buscar pedido, cliente, placa…</label>
      <input id="busca-geral" type="search" maxLength={80} placeholder="Buscar pedido, cliente, placa…" value={q} onChange={(e) => setQ(e.target.value)} />
      <button className="btn btn-ghost" aria-label="Buscar em tudo"><Icon name="search" size={16} /></button>
    </form>
  );
}
