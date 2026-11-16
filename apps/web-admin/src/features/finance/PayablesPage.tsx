import { useState } from "react";
import { Link } from "react-router";
import type { Page, Payable } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatMoney } from "../../app/format";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";

const STATE: Record<Payable["state"], string> = { OPEN: "Em aberto", PARTIALLY_PAID: "Pago em parte", PAID: "Pago", CANCELLED: "Cancelado" };
const date = (d: string | null) => (d ? d.split("-").reverse().join("/") : "—");

/** Obrigações a pagar. Compra de estoque é passivo (não despesa); a liquidação entra na F06. */
export function PayablesPage() {
  const [state, setState] = useState<"" | Payable["state"]>("OPEN");
  const [res, reload] = useResource<Page<Payable>>(`/api/v1/payables?limit=50${state ? `&state=${state}` : ""}`);
  return (
    <section className="page" aria-labelledby="titulo-pagar">
      <header className="page-head">
        <div>
          <h1 id="titulo-pagar">Contas a pagar</h1>
          <p className="muted">Nascem da confirmação de recebimentos de fornecedor. Compra de mercadoria vira estoque, não despesa; o custo só aparece no resultado quando a mercadoria sai (CMV).</p>
        </div>
        <div className="field inline">
          <label htmlFor="pagar-filtro">Situação</label>
          <select id="pagar-filtro" value={state} onChange={(e) => setState(e.target.value as Payable["state"] | "")}>
            <option value="OPEN">Em aberto</option><option value="PAID">Pagas</option><option value="">Todas</option>
          </select>
        </div>
      </header>
      <p className="notice">Registrar pagamento e conciliar com o caixa entra na fase <strong>F06</strong>; nada aqui é baixado manualmente ainda.</p>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhuma conta nesta situação" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Descrição</th><th scope="col">Unidade</th><th scope="col">Competência</th><th scope="col">Vencimento</th>
              <th scope="col" className="num">Valor</th><th scope="col" className="num">Saldo</th><th scope="col">Situação</th></tr></thead>
            <tbody>{res.data.items.map((p) => (
              <tr key={p.id}>
                <th scope="row">{p.source_type === "inventory_receipt" ? <Link to={`/operation/receipts/${p.source_id}`}>{p.description}</Link> : p.description}</th>
                <td data-label="Unidade">{p.branch}</td>
                <td data-label="Competência">{date(p.competence)}</td>
                <td data-label="Vencimento">{date(p.due_date)}</td>
                <td data-label="Valor" className="num">{formatMoney(p.amount)}</td>
                <td data-label="Saldo" className="num"><strong>{formatMoney(p.balance)}</strong></td>
                <td data-label="Situação">{STATE[p.state]}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}
