import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Page, Product, Receipt, Supplier } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDecimal, unitLabel, toDecimal } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

interface LineForm { product_id: string; unit: string; quantity_expected: string; quantity_received: string; lot: string; unit_cost: string }
const emptyLine = (): LineForm => ({ product_id: "", unit: "", quantity_expected: "", quantity_received: "", lot: "", unit_cost: "" });
const dec = toDecimal;

/** W06 — Conferir recebimento de fornecedor. Campos de custo só aparecem para quem tem acesso a custo. */
export function ReceiptFormPage() {
  const { can, canSeeField, me } = useSession();
  const navigate = useNavigate();
  const seesCost = canSeeField("purchase_cost");
  const [suppliers, reloadSuppliers] = useResource<Page<Supplier>>("/api/v1/suppliers?limit=100");
  const [products, reloadProducts] = useResource<Page<Product>>("/api/v1/products?limit=100");
  const [head, setHead] = useState({ supplier_id: "", document_number: "", notes: "", freight_amount: "" });
  const [lines, setLines] = useState<LineForm[]>([emptyLine()]);
  const [createKey] = useState(newIdempotencyKey);
  const [confirmKey] = useState(newIdempotencyKey);
  const [created, setCreated] = useState<Receipt | null>(null);
  const [busy, setBusy] = useState<null | "draft" | "confirm">(null);
  const [error, setError] = useState<ApiError | null>(null);

  if (suppliers.status === "loading" || products.status === "loading") return <LoadingState />;
  if (suppliers.status === "forbidden" || products.status === "forbidden") return <ForbiddenState />;
  if (suppliers.status === "error") return <ErrorState error={suppliers.error} onRetry={reloadSuppliers} />;
  if (products.status === "error") return <ErrorState error={products.error} onRetry={reloadProducts} />;
  const productList = products.data.items;
  const setLine = (i: number, patch: Partial<LineForm>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  const submit = async (mode: "draft" | "confirm", e?: FormEvent) => {
    e?.preventDefault();
    setBusy(mode);
    setError(null);
    try {
      let receipt = created;
      if (!receipt) {
        const res = await api.command<Receipt>("/api/v1/inventory/receipts", {
          supplier_id: head.supplier_id || null,
          document_number: head.document_number.trim() || null,
          notes: head.notes.trim() || null,
          freight_amount: seesCost ? dec(head.freight_amount) : null,
          lines: lines.map((l) => ({
            product_id: l.product_id || null, unit: l.unit || null, quantity_expected: dec(l.quantity_expected),
            quantity_received: dec(l.quantity_received) ?? "", lot: l.lot.trim() || null,
            unit_cost: seesCost ? dec(l.unit_cost) : null,
          })),
        }, createKey);
        receipt = res.data;
        setCreated(receipt); // não recria se a confirmação falhar
      }
      if (mode === "confirm") {
        await api.command<Receipt>(`/api/v1/inventory/receipts/${receipt.id}/confirm`, undefined, confirmKey, `"${receipt.version}"`);
      }
      navigate(`/operation/receipts/${receipt.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(null);
    }
  };

  return (
    <section className="page" aria-labelledby="titulo-receber">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/operation/receipts">Recebimentos</Link> / novo</p>
          <h1 id="titulo-receber">Conferir recebimento de fornecedor</h1>
          <p className="muted">Registre o que realmente chegou. Diferença entre previsto e recebido fica registrada; o estoque não é “completado” para fechar o documento.</p>
        </div>
      </header>
      {created && <p className="notice" role="status">Rascunho salvo. A confirmação ainda não aconteceu.</p>}
      <form className="card form-grid" onSubmit={(e) => void submit("draft", e)} noValidate aria-describedby="rec-ajuda">
        <Field id="rec-fornecedor" label="Fornecedor *" error={fieldError(error, "supplier_id")}>
          <select id="rec-fornecedor" value={head.supplier_id} disabled={!!created} onChange={(e) => setHead({ ...head, supplier_id: e.target.value })}>
            <option value="">Selecione</option>
            {suppliers.data.items.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </Field>
        <Field id="rec-doc" label="Documento (nota ou romaneio)" error={fieldError(error, "document_number")}>
          <input id="rec-doc" maxLength={60} value={head.document_number} disabled={!!created} onChange={(e) => setHead({ ...head, document_number: e.target.value })} />
        </Field>

        <fieldset className="span-2 lines" disabled={!!created}>
          <legend className="h3">Produto e medida · quantidade prevista / recebida</legend>
          {lines.map((l, i) => {
            const p = productList.find((x) => x.id === l.product_id);
            const f = `lines[${i}]`;
            return (
              <div className="line-grid" key={i} role="group" aria-label={`Linha ${i + 1}`}>
                <Field id={`l${i}-prod`} label="Produto *" error={fieldError(error, f + ".product_id")}>
                  <select id={`l${i}-prod`} value={l.product_id} onChange={(e) => setLine(i, { product_id: e.target.value, unit: "" })}>
                    <option value="">Selecione</option>
                    {productList.map((x) => <option key={x.id} value={x.id} disabled={!x.base_unit_confirmed}>{x.name}{x.base_unit_confirmed ? "" : " — unidade não confirmada"}</option>)}
                  </select>
                </Field>
                <Field id={`l${i}-un`} label="Medida" error={fieldError(error, f + ".unit")}>
                  <select id={`l${i}-un`} value={l.unit} disabled={!p} onChange={(e) => setLine(i, { unit: e.target.value })}>
                    {p && <option value="">{unitLabel(p.base_unit)}</option>}
                    {p?.conversions.map((c) => <option key={c.unit} value={c.unit}>{unitLabel(c.unit)} (= {formatDecimal(c.factor)} {unitLabel(p.base_unit, c.factor)})</option>)}
                  </select>
                </Field>
                <Field id={`l${i}-prev`} label="Prevista" error={fieldError(error, f + ".quantity_expected")}>
                  <input id={`l${i}-prev`} inputMode="decimal" value={l.quantity_expected} onChange={(e) => setLine(i, { quantity_expected: e.target.value })} />
                </Field>
                <Field id={`l${i}-rec`} label="Recebida *" error={fieldError(error, f + ".quantity_received")}>
                  <input id={`l${i}-rec`} inputMode="decimal" value={l.quantity_received} onChange={(e) => setLine(i, { quantity_received: e.target.value })} />
                </Field>
                <Field id={`l${i}-lote`} label="Lote" error={fieldError(error, f + ".lot")}>
                  <input id={`l${i}-lote`} maxLength={60} value={l.lot} onChange={(e) => setLine(i, { lot: e.target.value })} />
                </Field>
                {seesCost && (
                  <Field id={`l${i}-custo`} label="Custo unitário (R$)" error={fieldError(error, f + ".unit_cost")}>
                    <input id={`l${i}-custo`} className="money" inputMode="decimal" value={l.unit_cost} onChange={(e) => setLine(i, { unit_cost: e.target.value })} />
                  </Field>
                )}
                {lines.length > 1 && (
                  <button type="button" className="btn btn-small btn-ghost line-remove" onClick={() => setLines(lines.filter((_, j) => j !== i))} aria-label={`Remover linha ${i + 1}`}>
                    <Icon name="close" size={14} />
                  </button>
                )}
              </div>
            );
          })}
          <button type="button" className="btn btn-small" onClick={() => setLines([...lines, emptyLine()])}><Icon name="plus" size={14} /> Adicionar produto</button>
        </fieldset>

        {seesCost ? (
          <Field id="rec-frete" label="Frete de compra autorizado (R$)" hint="Rateado nas linhas pelo valor e incorporado ao custo; não vira despesa." error={fieldError(error, "freight_amount")}>
            <input id="rec-frete" className="money" inputMode="decimal" value={head.freight_amount} disabled={!!created} onChange={(e) => setHead({ ...head, freight_amount: e.target.value })} />
          </Field>
        ) : (
          <p className="notice span-2">Custo e frete ficam com quem tem acesso a custo de compra. Você registra as quantidades; a conferência completa o custo.</p>
        )}
        <Field id="rec-conferente" label="Conferente">
          <input id="rec-conferente" value={me?.user.display_name ?? ""} readOnly aria-readonly="true" />
        </Field>
        <div className="span-2">
          <Field id="rec-obs" label="Observação" error={fieldError(error, "notes")}>
            <input id="rec-obs" maxLength={500} value={head.notes} disabled={!!created} onChange={(e) => setHead({ ...head, notes: e.target.value })} />
          </Field>
        </div>
        <p id="rec-ajuda" className="muted small span-2">Validação no servidor · autor e histórico preservados.</p>
        <FormError error={error} />
        <div className="actions span-2">
          <button className="btn" disabled={busy !== null}>{busy === "draft" ? "Salvando…" : created ? "Abrir rascunho" : "Salvar rascunho"}</button>
          {can("inventory.receipts.confirm") && (
            <button type="button" className="btn btn-primary" disabled={busy !== null} onClick={() => void submit("confirm")}>
              {busy === "confirm" ? "Confirmando…" : "Confirmar entrada"}
            </button>
          )}
        </div>
      </form>
    </section>
  );
}
