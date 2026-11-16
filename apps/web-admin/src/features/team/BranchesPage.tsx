import { useState, type FormEvent } from "react";
import { ApiError, api } from "../../api/http";
import type { BranchView } from "../../api/types";
import { useResource } from "../../api/useResource";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ErrorState, ForbiddenState, LoadingState } from "../../components/States";

/** Unidades operacionais. Criar/desativar: só administrador (ADR-017); desativar exige unidade sem pessoas e sem estoque. */
export function BranchesPage() {
  const [res, reload] = useResource<{ items: BranchView[] }>("/api/v1/branches");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [rowError, setRowError] = useState<Record<string, string>>({});

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/v1/branches", { name: name.trim() });
      setName("");
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (b: BranchView) => {
    const reason = window.prompt(b.status === "ACTIVE" ? `Motivo para desativar "${b.name}"` : `Motivo para reativar "${b.name}"`);
    if (!reason?.trim()) return;
    try {
      await api.post(`/api/v1/branches/${b.id}/status`, { status: b.status === "ACTIVE" ? "INACTIVE" : "ACTIVE", reason: reason.trim() });
      setRowError({});
      reload();
    } catch (err) {
      setRowError({ [b.id]: err instanceof ApiError ? err.message : "Erro inesperado" });
    }
  };

  return (
    <section className="page" aria-labelledby="titulo-unidades">
      <header className="page-head">
        <div>
          <h1 id="titulo-unidades">Unidades</h1>
          <p className="muted">Cada unidade tem seu estoque, pessoas e fuso. Uma unidade só é desativada sem pessoas ativas e sem estoque.</p>
        </div>
      </header>
      <form className="card row-form" onSubmit={create} noValidate aria-labelledby="titulo-nova-unidade">
        <h2 id="titulo-nova-unidade" className="h3">Nova unidade</h2>
        <Field id="u-nome" label="Nome *" error={fieldError(error, "name")}>
          <input id="u-nome" maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <div className="field"><span>Fuso</span><input value="America/Sao_Paulo" readOnly aria-readonly="true" aria-label="Fuso" /></div>
        <button className="btn btn-primary" disabled={busy || !name.trim()}><Icon name="plus" size={16} /> Criar</button>
        <FormError error={error?.fieldErrors.length ? null : error} />
      </form>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Unidade</th><th scope="col">Fuso</th><th scope="col">Pessoas ativas</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{res.data.items.map((b) => (
              <tr key={b.id}>
                <th scope="row">{b.name}</th>
                <td data-label="Fuso">{b.timezone}</td>
                <td data-label="Pessoas ativas">{b.active_people}</td>
                <td data-label="Situação">{b.status === "ACTIVE" ? <span className="badge badge-ok">Ativa</span> : <span className="badge badge-error">Inativa</span>}</td>
                <td>
                  <div className="row-actions">
                    <button className="btn btn-small" onClick={() => void toggle(b)}>{b.status === "ACTIVE" ? "Desativar" : "Reativar"}</button>
                    {rowError[b.id] && <span className="field-error" role="alert">{rowError[b.id]}</span>}
                  </div>
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}
