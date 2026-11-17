import { useState, type FormEvent } from "react";
import { ApiError, api } from "../../api/http";
import type { Customer } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { PagedList, SearchBox } from "../../components/Lists";
import { ConflictState, EmptyState } from "../../components/States";
import { Icon } from "../../components/Icon";
import { useSession } from "../../session/SessionContext";

const Hidden = () => <span className="masked" title="Oculto pela sua permissão de campo"><Icon name="lock" size={14} /> oculto</span>;

/** Clientes no escopo do usuário. Campos sensíveis vêm nulos do servidor quando não liberados. */
export function CustomersPage() {
  const { can } = useSession();
  const [f, setF] = useUrlFilters(["q"] as const);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Customer | null>(null);
  const list = usePaged<Customer>(`/api/v1/customers?${qs({ limit: 25, q: f.q })}`);

  return (
    <section className="page" aria-labelledby="titulo-clientes">
      <header className="page-head">
        <div>
          <h1 id="titulo-clientes">Clientes</h1>
          <p className="muted">Somente os clientes que sua permissão alcança. Contato, endereço e documento aparecem conforme os campos liberados.</p>
        </div>
        {can("customers.create") && !creating && (
          <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={16} /> Novo cliente</button>
        )}
      </header>

      {creating && <CreateCustomer onDone={(name) => { setCreating(false); if (name) { if (name === f.q) list.reload(); else setF({ q: name }); } }} />}
      {editing && <EditCustomer key={editing.id} customer={editing} onDone={(changed) => { setEditing(null); if (changed) list.reload(); }} />}

      <SearchBox id="busca-clientes" label="Buscar cliente por nome" placeholder="Buscar por nome" value={f.q} onSearch={(q) => setF({ q })} />

      <PagedList list={list} empty={
        <EmptyState title={f.q ? `Nenhum cliente encontrado para “${f.q}”` : "Nenhum cliente cadastrado"}>
          {can("customers.create") && !f.q && <p>Use “Novo cliente” para cadastrar o primeiro.</p>}
        </EmptyState>}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead>
              <tr><th scope="col">Nome</th><th scope="col">Contato</th><th scope="col">Endereço</th><th scope="col">CPF/CNPJ</th><th scope="col">Unidade</th>
                <th scope="col"><span className="visually-hidden">Ações</span></th></tr>
            </thead>
            <tbody>
              {items.map((c) => (
                <tr key={c.id}>
                  <th scope="row">{c.name}</th>
                  <td data-label="Contato">{c.masked_fields.includes("contact") ? <Hidden /> : c.contact ?? "—"}</td>
                  <td data-label="Endereço">{c.masked_fields.includes("address") ? <Hidden /> : c.address ?? "—"}</td>
                  <td data-label="CPF/CNPJ">{c.masked_fields.includes("personal_document") ? <Hidden /> : c.personal_document ?? "—"}</td>
                  <td data-label="Unidade">{c.branch}</td>
                  <td>{can("customers.edit_draft") && <button className="btn btn-small" onClick={() => setEditing(c)} aria-label={`Editar ${c.name}`}>Editar</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}</PagedList>
    </section>
  );
}

/** Edição com versão (If-Match): campos ocultos não aparecem nem são enviados — o servidor os mantém. */
function EditCustomer({ customer, onDone }: { customer: Customer; onDone: (changed: boolean) => void }) {
  const { canSeeField } = useSession();
  const [form, setForm] = useState({ name: customer.name, contact: customer.contact ?? "", address: customer.address ?? "",
    personal_document: customer.personal_document ?? "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  const fieldError = (f: string) => error?.fieldErrors.find((x) => x.field === f)?.message;
  const save = async (status: "ACTIVE" | "ARCHIVED") => {
    setBusy(true);
    setError(null);
    const body: Record<string, string | null> = { name: form.name.trim(), status };
    if (canSeeField("operational_contact")) body.contact = form.contact.trim() || null;
    if (canSeeField("delivery_address")) body.address = form.address.trim() || null;
    if (canSeeField("personal_document")) body.personal_document = form.personal_document.trim() || null;
    try {
      await api.put<Customer>(`/api/v1/customers/${customer.id}`, body, `"${customer.version}"`);
      onDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };
  if (error?.status === 412) return <ConflictState onReload={() => onDone(true)} />;
  return (
    <form className="card form-grid" onSubmit={(e) => { e.preventDefault(); void save("ACTIVE"); }} aria-labelledby="titulo-editar-cliente" noValidate>
      <h2 id="titulo-editar-cliente" className="h3 span-2">Editar cliente</h2>
      <Field id="ce-nome" label="Nome *" error={fieldError("name")}>
        <input id="ce-nome" maxLength={200} value={form.name} onChange={set("name")} autoFocus />
      </Field>
      {canSeeField("operational_contact") && (
        <Field id="ce-contato" label="Contato" error={fieldError("contact")}><input id="ce-contato" value={form.contact} onChange={set("contact")} /></Field>
      )}
      {canSeeField("delivery_address") && (
        <Field id="ce-endereco" label="Endereço de entrega" error={fieldError("address")}><input id="ce-endereco" value={form.address} onChange={set("address")} /></Field>
      )}
      {canSeeField("personal_document") && (
        <Field id="ce-doc" label="CPF ou CNPJ" error={fieldError("personal_document")}><input id="ce-doc" inputMode="numeric" value={form.personal_document} onChange={set("personal_document")} /></Field>
      )}
      <div aria-live="assertive" className="span-2">{error && <p className="notice notice-error" role="alert">{error.message}</p>}</div>
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void save("ARCHIVED")}
          title="Some das listas e de novos pedidos; o histórico continua">Arquivar</button>
        <button className="btn btn-primary" disabled={busy || !form.name.trim()}>{busy ? "Salvando…" : "Salvar"}</button>
      </div>
    </form>
  );
}

function CreateCustomer({ onDone }: { onDone: (createdName: string | false) => void }) {
  const { canSeeField } = useSession();
  const [form, setForm] = useState({ name: "", contact: "", address: "", personal_document: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  const fieldError = (f: string) => error?.fieldErrors.find((x) => x.field === f)?.message;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = Object.fromEntries(Object.entries(form).filter(([, v]) => v.trim() !== ""));
    try {
      await api.post<Customer>("/api/v1/customers", body);
      onDone(form.name.trim());
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false); // dados digitados preservados
    }
  };

  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-novo-cliente" noValidate>
      <h2 id="titulo-novo-cliente" className="h3 span-2">Novo cliente</h2>
      <Field id="c-nome" label="Nome *" error={fieldError("name")}>
        <input id="c-nome" required maxLength={200} value={form.name} onChange={set("name")} autoFocus />
      </Field>
      {canSeeField("operational_contact") && (
        <Field id="c-contato" label="Contato" error={fieldError("contact")}>
          <input id="c-contato" value={form.contact} onChange={set("contact")} placeholder="Telefone ou WhatsApp" />
        </Field>
      )}
      {canSeeField("delivery_address") && (
        <Field id="c-endereco" label="Endereço de entrega" error={fieldError("address")}>
          <input id="c-endereco" value={form.address} onChange={set("address")} />
        </Field>
      )}
      {canSeeField("personal_document") && (
        <Field id="c-doc" label="CPF ou CNPJ" error={fieldError("personal_document")}>
          <input id="c-doc" inputMode="numeric" value={form.personal_document} onChange={set("personal_document")} />
        </Field>
      )}
      <div aria-live="assertive" className="span-2">
        {error && <p className="notice notice-error" role="alert">{error.message}</p>}
      </div>
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy || !form.name.trim()}>{busy ? "Salvando…" : "Cadastrar cliente"}</button>
      </div>
    </form>
  );
}

function Field({ id, label, error, children }: { id: string; label: string; error?: string; children: React.ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <span className="field-error" id={id + "-erro"}>{error}</span>}
    </div>
  );
}
