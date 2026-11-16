import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import { AUDIT_ACTION_LABELS, CLIENT_LABELS } from "../../api/labels";
import type { Order, OrderHistoryItem, Payment, PaymentMethod } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, unitLabel } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { ConflictState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { METHOD, ORDER_STATE, PAYMENT_STATE, PAY_STATE, TYPE } from "./orderLabels";

const isOpen = (o: Order) => ["CONFIRMED", "READY", "PARTIALLY_FULFILLED"].includes(o.state);
const positive = (v: string) => !v.startsWith("-") && Number(v) > 0; // só para exibir botões; o servidor valida

/** W04 — pedido com cada etapa e responsável: itens, pagamento, retirada, cancelamento, devolução e histórico. */
export function OrderDetailPage() {
  const { id } = useParams();
  const { can } = useSession();
  const [res, reload] = useResource<Order>(`/api/v1/orders/${id}`);
  const [pays, reloadPays] = useResource<{ items: Payment[] }>(can("payments.view") ? `/api/v1/orders/${id}/payments` : null);
  const [hist, reloadHist] = useResource<{ items: OrderHistoryItem[] }>(`/api/v1/orders/${id}/history`);
  const refresh = () => { reload(); reloadPays(); reloadHist(); };
  const [lastPaid, setLastPaid] = useState<Payment[] | null>(null);
  if (res.status === "loading" && !lastPaid) return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  if (res.status !== "ready") return <LoadingState />;
  const o = res.data;
  const r = o.type === "PICKUP" ? "orders.pickup" : "orders.delivery";

  return (
    <section className="page" aria-labelledby="titulo-pedido">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/sales/orders">Pedidos</Link> / #{o.number}</p>
          <h1 id="titulo-pedido">Pedido #{o.number} · {o.customer_name}</h1>
          <p className="muted">{TYPE[o.type]} · {o.branch} · cadastrado por {o.created_by_name} em {formatDateTime(o.created_at)}</p>
        </div>
        <div className="actions">
          <span className="badge badge-violet">{ORDER_STATE[o.state]}</span>
          <span className={"badge " + (o.payment_state === "PAID" ? "badge-ok" : o.payment_state === "REFUND_DUE" ? "badge-error" : "badge-violet")}>{PAYMENT_STATE[o.payment_state]}</span>
        </div>
      </header>

      <div className="kpis kpis-4">
        <div className="card kpi"><span className="kpi-label">Total do pedido</span><span className="kpi-value">{formatMoney(o.effective_total)}</span></div>
        <div className="card kpi"><span className="kpi-label">Pago (confirmado)</span><span className="kpi-value">{formatMoney(o.paid_amount)}</span></div>
        <div className="card kpi"><span className="kpi-label">Aguardando conferência</span><span className="kpi-value">{formatMoney(o.pending_amount)}</span></div>
        <div className="card kpi kpi-highlight"><span className="kpi-label">{o.balance_due.startsWith("-") ? "Devolver ao cliente" : "Saldo devedor"}</span>
          <span className="kpi-value">{formatMoney(o.balance_due.replace("-", ""))}</span></div>
      </div>

      <div className="table-wrap card">
        <table className="stack">
          <thead><tr><th scope="col">Item</th><th scope="col">Quantidade</th><th scope="col" className="num">Preço</th><th scope="col" className="num">Desconto</th>
            <th scope="col" className="num">Valor</th><th scope="col">Entregue</th><th scope="col">Pendente</th><th scope="col">Devolvido</th></tr></thead>
          <tbody>{o.lines.map((l) => (
            <tr key={l.line_no}>
              <th scope="row">{l.product_name}</th>
              <td data-label="Quantidade">{formatDecimal(l.quantity)} {unitLabel(l.unit, l.quantity)}</td>
              <td data-label="Preço" className="num">{formatMoney(l.unit_price)}</td>
              <td data-label="Desconto" className="num">{formatMoney(l.discount)}</td>
              <td data-label="Valor" className="num">{formatMoney(l.net)}</td>
              <td data-label="Entregue">{formatDecimal(l.fulfilled_quantity)}</td>
              <td data-label="Pendente">{formatDecimal(l.remaining_quantity)}{l.cancelled_quantity !== "0" && <span className="muted small"> (cancelado {formatDecimal(l.cancelled_quantity)})</span>}</td>
              <td data-label="Devolvido">{formatDecimal(l.returned_quantity)}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {o.cancel_reason && <p className="notice">Cancelamento: {o.cancel_reason}</p>}

      {lastPaid && (
        <div className="notice" role="status" aria-label="Último pagamento">
          {lastPaid.map((p) => (
            <p key={p.id} style={{ margin: 0 }}>{METHOD[p.method]} {formatMoney(p.amount)} — {PAY_STATE[p.state]}
              {p.change_given && p.change_given !== "0.00" && <strong className="big"> · Troco a entregar: {formatMoney(p.change_given)}</strong>}</p>
          ))}
        </div>
      )}
      {isOpen(o) && positive(o.balance_due) && (can("payments.create") || can("payments.confirm")) && (
        <PaymentForm order={o} onDone={(paid) => { setLastPaid(paid); refresh(); }} />
      )}
      {pays.status === "ready" && pays.data.items.length > 0 && <Payments items={pays.data.items} onChange={refresh} />}
      <Operations order={o} resource={r} onDone={refresh} />

      <section className="card" aria-labelledby="titulo-hist-pedido">
        <h2 id="titulo-hist-pedido" className="h3">Histórico do pedido</h2>
        {hist.status === "ready" && (
          <ul className="timeline">{hist.data.items.map((h, i) => (
            <li key={i}>
              <strong>{AUDIT_ACTION_LABELS[h.action] ?? h.action}</strong>
              <span>{h.actor_name ?? "—"}</span>
              <span className="muted small">{h.client_id ? CLIENT_LABELS[h.client_id] ?? h.client_id : ""} · {formatDateTime(h.occurred_at)}</span>
            </li>
          ))}</ul>
        )}
      </section>
    </section>
  );
}

interface Part { method: PaymentMethod; amount: string; presented_cash: string; fee: string; provider_reference: string; installments: string; due_date: string }
const newPart = (amount: string, method: PaymentMethod = "CASH"): Part =>
  ({ method, amount, presented_cash: "", fee: "", provider_reference: "", installments: "1", due_date: "" });

function PaymentForm({ order, onDone }: { order: Order; onDone: (paid: Payment[]) => void }) {
  const { me } = useSession();
  const [parts, setParts] = useState<Part[]>([newPart(order.balance_due)]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const set = (i: number, p: Partial<Part>) => setParts(parts.map((x, j) => (j === i ? { ...x, ...p } : x)));
  const dec = (v: string) => (v.trim() ? v.trim().replace(",", ".") : null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.command<{ payments: Payment[] }>(`/api/v1/orders/${order.id}/payments`, {
        parts: parts.map((p) => ({
          method: p.method, amount: dec(p.amount),
          presented_cash: p.method === "CASH" ? dec(p.presented_cash) : null,
          fee: ["DEBIT", "CREDIT"].includes(p.method) ? dec(p.fee) : null,
          provider_reference: ["DEBIT", "CREDIT"].includes(p.method) ? p.provider_reference.trim() || null : null,
          installments: p.method === "CREDIT" ? Number(p.installments) : null,
          due_date: p.method === "ON_ACCOUNT" ? p.due_date || null : null,
        })),
      }, key);
      setKey(newIdempotencyKey());
      onDone(data.payments);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="titulo-receber" noValidate>
      <h2 id="titulo-receber" className="h3 span-2">Receber pagamento</h2>
      <p className="muted span-2">A soma dos meios precisa ser igual ao saldo devedor ({formatMoney(order.balance_due)}). Pix e transferência ficam aguardando conferência; comprovante sozinho não confirma.</p>
      {parts.map((p, i) => (
        <fieldset className="span-2 lines" key={i}>
          <legend>Meio {i + 1}</legend>
          <div className="form-grid">
            <Field id={`pg-m${i}`} label="Meio *" error={fieldError(error, `parts[${i}].method`)}>
              <select id={`pg-m${i}`} value={p.method} onChange={(e) => set(i, { method: e.target.value as PaymentMethod })}>
                {(Object.keys(METHOD) as PaymentMethod[]).filter((m) => m !== "ON_ACCOUNT" || me?.admin).map((m) => <option key={m} value={m}>{METHOD[m]}</option>)}
              </select>
            </Field>
            <Field id={`pg-a${i}`} label="Valor (R$) *" error={fieldError(error, `parts[${i}].amount`)}>
              <input id={`pg-a${i}`} className="money" inputMode="decimal" value={p.amount} onChange={(e) => set(i, { amount: e.target.value })} />
            </Field>
            {p.method === "CASH" && (
              <Field id={`pg-c${i}`} label="Dinheiro apresentado (R$) *" hint="O troco é calculado e registrado pelo servidor." error={fieldError(error, `parts[${i}].presented_cash`)}>
                <input id={`pg-c${i}`} className="money" inputMode="decimal" value={p.presented_cash} onChange={(e) => set(i, { presented_cash: e.target.value })} />
              </Field>
            )}
            {(p.method === "DEBIT" || p.method === "CREDIT") && (<>
              <Field id={`pg-n${i}`} label="NSU / código da transação *" error={fieldError(error, `parts[${i}].provider_reference`)}>
                <input id={`pg-n${i}`} value={p.provider_reference} onChange={(e) => set(i, { provider_reference: e.target.value })} />
              </Field>
              <Field id={`pg-f${i}`} label="Taxa da maquininha (R$)" error={fieldError(error, `parts[${i}].fee`)}>
                <input id={`pg-f${i}`} className="money" inputMode="decimal" value={p.fee} onChange={(e) => set(i, { fee: e.target.value })} />
              </Field>
              {p.method === "CREDIT" && (
                <Field id={`pg-i${i}`} label="Parcelas" error={fieldError(error, `parts[${i}].installments`)}>
                  <input id={`pg-i${i}`} type="number" min={1} max={24} value={p.installments} onChange={(e) => set(i, { installments: e.target.value })} />
                </Field>
              )}
            </>)}
            {p.method === "ON_ACCOUNT" && (
              <Field id={`pg-v${i}`} label="Vencimento *" error={fieldError(error, `parts[${i}].due_date`)}>
                <input id={`pg-v${i}`} type="date" value={p.due_date} onChange={(e) => set(i, { due_date: e.target.value })} />
              </Field>
            )}
          </div>
          {parts.length > 1 && <button type="button" className="btn btn-small btn-ghost" onClick={() => setParts(parts.filter((_, j) => j !== i))}>Remover meio</button>}
        </fieldset>
      ))}
      <div className="span-2"><button type="button" className="btn btn-small" onClick={() => setParts([...parts, newPart("", "PIX")])}><Icon name="plus" size={14} /> Dividir em outro meio</button></div>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary" disabled={busy}>{busy ? "Registrando…" : "Registrar pagamento"}</button></div>
    </form>
  );
}

function Payments({ items, onChange }: { items: Payment[]; onChange: () => void }) {
  const { can } = useSession();
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const act = async (p: Payment, action: "confirm" | "reject" | "reversals") => {
    const note = window.prompt(action === "confirm" ? "Como conferiu? (ex.: extrato do banco)" : "Motivo");
    if (!note?.trim()) return;
    setError(null);
    try {
      await api.command(`/api/v1/payments/${p.id}/${action}`, { note: note.trim() }, newIdempotencyKey(), `"${p.version}"`);
      onChange();
    } catch (err) {
      const e = err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" });
      if (e.code === "VERSION_CONFLICT") setConflict(true);
      else setError(e.message);
    }
  };
  return (
    <section className="card" aria-labelledby="titulo-pagamentos">
      <h2 id="titulo-pagamentos" className="h3">Pagamentos</h2>
      {conflict && <ConflictState onReload={() => { setConflict(false); onChange(); }} />}
      {error && <p className="notice notice-error" role="alert">{error}</p>}
      <ul className="timeline">{items.map((p) => (
        <li key={p.id}>
          <strong>{METHOD[p.method]} {formatMoney(p.amount)}</strong>
          <span className={"badge " + (p.state === "CONFIRMED" ? "badge-ok" : p.state === "PENDING" ? "badge-violet" : "badge-error")}>{PAY_STATE[p.state]}</span>
          {p.change_given && p.change_given !== "0.00" && <span>troco {formatMoney(p.change_given)}</span>}
          {p.fee !== "0.00" && <span className="muted">taxa {formatMoney(p.fee)} · líquido {formatMoney(p.net)}</span>}
          {p.provider_reference && <span className="muted small">NSU {p.provider_reference}</span>}
          <span className="muted small">{p.created_by_name} · {formatDateTime(p.created_at)}{p.confirmed_by_name && ` · conferido por ${p.confirmed_by_name}`}</span>
          {p.state === "PENDING" && can("payments.confirm") && (<>
            <button className="btn btn-small btn-primary" onClick={() => void act(p, "confirm")}>Conferir</button>
            <button className="btn btn-small" onClick={() => void act(p, "reject")}>Recusar</button>
          </>)}
          {p.state === "CONFIRMED" && can("payments.reverse") && <button className="btn btn-small" onClick={() => void act(p, "reversals")}>Estornar</button>}
        </li>
      ))}</ul>
    </section>
  );
}

function Operations({ order, resource, onDone }: { order: Order; resource: string; onDone: () => void }) {
  const { can } = useSession();
  const [mode, setMode] = useState<null | "partial" | "cancel" | "return">(null);
  const [qty, setQty] = useState<Record<number, string>>({});
  const [reason, setReason] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const run = async (path: string, body: unknown, ifMatch?: string) => {
    setBusy(true);
    setError(null);
    try {
      await api.command(`/api/v1/orders/${order.id}/${path}`, body, key, ifMatch);
      setKey(newIdempotencyKey());
      setMode(null);
      setQty({});
      setReason("");
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
    } finally {
      setBusy(false);
    }
  };
  const lines = () => Object.entries(qty).filter(([, v]) => v.trim()).map(([n, v]) => ({ line_no: Number(n), quantity: v.replace(",", ".") }));
  const canConfirm = can(`${resource}.confirm`);
  if (error?.code === "VERSION_CONFLICT") return <ConflictState onReload={() => { setError(null); onDone(); }} />;
  return (
    <section className="card" aria-labelledby="titulo-etapas">
      <h2 id="titulo-etapas" className="h3">Etapas</h2>
      <div className="actions" style={{ justifyContent: "flex-start" }}>
        {order.state === "CONFIRMED" && canConfirm && (
          <button className="btn" disabled={busy} onClick={() => void run("ready", undefined, `"${order.version}"`)}>Marcar como pronto</button>
        )}
        {isOpen(order) && canConfirm && (<>
          <button className="btn btn-primary" disabled={busy} onClick={() => void run("fulfillments", undefined)}>
            <Icon name="check" size={16} /> {order.type === "PICKUP" ? "Registrar retirada de tudo" : "Registrar entrega de tudo"}
          </button>
          <button className="btn" onClick={() => setMode("partial")}>Retirada parcial</button>
        </>)}
        {isOpen(order) && can(`${resource}.cancel`) && <button className="btn" onClick={() => setMode("cancel")}>Cancelar pedido</button>}
        {["FULFILLED", "PARTIALLY_FULFILLED"].includes(order.state) && can(`${resource}.reverse`) && <button className="btn" onClick={() => setMode("return")}>Registrar devolução</button>}
      </div>
      {(mode === "partial" || mode === "return") && (
        <form className="form-grid" onSubmit={(e) => { e.preventDefault(); void run(mode === "partial" ? "fulfillments" : "returns",
          mode === "partial" ? { lines: lines() } : { lines: lines(), reason }); }}>
          {order.lines.map((l) => (
            <Field key={l.line_no} id={`op-${l.line_no}`} label={`${l.product_name} — ${mode === "partial" ? "pendente" : "entregue"} ${formatDecimal(mode === "partial" ? l.remaining_quantity : l.fulfilled_quantity)} ${unitLabel(l.unit)}`}>
              <input id={`op-${l.line_no}`} inputMode="decimal" value={qty[l.line_no] ?? ""} onChange={(e) => setQty({ ...qty, [l.line_no]: e.target.value })} />
            </Field>
          ))}
          {mode === "return" && (
            <div className="span-2"><Field id="op-motivo" label="Motivo da devolução *" error={fieldError(error, "reason")}>
              <input id="op-motivo" value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field></div>
          )}
          <div className="actions span-2">
            <button type="button" className="btn btn-ghost" onClick={() => setMode(null)}>Voltar</button>
            <button className="btn btn-primary" disabled={busy}>{mode === "partial" ? "Confirmar retirada parcial" : "Confirmar devolução"}</button>
          </div>
        </form>
      )}
      {mode === "cancel" && (
        <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void run("cancellations", { reason }, `"${order.version}"`); }}>
          <label htmlFor="op-cancel">Motivo *</label>
          <input id="op-cancel" value={reason} onChange={(e) => setReason(e.target.value)} />
          <button className="btn btn-primary" disabled={busy || !reason.trim()}>Confirmar cancelamento</button>
          <span className="muted small">Libera a reserva. Com entrega parcial, cancela só o que falta.</span>
        </form>
      )}
      <FormError error={error} />
    </section>
  );
}
