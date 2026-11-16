import { useState, type FormEvent } from "react";
import { ApiError, api } from "../../api/http";
import type { Page, Supplier } from "../../api/types";
import { useResource } from "../../api/useResource";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

export function SuppliersPage() {
  const { can } = useSession();
  const [res, reload] = useResource<Page<Supplier>>("/api/v1/suppliers?limit=100");
  const [creating, setCreating] = useState(false);
  return (
    <section className="page" aria-labelledby="titulo-fornecedores">
      <header className="page-head">
        <div>
          <h1 id="titulo-fornecedores">Fornecedores</h1>
          <p className="muted">Cadastro interno; não cria acesso para o fornecedor. Contato e documento seguem a sua permissão de campo.</p>
        </div>
        {can("suppliers.create") && !creating && <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={16} /> Novo fornecedor</button>}
      </header>
      {creating && <CreateSupplier onDone={(ok) => { setCreating(false); if (ok) reload(); }} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhum fornecedor cadastrado" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Nome</th><th scope="col">Contato</th><th scope="col">CPF/CNPJ</th></tr></thead>
            <tbody>{res.data.items.map((s) => (
              <tr key={s.id}>
                <th scope="row">{s.name}</th>
                <td data-label="Contato">{s.masked_fields.includes("contact") ? <Hidden /> : s.contact ?? "—"}</td>
                <td data-label="CPF/CNPJ">{s.masked_fields.includes("personal_document") ? <Hidden /> : s.personal_document ?? "—"}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function CreateSupplier({ onDone }: { onDone: (ok: boolean) => void }) {
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
      onDone(true);
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
