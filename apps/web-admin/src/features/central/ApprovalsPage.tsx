import { Link } from "react-router";
import type { ApprovalCenter, ApprovalItem } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { SECTOR_NAMES } from "../../app/origin";
import { Icon } from "../../components/Icon";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";

const KIND: Record<ApprovalItem["kind"], string> = {
  TAX: "Guia", PAYROLL: "Folha", LOSS: "Perda", ADJUSTMENT: "Ajuste de estoque", EXPENSE: "Despesa",
  VEHICLE_EXPENSE: "Gasto de veículo", CASH_SESSION: "Caixa", PAYMENT: "Pagamento", PERIOD_REOPEN: "Reabertura de mês",
  ABSENCE: "Folga/férias",
};

/**
 * Central de aprovações (F10): tudo que espera decisão de QUEM ESTÁ LOGADO, agrupado por setor. O servidor só devolve o
 * que o ator pode decidir; a decisão acontece na tela do módulo (com alçada, versão e histórico de lá).
 */
export function ApprovalsPage() {
  const [res, reload] = useResource<ApprovalCenter>("/api/v1/approvals");
  return (
    <section className="page" aria-labelledby="titulo-aprovacoes">
      <header className="page-head">
        <div>
          <h1 id="titulo-aprovacoes">Aprovações pendentes</h1>
          <p className="muted">Só aparece o que a sua permissão deixa decidir. Aprovar não paga: pagamento é sempre outra etapa.</p>
        </div>
        <button className="btn" onClick={reload}><Icon name="refresh" size={16} /> Atualizar</button>
      </header>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nada esperando a sua decisão" /> : (
        <>
          <ul className="sector-counts" aria-label="Pendências por setor">
            {res.data.sectors.filter((s) => s.pending > 0).map((s) => (
              <li key={s.sector}><a href={`#setor-${s.sector}`}><strong>{s.pending}</strong> {SECTOR_NAMES[s.sector]}</a></li>
            ))}
          </ul>
          {res.data.sectors.filter((s) => s.pending > 0).map((s) => (
            <section key={s.sector} className="card" id={`setor-${s.sector}`} aria-labelledby={`ap-${s.sector}`}>
              <h2 className="h3" id={`ap-${s.sector}`}>{SECTOR_NAMES[s.sector]} <span className="badge badge-warn">{s.pending}</span></h2>
              <ul className="approval-list">
                {res.data.items.filter((i) => i.sector === s.sector).map((i, n) => (
                  <li key={`${i.kind}-${i.id ?? n}`}>
                    <div>
                      <span className="badge badge-violet">{KIND[i.kind]}</span> <strong>{i.title}</strong>
                      {i.detail && <div className="muted small">{i.detail}</div>}
                      <div className="muted small">
                        {i.requested_by && <>Pedido por {i.requested_by}</>}
                        {i.requested_at && <> · {formatDateTime(i.requested_at)}</>}
                      </div>
                      {i.note && <div className="small notice">{i.note}</div>}
                    </div>
                    <div className="approval-side">
                      {i.amount !== null && <span className="num"><strong>{formatMoney(i.amount)}</strong></span>}
                      <Link className="btn btn-small btn-primary" to={i.path}>Abrir para decidir</Link>
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </>
      ))}
    </section>
  );
}
