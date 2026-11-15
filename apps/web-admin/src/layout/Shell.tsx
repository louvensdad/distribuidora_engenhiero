import { useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router";
import { visibleTabs } from "../app/navigation";
import { Icon } from "../components/Icon";
import { OfflineBanner } from "../components/States";
import { useSession } from "../session/SessionContext";
import marca from "../assets/marca.svg";

export function Shell() {
  const { me, can, logout } = useSession();
  const location = useLocation();
  const [leaving, setLeaving] = useState(false);
  const tabs = visibleTabs(can);
  const active = tabs.find((t) => location.pathname.startsWith(t.path));

  return (
    <div className="app">
      <a className="skip-link" href="#conteudo">Pular para o conteúdo</a>
      <header className="topbar">
        <img className="brand" src={marca} alt="Distribuidora de Cana Engenheiro Ltda" />
        <div className="context">
          <span className="org">{me?.organization.name}</span>
          <span className="muted small">Unidade: {me?.branch.name}</span>
        </div>
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
      <main id="conteudo" tabIndex={-1} className="content">
        <Outlet />
      </main>
      <footer className="footer muted small">
        Distribuidora de Cana Engenheiro Ltda · as permissões são verificadas pelo servidor a cada ação
      </footer>
    </div>
  );
}
