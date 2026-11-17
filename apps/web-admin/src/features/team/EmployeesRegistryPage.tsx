import { Link } from "react-router";
import { TEMPLATE_LABELS, label } from "../../api/labels";
import type { Employee, JobPosition } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { useResource } from "../../api/useResource";
import { Icon } from "../../components/Icon";
import { EmptyResult, PagedList, SearchBox } from "../../components/Lists";
import { useSession } from "../../session/SessionContext";
import { brDate } from "../finance/financeLabels";

export const EMPLOYEE_STATUS: Record<Employee["status"], [string, string]> = {
  ACTIVE: ["Ativo", "badge-ok"], ON_LEAVE: ["Afastado", "badge-warn"], TERMINATED: ["Desligado", "badge-muted"],
};

/** Equipe › Funcionários (ADR-027): cadastro de RH, com ou sem conta de acesso aos apps. */
export function EmployeesRegistryPage() {
  const { can } = useSession();
  const [f, setF] = useUrlFilters(["q", "status", "position"] as const);
  const [positions] = useResource<{ items: JobPosition[] }>("/api/v1/job-positions?include_archived=true");
  const list = usePaged<Employee>(`/api/v1/employees?${qs({ limit: 50, q: f.q, status: f.status, job_position_id: f.position })}`);
  return (
    <section className="page" aria-labelledby="titulo-funcionarios">
      <header className="page-head">
        <div>
          <h1 id="titulo-funcionarios">Funcionários</h1>
          <p className="muted">Cadastro da equipe com cargo, função, admissão e situação. A conta para entrar nos apps é opcional e criada à parte.</p>
        </div>
        <div className="actions">
          {can("users.view") && <Link className="btn" to="/team/positions">Cargos</Link>}
          {can("users.edit_draft") && <Link className="btn btn-primary" to="/team/employees/new"><Icon name="plus" size={16} /> Novo funcionário</Link>}
        </div>
      </header>
      <SearchBox id="busca-funcionarios" label="Buscar funcionário por nome" value={f.q} onSearch={(q) => setF({ q })} />
      <div className="actions" style={{ justifyContent: "flex-start" }}>
        <div className="field inline"><label htmlFor="fn-sit">Situação</label>
          <select id="fn-sit" value={f.status} onChange={(e) => setF({ status: e.target.value })}>
            <option value="">Todas</option>{Object.entries(EMPLOYEE_STATUS).map(([k, [v]]) => <option key={k} value={k}>{v}</option>)}
          </select></div>
        {positions.status === "ready" && (
          <div className="field inline"><label htmlFor="fn-cargo">Cargo</label>
            <select id="fn-cargo" value={f.position} onChange={(e) => setF({ position: e.target.value })}>
              <option value="">Todos</option>{positions.data.items.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select></div>
        )}
      </div>
      <PagedList list={list} empty={<EmptyResult q={f.q} what="funcionário cadastrado" />}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Nome</th><th scope="col">Cargo</th><th scope="col">Unidade</th><th scope="col">Admissão</th>
              <th scope="col">Situação</th><th scope="col">Acesso aos apps</th></tr></thead>
            <tbody>{items.map((e) => (
              <tr key={e.id}>
                <th scope="row"><Link to={`/team/employees/${e.id}`}>{e.full_name}</Link>{e.duties && <span className="muted small"><br />{e.duties}</span>}</th>
                <td data-label="Cargo">{e.job_position}</td>
                <td data-label="Unidade">{e.branch}</td>
                <td data-label="Admissão">{brDate(e.admission_date)}</td>
                <td data-label="Situação"><span className={"badge " + EMPLOYEE_STATUS[e.status][1]}>{EMPLOYEE_STATUS[e.status][0]}</span></td>
                <td data-label="Acesso aos apps">{e.account
                  ? <span className={"badge " + (e.account.status === "ACTIVE" ? "badge-ok" : "badge-error")}>{label(TEMPLATE_LABELS, e.account.role_template)}{e.account.status === "ACTIVE" ? "" : " · bloqueada"}</span>
                  : <span className="muted">sem conta</span>}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}</PagedList>
    </section>
  );
}
