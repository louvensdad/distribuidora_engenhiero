import { Icon } from "../../components/Icon";
import { ErrorState, LoadingState } from "../../components/States";
import type { SessionState } from "../../session/SessionContext";
import { useSession } from "../../session/SessionContext";
import marca from "../../assets/marca.svg";
import { rememberReturn } from "../../session/returnPath";

/** W01 — entrada segura. Senha e segundo fator ficam no provedor de identidade, não neste site. */
export function EntryPage({ state }: { state: Exclude<SessionState, { kind: "ready" }> }) {
  const { reload, logout } = useSession();
  return (
    <main className="entry">
      <section className="entry-card" aria-labelledby="entry-title">
        <img className="entry-brand" src={marca} alt="" />
        <h1 id="entry-title">Distribuidora de Cana Engenheiro Ltda</h1>
        <p className="muted">Site de gestão do patrão</p>
        {state.kind === "loading" && <LoadingState label="Verificando sessão" />}
        {state.kind === "anonymous" && (
          <>
            {state.expired && <p className="notice" role="status">Sua sessão terminou. Entre novamente.</p>}
            <a className="btn btn-primary btn-large" href={state.loginUrl} onClick={() => rememberReturn()}>
              <Icon name="shield" size={18} /> Entrar com segurança
            </a>
            <p className="muted small">
              Conta individual. A senha e o segundo fator são confirmados pelo provedor de identidade da empresa.
            </p>
          </>
        )}
        {state.kind === "not-configured" && (
          <div className="state state-warning" role="alert">
            <h2>Login do site NÃO CONFIGURADO</h2>
            <p>O servidor está sem o segredo do client <code>engenheiro-web</code>. Configure
              <code>ENGENHEIRO_WEB_CLIENT_SECRET</code> no servidor.</p>
          </div>
        )}
        {state.kind === "forbidden" && (
          <div className="state" role="alert">
            <Icon name="lock" size={28} />
            <h2>Esta conta não tem acesso ao site do patrão</h2>
            <p>Use o aplicativo do seu setor ou peça liberação ao administrador.</p>
            {state.requestId && <p className="muted small">Código: <code>{state.requestId}</code></p>}
            <button className="btn" onClick={() => void logout()}>Sair desta conta</button>
          </div>
        )}
        {state.kind === "error" && <ErrorState error={state.error} onRetry={() => void reload()} />}
      </section>
    </main>
  );
}
