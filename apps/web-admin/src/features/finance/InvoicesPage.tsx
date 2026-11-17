import { useState } from "react";
import { ApiError, api, newIdempotencyKey } from "../../api/http";
import type { FiscalSettings, Invoice, Order } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { DOC_TYPE, INVOICE_BADGE, INVOICE_STATE, OPERATION, OUTCOME, TAX_REGIME } from "./fiscalLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/**
 * Notas fiscais (F08): rascunho a partir do pedido → conferência do contador → transmissão pelo provedor →
 * autorizada | rejeitada | incerta; cancelamento à parte. Sem provedor contratado tudo fica BLOQUEADO, sem simulação.
 */
export function InvoicesPage() {
  const [res, reload] = useResource<{ items: Invoice[] }>("/api/v1/invoices");
  const [settings, reloadSettings] = useResource<FiscalSettings>("/api/v1/fiscal-settings");
  const [notice, setNotice] = useState<string | null>(null);
  const done = (m: string) => { setNotice(m); reload(); reloadSettings(); };
  return (
    <section className="page" aria-labelledby="titulo-notas">
      <header className="page-head">
        <div>
          <h1 id="titulo-notas">Notas fiscais</h1>
          <p className="muted">Transmitida, autorizada, rejeitada, incerta e cancelada são estados diferentes. Só a nota
            <strong> autorizada</strong> tem chave, protocolo e XML. Falha ou demora do provedor nunca aparece como emitida.</p>
        </div>
      </header>
      {notice && <p className="notice" role="status">{notice}</p>}
      {settings.status === "ready" && <SettingsCard s={settings.data} onDone={done} />}
      {settings.status === "ready" && settings.data.document_type && <NewDraft onDone={done} />}
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0
        ? <EmptyState title="Nenhuma nota" />
        : res.data.items.map((n) => <InvoiceCard key={n.id} n={n} onDone={done} />))}
    </section>
  );
}

function SettingsCard({ s, onDone }: { s: FiscalSettings; onDone: (m: string) => void }) {
  const { me } = useSession();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    document_type: s.document_type ?? "", environment: s.environment, series: s.series?.toString() ?? "",
    default_cfop: s.default_cfop ?? "", issuer_document: s.issuer_document ?? "", state_registration: s.state_registration ?? "",
    tax_regime: s.tax_regime ?? "", notes: s.notes ?? "",
  });
  const [error, setError] = useState<ApiError | null>(null);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  const fe = (f: string) => error?.fieldErrors.find((x) => x.field === f)?.message;
  const save = async () => {
    setError(null);
    try {
      await api.put("/api/v1/fiscal-settings", {
        ...form, document_type: form.document_type || null, series: form.series ? Number(form.series) : null,
        default_cfop: form.default_cfop || null, issuer_document: form.issuer_document || null,
        state_registration: form.state_registration || null, tax_regime: form.tax_regime || null, notes: form.notes || null,
      }, `"${s.version}"`);
      setOpen(false);
      onDone("Configuração fiscal salva.");
    } catch (err) {
      setError(asError(err));
    }
  };
  const blocked = s.provider_state === "NOT_CONFIGURED";
  return (
    <article className={"card" + (blocked ? " state-warning" : "")} aria-label="Configuração fiscal">
      <div className="page-head">
        <div>
          <h2 className="h3">Configuração fiscal</h2>
          <p className="muted small">
            {s.document_type ? DOC_TYPE[s.document_type] : "Tipo de documento ainda não definido com o contador"}
            {" · "}{s.environment === "PRODUCAO" ? "produção" : "homologação"}
            {s.tax_regime ? ` · ${TAX_REGIME[s.tax_regime] ?? s.tax_regime}` : ""}
          </p>
        </div>
        <span className={"badge " + (blocked ? "badge-warn" : "badge-ok")}>
          {blocked ? "Emissão BLOQUEADA — provedor NÃO CONFIGURADO" : `Provedor: ${s.provider}`}
        </span>
      </div>
      {blocked && <p className="text-warning">Provedor de emissão e certificado digital ainda não contratados. Rascunhos e
        conferência funcionam; nada é transmitido nem simulado.</p>}
      {s.missing.length > 0 && <p className="small">Falta definir: {s.missing.join(" · ")}</p>}
      {me?.admin && !open && <button className="btn btn-small btn-ghost" onClick={() => setOpen(true)}>Editar configuração</button>}
      {open && (
        <div className="form-grid" role="group" aria-label="Editar configuração fiscal">
          <div className="field"><label htmlFor="fs-tipo">Documento fiscal (com o contador)</label>
            <select id="fs-tipo" value={form.document_type} onChange={set("document_type")}>
              <option value="">Ainda não definido</option>
              {Object.entries(DOC_TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>{fe("document_type") && <span className="field-error">{fe("document_type")}</span>}</div>
          <div className="field"><label htmlFor="fs-amb">Ambiente</label>
            <select id="fs-amb" value={form.environment} onChange={set("environment")}>
              <option value="HOMOLOGACAO">Homologação (testes)</option><option value="PRODUCAO">Produção</option>
            </select></div>
          <div className="field"><label htmlFor="fs-cnpj">CNPJ do emitente</label>
            <input id="fs-cnpj" inputMode="numeric" value={form.issuer_document} onChange={set("issuer_document")} />
            {fe("issuer_document") && <span className="field-error">{fe("issuer_document")}</span>}</div>
          <div className="field"><label htmlFor="fs-ie">Inscrição estadual</label>
            <input id="fs-ie" value={form.state_registration} onChange={set("state_registration")} /></div>
          <div className="field"><label htmlFor="fs-reg">Regime tributário</label>
            <select id="fs-reg" value={form.tax_regime} onChange={set("tax_regime")}>
              <option value="">Selecione</option>
              {Object.entries(TAX_REGIME).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select></div>
          <div className="field"><label htmlFor="fs-serie">Série</label>
            <input id="fs-serie" inputMode="numeric" value={form.series} onChange={set("series")} />
            {fe("series") && <span className="field-error">{fe("series")}</span>}</div>
          <div className="field"><label htmlFor="fs-cfop">CFOP padrão de venda</label>
            <input id="fs-cfop" inputMode="numeric" value={form.default_cfop} onChange={set("default_cfop")} />
            {fe("default_cfop") && <span className="field-error">{fe("default_cfop")}</span>}</div>
          <div className="field span-2"><label htmlFor="fs-obs">Observação</label>
            <input id="fs-obs" value={form.notes} onChange={set("notes")} /></div>
          <div className="actions span-2">
            <button className="btn btn-primary" onClick={() => void save()}>Salvar configuração</button>
            <button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancelar</button>
          </div>
        </div>
      )}
      {error && error.fieldErrors.length === 0 && <p className="notice notice-error" role="alert">{error.message}</p>}
    </article>
  );
}

function NewDraft({ onDone }: { onDone: (m: string) => void }) {
  const { can } = useSession();
  const [orders] = useResource<{ items: Order[] }>("/api/v1/orders?limit=50");
  const [orderId, setOrderId] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  if (!can("invoices.create") || orders.status !== "ready") return null;
  const eligible = orders.data.items.filter((o) => o.state !== "CANCELLED");
  return (
    <form className="card form-grid" aria-label="Gerar rascunho de nota" onSubmit={async (e) => {
      e.preventDefault();
      setError(null);
      try {
        await api.command("/api/v1/invoices", { order_id: orderId || null }, key);
        setOrderId("");
        setKey(newIdempotencyKey());
        onDone("Rascunho criado. O contador confere antes de transmitir.");
      } catch (err) {
        setError(asError(err));
      }
    }}>
      <div className="field"><label htmlFor="nf-pedido">Pedido</label>
        <select id="nf-pedido" value={orderId} onChange={(e) => setOrderId(e.target.value)}>
          <option value="">Selecione</option>
          {eligible.map((o) => <option key={o.id} value={o.id}>#{o.number} · {o.customer_name} · {formatMoney(o.total)}</option>)}
        </select></div>
      <div className="actions"><button className="btn btn-primary" type="submit">Gerar rascunho</button></div>
      {error && <p className="notice notice-error span-2" role="alert">{error.message}</p>}
    </form>
  );
}

function InvoiceCard({ n, onDone }: { n: Invoice; onDone: (m: string) => void }) {
  const { can, me } = useSession();
  const [reason, setReason] = useState("");
  const [ncm, setNcm] = useState<Record<string, string>>({});
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState<ApiError | null>(null);
  const may = (cap: string) => me?.admin || can(cap);
  const run = async (fn: () => Promise<{ data: unknown }>, msg: (v: Invoice) => string) => {
    setError(null);
    try {
      const r = await fn();
      setKey(newIdempotencyKey());
      onDone(msg(r.data as Invoice));
    } catch (err) {
      setError(asError(err));
    }
  };
  const missingNcm = n.state === "DRAFT" ? n.lines.filter((l) => !l.ncm) : [];
  return (
    <article className="card" aria-label={`Nota do pedido ${n.order_number}`}>
      <div className="page-head">
        <div>
          <h2 className="h3">Pedido #{n.order_number} · {n.customer_name} · {formatMoney(n.total)}</h2>
          <p className="muted small">{DOC_TYPE[n.document_type]} · {n.environment === "PRODUCAO" ? "produção" : "homologação"}
            {n.number ? ` · nº ${n.number} série ${n.series}` : ""}</p>
        </div>
        <span className={"badge " + INVOICE_BADGE[n.state]}>{INVOICE_STATE[n.state]}</span>
      </div>
      <div className="table-wrap"><table className="table stack">
        <caption className="visually-hidden">Itens da nota</caption>
        <thead><tr><th scope="col">Item</th><th scope="col">NCM</th><th scope="col">CFOP</th><th scope="col">Qtd</th><th scope="col">Total</th></tr></thead>
        <tbody>{n.lines.map((l) => (
          <tr key={l.line_no}><td data-label="Item">{l.description}</td>
            <td data-label="NCM">{l.ncm ?? (n.state === "DRAFT" ? <span className="text-warning">falta</span> : "—")}</td>
            <td data-label="CFOP">{l.cfop ?? "—"}</td><td data-label="Qtd">{l.quantity} {l.unit}</td>
            <td data-label="Total" className="num">{formatMoney(l.total)}</td></tr>
        ))}</tbody>
      </table></div>
      {n.issues.length > 0 && <ul className="small text-warning">{n.issues.map((i) => <li key={i}>{i}</li>)}</ul>}
      {missingNcm.length > 0 && may("invoices.request") && missingNcm.map((l) => (
        <div className="actions" key={l.product_id}>
          <label htmlFor={`ncm-${n.id}-${l.line_no}`}>NCM de {l.description}</label>
          <input id={`ncm-${n.id}-${l.line_no}`} inputMode="numeric" placeholder="8 dígitos" value={ncm[l.product_id] ?? ""}
            onChange={(e) => setNcm({ ...ncm, [l.product_id]: e.target.value })} />
          <button className="btn btn-small btn-ghost" onClick={() => void run(
            () => api.put(`/api/v1/products/${l.product_id}/fiscal-data`, { ncm: ncm[l.product_id] ?? "" }),
            () => "NCM do produto salvo.")}>Salvar NCM</button>
        </div>
      ))}
      {n.state === "AUTHORIZED" || n.state === "CANCELLED" || n.state === "CANCEL_PENDING" ? (
        <dl className="kv small">
          <dt>Chave de acesso</dt><dd className="mono">{n.access_key}</dd>
          <dt>Protocolo</dt><dd>{n.protocol}</dd>
          {n.cancel_protocol && <><dt>Protocolo do cancelamento</dt><dd>{n.cancel_protocol}</dd></>}
          {n.xml_document_id && may("invoices.export") && <><dt>XML</dt><dd><a href={`/api/v1/documents/${n.xml_document_id}/download`}>Baixar XML da nota</a></dd></>}
        </dl>
      ) : null}
      {n.state === "REJECTED" && <p className="notice notice-error">Rejeitada{n.rejection_code ? ` (${n.rejection_code})` : ""}: {n.rejection_reason}</p>}
      {n.state === "UNCERTAIN" && <p className="text-warning">O provedor não respondeu a tempo: a nota pode ou não ter sido emitida.
        Ela não será reenviada; use “Consultar provedor”.</p>}
      <div className="actions">
        {n.state === "DRAFT" && may("invoices.request") && (
          <button className="btn btn-small btn-primary" onClick={() => void run(() => api.post(`/api/v1/invoices/${n.id}/review`, {}, `"${n.version}"`),
            () => "Nota conferida.")}>Conferir (contador)</button>)}
        {n.state === "READY" && may("invoices.confirm") && (
          <button className="btn btn-small btn-primary" onClick={() => void run(() => api.command(`/api/v1/invoices/${n.id}/transmission`, null, key, `"${n.version}"`),
            (v) => v.state === "AUTHORIZED" ? "Nota autorizada." : v.state === "REJECTED" ? "Nota rejeitada pela SEFAZ." : "Envio sem resposta definitiva: resultado incerto.")}>Transmitir</button>)}
        {(n.state === "UNCERTAIN" || n.state === "TRANSMITTING") && may("invoices.confirm") && (
          <button className="btn btn-small btn-ghost" onClick={() => void run(() => api.post(`/api/v1/invoices/${n.id}/resolution`, {}),
            (v) => `Consulta feita: ${INVOICE_STATE[v.state]}.`)}>Consultar provedor</button>)}
        {["DRAFT", "READY", "REJECTED"].includes(n.state) && may("invoices.create") && (
          <button className="btn btn-small btn-ghost" disabled={!reason.trim()} onClick={() => void run(() => api.post(`/api/v1/invoices/${n.id}/discard`, { reason }, `"${n.version}"`),
            () => "Nota descartada.")}>Descartar</button>)}
        {(n.state === "AUTHORIZED" || n.state === "CANCEL_PENDING") && may("invoices.cancel") && (
          <button className="btn btn-small btn-ghost" onClick={() => void run(() => api.command(`/api/v1/invoices/${n.id}/cancellation`, { reason }, key, `"${n.version}"`),
            (v) => v.state === "CANCELLED" ? "Nota cancelada." : "Cancelamento pendente de confirmação.")}>
            {n.state === "CANCEL_PENDING" ? "Consultar cancelamento" : "Cancelar nota"}</button>)}
        {(["DRAFT", "READY", "REJECTED", "AUTHORIZED"].includes(n.state)) && (
          <><label className="visually-hidden" htmlFor={`nr-${n.id}`}>Motivo</label>
            <input id={`nr-${n.id}`} placeholder={n.state === "AUTHORIZED" ? "Justificativa (15 a 255 caracteres)" : "Motivo do descarte"}
              value={reason} onChange={(e) => setReason(e.target.value)} /></>)}
      </div>
      {n.history.length > 0 && (
        <details>
          <summary>Histórico com o provedor ({n.history.length})</summary>
          <ul className="timeline">{n.history.map((h) => (
            <li key={h.id}><strong>{OPERATION[h.operation] ?? h.operation}: {OUTCOME[h.outcome]}</strong>
              <span>{h.detail}{h.actor_name ? ` — ${h.actor_name}` : ""}</span>
              <span className="muted small">{formatDateTime(h.started_at)}</span></li>
          ))}</ul>
        </details>
      )}
      <p className="muted small">Criada por {n.created_by_name} em {formatDateTime(n.created_at)}
        {n.reviewed_by_name ? ` · conferida por ${n.reviewed_by_name}` : ""}</p>
      {error && <p className="notice notice-error" role="alert">{error.message}</p>}
    </article>
  );
}
