import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api } from "../../api/http";
import type { BankTransaction, FinAccount, OpenFinanceStatus } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { CATEGORY, brDate } from "./financeLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));
const CONN_STATE: Record<string, string> = { PENDING_CONSENT: "Aguardando autorização no banco", ACTIVE: "Ativa", REVOKED: "Revogada", EXPIRED: "Consentimento expirado", ERROR: "Erro" };
const TX_STATE: Record<string, string> = { MATCHED: "Conciliada", SUGGESTED: "Despesa sugerida", UNMATCHED: "Para conferir", IGNORED: "Ignorada" };
const MATCH: Record<string, string> = { payable_settlement: "pagamento de conta", fin_transfer: "transferência", receivable_settlement: "recebimento", payment: "pagamento de cliente" };

/** Open Finance (ADR-021): extrato do banco pelo agregador, conciliado com o sistema; saída nova = despesa sugerida. */
export function OpenFinancePage() {
  const { me, can } = useSession();
  const [st, reload] = useResource<OpenFinanceStatus>("/api/v1/finance/open-finance");
  const [filter, setFilter] = useState("UNMATCHED");
  const [tx, reloadTx] = useResource<{ items: BankTransaction[] }>(`/api/v1/finance/open-finance/transactions${filter ? `?state=${filter}` : ""}`);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = () => { reload(); reloadTx(); };
  if (st.status === "loading") return <LoadingState />;
  if (st.status === "forbidden") return <ForbiddenState />;
  if (st.status === "error") return <ErrorState error={st.error} onRetry={reload} />;
  const s = st.data;
  return (
    <section className="page" aria-labelledby="titulo-of">
      <header className="page-head">
        <div>
          <h1 id="titulo-of">Open Finance</h1>
          <p className="muted">O extrato da conta da empresa chega pelo agregador autorizado (com consentimento do representante, banco a banco). O que já foi lançado é só conciliado; saída nova vira despesa sugerida para aprovação; entrada nunca vira receita sozinha.</p>
        </div>
        <span className={"badge " + (s.state === "CONFIGURADO" ? "badge-ok" : "badge-error")}>{s.state === "CONFIGURADO" ? `Agregador: ${s.provider}` : "NÃO CONFIGURADO"}</span>
      </header>
      {s.state !== "CONFIGURADO" && <p className="notice notice-error" role="status">{s.message}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}
      {me?.admin && <ConnectForm configured={s.state === "CONFIGURADO"} onDone={(m) => { setNotice(m); refresh(); }} />}
      {s.connections.length > 0 && (
        <section className="card" aria-labelledby="titulo-conexoes">
          <h2 id="titulo-conexoes" className="h3">Conexões</h2>
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Conta</th><th scope="col">Banco</th><th scope="col">Situação</th><th scope="col" className="num">Saldo no banco</th><th scope="col" className="num">Saldo no sistema</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{s.connections.map((c) => (
              <tr key={c.id}>
                <th scope="row">{c.account_name}<span className="muted small"><br />CNPJ {c.company_document}</span></th>
                <td data-label="Banco">{c.institution}<span className="muted small"><br />via {c.provider}</span></td>
                <td data-label="Situação">{CONN_STATE[c.state] ?? c.state}{c.last_sync_at && <span className="muted small"><br />última leitura {formatDateTime(c.last_sync_at)}</span>}
                  {c.state === "PENDING_CONSENT" && c.consent_url && <><br /><a href={c.consent_url} target="_blank" rel="noopener noreferrer">Autorizar no banco</a></>}</td>
                <td data-label="Saldo no banco" className="num">{formatMoney(c.provider_balance)}</td>
                <td data-label="Saldo no sistema" className="num">{formatMoney(c.ledger_balance)}</td>
                <td>{me?.admin && ["PENDING_CONSENT", "ACTIVE"].includes(c.state) && <ConnActions id={c.id} onDone={(m) => { setNotice(m); refresh(); }} />}</td>
              </tr>
            ))}</tbody>
          </table></div>
        </section>
      )}
      <section className="card" aria-labelledby="titulo-extrato-of">
        <div className="page-head">
          <h2 id="titulo-extrato-of" className="h3">Extrato importado</h2>
          <div className="field inline"><label htmlFor="of-filtro">Situação</label>
            <select id="of-filtro" value={filter} onChange={(e) => setFilter(e.target.value)}>
              <option value="UNMATCHED">Para conferir</option><option value="SUGGESTED">Despesas sugeridas</option>
              <option value="MATCHED">Conciliadas</option><option value="IGNORED">Ignoradas</option><option value="">Todas</option>
            </select></div>
        </div>
        {tx.status === "loading" && <LoadingState />}
        {tx.status === "ready" && (tx.data.items.length === 0 ? <EmptyState title="Nada nesta situação" /> : (
          <div className="table-wrap"><table className="stack">
            <thead><tr><th scope="col">Data</th><th scope="col">Descrição</th><th scope="col" className="num">Valor</th><th scope="col">Situação</th><th scope="col"><span className="visually-hidden">Ações</span></th></tr></thead>
            <tbody>{tx.data.items.map((t) => (
              <tr key={t.id}>
                <th scope="row">{brDate(t.booked_on)}<span className="muted small"><br />{t.account_name}</span></th>
                <td data-label="Descrição">{t.description}{t.counterpart && <span className="muted small"><br />{t.counterpart}</span>}</td>
                <td data-label="Valor" className="num">{t.direction === "OUT" ? "−" : "+"}{formatMoney(t.amount)}</td>
                <td data-label="Situação">{TX_STATE[t.state]}
                  {t.matched_type && <span className="muted small"><br />com {MATCH[t.matched_type] ?? t.matched_type}</span>}
                  {t.state === "SUGGESTED" && <span className="muted small"><br />{CATEGORY[t.suggested_category ?? ""] ?? ""} — <Link to="/finance/expenses">aprovar em Despesas</Link></span>}
                  {t.decided_note && <span className="muted small"><br />{t.decided_note}</span>}</td>
                <td>{t.state === "UNMATCHED" && can("expenses.confirm") && <IgnoreTx id={t.id} onDone={refresh} />}</td>
              </tr>
            ))}</tbody>
          </table></div>
        ))}
      </section>
    </section>
  );
}

function ConnectForm({ configured, onDone }: { configured: boolean; onDone: (m: string) => void }) {
  const [accs] = useResource<{ items: FinAccount[] }>("/api/v1/finance/accounts");
  const [f, setF] = useState({ account: "", institution: "", cnpj: "" });
  const [error, setError] = useState<ApiError | null>(null);
  const banks = accs.status === "ready" ? accs.data.items.filter((a) => a.kind === "BANK" && a.status === "ACTIVE") : [];
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.post("/api/v1/finance/open-finance/connections", { fin_account_id: f.account || null, institution: f.institution, company_document: f.cnpj });
      onDone("Conexão criada: abra “Autorizar no banco” com o representante da empresa.");
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label="Conectar conta bancária">
      <h2 className="h3 span-2">Conectar conta bancária da empresa</h2>
      <Field id="of-conta" label="Conta no sistema *" error={fieldError(error, "fin_account_id")}>
        <select id="of-conta" value={f.account} onChange={(e) => setF({ ...f, account: e.target.value })}>
          <option value="">Selecione</option>{banks.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
      </Field>
      <Field id="of-banco" label="Banco *" error={fieldError(error, "institution")}>
        <input id="of-banco" maxLength={120} value={f.institution} onChange={(e) => setF({ ...f, institution: e.target.value })} />
      </Field>
      <Field id="of-cnpj" label="CNPJ da empresa *" error={fieldError(error, "company_document")}>
        <input id="of-cnpj" inputMode="numeric" value={f.cnpj} onChange={(e) => setF({ ...f, cnpj: e.target.value })} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">{configured ? "Iniciar autorização" : "Conectar (requer agregador)"}</button></div>
    </form>
  );
}

function ConnActions({ id, onDone }: { id: string; onDone: (m: string) => void }) {
  const [error, setError] = useState<ApiError | null>(null);
  const run = async (action: "sync" | "revoke") => {
    setError(null);
    try {
      if (action === "sync") {
        const { data } = await api.post<{ imported: number; matched: number; suggested: number; unmatched: number }>(`/api/v1/finance/open-finance/connections/${id}/sync`, null);
        onDone(`Extrato lido: ${data.imported} nova(s), ${data.matched} conciliada(s), ${data.suggested} despesa(s) sugerida(s), ${data.unmatched} para conferir.`);
      } else {
        await api.post(`/api/v1/finance/open-finance/connections/${id}/revoke`, { reason: "revogada pelo administrador" });
        onDone("Conexão revogada.");
      }
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <div className="actions">
      <button className="btn btn-small btn-primary" onClick={() => void run("sync")}>Ler extrato</button>
      <button className="btn btn-small btn-ghost" onClick={() => void run("revoke")}>Revogar</button>
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </div>
  );
}

function IgnoreTx({ id, onDone }: { id: string; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const run = async () => {
    setError(null);
    try {
      await api.post(`/api/v1/finance/open-finance/transactions/${id}/ignore`, { reason });
      onDone();
    } catch (err) {
      setError(asError(err));
    }
  };
  return (
    <span className="actions">
      <label className="visually-hidden" htmlFor={`ig-${id}`}>Motivo</label>
      <input id={`ig-${id}`} placeholder="Motivo para ignorar" value={reason} onChange={(e) => setReason(e.target.value)} />
      <button className="btn btn-small" onClick={() => void run()}>Ignorar</button>
      {error && <span className="field-error" role="alert">{error.message}</span>}
    </span>
  );
}
