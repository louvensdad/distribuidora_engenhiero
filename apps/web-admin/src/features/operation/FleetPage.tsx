import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Vehicle } from "../../api/types";
import { useResource } from "../../api/useResource";
import { UNITS, formatDecimal, toDecimal, unitLabel } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { VEHICLE_STATUS } from "./fleetLabels";

/** W07 — frota: veículos por placa, capacidade, situação, viagem atual e avisos. */
export function FleetPage() {
  const { can } = useSession();
  const [res, reload] = useResource<{ items: Vehicle[] }>("/api/v1/vehicles");
  const [creating, setCreating] = useState(false);
  return (
    <section className="page" aria-labelledby="titulo-frota">
      <header className="page-head">
        <div>
          <h1 id="titulo-frota">Frota e entregas por viagem</h1>
          <p className="muted">Abastecimentos, manutenção e viagens por placa. Avisos de revisão não bloqueiam entrega; documento vencido bloqueia a atribuição.</p>
        </div>
        <div className="actions">
          {can("trips.view") && <Link className="btn" to="/operation/trips">Viagens</Link>}
          {can("vehicles.create") && !creating && (
            <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={16} /> Novo veículo</button>
          )}
        </div>
      </header>
      {creating && <VehicleForm onDone={() => { setCreating(false); reload(); }} onCancel={() => setCreating(false)} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhum veículo cadastrado" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Placa</th><th scope="col">Modelo</th><th scope="col">Capacidade</th><th scope="col">Odômetro</th>
              <th scope="col">Situação</th><th scope="col">Viagem atual</th><th scope="col">Avisos</th></tr></thead>
            <tbody>{res.data.items.map((v) => (
              <tr key={v.id}>
                <th scope="row"><Link to={`/operation/fleet/${v.id}`}>{v.plate}</Link></th>
                <td data-label="Modelo">{v.model}</td>
                <td data-label="Capacidade">{formatDecimal(v.capacity_quantity)} {unitLabel(v.capacity_unit, v.capacity_quantity)}</td>
                <td data-label="Odômetro">{formatDecimal(v.odometer_km)} km</td>
                <td data-label="Situação"><span className={"badge " + (v.status === "ACTIVE" ? "badge-ok" : "badge-violet")}>{VEHICLE_STATUS[v.status]}</span></td>
                <td data-label="Viagem atual">{v.current_trip_id ? <Link to={`/operation/trips/${v.current_trip_id}`}>V{v.current_trip_number} · {v.current_driver_name}</Link> : "—"}</td>
                <td data-label="Avisos">{v.alerts.length === 0 ? "—" : v.alerts.map((a) => <span key={a.code} className="badge badge-error">{a.message}</span>)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function VehicleForm({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const [f, setF] = useState({ plate: "", model: "", capacity_quantity: "", capacity_unit: "DUZIA", odometer_km: "", next_service_km: "",
    registration_valid_until: "", insurance_valid_until: "" });
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.command("/api/v1/vehicles", {
        plate: f.plate, model: f.model, capacity_quantity: toDecimal(f.capacity_quantity), capacity_unit: f.capacity_unit,
        odometer_km: toDecimal(f.odometer_km), next_service_km: toDecimal(f.next_service_km),
        registration_valid_until: f.registration_valid_until || null, insurance_valid_until: f.insurance_valid_until || null,
      }, key);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError({ status: 0, code: "UNKNOWN" }));
      setBusy(false);
    }
  };
  const input = (id: keyof typeof f, label: string, props: Record<string, unknown> = {}) => (
    <Field id={"vf-" + id} label={label} error={fieldError(error, id)}>
      <input id={"vf-" + id} value={f[id]} onChange={(e) => setF({ ...f, [id]: e.target.value })} {...props} />
    </Field>
  );
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Novo veículo">
      <h2 className="h3 span-2">Novo veículo</h2>
      {input("plate", "Placa *", { maxLength: 8, autoCapitalize: "characters" })}
      {input("model", "Modelo *", { maxLength: 80 })}
      {input("capacity_quantity", "Capacidade *", { inputMode: "decimal" })}
      <Field id="vf-unit" label="Medida da capacidade *" error={fieldError(error, "capacity_unit")}>
        <select id="vf-unit" value={f.capacity_unit} onChange={(e) => setF({ ...f, capacity_unit: e.target.value })}>
          {UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
        </select>
      </Field>
      {input("odometer_km", "Odômetro atual (km) *", { inputMode: "decimal" })}
      {input("next_service_km", "Próxima revisão (km)", { inputMode: "decimal" })}
      {input("registration_valid_until", "Licenciamento válido até", { type: "date" })}
      {input("insurance_valid_until", "Seguro válido até", { type: "date" })}
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy}>{busy ? "Salvando…" : "Cadastrar veículo"}</button>
      </div>
    </form>
  );
}
