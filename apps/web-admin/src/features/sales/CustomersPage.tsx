import { useEffect, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import { ApiError, api } from "../../api/http";
import type { Customer, Page } from "../../api/types";
import { useResource } from "../../api/useResource";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { Icon } from "../../components/Icon";
import { useSession } from "../../session/SessionContext";

const Hidden = () => <span className="masked" title="Oculto pela sua permissão de campo"><Icon name="lock" size={14} /> oculto</span>;

/** Clientes no escopo do usuário. Campos sensíveis vêm nulos do servidor quando não liberados. */
export function CustomersPage() {
  const { can } = useSession();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const [search, setSearch] = useState(q);
  const [creating, setCreating] = useState(false);
  const path = `/api/v1/customers?limit=25${q ? `&q=${encodeURIComponent(q)}` : ""}`;
  const [first, reload] = useResource<Page<Customer>>(path);
  const [items, setItems] = useState<Customer[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  useEffect(() => {
    if (first.status === "ready") {
      setItems(first.data.items);
      setCursor(first.data.next_cursor);
    }
  }, [first]);

  const loadMore = async () => {
    if (!cursor) return;
    setLoadingMore(true);
    try {
      const { data } = await api.get<Page<Customer>>(`${path}&cursor=${encodeURIComponent(cursor)}`);
      setItems((p) => [...p, ...data.items]);
      setCursor(data.next_cursor);
    } finally {
      setLoadingMore(false);
    }
  };

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

      {creating && <CreateCustomer onDone={(created) => { setCreating(false); if (created) reload(); }} />}

      <form className="search" role="search" onSubmit={(e) => { e.preventDefault(); setParams(search.trim() ? { q: search.trim() } : {}); }}>
        <label className="visually-hidden" htmlFor="busca-clientes">Buscar cliente por nome</label>
        <input id="busca-clientes" type="search" placeholder="Buscar por nome" value={search} onChange={(e) => setSearch(e.target.value)} />
        <button className="btn"><Icon name="search" size={16} /> Buscar</button>
      </form>

      {first.status === "loading" && <LoadingState />}
      {first.status === "forbidden" && <ForbiddenState />}
      {first.status === "error" && <ErrorState error={first.error} onRetry={reload} />}
      {first.status === "ready" && (items.length === 0 ? (
        <EmptyState title={q ? `Nenhum cliente encontrado para “${q}”` : "Nenhum cliente cadastrado"}>
          {can("customers.create") && <p>Use “Novo cliente” para cadastrar o primeiro.</p>}
        </EmptyState>
      ) : (
        <>
          <div className="table-wrap card">
            <table className="stack">
              <thead>
                <tr><th scope="col">Nome</th><th scope="col">Contato</th><th scope="col">Endereço</th><th scope="col">CPF/CNPJ</th><th scope="col">Unidade</th></tr>
              </thead>
              <tbody>
                {items.map((c) => (
                  <tr key={c.id}>
                    <th scope="row">{c.name}</th>
                    <td data-label="Contato">{c.masked_fields.includes("contact") ? <Hidden /> : c.contact ?? "—"}</td>
                    <td data-label="Endereço">{c.masked_fields.includes("address") ? <Hidden /> : c.address ?? "—"}</td>
                    <td data-label="CPF/CNPJ">{c.masked_fields.includes("personal_document") ? <Hidden /> : c.personal_document ?? "—"}</td>
                    <td data-label="Unidade">{c.branch}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {cursor && <button className="btn" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? "Carregando…" : "Carregar mais"}</button>}
        </>
      ))}
    </section>
  );
}

function CreateCustomer({ onDone }: { onDone: (created: boolean) => void }) {
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
      onDone(true);
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
