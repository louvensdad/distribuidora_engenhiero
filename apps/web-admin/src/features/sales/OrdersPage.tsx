import { useState } from "react";
import { Link } from "react-router";
import type { Order, Page } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime, formatMoney } from "../../app/format";
import { Icon } from "../../components/Icon";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { ORDER_STATE, PAYMENT_STATE, TYPE } from "./orderLabels";

/** W03 — pedidos de retirada e entrega no escopo do usuário. Estado do pedido e do pagamento separados. */
export function OrdersPage() {
  const { can } = useSession();
  const [type, setType] = useState("");
  const [state, setState] = useState("");
  const [res, reload] = useResource<Page<Order>>(`/api/v1/orders?limit=50${type ? `&type=${type}` : ""}${state ? `&state=${state}` : ""}`);
  return (
    <section className="page" aria-labelledby="titulo-pedidos">
      <header className="page-head">
        <div>
          <h1 id="titulo-pedidos">Pedidos de retirada e entrega</h1>
          <p className="muted">Pedido reserva estoque; a venda só conta quando é retirada ou entregue.</p>
        </div>
        {(can("orders.pickup.create") || can("orders.delivery.create")) && (
          <Link className="btn btn-primary" to="/sales/orders/new"><Icon name="plus" size={16} /> Novo pedido</Link>
        )}
      </header>
      <div className="actions" style={{ justifyContent: "flex-start" }}>
        <div className="field inline">
          <label htmlFor="ped-tipo">Setor</label>
          <select id="ped-tipo" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">Todos</option><option value="PICKUP">Retirada</option><option value="DELIVERY">Entrega</option>
          </select>
        </div>
        <div className="field inline">
          <label htmlFor="ped-estado">Situação</label>
          <select id="ped-estado" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">Todas</option>
            {Object.entries(ORDER_STATE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </div>
      </div>
      {res.status === "loading" && <LoadingState />}
      {res.status === "forbidden" && <ForbiddenState />}
      {res.status === "error" && <ErrorState error={res.error} onRetry={reload} />}
      {res.status === "ready" && (res.data.items.length === 0 ? <EmptyState title="Nenhum pedido" /> : (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Pedido</th><th scope="col">Cliente</th><th scope="col">Tipo</th><th scope="col" className="num">Total</th>
              <th scope="col">Situação</th><th scope="col">Pagamento</th><th scope="col">Cadastro</th></tr></thead>
            <tbody>{res.data.items.map((o) => (
              <tr key={o.id}>
                <th scope="row"><Link to={`/sales/orders/${o.id}`}>#{o.number}</Link></th>
                <td data-label="Cliente">{o.customer_name}</td>
                <td data-label="Tipo">{TYPE[o.type]}</td>
                <td data-label="Total" className="num">{formatMoney(o.effective_total)}</td>
                <td data-label="Situação">{ORDER_STATE[o.state]}</td>
                <td data-label="Pagamento"><span className={"badge " + (o.payment_state === "PAID" ? "badge-ok" : o.payment_state === "REFUND_DUE" ? "badge-error" : "badge-violet")}>{PAYMENT_STATE[o.payment_state]}</span></td>
                <td data-label="Cadastro">{o.created_by_name}<br /><span className="muted small">{formatDateTime(o.created_at)}</span></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ))}
    </section>
  );
}
