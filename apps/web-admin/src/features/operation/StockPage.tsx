import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Balance, Loss, LossReason, Page, Product, StockMovement } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, formatQty, unitLabel, toDecimal } from "../../app/format";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ConflictState, EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { AdjustmentsSection, CountForm } from "./Adjustments";

interface Summary { totals: { base_unit: string; physical: string; reserved: string; available: string; lost: string; products: number }[] }

export const LOSS_REASONS: Record<LossReason, string> = {
  QUEBRA: "Quebra", DETERIORACAO: "Deterioração", AVARIA_TRANSPORTE: "Avaria no transporte", FURTO: "Furto",
  ERRO_CONTAGEM: "Erro de contagem", OUTRO: "Outro",
};
const LOSS_STATUS: Record<Loss["status"], string> = { PENDING: "Aguardando aprovação", APPROVED: "Aprovada", REJECTED: "Recusada", REVERSED: "Estornada" };
export const MOVEMENT_KIND: Record<StockMovement["kind"], string> = {
  RECEIPT: "Entrada de fornecedor", SUPPLIER_RETURN: "Devolução ao fornecedor", LOSS: "Perda", LOSS_REVERSAL: "Estorno de perda",
  SALE_ISSUE: "Saída por venda", SALE_RETURN: "Devolução de cliente", ADJUSTMENT_IN: "Ajuste de contagem (sobra)",
  ADJUSTMENT_OUT: "Ajuste de contagem (falta)",
};

/** W05 — Estoque, reservas e fornecedores. Totais e saldos vêm do servidor; custo só com acesso a custo. */
export function StockPage() {
  const { can } = useSession();
  const [summary, reloadSummary] = useResource<Summary>("/api/v1/inventory/balances/summary");
  const [balances, reloadBalances] = useResource<Page<Balance>>("/api/v1/inventory/balances?limit=100");
  const [registering, setRegistering] = useState(false);
  const [openProduct, setOpenProduct] = useState<string | null>(null);
  const [lossesVersion, setLossesVersion] = useState(0);
  const [counting, setCounting] = useState(false);
  const reload = () => { reloadSummary(); reloadBalances(); };

  return (
    <section className="page" aria-labelledby="titulo-estoque">
      <header className="page-head">
        <div>
          <h1 id="titulo-estoque">Estoque, reservas e fornecedores</h1>
          <p className="muted">Disponível = físico − reservado. Pedidos reservam; só a retirada ou entrega baixa o físico.</p>
        </div>
        <div className="actions">
          {can("inventory.receipts.create") && (
            <Link className="btn btn-primary" to="/operation/receipts/new"><Icon name="box" size={16} /> Receber mercadoria</Link>
          )}
          {can("inventory.losses.create") && !registering && (
            <button className="btn" onClick={() => setRegistering(true)}><Icon name="loss" size={16} /> Registrar perda</button>
          )}
          {can("inventory.losses.create") && !counting && (
            <button className="btn" onClick={() => setCounting(true)}><Icon name="inventory" size={16} /> Registrar contagem</button>
          )}
        </div>
      </header>

      {summary.status === "loading" && <LoadingState />}
      {summary.status === "forbidden" && <ForbiddenState />}
      {summary.status === "error" && <ErrorState error={summary.error} onRetry={reloadSummary} />}
      {summary.status === "ready" && summary.data.totals.map((t) => (
        <div className="kpis kpis-4" key={t.base_unit} aria-label={`Totais em ${unitLabel(t.base_unit)}`}>
          <Kpi label="Físico" value={formatQty(t.physical, t.base_unit)} />
          <Kpi label="Reservado" value={formatQty(t.reserved, t.base_unit)} />
          <Kpi label="Disponível" value={formatQty(t.available, t.base_unit)} highlight />
          <Kpi label="Perdas" value={formatQty(t.lost, t.base_unit)} />
        </div>
      ))}

      {registering && <LossForm onDone={(ok) => { setRegistering(false); if (ok) { reload(); setLossesVersion((v) => v + 1); } }} />}

      {counting && <CountForm onDone={(ok) => { setCounting(false); if (ok) setLossesVersion((v) => v + 1); }} />}

      <h2 className="h3">Saldo por produto</h2>
      {balances.status === "loading" && <LoadingState />}
      {balances.status === "error" && <ErrorState error={balances.error} onRetry={reloadBalances} />}
      {balances.status === "ready" && (balances.data.items.length === 0 ? (
        <EmptyState title="Nenhum produto com estoque nesta unidade">
          {can("inventory.receipts.create") && <p>Use “Receber mercadoria” para registrar a primeira entrada.</p>}
        </EmptyState>
      ) : (
        <div className="table-wrap card">
          <table className="stack">
            <thead>
              <tr>
                <th scope="col">Produto</th><th scope="col">Unidade</th><th scope="col" className="num">Físico</th>
                <th scope="col" className="num">Reservado</th><th scope="col" className="num">Disponível</th>
                <th scope="col" className="num">Perdas</th><th scope="col" className="num">Valor em estoque</th>
                <th scope="col" className="num">Custo médio</th><th scope="col"><span className="visually-hidden">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {balances.data.items.map((b) => (
                <BalanceRow key={b.product_id + b.branch_id} b={b} open={openProduct === b.product_id + b.branch_id}
                  onToggle={() => setOpenProduct(openProduct === b.product_id + b.branch_id ? null : b.product_id + b.branch_id)} />
              ))}
            </tbody>
          </table>
        </div>
      ))}

      {can("inventory.losses.view") && <LossesSection key={lossesVersion} onChange={reload} />}
      {can("inventory.losses.view") && <AdjustmentsSection key={"a" + lossesVersion} onChange={reload} />}
    </section>
  );
}

function Kpi({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className={"card kpi" + (highlight ? " kpi-highlight" : "")}>
      <span className="kpi-label">{label}</span>
      <span className="kpi-value">{value}</span>
    </div>
  );
}

function BalanceRow({ b, open, onToggle }: { b: Balance; open: boolean; onToggle: () => void }) {
  const costHidden = b.masked_fields.includes("value");
  return (
    <>
      <tr>
        <th scope="row">{b.product_name} <span className="muted small">{b.sku}</span></th>
        <td data-label="Unidade">{b.branch}</td>
        <td data-label="Físico" className="num">{formatQty(b.physical, b.base_unit)}</td>
        <td data-label="Reservado" className="num">{formatQty(b.reserved, b.base_unit)}</td>
        <td data-label="Disponível" className="num"><strong>{formatQty(b.available, b.base_unit)}</strong></td>
        <td data-label="Perdas" className="num">{formatQty(b.lost, b.base_unit)}</td>
        <td data-label="Valor em estoque" className="num">{costHidden ? <Hidden /> : formatMoney(b.value)}</td>
        <td data-label="Custo médio" className="num">{costHidden ? <Hidden /> : b.average_cost ? `R$ ${formatDecimal(b.average_cost)}` : "—"}</td>
        <td><button className="btn btn-small btn-ghost" aria-expanded={open} onClick={onToggle}><Icon name="history" size={14} /> Movimentos</button></td>
      </tr>
      {open && (
        <tr className="detail-row"><td colSpan={9}><Movements productId={b.product_id} /></td></tr>
      )}
    </>
  );
}

function Movements({ productId }: { productId: string }) {
  const [res, reload] = useResource<Page<StockMovement>>(`/api/v1/inventory/movements?limit=25&product_id=${productId}`);
  if (res.status === "loading") return <LoadingState label="Carregando movimentos" />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  if (res.data.items.length === 0) return <p className="muted">Sem movimentos.</p>;
  return (
    <ul className="timeline" aria-label="Últimos movimentos do produto">
      {res.data.items.map((m) => (
        <li key={m.id}>
          <span className={"badge " + (m.quantity.startsWith("-") ? "badge-error" : "badge-ok")}>{MOVEMENT_KIND[m.kind]}</span>
          <strong className="num">{formatQty(m.quantity, m.base_unit)}</strong>
          <span className="muted">saldo {formatQty(m.balance_after, m.base_unit)}</span>
          {!m.masked_fields.includes("total_cost") && <span className="muted">{formatMoney(m.total_cost)}</span>}
          {m.reversal_of && <span className="badge badge-violet">vinculado ao movimento de origem</span>}
          {m.reason && <span className="muted">“{m.reason}”</span>}
          <span className="muted small">{m.created_by_name} · {formatDateTime(m.occurred_at)}</span>
        </li>
      ))}
    </ul>
  );
}

function LossForm({ onDone }: { onDone: (ok: boolean) => void }) {
  const [products] = useResource<Page<Product>>("/api/v1/products?limit=100");
  const [form, setForm] = useState({ product_id: "", unit: "", quantity: "", reason: "" as LossReason | "", notes: "" });
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const product = products.status === "ready" ? products.data.items.find((p) => p.id === form.product_id) : undefined;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.command<Loss>("/api/v1/inventory/losses", {
        product_id: form.product_id || null, unit: form.unit || null, quantity: toDecimal(form.quantity),
        reason: form.reason || null, notes: form.notes.trim() || null,
      }, key);
      onDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };

  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-perda" noValidate>
      <h2 id="titulo-perda" className="h3 span-2">Registrar perda</h2>
      <p className="muted span-2">O registro não baixa o estoque: a baixa acontece quando alguém com alçada aprova, pelo custo médio do momento.</p>
      <Field id="perda-produto" label="Produto *" error={fieldError(error, "loss.product_id")}>
        <select id="perda-produto" value={form.product_id} onChange={(e) => setForm({ ...form, product_id: e.target.value, unit: "" })}>
          <option value="">Selecione</option>
          {products.status === "ready" && products.data.items.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.sku})</option>)}
        </select>
      </Field>
      <Field id="perda-medida" label="Medida" error={fieldError(error, "loss.unit")}>
        <select id="perda-medida" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} disabled={!product}>
          {product && <option value="">{unitLabel(product.base_unit)} (base)</option>}
          {product?.conversions.map((c) => <option key={c.unit} value={c.unit}>{unitLabel(c.unit)} (1 = {formatDecimal(c.factor)} {unitLabel(product.base_unit, c.factor)})</option>)}
        </select>
      </Field>
      <Field id="perda-qtd" label="Quantidade *" error={fieldError(error, "quantity")}>
        <input id="perda-qtd" inputMode="decimal" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
      </Field>
      <Field id="perda-motivo" label="Motivo *" error={fieldError(error, "reason")}>
        <select id="perda-motivo" value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value as LossReason })}>
          <option value="">Selecione</option>
          {Object.entries(LOSS_REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
      <div className="span-2">
        <Field id="perda-obs" label={form.reason === "OUTRO" ? "Descrição *" : "Observação"} error={fieldError(error, "notes")}>
          <input id="perda-obs" maxLength={500} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
        </Field>
      </div>
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy}>{busy ? "Registrando…" : "Registrar perda"}</button>
      </div>
    </form>
  );
}

function LossesSection({ onChange }: { onChange: () => void }) {
  const { can } = useSession();
  const [status, setStatus] = useState<"" | Loss["status"]>("PENDING");
  const [res, reload] = useResource<Page<Loss>>(`/api/v1/inventory/losses?limit=50${status ? `&status=${status}` : ""}`);
  const [conflict, setConflict] = useState(false);
  useEffect(() => setConflict(false), [status]);

  return (
    <section aria-labelledby="titulo-perdas" className="page">
      <div className="page-head">
        <h2 id="titulo-perdas" className="h3">Perdas</h2>
        <div className="field inline">
          <label htmlFor="perdas-filtro">Situação</label>
          <select id="perdas-filtro" value={status} onChange={(e) => setStatus(e.target.value as Loss["status"] | "")}>
            <option value="PENDING">Aguardando aprovação</option>
            <option value="APPROVED">Aprovadas</option>
            <option value="REJECTED">Recusadas</option>
            <option value="REVERSED">Estornadas</option>
            <option value="">Todas</option>
          </select>
        </div>
      </div>
      {conflict && <ConflictState onReload={() => { setConflict(false); reload(); }} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhuma perda nesta situação" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead>
              <tr><th scope="col">Produto</th><th scope="col">Quantidade</th><th scope="col">Motivo</th><th scope="col">Custo</th>
                <th scope="col">Registro</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr>
            </thead>
            <tbody>
              {res.data.items.map((l) => (
                <tr key={l.id}>
                  <th scope="row">{l.product_name}</th>
                  <td data-label="Quantidade">{formatQty(l.quantity, l.unit)}</td>
                  <td data-label="Motivo">{LOSS_REASONS[l.reason]}{l.notes && <span className="muted small"> — {l.notes}</span>}</td>
                  <td data-label="Custo">{l.masked_fields.includes("total_cost") ? <Hidden /> : formatMoney(l.total_cost)}</td>
                  <td data-label="Registro">{l.created_by_name}<br /><span className="muted small">{formatDateTime(l.created_at)}</span></td>
                  <td data-label="Situação">{LOSS_STATUS[l.status]}{l.decided_by_name && <span className="muted small"><br />por {l.decided_by_name}</span>}
                    {l.decision_reason && <span className="muted small"><br />“{l.decision_reason}”</span>}</td>
                  <td>{can("inventory.losses.approve") && <LossActions loss={l} onDone={() => { reload(); onChange(); }} onConflict={() => setConflict(true)} />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function LossActions({ loss, onDone, onConflict }: { loss: Loss; onDone: () => void; onConflict: () => void }) {
  const [mode, setMode] = useState<null | "reject" | "reverse">(null);
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const run = async (action: "approve" | "reject" | "reverse") => {
    setBusy(true);
    setError(null);
    try {
      await api.command<Loss>(`/api/v1/inventory/losses/${loss.id}/${action}`, action === "approve" ? undefined : { reason }, key, `"${loss.version}"`);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      const e = err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" });
      if (e.code === "VERSION_CONFLICT") onConflict();
      setError(e);
      setBusy(false);
    }
  };

  if (loss.status !== "PENDING" && loss.status !== "APPROVED") return null;
  return (
    <div className="row-actions">
      {mode === null && loss.status === "PENDING" && (
        <>
          <button className="btn btn-small btn-primary" disabled={busy} onClick={() => void run("approve")}><Icon name="approve" size={14} /> Aprovar baixa</button>
          <button className="btn btn-small" disabled={busy} onClick={() => setMode("reject")}>Recusar</button>
        </>
      )}
      {mode === null && loss.status === "APPROVED" && (
        <button className="btn btn-small" onClick={() => setMode("reverse")}>Estornar</button>
      )}
      {mode !== null && (
        <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void run(mode); }}>
          <label className="visually-hidden" htmlFor={"motivo-" + loss.id}>Motivo</label>
          <input id={"motivo-" + loss.id} placeholder="Motivo *" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
          <button className="btn btn-small btn-primary" disabled={busy || !reason.trim()}>{mode === "reject" ? "Recusar" : "Estornar"}</button>
          <button type="button" className="btn btn-small btn-ghost" onClick={() => setMode(null)}>Cancelar</button>
        </form>
      )}
      {error && error.code !== "VERSION_CONFLICT" && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}
