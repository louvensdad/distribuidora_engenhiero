import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ApiError, api } from "../../api/http";
import { TEMPLATE_LABELS, label } from "../../api/labels";
import type { Employee, EmployeeAccessResult, EmployeeAccount, JobPosition, TemplateKey } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDecimal, formatMoney, toDecimal, todayBr } from "../../app/format";
import { Field, FormError, Hidden, fieldError } from "../../components/Form";
import { ConflictState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { brDate } from "../finance/financeLabels";
import { EMPLOYEE_STATUS } from "./EmployeesRegistryPage";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const TEMPLATES: TemplateKey[] = ["BALCAO", "RECEBIMENTO", "COORDENADOR_ENTREGAS", "MOTORISTA", "CONTADOR"];

export function NewEmployeePage() {
  const navigate = useNavigate();
  return (
    <section className="page" aria-labelledby="titulo-novo-func">
      <header className="page-head"><div>
        <h1 id="titulo-novo-func">Novo funcionário</h1>
        <p className="muted">O cadastro não cria conta de acesso. Depois de salvar, use “Dar acesso aos apps” se a pessoa for usar o sistema.</p>
      </div></header>
      <EmployeeForm employee={null} onSaved={(e) => navigate(`/team/employees/${e.id}`, { replace: true })} onCancel={() => navigate("/team/employees")} />
    </section>
  );
}

/** Ficha do funcionário: dados, edição com versão, desligamento, conta de acesso e histórico. */
export function EmployeeDetailPage() {
  const { id } = useParams();
  const { can } = useSession();
  const [res, reload] = useResource<Employee>(`/api/v1/employees/${id}`);
  const [editing, setEditing] = useState(false);
  if (res.status === "loading") return <LoadingState />;
  if (res.status === "forbidden") return <ForbiddenState />;
  if (res.status === "error") return res.error.status === 404 ? <ForbiddenState /> : <ErrorState error={res.error} onRetry={reload} />;
  const e = res.data;
  const [stLabel, stCls] = EMPLOYEE_STATUS[e.status];
  const hidden = (f: string) => e.masked_fields.includes(f);
  const show = (f: string, v: string | null) => (hidden(f) ? <Hidden /> : v ?? "—");
  return (
    <section className="page" aria-labelledby="titulo-ficha">
      <header className="page-head">
        <div>
          <p className="muted small"><Link to="/team/employees">Funcionários</Link> ›</p>
          <h1 id="titulo-ficha">{e.full_name}</h1>
          <p><span className={"badge " + stCls}>{stLabel}</span> <span className="muted">{e.job_position} · {e.branch}</span></p>
        </div>
        <div className="actions">
          {can("audit.view") && <Link className="btn" to={`/team/audit?entity_type=employee&entity_id=${e.id}`}>Histórico</Link>}
          {can("users.edit_draft") && e.status !== "TERMINATED" && !editing && <button className="btn btn-primary" onClick={() => setEditing(true)}>Editar</button>}
        </div>
      </header>
      {editing ? (
        <EmployeeForm employee={e} onSaved={() => { setEditing(false); reload(); }} onCancel={() => setEditing(false)} />
      ) : (
        <div className="grid-2">
          <div className="card">
            <h2 className="h3">Trabalho</h2>
            <dl className="kv">
              <dt>Cargo</dt><dd>{e.job_position}</dd>
              <dt>Função / atividades</dt><dd>{e.duties ?? "—"}</dd>
              <dt>Unidade</dt><dd>{e.branch}</dd>
              <dt>Admissão</dt><dd>{brDate(e.admission_date)}</dd>
              {e.termination_date && <><dt>Desligamento</dt><dd>{brDate(e.termination_date)}</dd></>}
              <dt>Jornada</dt><dd>{e.work_schedule ?? "—"}</dd>
              <dt>Salário base</dt><dd>{hidden("base_salary") ? <Hidden /> : e.base_salary ? formatMoney(e.base_salary) : "—"}</dd>
            </dl>
          </div>
          <div className="card">
            <h2 className="h3">Contato e dados pessoais</h2>
            <dl className="kv">
              <dt>Telefone</dt><dd>{show("phone", e.phone)}</dd>
              <dt>CPF</dt><dd>{show("cpf", e.cpf)}</dd>
              <dt>Nascimento</dt><dd>{hidden("birth_date") ? <Hidden /> : e.birth_date ? brDate(e.birth_date) : "—"}</dd>
              <dt>Endereço</dt><dd>{show("address", e.address)}</dd>
              <dt>Observações</dt><dd>{e.notes ?? "—"}</dd>
            </dl>
            <p className="muted small">Cadastrado por {e.created_by_name}.</p>
          </div>
        </div>
      )}
      <AccessCard e={e} onChanged={reload} />
      {can("users.edit_draft") && e.status !== "TERMINATED" && !editing && <TerminationCard e={e} onDone={reload} />}
    </section>
  );
}

function EmployeeForm({ employee, onSaved, onCancel }: { employee: Employee | null; onSaved: (e: Employee) => void; onCancel: () => void }) {
  const { canSeeField, me } = useSession();
  const [positions] = useResource<{ items: JobPosition[] }>("/api/v1/job-positions");
  const [branches] = useResource<{ items: { id: string; name: string; status: string }[] }>("/api/v1/branches");
  const [form, setForm] = useState({
    full_name: employee?.full_name ?? "", job_position_id: employee?.job_position_id ?? "", branch_id: employee?.branch_id ?? me?.branch.id ?? "",
    duties: employee?.duties ?? "", admission_date: employee?.admission_date ?? todayBr(), status: employee?.status ?? "ACTIVE",
    work_schedule: employee?.work_schedule ?? "", phone: employee?.phone ?? "", cpf: employee?.cpf ?? "", birth_date: employee?.birth_date ?? "",
    address: employee?.address ?? "", base_salary: employee?.base_salary ? formatDecimal(employee.base_salary) : "", notes: employee?.notes ?? "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    const body: Record<string, string | null> = {
      full_name: form.full_name.trim(), job_position_id: form.job_position_id || null, branch_id: form.branch_id || null,
      duties: form.duties.trim() || null, admission_date: form.admission_date, status: form.status,
      work_schedule: form.work_schedule.trim() || null, notes: form.notes.trim() || null,
    };
    // Campos que a pessoa não vê não são enviados (o servidor mantém o valor gravado).
    if (canSeeField("operational_contact")) body.phone = form.phone.trim() || null;
    if (canSeeField("personal_document")) {
      body.cpf = form.cpf.trim() || null;
      body.birth_date = form.birth_date || null;
      body.address = form.address.trim() || null;
    }
    if (canSeeField("employee_salary")) body.base_salary = form.base_salary.trim() ? toDecimal(form.base_salary) ?? form.base_salary : null;
    try {
      const { data } = employee
        ? await api.put<Employee>(`/api/v1/employees/${employee.id}`, body, `"${employee.version}"`)
        : await api.post<Employee>("/api/v1/employees", body);
      onSaved(data);
    } catch (e) {
      setError(asError(e));
      setBusy(false);
    }
  };
  if (error?.status === 412) return <ConflictState onReload={onCancel} />;
  const active = positions.status === "ready" ? positions.data.items : [];
  return (
    <form className="card form-grid" onSubmit={(e) => void submit(e)} aria-label={employee ? "Editar funcionário" : "Cadastro do funcionário"} noValidate>
      <h2 className="h3 span-2">Trabalho</h2>
      <Field id="fn-nome" label="Nome completo *" error={fieldError(error, "full_name")}>
        <input id="fn-nome" maxLength={160} value={form.full_name} onChange={set("full_name")} autoFocus={!employee} />
      </Field>
      <Field id="fn-cargo-sel" label="Cargo *" hint={active.length === 0 ? "Cadastre os cargos em Equipe › Cargos" : undefined} error={fieldError(error, "job_position_id")}>
        <select id="fn-cargo-sel" value={form.job_position_id} onChange={set("job_position_id")}>
          <option value="">Escolha…</option>
          {active.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          {employee && !active.some((p) => p.id === employee.job_position_id) && <option value={employee.job_position_id}>{employee.job_position} (arquivado)</option>}
        </select>
      </Field>
      <Field id="fn-funcao" label="Função / atividades" error={fieldError(error, "duties")}>
        <input id="fn-funcao" maxLength={500} value={form.duties} onChange={set("duties")} placeholder="Ex.: atende o balcão e confere o caixa" />
      </Field>
      <Field id="fn-unidade" label="Unidade" error={fieldError(error, "branch_id")}>
        <select id="fn-unidade" value={form.branch_id} onChange={set("branch_id")}>
          {branches.status === "ready" ? branches.data.items.filter((b) => b.status === "ACTIVE" || b.id === form.branch_id)
            .map((b) => <option key={b.id} value={b.id}>{b.name}</option>) : <option value={form.branch_id}>{employee?.branch ?? me?.branch.name}</option>}
        </select>
      </Field>
      <Field id="fn-adm" label="Admissão *" error={fieldError(error, "admission_date")}>
        <input id="fn-adm" type="date" value={form.admission_date} onChange={set("admission_date")} />
      </Field>
      {employee && (
        <Field id="fn-sit-edit" label="Situação" error={fieldError(error, "status")}>
          <select id="fn-sit-edit" value={form.status} onChange={set("status")}>
            <option value="ACTIVE">Ativo</option><option value="ON_LEAVE">Afastado</option>
          </select>
        </Field>
      )}
      <Field id="fn-jornada" label="Jornada" error={fieldError(error, "work_schedule")}>
        <input id="fn-jornada" maxLength={120} value={form.work_schedule} onChange={set("work_schedule")} placeholder="Ex.: seg a sáb, 6h às 14h" />
      </Field>
      {canSeeField("employee_salary") && (
        <Field id="fn-salario" label="Salário base (R$)" error={fieldError(error, "base_salary")}>
          <input id="fn-salario" inputMode="decimal" value={form.base_salary} onChange={set("base_salary")} placeholder="Ex.: 2.500,00" />
        </Field>
      )}
      <h2 className="h3 span-2">Contato e dados pessoais</h2>
      {canSeeField("operational_contact") && (
        <Field id="fn-tel" label="Telefone" error={fieldError(error, "phone")}><input id="fn-tel" maxLength={40} value={form.phone} onChange={set("phone")} /></Field>
      )}
      {canSeeField("personal_document") ? (
        <>
          <Field id="fn-cpf" label="CPF" error={fieldError(error, "cpf")}><input id="fn-cpf" inputMode="numeric" value={form.cpf} onChange={set("cpf")} /></Field>
          <Field id="fn-nasc" label="Nascimento" error={fieldError(error, "birth_date")}><input id="fn-nasc" type="date" value={form.birth_date} onChange={set("birth_date")} /></Field>
          <Field id="fn-end" label="Endereço" error={fieldError(error, "address")}><input id="fn-end" maxLength={300} value={form.address} onChange={set("address")} /></Field>
        </>
      ) : <p className="muted small span-2">CPF, nascimento e endereço ficam ocultos pela sua permissão (e não são alterados ao salvar).</p>}
      <Field id="fn-obs" label="Observações" error={fieldError(error, "notes")}><input id="fn-obs" maxLength={1000} value={form.notes} onChange={set("notes")} /></Field>
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy || !form.full_name.trim()}>{busy ? "Salvando…" : employee ? "Salvar alterações" : "Cadastrar funcionário"}</button>
      </div>
    </form>
  );
}

/** Conta de acesso: mostra a conta ligada, ou cria no provedor de identidade (NÃO CONFIGURADO sem credencial), ou liga uma existente. */
function AccessCard({ e, onChanged }: { e: Employee; onChanged: () => void }) {
  const { can } = useSession();
  const manage = can("users.edit_draft") && e.status !== "TERMINATED" && !e.account;
  const [info] = useResource<{ items: EmployeeAccount[]; account_creation: "CONFIGURED" | "NOT_CONFIGURED" }>(manage ? "/api/v1/employees/unlinked-accounts" : null);
  const [positions] = useResource<{ items: JobPosition[] }>(manage ? "/api/v1/job-positions?include_archived=true" : null);
  const suggested = positions.status === "ready" ? positions.data.items.find((p) => p.id === e.job_position_id)?.suggested_template ?? "" : "";
  const [username, setUsername] = useState(() => e.full_name.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, ".").replace(/^\.|\.$/g, "").slice(0, 40));
  const [template, setTemplate] = useState<string>("");
  const [link, setLink] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [created, setCreated] = useState<EmployeeAccessResult | null>(null);
  const tpl = template || suggested;

  const grant = async () => {
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.post<EmployeeAccessResult>(`/api/v1/employees/${e.id}/access`, { username: username.trim(), role_template: tpl });
      setCreated(data);
    } catch (err) {
      setError(asError(err));
    } finally {
      setBusy(false);
    }
  };
  const doLink = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/v1/employees/${e.id}/account-link`, { user_id: link });
      onChanged();
    } catch (err) {
      setError(asError(err));
      setBusy(false);
    }
  };

  return (
    <section className="card" aria-labelledby="titulo-acesso-func">
      <h2 className="h3" id="titulo-acesso-func">Acesso aos apps</h2>
      {created && (
        <div className="notice notice-ok" role="alert">
          <p><strong>Conta criada: {created.username}</strong> · modelo {label(TEMPLATE_LABELS, tpl)}</p>
          <p>Senha temporária (aparece só agora): <code className="big">{created.temporary_password}</code></p>
          <p className="small">{created.note}</p>
          <button className="btn btn-small" onClick={() => { setCreated(null); onChanged(); }}>Já entreguei a senha</button>
        </div>
      )}
      {!created && e.account && (
        <dl className="kv">
          <dt>Conta</dt><dd>{e.account.display_name} <span className={"badge " + (e.account.status === "ACTIVE" ? "badge-ok" : "badge-error")}>{e.account.status === "ACTIVE" ? "ativa" : "bloqueada"}</span></dd>
          <dt>Modelo de acesso</dt><dd>{label(TEMPLATE_LABELS, e.account.role_template)}</dd>
          <dt>Permissões</dt><dd>{can("permissions.view") ? <Link to={`/team/access?user=${e.account.user_id}`}>Ver no mapa de acessos</Link> : "—"}</dd>
        </dl>
      )}
      {!created && !e.account && !manage && <p className="muted">Sem conta de acesso.{e.status === "TERMINATED" ? " Funcionário desligado." : ""}</p>}
      {!created && manage && info.status === "ready" && (
        <>
          {info.data.account_creation === "NOT_CONFIGURED" ? (
            <div className="state state-warning" role="status">
              <h3>Criação de conta NÃO CONFIGURADA</h3>
              <p>Falta a credencial de serviço do provedor de identidade no servidor. O cadastro vale normalmente; a conta pode ser criada quando a credencial for configurada, ou ligada a uma conta já existente abaixo.</p>
            </div>
          ) : (
            <div className="form-grid">
              <Field id="ac-user" label="Nome de usuário *" hint="Letras minúsculas, números e ponto" error={fieldError(error, "username")}>
                <input id="ac-user" value={username} onChange={(ev) => setUsername(ev.target.value.toLowerCase())} maxLength={40} />
              </Field>
              <Field id="ac-tpl" label="Modelo de acesso *" hint={suggested ? `Sugestão do cargo: ${label(TEMPLATE_LABELS, suggested)}` : undefined} error={fieldError(error, "role_template")}>
                <select id="ac-tpl" value={tpl} onChange={(ev) => setTemplate(ev.target.value)}>
                  <option value="">Escolha…</option>
                  {TEMPLATES.map((t) => <option key={t} value={t}>{label(TEMPLATE_LABELS, t)}</option>)}
                </select>
              </Field>
              <div className="actions span-2">
                <button className="btn btn-primary" disabled={busy || !tpl || username.length < 3 || !can("permissions.edit_draft")} onClick={() => void grant()}>
                  {busy ? "Criando…" : "Criar conta e dar acesso"}
                </button>
              </div>
              <p className="muted small span-2">A pessoa troca a senha no primeiro acesso. Administrador não é concedido por aqui; ajustes finos ficam no mapa de acessos.</p>
            </div>
          )}
          {info.data.items.length > 0 && (
            <div className="form-grid">
              <Field id="ac-link" label="Ou ligar a uma conta que já existe">
                <select id="ac-link" value={link} onChange={(ev) => setLink(ev.target.value)}>
                  <option value="">Escolha…</option>
                  {info.data.items.map((a) => <option key={a.user_id} value={a.user_id}>{a.display_name} — {label(TEMPLATE_LABELS, a.role_template)}</option>)}
                </select>
              </Field>
              <div className="actions"><button className="btn" disabled={busy || !link} onClick={() => void doLink()}>Ligar conta</button></div>
            </div>
          )}
          <FormError error={error} />
        </>
      )}
    </section>
  );
}

function TerminationCard({ e, onDone }: { e: Employee; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [date, setDate] = useState(todayBr());
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  if (!open) return <p><button className="btn btn-ghost" onClick={() => setOpen(true)}>Registrar desligamento…</button></p>;
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/v1/employees/${e.id}/termination`, { termination_date: date, reason: reason.trim() }, `"${e.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
      setBusy(false);
    }
  };
  return (
    <section className="card form-grid" aria-labelledby="titulo-deslig">
      <h2 className="h3 span-2" id="titulo-deslig">Registrar desligamento</h2>
      <Field id="dl-data" label="Data do desligamento *" error={fieldError(error, "termination_date")}>
        <input id="dl-data" type="date" value={date} onChange={(ev) => setDate(ev.target.value)} />
      </Field>
      <Field id="dl-motivo" label="Motivo *" error={fieldError(error, "reason")}>
        <input id="dl-motivo" maxLength={500} value={reason} onChange={(ev) => setReason(ev.target.value)} />
      </Field>
      <p className="muted small span-2">{e.account?.status === "ACTIVE" ? "A conta de acesso é bloqueada na hora (os apps param de funcionar para esta pessoa)." : "Sem conta ativa para bloquear."} O histórico continua.</p>
      <FormError error={error} />
      <div className="actions span-2">
        <button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy || !reason.trim()} onClick={() => void submit()}>{busy ? "Registrando…" : "Confirmar desligamento"}</button>
      </div>
    </section>
  );
}
