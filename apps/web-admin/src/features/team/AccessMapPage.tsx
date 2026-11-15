import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { ApiError, api } from "../../api/http";
import { ACTION_LABELS, CLIENT_LABELS, FIELD_LABELS, RESOURCE_LABELS, SCOPE_LABELS, TEMPLATE_LABELS, label } from "../../api/labels";
import type { Page, Policy, RoleTemplate, Scope, UserView } from "../../api/types";
import { useResource } from "../../api/useResource";
import { ConflictState, EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { CLIENTS, FIELDS, SCOPES, cellOf, describeChanges, fromPolicy, grantableMatrix, toPayload, withCell, type CellEffect, type Draft } from "./policyModel";

/** W15 — mapa individual de permissões. Salvar exige motivo, versão (If-Match) e passa pela validação do servidor. */
export function AccessMapPage() {
  const { me, can } = useSession();
  const [params, setParams] = useSearchParams();
  const userId = params.get("user");
  const [users] = useResource<Page<UserView>>(can("users.view") ? "/api/v1/users" : null);

  return (
    <section className="page" aria-labelledby="titulo-acessos">
      <header className="page-head">
        <div>
          <h1 id="titulo-acessos">Mapa de acessos</h1>
          <p className="muted">Permissões por pessoa, ação, escopo e campo. O servidor nega tudo que não estiver concedido.</p>
        </div>
        {users.status === "ready" && (
          <label className="field inline">
            <span>Pessoa</span>
            <select value={userId ?? ""} onChange={(e) => setParams(e.target.value ? { user: e.target.value } : {})}>
              <option value="">Selecione…</option>
              {users.data.items.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.display_name} — {label(TEMPLATE_LABELS, u.role_template)}{u.status === "DISABLED" ? " (bloqueada)" : ""}
                </option>
              ))}
            </select>
          </label>
        )}
      </header>
      {!userId ? (
        <EmptyState title="Escolha uma pessoa">
          <p>Selecione alguém para ver e ajustar o que essa pessoa pode fazer em cada aplicativo.</p>
        </EmptyState>
      ) : userId === me?.user.id ? (
        <div className="state" role="status">
          <h3>Você não pode alterar o próprio acesso</h3>
          <p>Por segurança, outro administrador precisa fazer essa mudança.</p>
        </div>
      ) : (
        <PolicyEditor key={userId} userId={userId} />
      )}
    </section>
  );
}

function PolicyEditor({ userId }: { userId: string }) {
  const { me, can } = useSession();
  const [res, reload] = useResource<Policy>(`/api/v1/users/${userId}/access-policy`);
  const [templates] = useResource<RoleTemplate[]>("/api/v1/role-templates");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [reason, setReason] = useState("");
  const [template, setTemplate] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);

  const original = useMemo(() => (res.status === "ready" ? fromPolicy(res.data) : null), [res]);
  useEffect(() => setDraft(original), [original]);
  const matrix = useMemo(() => grantableMatrix(me?.capabilities ?? []), [me]);
  const canEdit = can("permissions.edit_draft");

  if (res.status === "loading" || (res.status === "ready" && !draft)) return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  if (conflict) return <ConflictState onReload={() => { setConflict(false); setSaved(null); reload(); }} />;
  const policy = res.data;
  const d = draft!;
  const changes = describeChanges(original!, d);

  const submit = async (body: unknown) => {
    setSaving(true);
    setError(null);
    setSaved(null);
    try {
      await api.post<Policy>(`/api/v1/users/${userId}/access-policy`, body, res.etag ?? `"${policy.user.version}"`);
      setSaved("Permissões salvas. A mudança vale na próxima ação da pessoa.");
      setReason("");
      setTemplate("");
      reload();
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) setConflict(true);
      else setError(e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setSaving(false);
    }
  };

  const setField = (f: string, effect: CellEffect) => setDraft({ ...d, fields: { ...d.fields, [f]: effect } });
  const toggleClient = (c: string) =>
    setDraft({ ...d, clients: d.clients.includes(c) ? d.clients.filter((x) => x !== c) : [...d.clients, c].sort() });

  return (
    <div className="editor">
      <div className="card person">
        <strong>{policy.user.display_name}</strong>
        <span className="muted">{label(TEMPLATE_LABELS, policy.user.role_template)} · {policy.user.branch}</span>
        {policy.user.admin && <span className="badge badge-violet">Administrador — acesso integral</span>}
        {policy.user.status === "DISABLED" && <span className="badge badge-error">Conta bloqueada</span>}
      </div>

      {canEdit && templates.status === "ready" && (
        <form className="card row-form" onSubmit={(e) => { e.preventDefault(); void submit({ template, reason }); }}>
          <h2 className="h3">Aplicar modelo</h2>
          <p className="muted small">Substitui as permissões atuais pelas do modelo. Depois você ajusta individualmente.</p>
          <label className="field">
            <span>Modelo</span>
            <select value={template} onChange={(e) => setTemplate(e.target.value)} required>
              <option value="">Selecione…</option>
              {templates.data.filter((t) => !t.admin || me?.admin).map((t) => (
                <option key={t.name} value={t.name}>{label(TEMPLATE_LABELS, t.name)}</option>
              ))}
            </select>
          </label>
          <ReasonField value={reason} onChange={setReason} id="motivo-modelo" />
          <button className="btn" disabled={saving || !template || !reason.trim()}>Aplicar modelo</button>
        </form>
      )}

      <div className="card">
        <h2 className="h3">Aplicativos liberados</h2>
        <div className="chips" role="group" aria-label="Aplicativos liberados">
          {CLIENTS.map((c) => (
            <label key={c} className="chip">
              <input type="checkbox" checked={d.clients.includes(c)} disabled={!canEdit} onChange={() => toggleClient(c)} />
              {label(CLIENT_LABELS, c)}
            </label>
          ))}
        </div>
      </div>

      {policy.user.admin ? (
        <p className="notice">Administradores têm todas as capacidades do catálogo, exceto as negadas explicitamente.</p>
      ) : (
        <div className="card">
          <h2 className="h3">Ações por área</h2>
          <p className="muted small">Abra uma área para ajustar. Áreas com alguma permissão já começam abertas.</p>
          <div className="groups">
            {matrix.map(({ resource, actions }) => {
              const granted = actions.filter((a) => cellOf(d, resource, a).effect === "ALLOW").length;
              const denied = actions.filter((a) => cellOf(d, resource, a).effect === "DENY").length;
              const touched = granted + denied > 0;
              return (
                <details key={resource} className="group" open={touched || undefined}>
                  <summary>
                    <span className="group-name">{label(RESOURCE_LABELS, resource)}</span>
                    <span className={"badge " + (granted ? "badge-ok" : "")}>{granted} de {actions.length} liberadas</span>
                    {denied > 0 && <span className="badge badge-error">{denied} bloqueadas</span>}
                  </summary>
                  <table className="matrix stack">
                    <caption className="visually-hidden">Permissões de {label(RESOURCE_LABELS, resource)}</caption>
                    <thead>
                      <tr><th scope="col">Ação</th><th scope="col">Acesso</th><th scope="col">Escopo</th><th scope="col">Alçada (R$)</th></tr>
                    </thead>
                    <tbody>
                      {actions.map((action) => {
                        const c = cellOf(d, resource, action);
                        const id = `${resource}-${action}`;
                        return (
                          <tr key={id} className={c.effect === "DENY" ? "row-deny" : c.effect === "ALLOW" ? "row-allow" : ""}>
                            <th scope="row"><label htmlFor={`ef-${id}`}>{label(ACTION_LABELS, action)}</label></th>
                            <td data-label="Acesso">
                              <select id={`ef-${id}`} value={c.effect} disabled={!canEdit}
                                onChange={(e) => setDraft(withCell(d, resource, action, { effect: e.target.value as CellEffect }))}>
                                <option value="NONE">Sem acesso</option>
                                <option value="ALLOW">Permitido</option>
                                <option value="DENY">Bloqueado</option>
                              </select>
                            </td>
                            <td data-label={c.effect === "ALLOW" ? "Escopo" : undefined}>
                              {c.effect === "ALLOW" && (
                                <>
                                  <select aria-label={`Escopo de ${label(ACTION_LABELS, action)}`} value={c.scope} disabled={!canEdit}
                                    onChange={(e) => setDraft(withCell(d, resource, action, { scope: e.target.value as Scope }))}>
                                    {SCOPES.map((sc) => <option key={sc} value={sc}>{label(SCOPE_LABELS, sc)}</option>)}
                                  </select>
                                  {c.extraScopes.length > 0 && (
                                    <span className="muted small"> também: {c.extraScopes.map((x) => label(SCOPE_LABELS, x)).join(", ")}</span>
                                  )}
                                  {c.expiresAt && <span className="muted small"> até {new Date(c.expiresAt).toLocaleString("pt-BR")}</span>}
                                </>
                              )}
                            </td>
                            <td data-label={c.effect === "ALLOW" ? "Alçada (R$)" : undefined}>
                              {c.effect === "ALLOW" && (
                                <input className="money" inputMode="decimal" aria-label={`Alçada de ${label(ACTION_LABELS, action)}`}
                                  placeholder="sem limite" value={c.maxAmount} disabled={!canEdit} pattern="[0-9]+([.,][0-9]{1,2})?"
                                  onChange={(e) => setDraft(withCell(d, resource, action, { maxAmount: e.target.value }))} />
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </details>
              );
            })}
          </div>
        </div>
      )}

      <div className="card">
        <h2 className="h3">Campos sensíveis</h2>
        <p className="muted small">Ocultos por padrão. Liberar só o necessário para a tarefa.</p>
        <div className="fields-grid">
          {FIELDS.map((f) => (
            <label key={f} className="field">
              <span>{label(FIELD_LABELS, f)}</span>
              <select value={d.fields[f] ?? "NONE"} disabled={!canEdit} onChange={(e) => setField(f, e.target.value as CellEffect)}>
                <option value="NONE">Oculto (padrão)</option>
                <option value="ALLOW">Visível</option>
                <option value="DENY">Bloqueado</option>
              </select>
            </label>
          ))}
        </div>
      </div>

      {canEdit && (
        <form className="card summary" onSubmit={(e) => { e.preventDefault(); void submit(toPayload(d, reason)); }}>
          <h2 className="h3">Resumo antes de salvar</h2>
          {changes.length === 0 ? <p className="muted">Nenhuma alteração.</p> : (
            <ul className="changes">{changes.map((c) => <li key={c}>{c}</li>)}</ul>
          )}
          <ReasonField value={reason} onChange={setReason} id="motivo-salvar" />
          <div className="actions">
            <button type="button" className="btn btn-ghost" disabled={saving || changes.length === 0} onClick={() => setDraft(original)}>
              Descartar alterações
            </button>
            <button className="btn btn-primary" disabled={saving || changes.length === 0 || !reason.trim()}>
              {saving ? "Salvando…" : "Salvar permissões"}
            </button>
          </div>
        </form>
      )}
      <div aria-live="polite" className="live">
        {saved && <p className="notice notice-ok">{saved}</p>}
        {error && (
          <div className="notice notice-error" role="alert">
            <strong>{error.message}</strong>
            {error.fieldErrors.length > 0 && <ul>{error.fieldErrors.map((f) => <li key={f.field + f.message}>{f.message}</li>)}</ul>}
            {error.requestId && <p className="small">Código: <code>{error.requestId}</code></p>}
          </div>
        )}
      </div>
    </div>
  );
}

function ReasonField({ value, onChange, id }: { value: string; onChange: (v: string) => void; id: string }) {
  return (
    <label className="field" htmlFor={id}>
      <span>Motivo (fica no histórico)</span>
      <input id={id} value={value} maxLength={300} required onChange={(e) => onChange(e.target.value)}
        placeholder="Ex.: passou a conferir recebimentos" />
    </label>
  );
}
