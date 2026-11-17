import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Trip, TripSummary } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { EmptyResult, PagedList, SearchBox } from "../../components/Lists";
import { formatDateTime } from "../../app/format";
import { FormError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { useSession } from "../../session/SessionContext";
import { TRIP_STATE } from "./fleetLabels";

/** Viagens no escopo: coordenação vê o setor; motorista só as atribuídas a ele (filtro do servidor). */
export function TripsPage() {
  const { can } = useSession();
  const navigate = useNavigate();
  const [f, setF] = useUrlFilters(["state", "q"] as const);
  const list = usePaged<TripSummary>(`/api/v1/trips?${qs({ limit: 50, state: f.state, q: f.q })}`);
  const [key] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const create = async () => {
    setError(null);
    try {
      const { data } = await api.command<Trip>("/api/v1/trips", {}, key);
      navigate(`/operation/trips/${data.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
    }
  };
  return (
    <section className="page" aria-labelledby="titulo-viagens">
      <header className="page-head">
        <div>
          <h1 id="titulo-viagens">Viagens de entrega</h1>
          <p className="muted">Rascunho → atribuída → em rota → retornou → conferida. Ocorrência nunca conta como entrega.</p>
        </div>
        <div className="actions">
          {can("vehicles.view") && <Link className="btn" to="/operation/fleet">Frota</Link>}
          {can("trips.create") && <button className="btn btn-primary" onClick={() => void create()}><Icon name="plus" size={16} /> Criar viagem</button>}
        </div>
      </header>
      <FormError error={error} />
      <SearchBox id="busca-viagens" label="Buscar viagem por número ou placa" placeholder="Nº da viagem ou placa" value={f.q} onSearch={(q) => setF({ q })} />
      <div className="field inline">
        <label htmlFor="vg-estado">Situação</label>
        <select id="vg-estado" value={f.state} onChange={(e) => setF({ state: e.target.value })}>
          <option value="">Todas</option>
          {Object.entries(TRIP_STATE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </div>
      <PagedList list={list} empty={<EmptyResult q={f.q} what="viagem" />}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Viagem</th><th scope="col">Data</th><th scope="col">Veículo</th><th scope="col">Motorista</th>
              <th scope="col">Entregas</th><th scope="col">Situação</th><th scope="col">Criada</th></tr></thead>
            <tbody>{items.map((t) => (
              <tr key={t.id}>
                <th scope="row"><Link to={`/operation/trips/${t.id}`}>V{t.number}</Link></th>
                <td data-label="Data">{t.planned_date}</td>
                <td data-label="Veículo">{t.plate ?? "—"}</td>
                <td data-label="Motorista">{t.driver_name ?? "—"}</td>
                <td data-label="Entregas">{t.delivered}/{t.stops}</td>
                <td data-label="Situação"><span className="badge badge-violet">{TRIP_STATE[t.state]}</span></td>
                <td data-label="Criada">{formatDateTime(t.created_at)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}</PagedList>
    </section>
  );
}
