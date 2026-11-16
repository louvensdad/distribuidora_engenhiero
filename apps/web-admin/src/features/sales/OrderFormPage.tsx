import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Customer, Order, Page, Product } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatMoney, unitLabel } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

interface Item { product_id: string; unit: string; quantity: string; discount: string }
const empty = (): Item => ({ product_id: "", unit: "", quantity: "", discount: "" });

/** Novo pedido: o servidor calcula preço e total e reserva o estoque; desconto só dentro da alçada. */
export function OrderFormPage() {
  const { can, me } = useSession();
  const navigate = useNavigate();
  const [customers, reloadC] = useResource<Page<Customer>>("/api/v1/customers?limit=100");
  const [products, reloadP] = useResource<Page<Product>>("/api/v1/products?limit=100");
  const types = [can("orders.pickup.create") && "PICKUP", can("orders.delivery.create") && "DELIVERY"].filter(Boolean) as string[];
  const [type, setType] = useState(types[0] ?? "PICKUP");
  const [customer, setCustomer] = useState("");
  const [notes, setNotes] = useState("");
  const [items, setItems] = useState<Item[]>([empty()]);
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const mayDiscount = me?.admin || can("orders.pickup.edit_draft") || can("orders.delivery.edit_draft");

  if (customers.status === "loading" || products.status === "loading") return <LoadingState />;
  if (customers.status === "forbidden" || products.status === "forbidden") return <ForbiddenState />;
  if (customers.status === "error") return <ErrorState error={customers.error} onRetry={reloadC} />;
  if (products.status === "error") return <ErrorState error={products.error} onRetry={reloadP} />;
  const set = (i: number, patch: Partial<Item>) => setItems(items.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.command<Order>("/api/v1/orders", {
        type, customer_id: customer || null, notes: notes.trim() || null,
        items: items.map((x) => ({ product_id: x.product_id || null, unit: x.unit || null, quantity: x.quantity.replace(",", "."),
          requested_discount: x.discount.trim() ? x.discount.replace(",", ".") : null })),
      }, key);
      navigate(`/sales/orders/${data.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };

  return (
    <section className="page" aria-labelledby="titulo-novo-pedido">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/sales/orders">Pedidos</Link> / novo</p>
          <h1 id="titulo-novo-pedido">Novo pedido</h1>
          <p className="muted">Preço e total são calculados pelo servidor com a tabela vigente. O estoque fica reservado ao salvar.</p>
        </div>
      </header>
      <form className="card form-grid" onSubmit={submit} noValidate>
        <Field id="np-tipo" label="Tipo *" error={fieldError(error, "type")}>
          <select id="np-tipo" value={type} onChange={(e) => setType(e.target.value)}>
            {types.map((t) => <option key={t} value={t}>{t === "PICKUP" ? "Retirada no balcão" : "Entrega"}</option>)}
          </select>
        </Field>
        <Field id="np-cliente" label="Cliente *" error={fieldError(error, "customer_id")}>
          <select id="np-cliente" value={customer} onChange={(e) => setCustomer(e.target.value)}>
            <option value="">Selecione</option>
            {customers.data.items.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <fieldset className="span-2 lines">
          <legend className="h3">Itens</legend>
          {items.map((it, i) => {
            const p = products.data.items.find((x) => x.id === it.product_id);
            const unit = it.unit || p?.base_unit;
            const price = p?.prices?.find((x) => x.unit === unit);
            const f = `items[${i}]`;
            return (
              <div className="line-grid" key={i} role="group" aria-label={`Item ${i + 1}`}>
                <Field id={`np-p${i}`} label="Produto *" error={fieldError(error, f + ".product_id")}>
                  <select id={`np-p${i}`} value={it.product_id} onChange={(e) => set(i, { product_id: e.target.value, unit: "" })}>
                    <option value="">Selecione</option>
                    {products.data.items.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                </Field>
                <Field id={`np-u${i}`} label="Medida" error={fieldError(error, f + ".unit")}>
                  <select id={`np-u${i}`} value={it.unit} disabled={!p} onChange={(e) => set(i, { unit: e.target.value })}>
                    {p && <option value="">{unitLabel(p.base_unit)}</option>}
                    {p?.conversions.map((c) => <option key={c.unit} value={c.unit}>{unitLabel(c.unit)}</option>)}
                  </select>
                </Field>
                <Field id={`np-q${i}`} label="Quantidade *" error={fieldError(error, f + ".quantity")}>
                  <input id={`np-q${i}`} inputMode="decimal" value={it.quantity} onChange={(e) => set(i, { quantity: e.target.value })} />
                </Field>
                <div className="field"><span>Preço vigente</span><strong>{p ? (price ? `${formatMoney(price.price)} / ${unitLabel(unit!, "1")}` : "sem preço") : "—"}</strong></div>
                {mayDiscount ? (
                  <Field id={`np-d${i}`} label="Desconto (R$)" error={fieldError(error, f + ".requested_discount")}>
                    <input id={`np-d${i}`} className="money" inputMode="decimal" value={it.discount} onChange={(e) => set(i, { discount: e.target.value })} />
                  </Field>
                ) : <span />}
                <span />
                {items.length > 1 && (
                  <button type="button" className="btn btn-small btn-ghost line-remove" aria-label={`Remover item ${i + 1}`}
                    onClick={() => setItems(items.filter((_, j) => j !== i))}><Icon name="close" size={14} /></button>
                )}
              </div>
            );
          })}
          <button type="button" className="btn btn-small" onClick={() => setItems([...items, empty()])}><Icon name="plus" size={14} /> Adicionar item</button>
        </fieldset>
        <div className="span-2">
          <Field id="np-obs" label="Observação" error={fieldError(error, "notes")}>
            <input id="np-obs" maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </Field>
        </div>
        <FormError error={error} />
        <div className="actions span-2">
          <Link className="btn btn-ghost" to="/sales/orders">Cancelar</Link>
          <button className="btn btn-primary" disabled={busy}>{busy ? "Salvando…" : "Salvar e reservar"}</button>
        </div>
      </form>
    </section>
  );
}
