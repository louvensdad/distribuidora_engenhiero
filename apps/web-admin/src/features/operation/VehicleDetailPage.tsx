import { useState, type FormEvent, type ReactNode } from "react";
import { Link, useParams } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import { AUDIT_ACTION_LABELS, CLIENT_LABELS } from "../../api/labels";
import type { EntityHistoryItem, OdometerReading, Vehicle, VehicleExpense } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatDecimal, formatMoney, toDecimal, unitLabel } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { ConflictState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { EXPENSE_KIND, EXPENSE_STATE, FUEL_TYPE, ODOMETER_KIND, PAID_WITH, VEHICLE_STATUS } from "./fleetLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** E11/W07 — veículo: avisos, consumo (só com dados suficientes), gastos por acesso, odômetro e histórico. */
export function VehicleDetailPage() {
  const { id } = useParams();
  const { can, me } = useSession();
  const [res, reload] = useResource<Vehicle>(`/api/v1/vehicles/${id}`);
  const seesExpenses = can("fuel.view") || can("expenses.view");
  const [exp, reloadExp] = useResource<{ items: VehicleExpense[] }>(seesExpenses ? `/api/v1/vehicle-expenses?vehicle_id=${id}` : null);
  const [odo, reloadOdo] = useResource<{ items: OdometerReading[] }>(`/api/v1/vehicles/${id}/odometer`);
  const [hist, reloadHist] = useResource<{ items: EntityHistoryItem[] }>(`/api/v1/vehicles/${id}/history`);
  const refresh = () => { reload(); reloadExp(); reloadOdo(); reloadHist(); };
  if (res.status === "loading") return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return <ErrorState error={res.error} onRetry={reload} />;
  const v = res.data;
  const c = v.consumption;
  return (
    <section className="page" aria-labelledby="titulo-veiculo">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/operation/fleet">Frota</Link> / {v.plate}</p>
          <h1 id="titulo-veiculo">{v.model} · {v.plate}</h1>
          <p className="muted">{v.branch} · capacidade {formatDecimal(v.capacity_quantity)} {unitLabel(v.capacity_unit, v.capacity_quantity)}</p>
        </div>
        <span className={"badge " + (v.status === "ACTIVE" ? "badge-ok" : "badge-violet")}>{VEHICLE_STATUS[v.status]}</span>
      </header>
      {v.alerts.map((a) => <p key={a.code} className="notice notice-error" role="status">{a.message}</p>)}
      <div className="kpis kpis-4">
        <div className="card kpi"><span className="kpi-label">Odômetro</span><span className="kpi-value">{formatDecimal(v.odometer_km)} km</span></div>
        <div className="card kpi"><span className="kpi-label">Próxima revisão</span><span className="kpi-value">{v.next_service_km ? `${formatDecimal(v.next_service_km)} km` : "—"}</span></div>
        <div className="card kpi"><span className="kpi-label">Consumo</span>
          <span className="kpi-value">{c.state === "OK" ? `${formatDecimal(c.km_per_liter)} km/l` : "Dados insuficientes"}</span>
          <span className="muted small">{c.state === "OK" ? `${formatDecimal(c.km)} km com ${formatDecimal(c.liters)} l` : c.message}</span></div>
        <div className="card kpi"><span className="kpi-label">Viagem atual</span>
          <span className="kpi-value">{v.current_trip_id ? <Link to={`/operation/trips/${v.current_trip_id}`}>V{v.current_trip_number}</Link> : "—"}</span>
          <span className="muted small">{v.current_driver_name ?? ""}</span></div>
      </div>
      <dl className="facts card">
        <div><dt>Licenciamento</dt><dd>{v.registration_valid_until ?? "não informado"}</dd></div>
        <div><dt>Seguro</dt><dd>{v.insurance_valid_until ?? "não informado"}</dd></div>
        <div><dt>Cadastro</dt><dd>{formatDateTime(v.created_at)}</dd></div>
      </dl>

      <div className="grid-2">
        {can("fuel.create") && v.status !== "INACTIVE" && (
          <Collapsible title="Registrar abastecimento"><FuelForm v={v} onDone={refresh} /></Collapsible>
        )}
        {can("vehicles.edit_draft") && v.status !== "INACTIVE" && (
          <Collapsible title="Registrar manutenção ou outro gasto"><MaintenanceForm v={v} onDone={refresh} /></Collapsible>
        )}
        {can("vehicles.edit_draft") && <Collapsible title="Alterar situação"><StatusForm v={v} onDone={refresh} /></Collapsible>}
        {me?.admin && <Collapsible title="Corrigir odômetro (administrador)"><CorrectionForm v={v} onDone={refresh} /></Collapsible>}
      </div>

      {seesExpenses && (
        <section className="card" aria-labelledby="titulo-gastos">
          <h2 id="titulo-gastos" className="h3">Gastos do veículo</h2>
          <p className="muted small">Cadastro não é pagamento: o gasto vira despesa (uma vez) só na aprovação; pago pela empresa, vira também conta a pagar.</p>
          {exp.status === "ready" && (exp.data.items.length === 0 ? <p className="muted">Nenhum gasto visível para você.</p> : (
            <div className="table-wrap">
              <table className="stack">
                <thead><tr><th scope="col">Data</th><th scope="col">Tipo</th><th scope="col" className="num">Valor</th><th scope="col">Detalhe</th>
                  <th scope="col">Pago com</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
                <tbody>{exp.data.items.map((e) => (
                  <tr key={e.id}>
                    <th scope="row">{e.occurred_on}</th>
                    <td data-label="Tipo">{EXPENSE_KIND[e.kind]}{e.trip_number && <span className="muted small"> · V{e.trip_number}</span>}</td>
                    <td data-label="Valor" className="num">{formatMoney(e.amount)}</td>
                    <td data-label="Detalhe">{e.kind === "FUEL"
                      ? `${formatDecimal(e.liters)} l ${FUEL_TYPE[e.fuel_type ?? ""] ?? ""} · ${formatDecimal(e.odometer)} km${e.full_tank ? " · tanque cheio" : ""}${e.station ? ` · ${e.station}` : ""}`
                      : [e.maintenance_type === "PREVENTIVE" ? "preventiva" : e.maintenance_type === "CORRECTIVE" ? "corretiva" : null, e.workshop, e.parts, e.description].filter(Boolean).join(" · ")}
                      <span className="muted small"><br />por {e.created_by_name}</span></td>
                    <td data-label="Pago com">{PAID_WITH[e.paid_with]}</td>
                    <td data-label="Situação">{EXPENSE_STATE[e.state]}{e.decided_by_name && <span className="muted small"><br />{e.decided_by_name}{e.decided_note ? ` — ${e.decided_note}` : ""}</span>}</td>
                    <td><ExpenseDecision e={e} onDone={refresh} /></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          ))}
        </section>
      )}

      <div className="grid-2">
        <section className="card" aria-labelledby="titulo-odometro">
          <h2 id="titulo-odometro" className="h3">Leituras de odômetro</h2>
          {odo.status === "ready" && (
            <ul className="timeline">{odo.data.items.map((r, i) => (
              <li key={i}><strong>{formatDecimal(r.reading)} km</strong><span>{ODOMETER_KIND[r.kind] ?? r.kind}{r.reason ? ` — ${r.reason}` : ""}</span>
                <span className="muted small">{r.recorded_by_name} · {formatDateTime(r.recorded_at)}</span></li>
            ))}</ul>
          )}
        </section>
        <section className="card" aria-labelledby="titulo-hist-veiculo">
          <h2 id="titulo-hist-veiculo" className="h3">Histórico do veículo</h2>
          {hist.status === "ready" && (
            <ul className="timeline">{hist.data.items.map((h, i) => (
              <li key={i}><strong>{AUDIT_ACTION_LABELS[h.action] ?? h.action}</strong><span>{h.actor_name ?? "—"}</span>
                <span className="muted small">{h.client_id ? CLIENT_LABELS[h.client_id] ?? h.client_id : ""} · {formatDateTime(h.occurred_at)}</span></li>
            ))}</ul>
          )}
        </section>
      </div>
    </section>
  );
}

export function Collapsible({ title, children }: { title: string; children: ReactNode }) {
  return (
    <details className="card">
      <summary className="h3">{title}</summary>
      {children}
    </details>
  );
}

function FuelForm({ v, onDone }: { v: Vehicle; onDone: () => void }) {
  const [f, setF] = useState({ odometer: "", liters: "", fuel_type: "DIESEL", full_tank: "true", amount: "", station: "", paid_with: "COMPANY" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const [ok, setOk] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setOk(false);
    try {
      await api.command(`/api/v1/vehicles/${v.id}/fuel`, {
        odometer: toDecimal(f.odometer), liters: toDecimal(f.liters), fuel_type: f.fuel_type, full_tank: f.full_tank === "true",
        amount: toDecimal(f.amount), station: f.station.trim() || null, paid_with: f.paid_with,
      }, key);
      setKey(newIdempotencyKey());
      setOk(true);
      setF({ ...f, odometer: "", liters: "", amount: "" });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label="Abastecimento">
      <Field id="ab-odo" label={`Odômetro (km) * — último ${formatDecimal(v.odometer_km)}`} error={fieldError(error, "odometer")}>
        <input id="ab-odo" inputMode="decimal" value={f.odometer} onChange={(e) => setF({ ...f, odometer: e.target.value })} />
      </Field>
      <Field id="ab-litros" label="Litros *" error={fieldError(error, "liters")}>
        <input id="ab-litros" inputMode="decimal" value={f.liters} onChange={(e) => setF({ ...f, liters: e.target.value })} />
      </Field>
      <Field id="ab-comb" label="Combustível *" error={fieldError(error, "fuel_type")}>
        <select id="ab-comb" value={f.fuel_type} onChange={(e) => setF({ ...f, fuel_type: e.target.value })}>
          {Object.entries(FUEL_TYPE).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      <Field id="ab-cheio" label="Completou o tanque? *" error={fieldError(error, "full_tank")}>
        <select id="ab-cheio" value={f.full_tank} onChange={(e) => setF({ ...f, full_tank: e.target.value })}>
          <option value="true">Sim (conta para km/l)</option><option value="false">Não, parcial</option>
        </select>
      </Field>
      <Field id="ab-valor" label="Valor pago (R$) *" error={fieldError(error, "amount")}>
        <input id="ab-valor" className="money" inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
      </Field>
      <Field id="ab-posto" label="Posto" error={fieldError(error, "station")}>
        <input id="ab-posto" maxLength={120} value={f.station} onChange={(e) => setF({ ...f, station: e.target.value })} />
      </Field>
      <Field id="ab-pago" label="Pago com *" error={fieldError(error, "paid_with")}>
        <select id="ab-pago" value={f.paid_with} onChange={(e) => setF({ ...f, paid_with: e.target.value })}>
          {Object.entries(PAID_WITH).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      <FormError error={error} />
      {ok && <p className="notice span-2" role="status">Abastecimento registrado — aguardando aprovação.</p>}
      <div className="actions span-2"><button className="btn btn-primary">Registrar abastecimento</button></div>
    </form>
  );
}

function MaintenanceForm({ v, onDone }: { v: Vehicle; onDone: () => void }) {
  const [f, setF] = useState({ kind: "MAINTENANCE", maintenance_type: "PREVENTIVE", odometer: "", amount: "", workshop: "", parts: "",
    next_service_km: "", description: "", paid_with: "COMPANY" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const [ok, setOk] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setOk(false);
    try {
      await api.command(`/api/v1/vehicles/${v.id}/maintenance`, {
        kind: f.kind, maintenance_type: f.kind === "MAINTENANCE" ? f.maintenance_type : null, odometer: toDecimal(f.odometer),
        amount: toDecimal(f.amount), workshop: f.workshop.trim() || null, parts: f.parts.trim() || null,
        next_service_km: toDecimal(f.next_service_km), description: f.description.trim() || null, paid_with: f.paid_with,
      }, key);
      setKey(newIdempotencyKey());
      setOk(true);
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  const text = (id: keyof typeof f, label: string, extra: Record<string, unknown> = {}) => (
    <Field id={"mn-" + id} label={label} error={fieldError(error, id)}>
      <input id={"mn-" + id} value={f[id]} onChange={(e) => setF({ ...f, [id]: e.target.value })} {...extra} />
    </Field>
  );
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label="Manutenção ou gasto">
      <Field id="mn-kind" label="Tipo *" error={fieldError(error, "kind")}>
        <select id="mn-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
          {Object.entries(EXPENSE_KIND).filter(([k]) => k !== "FUEL").map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      {f.kind === "MAINTENANCE" && (
        <Field id="mn-tipo" label="Preventiva ou corretiva *" error={fieldError(error, "maintenance_type")}>
          <select id="mn-tipo" value={f.maintenance_type} onChange={(e) => setF({ ...f, maintenance_type: e.target.value })}>
            <option value="PREVENTIVE">Preventiva</option><option value="CORRECTIVE">Corretiva</option>
          </select>
        </Field>
      )}
      {text("amount", "Valor (R$) *", { className: "money", inputMode: "decimal" })}
      {text("odometer", "Odômetro (km)", { inputMode: "decimal" })}
      {text("workshop", "Oficina/fornecedor", { maxLength: 120 })}
      {text("parts", "Peças", { maxLength: 300 })}
      {text("next_service_km", "Próxima revisão (km)", { inputMode: "decimal" })}
      {text("description", f.kind === "MAINTENANCE" ? "Descrição" : "Descrição *", { maxLength: 500 })}
      <Field id="mn-pago" label="Pago com *" error={fieldError(error, "paid_with")}>
        <select id="mn-pago" value={f.paid_with} onChange={(e) => setF({ ...f, paid_with: e.target.value })}>
          {Object.entries(PAID_WITH).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      <FormError error={error} />
      {ok && <p className="notice span-2" role="status">Gasto registrado — aguardando aprovação.</p>}
      <div className="actions span-2"><button className="btn btn-primary">Registrar gasto</button></div>
    </form>
  );
}

function StatusForm({ v, onDone }: { v: Vehicle; onDone: () => void }) {
  const [status, setStatus] = useState(v.status);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post(`/api/v1/vehicles/${v.id}/status`, { status, reason }, `"${v.version}"`);
      setReason("");
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  if (error?.status === 412) return <ConflictState onReload={onDone} />;
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label="Situação do veículo">
      <Field id="vs-status" label="Situação *" error={fieldError(error, "status")}>
        <select id="vs-status" value={status} onChange={(e) => setStatus(e.target.value as Vehicle["status"])}>
          {Object.entries(VEHICLE_STATUS).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
        </select>
      </Field>
      <Field id="vs-motivo" label="Motivo *" error={fieldError(error, "reason")}>
        <input id="vs-motivo" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn">Salvar situação</button></div>
    </form>
  );
}

function CorrectionForm({ v, onDone }: { v: Vehicle; onDone: () => void }) {
  const [reading, setReading] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post(`/api/v1/vehicles/${v.id}/odometer-corrections`, { reading: toDecimal(reading), reason });
      setReading("");
      setReason("");
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} noValidate aria-label="Correção de odômetro">
      <p className="muted small span-2">Única forma de registrar leitura menor que a anterior. Fica no histórico com o motivo.</p>
      <Field id="oc-km" label="Leitura correta (km) *" error={fieldError(error, "reading")}>
        <input id="oc-km" inputMode="decimal" value={reading} onChange={(e) => setReading(e.target.value)} />
      </Field>
      <Field id="oc-motivo" label="Motivo *" error={fieldError(error, "reason")}>
        <input id="oc-motivo" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn">Corrigir odômetro</button></div>
    </form>
  );
}

/** Aprovar/recusar gasto pendente: quem registrou não decide o próprio gasto (o servidor confere). */
export function ExpenseDecision({ e, onDone }: { e: VehicleExpense; onDone: () => void }) {
  const { can, me } = useSession();
  const [mode, setMode] = useState<"idle" | "reject">("idle");
  const [note, setNote] = useState("");
  const [key] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const may = e.state === "PENDING" && (me?.admin || can(e.kind === "FUEL" ? "fuel.approve" : "expenses.approve"))
    && (me?.admin || e.created_by_name !== me?.user.display_name);
  if (!may) return null;
  const run = async (action: "approve" | "reject") => {
    setError(null);
    try {
      if (action === "approve") await api.command(`/api/v1/vehicle-expenses/${e.id}/approve`, {}, key, `"${e.version}"`);
      else await api.post(`/api/v1/vehicle-expenses/${e.id}/reject`, { note }, `"${e.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <div className="actions">
      {mode === "idle" ? (
        <>
          <button className="btn btn-small btn-primary" onClick={() => void run("approve")}>Aprovar</button>
          <button className="btn btn-small btn-ghost" onClick={() => setMode("reject")}>Recusar</button>
        </>
      ) : (
        <>
          <label className="visually-hidden" htmlFor={`rej-${e.id}`}>Motivo da recusa</label>
          <input id={`rej-${e.id}`} placeholder="Motivo da recusa" value={note} onChange={(x) => setNote(x.target.value)} />
          <button className="btn btn-small" onClick={() => void run("reject")}>Confirmar recusa</button>
        </>
      )}
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}
