import { useState } from "react";
import { Link } from "react-router";
import { ApiError, api } from "../../api/http";
import { SECTOR_LABELS, TEMPLATE_LABELS, label } from "../../api/labels";
import type { Page, UserView } from "../../api/types";
import { useResource } from "../../api/useResource";
import { ConflictState, EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

export function PeoplePage() {
  const { can, me } = useSession();
  const [res, reload] = useResource<Page<UserView>>("/api/v1/users");
  const [editing, setEditing] = useState<UserView | null>(null);

  if (res.status === "loading") return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  const people = res.data.items;

  return (
    <section className="page" aria-labelledby="titulo-pessoas">
      <header className="page-head">
        <div>
          <h1 id="titulo-pessoas">Pessoas</h1>
          <p className="muted">{people.length} {people.length === 1 ? "pessoa vinculada" : "pessoas vinculadas"} à empresa.</p>
        </div>
      </header>
      {people.length === 0 ? (
        <EmptyState title="Ninguém vinculado ainda" />
      ) : (
        <div className="table-wrap card">
          <table className="stack">
            <thead>
              <tr><th scope="col">Nome</th><th scope="col">Setor</th><th scope="col">Modelo</th><th scope="col">Unidade</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr>
            </thead>
            <tbody>
              {people.map((u) => (
                <tr key={u.id}>
                  <th scope="row">{u.display_name}{u.admin && <span className="badge badge-violet">admin</span>}</th>
                  <td data-label="Setor">{label(SECTOR_LABELS, u.sector)}</td>
                  <td data-label="Modelo">{label(TEMPLATE_LABELS, u.role_template)}</td>
                  <td data-label="Unidade">{u.branch}</td>
                  <td data-label="Situação"><span className={"badge " + (u.status === "ACTIVE" ? "badge-ok" : "badge-error")}>{u.status === "ACTIVE" ? "Ativa" : "Bloqueada"}</span></td>
                  <td className="row-actions">
                    {can("permissions.view") && <Link className="btn btn-small" to={`/team/access?user=${u.id}`}>Acessos</Link>}
                    {can("users.edit_draft") && u.id !== me?.user.id && (
                      <button className="btn btn-small btn-ghost" onClick={() => setEditing(u)}>
                        {u.status === "ACTIVE" ? "Bloquear" : "Reativar"}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <StatusDialog user={editing} onClose={(changed) => { setEditing(null); if (changed) reload(); }} />}
    </section>
  );
}

function StatusDialog({ user, onClose }: { user: UserView; onClose: (changed: boolean) => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [conflict, setConflict] = useState(false);
  const target = user.status === "ACTIVE" ? "DISABLED" : "ACTIVE";

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/v1/users/${user.id}/status`, { status: target, reason }, `"${user.version}"`);
      onClose(true);
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) setConflict(true);
      else setError(e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };

  return (
    <div className="dialog-backdrop" onKeyDown={(e) => e.key === "Escape" && onClose(false)}>
      <div className="dialog card" role="dialog" aria-modal="true" aria-labelledby="dlg-status">
        {conflict ? <ConflictState onReload={() => onClose(true)} /> : (
          <form onSubmit={(e) => { e.preventDefault(); void submit(); }}>
            <h2 id="dlg-status" className="h3">{target === "DISABLED" ? "Bloquear" : "Reativar"} {user.display_name}?</h2>
            <p className="muted">
              {target === "DISABLED"
                ? "A pessoa perde o acesso na próxima ação em qualquer aplicativo. O histórico é preservado."
                : "A pessoa volta a acessar com as permissões que já tinha."}
            </p>
            <label className="field">
              <span>Motivo (fica no histórico)</span>
              <input autoFocus required value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} />
            </label>
            {error && <p className="notice notice-error" role="alert">{error.message}</p>}
            <div className="actions">
              <button type="button" className="btn btn-ghost" onClick={() => onClose(false)}>Cancelar</button>
              <button className={"btn " + (target === "DISABLED" ? "btn-danger" : "btn-primary")} disabled={busy || !reason.trim()}>
                {busy ? "Salvando…" : target === "DISABLED" ? "Bloquear conta" : "Reativar conta"}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
