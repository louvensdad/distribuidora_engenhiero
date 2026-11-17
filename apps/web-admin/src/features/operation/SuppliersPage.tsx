import { useState, type FormEvent } from "react";
import { ApiError, api } from "../../api/http";
import type { Supplier } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { EmptyResult, PagedList, SearchBox } from "../../components/Lists";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ConflictState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

export function SuppliersPage() {
  const { can } = useSession();
  const [f, setF] = useUrlFilters(["q"] as const);
  const list = usePaged<Supplier>(`/api/v1/suppliers?${qs({ limit: 50, q: f.q })}`);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Supplier | null>(null);
  return (
    <section className="page" aria-labelledby="titulo-fornecedores">
      <header className="page-head">
        <div>
          <h1 id="titulo-fornecedores">Fornecedores</h1>
          <p className="muted">Cadastro interno; não cria acesso para o fornecedor. Contato e documento seguem a sua permissão de campo.</p>
        </div>
        {can("suppliers.create") && !creating && <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={16} /> Novo fornecedor</button>}
      </header>
      {creating && <CreateSupplier onDone={(name) => { setCreating(false); if (name) { if (name === f.q) list.reload(); else setF({ q: name }); } }} />}
      {editing && <EditSupplier key={editing.id} supplier={editing} onDone={(ok) => { setEditing(null); if (ok) list.reload(); }} />}
      <SearchBox id="busca-fornecedores" label="Buscar fornecedor por nome" value={f.q} onSearch={(q) => setF({ q })} />
      <PagedList list={list} empty={<EmptyResult q={f.q} what="fornecedor cadastrado" />}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Nome</th><th scope="col">Contato</th><th scope="col">CPF/CNPJ</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{items.map((s) => (
              <tr key={s.id}>
                <th scope="row">{s.name}</th>
                <td data-label="Contato">{s.masked_fields.includes("contact") ? <Hidden /> : s.contact ?? "—"}</td>
                <td data-label="CPF/CNPJ">{s.masked_fields.includes("personal_document") ? <Hidden /> : s.personal_document ?? "—"}</td>
                <td>{can("suppliers.edit_draft") && <button className="btn btn-small" onClick={() => setEditing(s)} aria-label={`Editar ${s.name}`}>Editar</button>}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}</PagedList>
    </section>
  );
}

function EditSupplier({ supplier, onDone }: { supplier: Supplier; onDone: (ok: boolean) => void }) {
  const { canSeeField } = useSession();
  const [form, setForm] = useState({ name: supplier.name, contact: supplier.contact ?? "", personal_document: supplier.personal_document ?? "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const save = async (status: "ACTIVE" | "ARCHIVED") => {
    setBusy(true);
    setError(null);
    const body: Record<string, string | null> = { name: form.name.trim(), status };
    if (canSeeField("operational_contact")) body.contact = form.contact.trim() || null;
    if (canSeeField("personal_document")) body.personal_document = form.personal_document.trim() || null;
    try {
      await api.put<Supplier>(`/api/v1/suppliers/${supplier.id}`, body, `"${supplier.version}"`);
      onDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };
  if (error?.status === 412) return <ConflictState onReload={() => onDone(true)} />;
  return (
    <form className="card form-grid" onSubmit={(e) => { e.preventDefault(); void save("ACTIVE"); }} aria-labelledby="titulo-editar-fornecedor" noValidate>
      <h2 id="titulo-editar-fornecedor" className="h3 span-2">Editar fornecedor</h2>
      <Field id="fe-nome" label="Nome *" error={fieldError(error, "name")}>
        <input id="fe-nome" maxLength={200} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
      </Field>
      {canSeeField("operational_contact") && (
        <Field id="fe-contato" label="Contato" error={fieldError(error, "contact")}>
          <input id="fe-contato" value={form.contact} onChange={(e) => setForm({ ...form, contact: e.target.value })} />
        </Field>
      )}
      {canSeeField("personal_document") && (
        <Field id="fe-doc" label="CPF ou CNPJ" error={fieldError(error, "personal_document")}>
          <input id="fe-doc" inputMode="numeric" value={form.personal_document} onChange={(e) => setForm({ ...form, personal_document: e.target.value })} />
        </Field>
      )}
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void save("ARCHIVED")}
          title="Some das listas e de novos recebimentos; o histórico continua">Arquivar</button>
        <button className="btn btn-primary" disabled={busy || !form.name.trim()}>{busy ? "Salvando…" : "Salvar"}</button>
      </div>
    </form>
  );
}

function CreateSupplier({ onDone }: { onDone: (createdName: string | false) => void }) {
  const { canSeeField } = useSession();
  const [form, setForm] = useState({ name: "", contact: "", personal_document: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post<Supplier>("/api/v1/suppliers", Object.fromEntries(Object.entries(form).filter(([, v]) => v.trim() !== "")));
      onDone(form.name.trim());
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-novo-fornecedor" noValidate>
      <h2 id="titulo-novo-fornecedor" className="h3 span-2">Novo fornecedor</h2>
      <Field id="f-nome" label="Nome *" error={fieldError(error, "name")}>
        <input id="f-nome" maxLength={200} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
      </Field>
      {canSeeField("operational_contact") && (
        <Field id="f-contato" label="Contato" error={fieldError(error, "contact")}>
          <input id="f-contato" value={form.contact} onChange={(e) => setForm({ ...form, contact: e.target.value })} />
        </Field>
      )}
      {canSeeField("personal_document") && (
        <Field id="f-doc" label="CPF ou CNPJ" error={fieldError(error, "personal_document")}>
          <input id="f-doc" inputMode="numeric" value={form.personal_document} onChange={(e) => setForm({ ...form, personal_document: e.target.value })} />
        </Field>
      )}
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy || !form.name.trim()}>{busy ? "Salvando…" : "Cadastrar fornecedor"}</button>
      </div>
    </form>
  );
}
