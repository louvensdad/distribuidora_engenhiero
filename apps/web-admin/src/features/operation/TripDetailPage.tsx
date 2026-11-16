import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import { AUDIT_ACTION_LABELS, CLIENT_LABELS } from "../../api/labels";
import type { DriverOption, EntityHistoryItem, Order, Page, PaymentMethod, Trip, TripCosts, TripStop, Vehicle, VehicleExpense } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, toDecimal, unitLabel } from "../../app/format";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { ConflictState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { METHOD, PAYMENT_STATE } from "../sales/orderLabels";
import { EXPENSE_KIND, EXPENSE_STATE, INCIDENT_KIND, PAID_WITH, REQUESTED_ACTION, STOP_STATE, TRIP_STATE } from "./fleetLabels";
import { Collapsible, ExpenseDecision } from "./VehicleDetailPage";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** E04/E05/E10 no site — despacho, saída, paradas (entrega/ocorrência), retorno, conferência, custos e histórico. */
export function TripDetailPage() {
  const { id } = useParams();
  const { can, me } = useSession();
  const [res, reload] = useResource<Trip>(`/api/v1/trips/${id}`);
  const seesCost = !!me?.admin || can("expenses.view");
  const [costs, reloadCosts] = useResource<TripCosts>(seesCost ? `/api/v1/trips/${id}/costs` : null);
  const [exp, reloadExp] = useResource<{ items: VehicleExpense[] }>(can("fuel.view") || can("expenses.view") ? `/api/v1/vehicle-expenses?trip_id=${id}` : null);
  const [hist, reloadHist] = useResource<{ items: EntityHistoryItem[] }>(`/api/v1/trips/${id}/history`);
  const refresh = () => { reload(); reloadCosts(); reloadExp(); reloadHist(); };
  if (res.status === "loading") return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  const t = res.data;
  const planning = t.state === "DRAFT" || t.state === "ASSIGNED";
  return (
    <section className="page" aria-labelledby="titulo-viagem">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/operation/trips">Viagens</Link> / V{t.number}</p>
          <h1 id="titulo-viagem">Viagem V{t.number}</h1>
          <p className="muted">{t.plate ? `${t.vehicle_model} · ${t.plate}` : "sem veículo"} · {t.driver_name ?? "sem motorista"} · {t.planned_date} · criada por {t.created_by_name}</p>
        </div>
        <span className="badge badge-violet">{TRIP_STATE[t.state]}</span>
      </header>

      <div className="kpis kpis-4">
        <div className="card kpi"><span className="kpi-label">Carga</span>
          <span className="kpi-value">{t.load && t.capacity_unit ? `${formatDecimal(t.load)} / ${formatDecimal(t.capacity_quantity)}` : "—"}</span>
          <span className="muted small">{t.capacity_unit ? unitLabel(t.capacity_unit) : ""}</span></div>
        <div className="card kpi"><span className="kpi-label">Saída</span>
          <span className="kpi-value">{t.departure_odometer ? `${formatDecimal(t.departure_odometer)} km` : "—"}</span>
          <span className="muted small">{t.departed_at ? formatDateTime(t.departed_at) : ""}</span></div>
        <div className="card kpi"><span className="kpi-label">Retorno</span>
          <span className="kpi-value">{t.return_odometer ? `${formatDecimal(t.return_odometer)} km` : "—"}</span>
          <span className="muted small">{t.distance_km ? `${formatDecimal(t.distance_km)} km rodados` : ""}</span></div>
        <div className="card kpi kpi-highlight"><span className="kpi-label">Dinheiro a prestar</span>
          <span className="kpi-value">{formatMoney(t.cash_expected)}</span>
          <span className="muted small">{t.cash_declared ? `declarado ${formatMoney(t.cash_declared)} · diferença ${formatMoney(t.cash_difference)}` : ""}</span></div>
      </div>
      {t.cancel_reason && <p className="notice">Cancelada: {t.cancel_reason}</p>}
      {t.return_note && <p className="notice">Justificativa do retorno: {t.return_note}</p>}
      {t.reconciled_by_name && <p className="notice" role="status">Conferida por {t.reconciled_by_name} em {formatDateTime(t.reconciled_at!)}{t.reconcile_note ? ` — ${t.reconcile_note}` : ""}</p>}

      {planning && can("trips.assign") && <AssignForm t={t} onDone={refresh} />}
      {t.state === "ASSIGNED" && can("trips.confirm") && <DepartForm t={t} onDone={refresh} />}

      <h2 className="h3">Paradas</h2>
      {t.stops.length === 0 && <p className="muted">Nenhum pedido nesta viagem.</p>}
      {t.stops.map((s) => <StopCard key={s.id} t={t} s={s} onDone={refresh} />)}
      {t.incidents.length > 0 && (
        <section className="card"><h3 className="h3">Ocorrências da viagem</h3>
          <ul className="timeline">{t.incidents.map((i) => <li key={i.id}><strong>{INCIDENT_KIND[i.kind]}</strong><span>{i.description}</span>
            <span className="muted small">{i.created_by_name} · {formatDateTime(i.created_at)}</span></li>)}</ul>
        </section>
      )}

      {t.state === "DEPARTED" && can("trips.close") && <ReturnForm t={t} onDone={refresh} />}
      {t.state === "RETURNED" && can("trips.close") && t.driver_id !== me?.user.id && <ReconcileForm t={t} onDone={refresh} />}
      {planning && can("trips.assign") && <CancelForm t={t} onDone={refresh} />}

      {exp.status === "ready" && exp.data.items.length > 0 && (
        <section className="card" aria-labelledby="titulo-gastos-viagem">
          <h2 id="titulo-gastos-viagem" className="h3">Gastos da viagem</h2>
          <ul className="timeline">{exp.data.items.map((e) => (
            <li key={e.id}><strong>{EXPENSE_KIND[e.kind]} · {formatMoney(e.amount)}</strong>
              <span>{PAID_WITH[e.paid_with]} · {EXPENSE_STATE[e.state]} · por {e.created_by_name}</span>
              <ExpenseDecision e={e} onDone={refresh} /></li>
          ))}</ul>
        </section>
      )}
      {costs.status === "ready" && <CostsCard c={costs.data} />}

      <section className="card" aria-labelledby="titulo-hist-viagem">
        <h2 id="titulo-hist-viagem" className="h3">Histórico da viagem</h2>
        {hist.status === "ready" && (
          <ul className="timeline">{hist.data.items.map((h, i) => (
            <li key={i}><strong>{AUDIT_ACTION_LABELS[h.action] ?? h.action}</strong><span>{h.actor_name ?? "—"}</span>
              <span className="muted small">{h.client_id ? CLIENT_LABELS[h.client_id] ?? h.client_id : ""} · {formatDateTime(h.occurred_at)}</span></li>
          ))}</ul>
        )}
      </section>
    </section>
  );
}

function AssignForm({ t, onDone }: { t: Trip; onDone: () => void }) {
  const [vehicles] = useResource<{ items: Vehicle[] }>("/api/v1/vehicles?status=ACTIVE");
  const [drivers] = useResource<{ items: DriverOption[] }>("/api/v1/trips/eligible-drivers");
  const [orders] = useResource<Page<Order>>("/api/v1/orders?type=DELIVERY&limit=100");
  const [vehicle, setVehicle] = useState(t.vehicle_id ?? "");
  const [driver, setDriver] = useState(t.driver_id ?? "");
  const [selected, setSelected] = useState<string[]>(t.stops.filter((s) => s.state === "PENDING").map((s) => s.order.id));
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  if (vehicles.status !== "ready" || drivers.status !== "ready" || orders.status !== "ready") return <LoadingState label="Carregando despacho" />;
  const candidates = orders.data.items.filter((o) => ["CONFIRMED", "READY", "PARTIALLY_FULFILLED"].includes(o.state)
    && (!o.dispatch || !["ASSIGNED", "DEPARTED", "DRAFT"].includes(o.dispatch.trip_state) || o.dispatch.trip_id === t.id
      || o.dispatch.stop_state === "REMOVED"));
  const toggle = (id: string) => setSelected(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/v1/trips/${t.id}/assignments`, { vehicle_id: vehicle || null, driver_id: driver || null, order_ids: selected }, `"${t.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
    } finally {
      setBusy(false);
    }
  };
  if (error?.status === 412) return <ConflictState onReload={onDone} />;
  const orderErrors = error?.fieldErrors.filter((f) => f.field.startsWith("order_ids")) ?? [];
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Atribuir carro e motorista">
      <h2 className="h3 span-2">Atribuir carro, motorista e pedidos</h2>
      <Field id="at-veiculo" label="Veículo e placa *" error={fieldError(error, "vehicle_id")}>
        <select id="at-veiculo" value={vehicle} onChange={(e) => setVehicle(e.target.value)}>
          <option value="">Selecione</option>
          {vehicles.data.items.map((v) => <option key={v.id} value={v.id} disabled={!!v.current_trip_id && v.current_trip_id !== t.id}>
            {v.plate} · {v.model} · {formatDecimal(v.capacity_quantity)} {unitLabel(v.capacity_unit, v.capacity_quantity)}{v.current_trip_id && v.current_trip_id !== t.id ? ` (em V${v.current_trip_number})` : ""}</option>)}
        </select>
      </Field>
      <Field id="at-motorista" label="Motorista autorizado *" error={fieldError(error, "driver_id")}>
        <select id="at-motorista" value={driver} onChange={(e) => setDriver(e.target.value)}>
          <option value="">Selecione</option>
          {drivers.data.items.map((d) => <option key={d.id} value={d.id}>{d.name}{d.busy && d.id !== t.driver_id ? " (em viagem)" : ""}</option>)}
        </select>
      </Field>
      <fieldset className="span-2">
        <legend>Pedidos de entrega *</legend>
        {candidates.length === 0 ? <p className="muted">Nenhum pedido de entrega aguardando despacho.</p> : (
          <ul className="check-list">{candidates.map((o) => (
            <li key={o.id}><label>
              <input type="checkbox" checked={selected.includes(o.id)} onChange={() => toggle(o.id)} />
              <span>#{o.number} · {o.customer_name} · {o.lines.map((l) => `${formatDecimal(l.remaining_quantity)} ${unitLabel(l.unit, l.remaining_quantity)}`).join(", ")}
                <span className="muted small"> · {PAYMENT_STATE[o.payment_state]}{o.delivery?.agreed_payment ? ` · combinado ${METHOD[o.delivery.agreed_payment]}` : ""}</span></span>
            </label></li>
          ))}</ul>
        )}
        {orderErrors.map((f) => <p key={f.field} className="field-error">{f.message}</p>)}
      </fieldset>
      <FormError error={error} />
      <div className="actions span-2">
        <button className="btn btn-primary" disabled={busy}>{busy ? "Validando…" : "Validar capacidade e atribuir"}</button>
      </div>
    </form>
  );
}

function DepartForm({ t, onDone }: { t: Trip; onDone: () => void }) {
  const [odo, setOdo] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post(`/api/v1/trips/${t.id}/depart`, { odometer: toDecimal(odo) }, `"${t.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  if (error?.status === 412) return <ConflictState onReload={onDone} />;
  return (
    <form className="card row-form" onSubmit={submit} noValidate aria-label="Registrar saída">
      <h2 className="h3">Registrar saída</h2>
      <Field id="sd-odo" label="Odômetro de saída (km) *" error={fieldError(error, "odometer")}>
        <input id="sd-odo" inputMode="decimal" value={odo} onChange={(e) => setOdo(e.target.value)} />
      </Field>
      <span />
      <button className="btn btn-primary">Registrar saída</button>
      <FormError error={error?.fieldErrors.length ? null : error} />
    </form>
  );
}

function StopCard({ t, s, onDone }: { t: Trip; s: TripStop; onDone: () => void }) {
  const { can } = useSession();
  const o = s.order;
  const paid = o.payment_state === "PAID" || o.payment_state === "ON_ACCOUNT";
  const active = t.state === "DEPARTED" && s.state === "PENDING" && can("trips.confirm");
  const cls = "card stop " + (s.state === "DELIVERED" ? "stop-delivered" : s.state === "FAILED" ? "stop-failed" : "");
  return (
    <article className={cls} aria-label={`Parada ${s.seq}: pedido ${o.number}`}>
      <header className="page-head">
        <div>
          <h3 className="h3">{s.seq}. Pedido #{o.number} · {o.customer_name}</h3>
          <p className="muted small">
            {o.masked_fields.includes("delivery.address") ? <Hidden /> : `${o.delivery?.address ?? "—"}${o.delivery?.reference ? ` (${o.delivery.reference})` : ""}`}
            {" · "}{o.masked_fields.includes("delivery.contact") ? <Hidden /> : o.delivery?.contact ?? "sem contato"}
            {o.delivery?.window_start && o.delivery.window_end && <> · janela {formatDateTime(o.delivery.window_start)}–{formatDateTime(o.delivery.window_end)}</>}
          </p>
        </div>
        <span className="badge badge-violet">{STOP_STATE[s.state]}</span>
      </header>
      <p>{o.lines.map((l) => `${l.product_name}: ${formatDecimal(l.remaining_quantity !== "0" ? l.remaining_quantity : l.fulfilled_quantity)} ${unitLabel(l.unit)}`).join(" · ")}</p>
      <p className={paid ? "notice" : "notice notice-error"}>
        {paid ? `${PAYMENT_STATE[o.payment_state]} — não cobrar novamente.`
          : `Cobrar ${formatMoney(o.balance_due)}${o.delivery?.agreed_payment ? ` (combinado: ${METHOD[o.delivery.agreed_payment]})` : ""}.`}
        {o.pending_amount !== "0.00" && ` Aguardando conferência: ${formatMoney(o.pending_amount)}.`}
      </p>
      {s.recipient_name && <p className="muted small">Recebido por {s.recipient_name} · {s.delivered_by_name} · {formatDateTime(s.delivered_at!)}</p>}
      {s.incidents.map((i) => <p key={i.id} className="muted small">Ocorrência: {INCIDENT_KIND[i.kind]} — {i.description} ({REQUESTED_ACTION[i.requested_action]}) · {i.created_by_name}</p>)}
      {active && (
        <div className="grid-2">
          <Collapsible title="Confirmar entrega"><DeliverForm t={t} s={s} onDone={onDone} /></Collapsible>
          <Collapsible title="Registrar ocorrência"><IncidentForm t={t} s={s} onDone={onDone} /></Collapsible>
        </div>
      )}
    </article>
  );
}

function DeliverForm({ t, s, onDone }: { t: Trip; s: TripStop; onDone: () => void }) {
  const o = s.order;
  const due = !o.balance_due.startsWith("-") && o.balance_due !== "0.00";
  const [recipient, setRecipient] = useState("");
  const [qty, setQty] = useState<Record<number, string>>(Object.fromEntries(o.lines.map((l) => [l.line_no, l.remaining_quantity])));
  const [reason, setReason] = useState("");
  const [method, setMethod] = useState<PaymentMethod>(o.delivery?.agreed_payment && o.delivery.agreed_payment !== "ON_ACCOUNT" ? o.delivery.agreed_payment : "CASH");
  const [presented, setPresented] = useState("");
  const [nsu, setNsu] = useState("");
  const [charge, setCharge] = useState(due);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const partial = o.lines.some((l) => qty[l.line_no] !== l.remaining_quantity);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command(`/api/v1/trips/${t.id}/deliveries`, {
        stop_id: s.id, recipient_name: recipient.trim() || null,
        lines: partial ? o.lines.filter((l) => toDecimal(qty[l.line_no] ?? "") && toDecimal(qty[l.line_no] ?? "") !== "0")
          .map((l) => ({ line_no: l.line_no, quantity: toDecimal(qty[l.line_no] ?? "") })) : null,
        partial_reason: partial ? reason.trim() || null : null,
        payment: charge ? { parts: [{ method, amount: o.balance_due,
          presented_cash: method === "CASH" ? toDecimal(presented) : null,
          provider_reference: ["DEBIT", "CREDIT"].includes(method) ? nsu.trim() || null : null }] } : null,
      }, key);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label={`Entrega do pedido ${o.number}`}>
      <Field id={`en-rec-${s.id}`} label="Quem recebeu *" error={fieldError(error, "recipient_name")}>
        <input id={`en-rec-${s.id}`} maxLength={120} value={recipient} onChange={(e) => setRecipient(e.target.value)} />
      </Field>
      {o.lines.map((l) => (
        <Field key={l.line_no} id={`en-q-${s.id}-${l.line_no}`} label={`${l.product_name} entregue (${unitLabel(l.unit)})`}
          error={fieldError(error, `lines[${o.lines.indexOf(l)}].quantity`)}>
          <input id={`en-q-${s.id}-${l.line_no}`} inputMode="decimal" value={qty[l.line_no] ?? ""} onChange={(e) => setQty({ ...qty, [l.line_no]: e.target.value })} />
        </Field>
      ))}
      {partial && (
        <Field id={`en-mot-${s.id}`} label="Motivo da entrega parcial * (gera ocorrência)" error={fieldError(error, "partial_reason")}>
          <input id={`en-mot-${s.id}`} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
      )}
      {due && (
        <fieldset className="span-2 form-grid">
          <legend>Recebimento na entrega ({formatMoney(o.balance_due)})</legend>
          <label className="span-2"><input type="checkbox" checked={charge} onChange={(e) => setCharge(e.target.checked)} /> Receber agora</label>
          {charge && (
            <>
              <Field id={`en-m-${s.id}`} label="Meio">
                <select id={`en-m-${s.id}`} value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
                  {(["CASH", "PIX", "DEBIT", "CREDIT", "BANK_TRANSFER"] as PaymentMethod[]).map((m) => <option key={m} value={m}>{METHOD[m]}</option>)}
                </select>
              </Field>
              {method === "CASH" && (
                <Field id={`en-ap-${s.id}`} label="Valor entregue pelo cliente (R$) *" error={fieldError(error, "parts[0].presented_cash")}>
                  <input id={`en-ap-${s.id}`} className="money" inputMode="decimal" value={presented} onChange={(e) => setPresented(e.target.value)} />
                </Field>
              )}
              {["DEBIT", "CREDIT"].includes(method) && (
                <Field id={`en-nsu-${s.id}`} label="NSU/código da transação *" error={fieldError(error, "parts[0].provider_reference")}>
                  <input id={`en-nsu-${s.id}`} value={nsu} onChange={(e) => setNsu(e.target.value)} />
                </Field>
              )}
              {["PIX", "BANK_TRANSFER"].includes(method) && <p className="muted small span-2">Pix fica aguardando conferência; sem conferência, a entrega é recusada.</p>}
            </>
          )}
        </fieldset>
      )}
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Confirmar entrega</button></div>
    </form>
  );
}

function IncidentForm({ t, s, onDone }: { t: Trip; s: TripStop; onDone: () => void }) {
  const [kind, setKind] = useState("CUSTOMER_ABSENT");
  const [action, setAction] = useState("RESCHEDULE");
  const [desc, setDesc] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command(`/api/v1/trips/${t.id}/incidents`, { stop_id: s.id, kind, requested_action: action, description: desc.trim() || null }, key);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label={`Ocorrência do pedido ${s.order.number}`}>
      <Field id={`oc-k-${s.id}`} label="Motivo *" error={fieldError(error, "kind")}>
        <select id={`oc-k-${s.id}`} value={kind} onChange={(e) => setKind(e.target.value)}>
          {Object.entries(INCIDENT_KIND).filter(([k]) => k !== "PARTIAL").map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      <Field id={`oc-a-${s.id}`} label="Pedido">
        <select id={`oc-a-${s.id}`} value={action} onChange={(e) => setAction(e.target.value)}>
          {Object.entries(REQUESTED_ACTION).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      <Field id={`oc-d-${s.id}`} label="O que aconteceu *" error={fieldError(error, "description")}>
        <input id={`oc-d-${s.id}`} maxLength={500} value={desc} onChange={(e) => setDesc(e.target.value)} />
      </Field>
      <p className="muted small span-2">Não marca como entregue: o pedido segue reservado e volta para despacho no retorno.</p>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn">Salvar ocorrência</button></div>
    </form>
  );
}

function ReturnForm({ t, onDone }: { t: Trip; onDone: () => void }) {
  const [odo, setOdo] = useState("");
  const [cash, setCash] = useState("");
  const [just, setJust] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.command(`/api/v1/trips/${t.id}/return`, { odometer: toDecimal(odo), cash_declared: toDecimal(cash), justification: just.trim() || null }, key, `"${t.version}"`);
      setKey(newIdempotencyKey());
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  if (error?.status === 412) return <ConflictState onReload={onDone} />;
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Retorno e prestação de contas">
      <h2 className="h3 span-2">Retorno e prestação de contas</h2>
      <p className="muted small span-2">A prestação é o dinheiro recebido nas entregas menos os gastos pagos com o dinheiro do motorista. Paradas sem registro bloqueiam o retorno.</p>
      <Field id="rt-odo" label="Odômetro de chegada (km) *" error={fieldError(error, "odometer")}>
        <input id="rt-odo" inputMode="decimal" value={odo} onChange={(e) => setOdo(e.target.value)} />
      </Field>
      <Field id="rt-din" label="Dinheiro entregue (R$) *" error={fieldError(error, "cash_declared")}>
        <input id="rt-din" className="money" inputMode="decimal" value={cash} onChange={(e) => setCash(e.target.value)} />
      </Field>
      <Field id="rt-just" label="Justificativa (obrigatória se houver diferença)" error={fieldError(error, "justification")}>
        <input id="rt-just" maxLength={500} value={just} onChange={(e) => setJust(e.target.value)} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Enviar prestação</button></div>
    </form>
  );
}

function ReconcileForm({ t, onDone }: { t: Trip; onDone: () => void }) {
  const [note, setNote] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post(`/api/v1/trips/${t.id}/reconcile`, { note: note.trim() || null }, `"${t.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  if (error?.status === 412) return <ConflictState onReload={onDone} />;
  return (
    <form className="card row-form" onSubmit={submit} noValidate aria-label="Conferir prestação">
      <h2 className="h3">Conferir prestação</h2>
      <p className="muted small">Esperado {formatMoney(t.cash_expected)} · declarado {formatMoney(t.cash_declared)}. Gastos pendentes da viagem precisam ser decididos antes.</p>
      <Field id="cf-nota" label="Observação (obrigatória com diferença)" error={fieldError(error, "note")}>
        <input id="cf-nota" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <span />
      <button className="btn btn-primary">Conferir retorno</button>
      <FormError error={error?.fieldErrors.length ? null : error} />
    </form>
  );
}

function CancelForm({ t, onDone }: { t: Trip; onDone: () => void }) {
  const [note, setNote] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post(`/api/v1/trips/${t.id}/cancel`, { note: note.trim() || null }, `"${t.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <Collapsible title="Cancelar viagem">
      <form className="row-form" onSubmit={submit} noValidate aria-label="Cancelar viagem">
        <Field id="cv-motivo" label="Motivo *" error={fieldError(error, "note")}>
          <input id="cv-motivo" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <span />
        <button className="btn">Cancelar viagem</button>
        <FormError error={error?.fieldErrors.length ? null : error} />
      </form>
    </Collapsible>
  );
}

function CostsCard({ c }: { c: TripCosts }) {
  return (
    <section className="card" aria-labelledby="titulo-custos">
      <h2 id="titulo-custos" className="h3">Custo da viagem e rateio por entrega</h2>
      <p className="muted small">Regra {c.rule_code} v{c.rule_version}: {c.rule_description} Visão analítica — não cria despesa nem pagamento.</p>
      <div className="kpis kpis-4">
        <div className="card kpi"><span className="kpi-label">Custo aprovado</span><span className="kpi-value">{formatMoney(c.total_cost)}</span></div>
        <div className="card kpi"><span className="kpi-label">Rateado</span><span className="kpi-value">{formatMoney(c.allocated_total)}</span></div>
        <div className="card kpi"><span className="kpi-label">Não alocado</span><span className="kpi-value">{formatMoney(c.unallocated)}</span></div>
        <div className="card kpi"><span className="kpi-label">Pendente de aprovação</span><span className="kpi-value">{formatMoney(c.pending_cost)}</span>
          <span className="muted small">{c.cost_per_km ? `${formatMoney(c.cost_per_km)}/km` : ""}</span></div>
      </div>
      {c.message && <p className="notice">{c.message}</p>}
      {c.lines.length > 0 && (
        <div className="table-wrap">
          <table className="stack">
            <thead><tr><th scope="col">Pedido</th><th scope="col">Entregue</th><th scope="col">Participação</th><th scope="col" className="num">Custo rateado</th></tr></thead>
            <tbody>{c.lines.map((l) => (
              <tr key={l.stop_id}>
                <th scope="row">#{l.order_number} · {l.customer_name}</th>
                <td data-label="Entregue">{formatDecimal(l.delivered_quantity)} {c.capacity_unit ? unitLabel(c.capacity_unit, l.delivered_quantity) : ""}</td>
                <td data-label="Participação">{formatDecimal((Number(l.share) * 100).toFixed(2))}%</td>
                <td data-label="Custo rateado" className="num">{formatMoney(l.allocated_cost)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}
