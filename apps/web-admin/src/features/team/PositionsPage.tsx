import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api } from "../../api/http";
import { SECTOR_LABELS, TEMPLATE_LABELS, label } from "../../api/labels";
import type { JobPosition } from "../../api/types";
import { useResource } from "../../api/useResource";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ConflictState, EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

const SECTORS = ["RETIRADA", "ENTREGAS", "CONTABILIDADE", "ADMINISTRACAO"] as const;
const TEMPLATES = ["BALCAO", "RECEBIMENTO", "COORDENADOR_ENTREGAS", "MOTORISTA", "CONTADOR"] as const;
const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** Equipe › Cargos (ADR-027): nome, setor, modelo de acesso sugerido e descrição. Arquivar não apaga histórico. */
export function PositionsPage() {
  const { can } = useSession();
  const [archived, setArchived] = useState(false);
  const [res, reload] = useResource<{ items: JobPosition[] }>(`/api/v1/job-positions?include_archived=${archived}`);
  const [editing, setEditing] = useState<JobPosition | "new" | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-cargos">
      <header className="page-head">
        <div>
          <h1 id="titulo-cargos">Cargos</h1>
          <p className="muted">O cargo organiza a equipe e sugere o modelo de acesso. O acesso de cada pessoa continua sendo decidido no mapa de acessos.</p>
        </div>
        {can("users.edit_draft") && editing === null && (
          <button className="btn btn-primary" onClick={() => setEditing("new")}><Icon name="plus" size={16} /> Novo cargo</button>
        )}
      </header>
      {editing !== null && (
        <PositionForm key={editing === "new" ? "new" : editing.id} position={editing === "new" ? null : editing}
          onDone={(changed) => { setEditing(null); if (changed) reload(); }} />
      )}
      <label className="field inline"><input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} /> Mostrar arquivados</label>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? (
        <EmptyState title="Nenhum cargo cadastrado">{can("users.edit_draft") && <p>Comece por “Novo cargo” — por exemplo Balconista, Motorista, Conferente.</p>}</EmptyState>
      ) : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Cargo</th><th scope="col">Setor</th><th scope="col">Acesso sugerido</th><th scope="col">Funcionários</th>
              <th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{res.data.items.map((p) => (
              <tr key={p.id}>
                <th scope="row">{p.name}{p.description && <span className="muted small"><br />{p.description}</span>}</th>
                <td data-label="Setor">{label(SECTOR_LABELS, p.sector)}</td>
                <td data-label="Acesso sugerido">{p.suggested_template ? label(TEMPLATE_LABELS, p.suggested_template) : "—"}</td>
                <td data-label="Funcionários"><Link to={`/team/employees?position=${p.id}`}>{p.employees}</Link></td>
                <td data-label="Situação"><span className={"badge " + (p.status === "ACTIVE" ? "badge-ok" : "badge-muted")}>{p.status === "ACTIVE" ? "Ativo" : "Arquivado"}</span></td>
                <td>{can("users.edit_draft") && <button className="btn btn-small" onClick={() => setEditing(p)} aria-label={`Editar ${p.name}`}>Editar</button>}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function PositionForm({ position, onDone }: { position: JobPosition | null; onDone: (changed: boolean) => void }) {
  const [form, setForm] = useState({ name: position?.name ?? "", sector: position?.sector ?? "RETIRADA",
    suggested_template: position?.suggested_template ?? "", description: position?.description ?? "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const save = async (status?: "ARCHIVED" | "ACTIVE") => {
    setBusy(true);
    setError(null);
    const body = { ...form, suggested_template: form.suggested_template || null, description: form.description.trim() || null,
      status: status ?? position?.status ?? "ACTIVE" };
    try {
      if (position) await api.put(`/api/v1/job-positions/${position.id}`, body, `"${position.version}"`);
      else await api.post("/api/v1/job-positions", body);
      onDone(true);
    } catch (e) {
      setError(asError(e));
      setBusy(false);
    }
  };
  if (error?.status === 412) return <ConflictState onReload={() => onDone(true)} />;
  return (
    <form className="card form-grid" aria-label={position ? "Editar cargo" : "Novo cargo"} noValidate
      onSubmit={(e: FormEvent) => { e.preventDefault(); void save(); }}>
      <h2 className="h3 span-2">{position ? `Editar cargo: ${position.name}` : "Novo cargo"}</h2>
      <Field id="cg-nome" label="Nome do cargo *" error={fieldError(error, "name")}>
        <input id="cg-nome" maxLength={80} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
      </Field>
      <Field id="cg-setor" label="Setor *" error={fieldError(error, "sector")}>
        <select id="cg-setor" value={form.sector} onChange={(e) => setForm({ ...form, sector: e.target.value as JobPosition["sector"] })}>
          {SECTORS.map((s) => <option key={s} value={s}>{label(SECTOR_LABELS, s)}</option>)}
        </select>
      </Field>
      <Field id="cg-modelo" label="Acesso sugerido" hint="Usado ao dar acesso aos apps; pode ser trocado na hora" error={fieldError(error, "suggested_template")}>
        <select id="cg-modelo" value={form.suggested_template} onChange={(e) => setForm({ ...form, suggested_template: e.target.value })}>
          <option value="">Sem sugestão</option>
          {TEMPLATES.map((t) => <option key={t} value={t}>{label(TEMPLATE_LABELS, t)}</option>)}
        </select>
      </Field>
      <Field id="cg-desc" label="Descrição" error={fieldError(error, "description")}>
        <input id="cg-desc" maxLength={500} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}
          placeholder="Ex.: atende no balcão e recebe em dinheiro/Pix" />
      </Field>
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        {position && position.status === "ACTIVE" && <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void save("ARCHIVED")}>Arquivar</button>}
        {position && position.status === "ARCHIVED" && <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void save("ACTIVE")}>Reativar</button>}
        <button className="btn btn-primary" disabled={busy || !form.name.trim()}>{busy ? "Salvando…" : "Salvar cargo"}</button>
      </div>
    </form>
  );
}
