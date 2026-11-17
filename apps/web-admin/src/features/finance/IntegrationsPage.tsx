import type { IntegrationStatus, WebhookEvent } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { OPERATION, OUTCOME, WEBHOOK_STATUS } from "./fiscalLabels";

/** F08 — estado honesto de cada integração externa, pendências de triagem e histórico de tentativas. */
export function IntegrationsPage() {
  const { me } = useSession();
  const [res, reload] = useResource<IntegrationStatus>("/api/v1/integrations");
  return (
    <section className="page" aria-labelledby="titulo-integracoes">
      <header className="page-head">
        <div>
          <h1 id="titulo-integracoes">Integrações</h1>
          <p className="muted">Sem provedor contratado, a integração fica <strong>NÃO CONFIGURADA</strong> e bloqueada: nada é
            enviado nem simulado. Credenciais ficam só no servidor e nunca aparecem aqui.</p>
        </div>
      </header>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (
        <>
          <div className="cards-grid">
            {res.data.items.map((i) => (
              <article key={i.integration} className={"card" + (i.state === "NOT_CONFIGURED" ? " state-warning" : "")} aria-label={i.label}>
                <h2 className="h3">{i.label}</h2>
                <span className={"badge " + (i.state === "CONFIGURED" ? "badge-ok" : "badge-warn")}>
                  {i.state === "CONFIGURED" ? `Configurada · ${i.provider}` : "NÃO CONFIGURADA — bloqueada"}</span>
                <p className="small">{i.message}</p>
                <p className="small"><strong>{i.pending}</strong> {i.pending_label}</p>
              </article>
            ))}
          </div>
          {me?.admin && <Triage />}
          {me?.admin && (
            <article className="card" aria-label="Tentativas recentes">
              <h2 className="h3">Tentativas recentes</h2>
              {res.data.recent.length === 0 ? <EmptyState title="Nenhuma tentativa registrada" /> : (
                <div className="table-wrap"><table className="table stack">
                  <thead><tr><th scope="col">Quando</th><th scope="col">Integração</th><th scope="col">Operação</th>
                    <th scope="col">Resultado</th><th scope="col">Detalhe</th><th scope="col">Quem</th></tr></thead>
                  <tbody>{res.data.recent.map((a) => (
                    <tr key={a.id}><td data-label="Quando">{formatDateTime(a.started_at)}</td><td data-label="Integração">{a.integration}</td>
                      <td data-label="Operação">{OPERATION[a.operation] ?? a.operation}</td><td data-label="Resultado">{OUTCOME[a.outcome]}</td>
                      <td data-label="Detalhe">{a.detail}</td><td data-label="Quem">{a.actor_name ?? "—"}</td></tr>
                  ))}</tbody>
                </table></div>
              )}
            </article>
          )}
        </>
      )}
    </section>
  );
}

function Triage() {
  const [res] = useResource<{ items: WebhookEvent[] }>("/api/v1/integrations/webhook-events");
  if (res.status !== "ready") return null;
  return (
    <article className="card" aria-label="Avisos de pagamento">
      <h2 className="h3">Avisos dos provedores de pagamento</h2>
      <p className="muted small">Assinatura inválida, repetição ou valor diferente nunca mexem no caixa; ficam aqui para conferência.</p>
      {res.data.items.length === 0 ? <EmptyState title="Nenhum aviso recebido" /> : (
        <div className="table-wrap"><table className="table stack">
          <thead><tr><th scope="col">Recebido</th><th scope="col">Provedor</th><th scope="col">Evento</th>
            <th scope="col">Valor</th><th scope="col">Situação</th><th scope="col">Detalhe</th></tr></thead>
          <tbody>{res.data.items.map((e) => (
            <tr key={e.id}><td data-label="Recebido">{formatDateTime(e.received_at)}</td><td data-label="Provedor">{e.provider}</td>
              <td data-label="Evento">{e.event_type ?? "—"}</td><td data-label="Valor">{e.amount ? formatMoney(e.amount) : "—"}</td>
              <td data-label="Situação">{WEBHOOK_STATUS[e.status] ?? e.status}</td><td data-label="Detalhe">{e.detail}</td></tr>
          ))}</tbody>
        </table></div>
      )}
    </article>
  );
}
