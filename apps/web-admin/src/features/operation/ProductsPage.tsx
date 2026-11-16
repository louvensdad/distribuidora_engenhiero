import { useState, type FormEvent } from "react";
import { ApiError, api } from "../../api/http";
import type { Page, Product } from "../../api/types";
import { useResource } from "../../api/useResource";
import { UNITS, formatDateTime, formatDecimal, formatMoney, unitLabel, toDecimal } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ConflictState, EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/** Produtos e medidas: unidade-base, conversões por produto (com histórico) e confirmação da unidade comercial. */
export function ProductsPage() {
  const { can } = useSession();
  const [res, reload] = useResource<Page<Product>>("/api/v1/products?limit=100");
  const [creating, setCreating] = useState(false);
  return (
    <section className="page" aria-labelledby="titulo-produtos">
      <header className="page-head">
        <div>
          <h1 id="titulo-produtos">Produtos e medidas</h1>
          <p className="muted">Cada produto tem sua unidade-base e fatores próprios: 1 feixe de um produto não vale o mesmo que de outro.</p>
        </div>
        {can("catalog.create") && !creating && <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={16} /> Novo produto</button>}
      </header>
      {creating && <CreateProduct onDone={(ok) => { setCreating(false); if (ok) reload(); }} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhum produto cadastrado" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Produto</th><th scope="col">Código</th><th scope="col">Unidade-base</th><th scope="col">Conversões vigentes</th><th scope="col">Preço de venda</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{res.data.items.map((p) => <ProductRow key={p.id} p={p} onChange={reload} />)}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function ProductRow({ p, onChange }: { p: Product; onChange: () => void }) {
  const { can } = useSession();
  const [adding, setAdding] = useState(false);
  const [pricing, setPricing] = useState(false);
  const [price, setPrice] = useState({ unit: "", price: "" });
  const [conv, setConv] = useState({ unit: "", factor: "" });
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (path: string, body?: unknown) => {
    setBusy(true);
    setError(null);
    try {
      await api.post<Product>(path, body, `"${p.version}"`);
      setAdding(false);
      setPricing(false);
      onChange();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <tr>
        <th scope="row">{p.name}</th>
        <td data-label="Código">{p.sku}</td>
        <td data-label="Unidade-base">{unitLabel(p.base_unit, "1")}{p.base_unit_confirmed
          ? <span className="badge badge-ok" title={p.unit_confirmed_at ? `Confirmada em ${formatDateTime(p.unit_confirmed_at)}` : undefined}>confirmada</span>
          : <span className="badge badge-error">não confirmada — não movimenta estoque</span>}</td>
        <td data-label="Conversões">{p.conversions.length === 0 ? "—" : p.conversions.map((c) => `1 ${unitLabel(c.unit, "1")} = ${formatDecimal(c.factor)} ${unitLabel(p.base_unit, c.factor)}`).join(" · ")}</td>
        <td data-label="Preço de venda">{p.prices?.length ? p.prices.map((x) => `${formatMoney(x.price)} / ${unitLabel(x.unit, "1")}`).join(" · ") : "sem preço"}</td>
        <td>
          {can("catalog.edit_draft") && (
            <div className="row-actions">
              <button className="btn btn-small" onClick={() => setPricing(!pricing)} aria-expanded={pricing}>Preço</button>
              {!p.base_unit_confirmed && <button className="btn btn-small btn-primary" disabled={busy} onClick={() => void run(`/api/v1/products/${p.id}/unit-confirmation`)}>Confirmar unidade</button>}
              <button className="btn btn-small" onClick={() => setAdding(!adding)} aria-expanded={adding}>Conversão</button>
            </div>
          )}
        </td>
      </tr>
      {pricing && (
        <tr className="detail-row"><td colSpan={6}>
          <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void run(`/api/v1/products/${p.id}/prices`, { unit: price.unit || null, price: toDecimal(price.price) }); }}>
            <label htmlFor={"pr-u-" + p.id}>Preço por</label>
            <select id={"pr-u-" + p.id} value={price.unit} onChange={(e) => setPrice({ ...price, unit: e.target.value })}>
              <option value="">{unitLabel(p.base_unit, "1")}</option>
              {p.conversions.map((c) => <option key={c.unit} value={c.unit}>{unitLabel(c.unit, "1")}</option>)}
            </select>
            <label className="visually-hidden" htmlFor={"pr-v-" + p.id}>Valor</label>
            <input id={"pr-v-" + p.id} className="money" inputMode="decimal" placeholder="R$" value={price.price} onChange={(e) => setPrice({ ...price, price: e.target.value })} />
            <button className="btn btn-small btn-primary" disabled={busy || !price.price}>Salvar nova vigência</button>
            {error && <span className="field-error" role="alert">{error.fieldErrors[0]?.message ?? error.message}</span>}
          </form>
        </td></tr>
      )}
      {adding && (
        <tr className="detail-row"><td colSpan={6}>
          {error?.code === "VERSION_CONFLICT" ? <ConflictState onReload={onChange} /> : (
            <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void run(`/api/v1/products/${p.id}/conversions`, { unit: conv.unit, factor: toDecimal(conv.factor) }); }}>
              <span>1</span>
              <label className="visually-hidden" htmlFor={"cv-u-" + p.id}>Medida</label>
              <select id={"cv-u-" + p.id} value={conv.unit} onChange={(e) => setConv({ ...conv, unit: e.target.value })}>
                <option value="">medida</option>
                {UNITS.filter((u) => u !== p.base_unit).map((u) => <option key={u} value={u}>{unitLabel(u, "1")}</option>)}
              </select>
              <span>=</span>
              <label className="visually-hidden" htmlFor={"cv-f-" + p.id}>Fator</label>
              <input id={"cv-f-" + p.id} inputMode="decimal" placeholder="fator" value={conv.factor} onChange={(e) => setConv({ ...conv, factor: e.target.value })} />
              <span>{unitLabel(p.base_unit)}</span>
              <button className="btn btn-small btn-primary" disabled={busy || !conv.unit || !conv.factor}>Salvar nova vigência</button>
              {error && <span className="field-error" role="alert">{error.fieldErrors[0]?.message ?? error.message}</span>}
              <span className="muted small">Entradas já gravadas mantêm o fator usado na época.</span>
            </form>
          )}
        </td></tr>
      )}
    </>
  );
}

function CreateProduct({ onDone }: { onDone: (ok: boolean) => void }) {
  const [form, setForm] = useState({ sku: "", name: "", base_unit: "DUZIA", base_unit_confirmed: false });
  const [convs, setConvs] = useState<{ unit: string; factor: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post<Product>("/api/v1/products", { ...form, conversions: convs.filter((c) => c.unit).map((c) => ({ unit: c.unit, factor: toDecimal(c.factor) })) });
      onDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-novo-produto" noValidate>
      <h2 id="titulo-novo-produto" className="h3 span-2">Novo produto</h2>
      <Field id="p-nome" label="Nome *" error={fieldError(error, "name")}>
        <input id="p-nome" maxLength={200} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
      </Field>
      <Field id="p-sku" label="Código *" hint="Letras, números, ponto, hífen" error={fieldError(error, "sku")}>
        <input id="p-sku" maxLength={40} value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value.toUpperCase() })} />
      </Field>
      <Field id="p-base" label="Unidade-base de estoque *" error={fieldError(error, "base_unit")}>
        <select id="p-base" value={form.base_unit} onChange={(e) => setForm({ ...form, base_unit: e.target.value })}>
          {UNITS.map((u) => <option key={u} value={u}>{unitLabel(u, "1")}</option>)}
        </select>
      </Field>
      <div className="field">
        <span>Unidade comercial</span>
        <label className="check">
          <input type="checkbox" checked={form.base_unit_confirmed} onChange={(e) => setForm({ ...form, base_unit_confirmed: e.target.checked })} />
          Confirmo que esta é a unidade em que o produto é comprado, estocado e vendido
        </label>
      </div>
      <fieldset className="span-2 lines">
        <legend>Conversões deste produto</legend>
        {convs.map((c, i) => (
          <div className="inline-form" key={i}>
            <span>1</span>
            <label className="visually-hidden" htmlFor={`pc-u-${i}`}>Medida</label>
            <select id={`pc-u-${i}`} value={c.unit} onChange={(e) => setConvs(convs.map((x, j) => (j === i ? { ...x, unit: e.target.value } : x)))}>
              <option value="">medida</option>
              {UNITS.filter((u) => u !== form.base_unit).map((u) => <option key={u} value={u}>{unitLabel(u, "1")}</option>)}
            </select>
            <span>=</span>
            <label className="visually-hidden" htmlFor={`pc-f-${i}`}>Fator</label>
            <input id={`pc-f-${i}`} inputMode="decimal" placeholder="fator" value={c.factor} onChange={(e) => setConvs(convs.map((x, j) => (j === i ? { ...x, factor: e.target.value } : x)))} />
            <span>{unitLabel(form.base_unit)}</span>
            {fieldError(error, `conversions[${i}].factor`) && <span className="field-error">{fieldError(error, `conversions[${i}].factor`)}</span>}
          </div>
        ))}
        <button type="button" className="btn btn-small" onClick={() => setConvs([...convs, { unit: "", factor: "" }])}><Icon name="plus" size={14} /> Adicionar conversão</button>
      </fieldset>
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy || !form.name.trim() || !form.sku.trim()}>{busy ? "Salvando…" : "Cadastrar produto"}</button>
      </div>
    </form>
  );
}
