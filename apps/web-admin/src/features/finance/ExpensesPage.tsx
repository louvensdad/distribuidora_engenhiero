import { useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { Expense } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { PagedList } from "../../components/Lists";
import { formatDateTime, formatMoney, toDecimal, todayBr } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { EmptyState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { CATEGORY, EXPENSE_STATE, PAYABLE_STATE, brDate } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** FIN-01 — despesas por categoria, com competência, vencimento, situação e conta a pagar vinculada. */
export function ExpensesPage() {
  const { can } = useSession();
  const [f, setF] = useUrlFilters(["state", "category"] as const);
  const [creating, setCreating] = useState(false);
  const list = usePaged<Expense>(`/api/v1/expenses?${qs({ limit: 50, state: f.state, category: f.category })}`);
  const reload = list.reload;
  return (
    <section className="page" aria-labelledby="titulo-despesas">
      <header className="page-head">
        <div>
          <h1 id="titulo-despesas">Despesas</h1>
          <p className="muted">A despesa entra no resultado uma vez, na aprovação, pela competência. Pagar é baixar a conta a pagar — não gera outra despesa.</p>
        </div>
        {can("expenses.create") && !creating && (
          <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={16} /> Cadastrar despesa</button>
        )}
      </header>
      {creating && <ExpenseForm onDone={() => { setCreating(false); reload(); }} onCancel={() => setCreating(false)} />}
      <div className="actions" style={{ justifyContent: "flex-start" }}>
        <div className="field inline"><label htmlFor="dp-sit">Situação</label>
          <select id="dp-sit" value={f.state} onChange={(e) => setF({ state: e.target.value })}>
            <option value="">Todas</option>{Object.entries(EXPENSE_STATE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></div>
        <div className="field inline"><label htmlFor="dp-cat">Categoria</label>
          <select id="dp-cat" value={f.category} onChange={(e) => setF({ category: e.target.value })}>
            <option value="">Todas</option>{Object.entries(CATEGORY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></div>
      </div>
      <PagedList list={list} empty={<EmptyState title="Nenhuma despesa" />}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Despesa</th><th scope="col">Categoria</th><th scope="col">Competência</th><th scope="col">Vencimento</th>
              <th scope="col" className="num">Valor</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{items.map((x) => (
              <tr key={x.id}>
                <th scope="row">{x.description}<span className="muted small"><br />{x.payee ?? x.supplier_name ?? ""} · por {x.created_by_name} {formatDateTime(x.created_at)}</span></th>
                <td data-label="Categoria">{CATEGORY[x.category]}<span className="muted small"><br />{x.subcategory}</span></td>
                <td data-label="Competência">{brDate(x.competence)}</td>
                <td data-label="Vencimento">{brDate(x.due_date)}</td>
                <td data-label="Valor" className="num">{formatMoney(x.amount)}</td>
                <td data-label="Situação">{EXPENSE_STATE[x.state]}
                  {x.payable_state && <span className="muted small"><br />conta: {PAYABLE_STATE[x.payable_state]}{x.payable_balance && x.payable_balance !== "0.00" ? ` (${formatMoney(x.payable_balance)})` : ""}</span>}
                  {x.decided_by_name && <span className="muted small"><br />{x.decided_by_name}{x.decided_note ? ` — ${x.decided_note}` : ""}</span>}
                  {x.reverse_reason && <span className="muted small"><br />estorno: {x.reverse_reason}</span>}</td>
                <td><ExpenseActions x={x} onDone={reload} /></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}</PagedList>
    </section>
  );
}

function ExpenseForm({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const today = todayBr();
  const [f, setF] = useState({ category: "ESTRUTURA", subcategory: "", description: "", payee: "", amount: "", competence: today, due_date: "" });
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.command("/api/v1/expenses", { category: f.category, subcategory: f.subcategory, description: f.description,
        payee: f.payee.trim() || null, amount: toDecimal(f.amount), competence: f.competence || null, due_date: f.due_date || null }, key);
      onDone();
    } catch (err) {
      setError(asError(err));
      setBusy(false);
    }
  };
  const input = (id: keyof typeof f, label: string, extra: Record<string, unknown> = {}) => (
    <Field id={"dp-" + id} label={label} error={fieldError(error, id)}>
      <input id={"dp-" + id} value={f[id]} onChange={(e) => setF({ ...f, [id]: e.target.value })} {...extra} />
    </Field>
  );
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Nova despesa">
      <h2 className="h3 span-2">Nova despesa</h2>
      <Field id="dp-category" label="Categoria *" error={fieldError(error, "category")}>
        <select id="dp-category" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}>
          {Object.entries(CATEGORY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
      {input("subcategory", "Subcategoria *", { maxLength: 60, placeholder: "ex.: Aluguel, Energia, Guia do Simples" })}
      {input("description", "Descrição *", { maxLength: 300 })}
      {input("payee", "Favorecido", { maxLength: 120 })}
      {input("amount", "Valor (R$) *", { className: "money", inputMode: "decimal" })}
      {input("competence", "Competência *", { type: "date" })}
      {input("due_date", "Vencimento", { type: "date" })}
      <FormError error={error} />
      <div className="actions span-2">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancelar</button>
        <button className="btn btn-primary" disabled={busy}>{busy ? "Salvando…" : "Cadastrar despesa"}</button>
      </div>
    </form>
  );
}

function ExpenseActions({ x, onDone }: { x: Expense; onDone: () => void }) {
  const { can, me } = useSession();
  const [mode, setMode] = useState<"idle" | "reject" | "reverse">("idle");
  const [note, setNote] = useState("");
  const [key] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const mayDecide = x.state === "PENDING" && (me?.admin || can("expenses.approve")) && (me?.admin || x.created_by_name !== me?.user.display_name);
  const mayReverse = x.state === "APPROVED" && (me?.admin || can("expenses.reverse"));
  if (!mayDecide && !mayReverse) return null;
  const run = async (action: "approve" | "reject" | "reverse") => {
    setError(null);
    try {
      if (action === "approve") await api.command(`/api/v1/expenses/${x.id}/approve`, {}, key, `"${x.version}"`);
      else await api.post(`/api/v1/expenses/${x.id}/${action}`, { note }, `"${x.version}"`);
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <div className="actions">
      {mode === "idle" && mayDecide && <>
        <button className="btn btn-small btn-primary" onClick={() => void run("approve")}>Aprovar</button>
        <button className="btn btn-small btn-ghost" onClick={() => setMode("reject")}>Recusar</button>
      </>}
      {mode === "idle" && mayReverse && <button className="btn btn-small btn-ghost" onClick={() => setMode("reverse")}>Estornar</button>}
      {mode !== "idle" && <>
        <label className="visually-hidden" htmlFor={`dpn-${x.id}`}>Motivo</label>
        <input id={`dpn-${x.id}`} placeholder="Motivo" value={note} onChange={(e) => setNote(e.target.value)} />
        <button className="btn btn-small" onClick={() => void run(mode)}>{mode === "reject" ? "Confirmar recusa" : "Confirmar estorno"}</button>
      </>}
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}
