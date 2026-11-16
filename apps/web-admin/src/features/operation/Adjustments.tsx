import { useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Adjustment, Page, Product } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, formatQty, unitLabel, toDecimal } from "../../app/format";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { ConflictState, EmptyState, ErrorState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

/** Contagem física: registra contado × sistema; a aprovação aplica só a diferença (sobra/falta) pelo custo médio. */
export function CountForm({ onDone }: { onDone: (ok: boolean) => void }) {
  const { canSeeField } = useSession();
  const [products] = useResource<Page<Product>>("/api/v1/products?limit=100");
  const [form, setForm] = useState({ product_id: "", unit: "", counted_quantity: "", unit_cost: "", notes: "" });
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const product = products.status === "ready" ? products.data.items.find((p) => p.id === form.product_id) : undefined;
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.command<Adjustment>("/api/v1/inventory/adjustments", {
        product_id: form.product_id || null, unit: form.unit || null, counted_quantity: toDecimal(form.counted_quantity),
        unit_cost: toDecimal(form.unit_cost), notes: form.notes.trim(),
      }, key);
      onDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-contagem" noValidate>
      <h2 id="titulo-contagem" className="h3 span-2">Registrar contagem</h2>
      <p className="muted span-2">Informe quanto foi contado. O sistema guarda o físico deste momento; ao aprovar, só a diferença é lançada.</p>
      <Field id="ct-produto" label="Produto *" error={fieldError(error, "adjustment.product_id")}>
        <select id="ct-produto" value={form.product_id} onChange={(e) => setForm({ ...form, product_id: e.target.value, unit: "" })}>
          <option value="">Selecione</option>
          {products.status === "ready" && products.data.items.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.sku})</option>)}
        </select>
      </Field>
      <Field id="ct-medida" label="Medida" error={fieldError(error, "adjustment.unit")}>
        <select id="ct-medida" value={form.unit} disabled={!product} onChange={(e) => setForm({ ...form, unit: e.target.value })}>
          {product && <option value="">{unitLabel(product.base_unit)} (base)</option>}
          {product?.conversions.map((c) => <option key={c.unit} value={c.unit}>{unitLabel(c.unit)}</option>)}
        </select>
      </Field>
      <Field id="ct-qtd" label="Quantidade contada *" error={fieldError(error, "counted_quantity")}>
        <input id="ct-qtd" inputMode="decimal" value={form.counted_quantity} onChange={(e) => setForm({ ...form, counted_quantity: e.target.value })} />
      </Field>
      {canSeeField("purchase_cost") && (
        <Field id="ct-custo" label="Custo unitário (só se o estoque estiver zerado)" error={fieldError(error, "unit_cost")}>
          <input id="ct-custo" className="money" inputMode="decimal" value={form.unit_cost} onChange={(e) => setForm({ ...form, unit_cost: e.target.value })} />
        </Field>
      )}
      <div className="span-2">
        <Field id="ct-obs" label="Observação da contagem *" error={fieldError(error, "notes")}>
          <input id="ct-obs" maxLength={500} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
        </Field>
      </div>
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy}>{busy ? "Registrando…" : "Registrar contagem"}</button>
      </div>
    </form>
  );
}

const STATUS: Record<Adjustment["status"], string> = { PENDING: "Aguardando aprovação", APPROVED: "Aplicado", REJECTED: "Recusado" };

export function AdjustmentsSection({ onChange }: { onChange: () => void }) {
  const { can } = useSession();
  const [res, reload] = useResource<Page<Adjustment>>("/api/v1/inventory/adjustments?limit=50");
  const [conflict, setConflict] = useState(false);
  return (
    <section aria-labelledby="titulo-ajustes" className="page">
      <h2 id="titulo-ajustes" className="h3">Ajustes por contagem</h2>
      {conflict && <ConflictState onReload={() => { setConflict(false); reload(); }} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhuma contagem registrada" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Produto</th><th scope="col">Contado</th><th scope="col">Sistema</th><th scope="col">Diferença</th>
              <th scope="col">Custo</th><th scope="col">Registro</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{res.data.items.map((a) => (
              <tr key={a.id}>
                <th scope="row">{a.product_name}<br /><span className="muted small">{a.notes}</span></th>
                <td data-label="Contado">{formatQty(a.counted_quantity, a.base_unit)}</td>
                <td data-label="Sistema">{formatQty(a.system_quantity, a.base_unit)}</td>
                <td data-label="Diferença" className={a.difference.startsWith("-") ? "text-warning" : ""}>{a.difference.startsWith("-") ? "" : "+"}{formatDecimal(a.difference)}</td>
                <td data-label="Custo">{a.masked_fields.includes("total_cost") ? <Hidden /> : formatMoney(a.total_cost)}</td>
                <td data-label="Registro">{a.created_by_name}<br /><span className="muted small">{formatDateTime(a.created_at)}</span></td>
                <td data-label="Situação">{STATUS[a.status]}{a.decision_reason && <span className="muted small"><br />“{a.decision_reason}”</span>}</td>
                <td>{can("inventory.losses.approve") && a.status === "PENDING" &&
                  <AdjustmentActions a={a} onDone={() => { reload(); onChange(); }} onConflict={() => setConflict(true)} />}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function AdjustmentActions({ a, onDone, onConflict }: { a: Adjustment; onDone: () => void; onConflict: () => void }) {
  const [key, setKey] = useState(newIdempotencyKey);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const run = async (action: "approve" | "reject") => {
    setBusy(true);
    setError(null);
    try {
      await api.command(`/api/v1/inventory/adjustments/${a.id}/${action}`, action === "approve" ? undefined : { reason }, key, `"${a.version}"`);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      const e = err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" });
      if (e.code === "VERSION_CONFLICT") onConflict();
      setError(e);
      setBusy(false);
    }
  };
  return (
    <div className="row-actions">
      {!rejecting ? (
        <>
          <button className="btn btn-small btn-primary" disabled={busy} onClick={() => void run("approve")}>Aplicar ajuste</button>
          <button className="btn btn-small" disabled={busy} onClick={() => setRejecting(true)}>Recusar</button>
        </>
      ) : (
        <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void run("reject"); }}>
          <label className="visually-hidden" htmlFor={"aj-motivo-" + a.id}>Motivo</label>
          <input id={"aj-motivo-" + a.id} placeholder="Motivo *" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
          <button className="btn btn-small btn-primary" disabled={busy || !reason.trim()}>Recusar</button>
        </form>
      )}
      {error && error.code !== "VERSION_CONFLICT" && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}
