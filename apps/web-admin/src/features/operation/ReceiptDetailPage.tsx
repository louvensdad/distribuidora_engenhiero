import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Receipt, ReceiptLine } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, formatQty } from "../../app/format";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { ConflictState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { RECEIPT_STATUS } from "./ReceiptsPage";

export function ReceiptDetailPage() {
  const { id } = useParams();
  const { can, canSeeField } = useSession();
  const [res, reload] = useResource<Receipt>(`/api/v1/inventory/receipts/${id}`);
  if (res.status === "loading") return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  const r = res.data;
  const costHidden = r.masked_fields.includes("total_cost");

  return (
    <section className="page" aria-labelledby="titulo-recebimento">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/operation/receipts">Recebimentos</Link> / {r.document_number ?? "sem documento"}</p>
          <h1 id="titulo-recebimento">{r.supplier_name}</h1>
          <p className="muted">
            {r.branch} · registrado por {r.created_by_name} em {formatDateTime(r.received_at)}
            {r.confirmed_by_name && <> · conferido por {r.confirmed_by_name} em {formatDateTime(r.confirmed_at!)}</>}
          </p>
        </div>
        <span className={"badge " + (r.status === "CONFIRMED" ? "badge-ok" : "badge-violet")}>{RECEIPT_STATUS[r.status]}</span>
      </header>

      <div className="table-wrap card">
        <table className="stack">
          <thead><tr><th scope="col">#</th><th scope="col">Produto</th><th scope="col">Prevista</th><th scope="col">Recebida</th>
            <th scope="col">Diferença</th><th scope="col">Lote</th><th scope="col">Custo unit.</th><th scope="col">Mercadoria</th>
            <th scope="col">Frete rateado</th><th scope="col">Devolvido</th></tr></thead>
          <tbody>
            {r.lines.map((l) => (
              <tr key={l.line_no}>
                <td data-label="#">{l.line_no}</td>
                <th scope="row">{l.product_name} <span className="muted small">{l.sku}</span></th>
                <td data-label="Prevista">{l.quantity_expected ? formatQty(l.quantity_expected, l.unit) : "—"}</td>
                <td data-label="Recebida">{formatQty(l.quantity_received, l.unit)}{l.unit !== l.base_unit && <span className="muted small"><br />= {formatQty(l.base_quantity, l.base_unit)}</span>}</td>
                <td data-label="Diferença" className={l.difference && l.difference !== "0" ? "text-warning" : ""}>{l.difference ? formatDecimal(l.difference) : "—"}</td>
                <td data-label="Lote">{l.lot ?? "—"}</td>
                <td data-label="Custo unit.">{costHidden ? <Hidden /> : l.unit_cost ? `R$ ${formatDecimal(l.unit_cost)}` : "pendente"}</td>
                <td data-label="Mercadoria">{costHidden ? <Hidden /> : formatMoney(l.goods_cost)}</td>
                <td data-label="Frete rateado">{costHidden ? <Hidden /> : formatMoney(l.freight_share)}</td>
                <td data-label="Devolvido">{l.returned_base_quantity === "0" ? "—" : formatQty(l.returned_base_quantity, l.base_unit)}</td>
              </tr>
            ))}
          </tbody>
          {r.status === "CONFIRMED" && !costHidden && (
            <tfoot><tr><th scope="row" colSpan={7}>Total (mercadoria + frete {formatMoney(r.freight_amount)})</th><td colSpan={3}><strong>{formatMoney(r.total_cost)}</strong></td></tr></tfoot>
          )}
        </table>
      </div>
      {r.notes && <p className="muted">Observação: {r.notes}</p>}

      {r.status === "DRAFT" && can("inventory.receipts.confirm") && (
        <ConfirmForm receipt={r} seesCost={canSeeField("purchase_cost")} onDone={reload} />
      )}
      {r.status === "CONFIRMED" && can("inventory.receipts.reverse") && <ReturnForm receipt={r} onDone={reload} />}
    </section>
  );
}

function ConfirmForm({ receipt, seesCost, onDone }: { receipt: Receipt; seesCost: boolean; onDone: () => void }) {
  const [costs, setCosts] = useState<Record<number, string>>(() =>
    Object.fromEntries(receipt.lines.map((l) => [l.line_no, l.unit_cost ?? ""])));
  const [freight, setFreight] = useState(receipt.freight_amount ?? "");
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const pending = receipt.lines.filter((l) => l.unit_cost == null && l.quantity_received !== "0");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = seesCost ? {
      freight_amount: freight.trim() ? freight.trim().replace(",", ".") : null,
      lines: receipt.lines.filter((l) => (costs[l.line_no] ?? "").trim()).map((l) => ({ line_no: l.line_no, unit_cost: costs[l.line_no]!.trim().replace(",", ".") })),
    } : undefined;
    try {
      await api.command<Receipt>(`/api/v1/inventory/receipts/${receipt.id}/confirm`, body, key, `"${receipt.version}"`);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };

  if (error?.code === "VERSION_CONFLICT") return <ConflictState onReload={onDone} />;
  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-conferir" noValidate>
      <h2 id="titulo-conferir" className="h3 span-2">Conferir e confirmar entrada</h2>
      <p className="muted span-2">Ao confirmar, as quantidades recebidas entram no estoque físico e o custo médio é recalculado. Recebimento confirmado não é editado; correções viram devolução.</p>
      {seesCost ? (
        <>
          {receipt.lines.map((l: ReceiptLine) => (
            <Field key={l.line_no} id={`c-${l.line_no}`} label={`Custo unitário — ${l.product_name} (por ${l.unit.toLowerCase()})`}
              error={fieldError(error, `lines`)}>
              <input id={`c-${l.line_no}`} className="money" inputMode="decimal" value={costs[l.line_no] ?? ""}
                onChange={(e) => setCosts({ ...costs, [l.line_no]: e.target.value })} />
            </Field>
          ))}
          <Field id="c-frete" label="Frete de compra autorizado (R$)" hint="Rateado pelo valor da mercadoria; a diferença de centavos vai para a última linha." error={fieldError(error, "freight_amount")}>
            <input id="c-frete" className="money" inputMode="decimal" value={freight} onChange={(e) => setFreight(e.target.value)} />
          </Field>
        </>
      ) : pending.length > 0 ? (
        <p className="notice span-2">Há {pending.length} linha(s) sem custo. Quem tem acesso a custo de compra precisa conferir antes da entrada.</p>
      ) : (
        <p className="notice span-2">Custo já informado por quem tem acesso; você confirma as quantidades.</p>
      )}
      <FormError error={error} />
      <div className="actions span-2">
        <button className="btn btn-primary" disabled={busy || (!seesCost && pending.length > 0)}>{busy ? "Confirmando…" : "Confirmar entrada"}</button>
      </div>
    </form>
  );
}

function ReturnForm({ receipt, onDone }: { receipt: Receipt; onDone: () => void }) {
  const [form, setForm] = useState({ line_no: "", quantity: "", reason: "" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.command<{ total_cost: string | null; base_quantity: string }>(`/api/v1/inventory/receipts/${receipt.id}/returns`,
        { line_no: Number(form.line_no), quantity: form.quantity.replace(",", "."), reason: form.reason }, key);
      setDone(`Devolução registrada${data.total_cost ? `: ${formatMoney(data.total_cost)} ao custo histórico` : ""}.`);
      setKey(newIdempotencyKey());
      setForm({ line_no: "", quantity: "", reason: "" });
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-devolver" noValidate>
      <h2 id="titulo-devolver" className="h3 span-2">Devolver ao fornecedor</h2>
      <p className="muted span-2">Sai do estoque pelo custo histórico desta entrada, vinculado ao movimento original.</p>
      <Field id="d-linha" label="Linha *" error={fieldError(error, "line_no")}>
        <select id="d-linha" value={form.line_no} onChange={(e) => setForm({ ...form, line_no: e.target.value })}>
          <option value="">Selecione</option>
          {receipt.lines.map((l) => <option key={l.line_no} value={l.line_no}>{l.line_no}. {l.product_name} ({l.unit.toLowerCase()})</option>)}
        </select>
      </Field>
      <Field id="d-qtd" label="Quantidade (na medida da linha) *" error={fieldError(error, "quantity")}>
        <input id="d-qtd" inputMode="decimal" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
      </Field>
      <div className="span-2">
        <Field id="d-motivo" label="Motivo *" error={fieldError(error, "reason")}>
          <input id="d-motivo" maxLength={500} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />
        </Field>
      </div>
      <FormError error={error} />
      {done && <p className="notice span-2" role="status">{done}</p>}
      <div className="actions span-2"><button className="btn" disabled={busy}>{busy ? "Registrando…" : "Registrar devolução"}</button></div>
    </form>
  );
}
