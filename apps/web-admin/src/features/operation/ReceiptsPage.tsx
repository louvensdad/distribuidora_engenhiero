import { Link } from "react-router";
import type { Receipt } from "../../api/types";
import { qs, usePaged, useUrlFilters } from "../../api/usePaged";
import { PagedList } from "../../components/Lists";
import { formatDateTime, formatMoney } from "../../app/format";
import { Hidden } from "../../components/Form";
import { Icon } from "../../components/Icon";
import { EmptyState } from "../../components/States";
import { useSession } from "../../session/SessionContext";

export const RECEIPT_STATUS: Record<Receipt["status"], string> = { DRAFT: "Rascunho — aguardando conferência", CONFIRMED: "Confirmado" };

export function ReceiptsPage() {
  const { can } = useSession();
  const [f, setF] = useUrlFilters(["status"] as const);
  const list = usePaged<Receipt>(`/api/v1/inventory/receipts?${qs({ limit: 50, status: f.status })}`);
  return (
    <section className="page" aria-labelledby="titulo-recebimentos">
      <header className="page-head">
        <div>
          <h1 id="titulo-recebimentos">Recebimentos de fornecedor</h1>
          <p className="muted">Rascunho registra o que chegou; a conferência confirma a entrada no estoque com custo e frete.</p>
        </div>
        {can("inventory.receipts.create") && (
          <Link className="btn btn-primary" to="/operation/receipts/new"><Icon name="plus" size={16} /> Receber mercadoria</Link>
        )}
      </header>
      <div className="field inline">
        <label htmlFor="rec-filtro">Situação</label>
        <select id="rec-filtro" value={f.status} onChange={(e) => setF({ status: e.target.value })}>
          <option value="">Todos</option><option value="DRAFT">Rascunhos</option><option value="CONFIRMED">Confirmados</option>
        </select>
      </div>
      <PagedList list={list} empty={<EmptyState title="Nenhum recebimento" />}>{(items) => (
        <div className="table-wrap card">
          <table className="stack">
            <thead><tr><th scope="col">Fornecedor</th><th scope="col">Documento</th><th scope="col">Itens</th><th scope="col">Total</th>
              <th scope="col">Registro</th><th scope="col">Situação</th></tr></thead>
            <tbody>
              {items.map((r) => (
                <tr key={r.id}>
                  <th scope="row"><Link to={`/operation/receipts/${r.id}`}>{r.supplier_name}</Link></th>
                  <td data-label="Documento">{r.document_number ?? "—"}</td>
                  <td data-label="Itens">{r.lines.length}</td>
                  <td data-label="Total">{r.masked_fields.includes("total_cost") ? <Hidden /> : formatMoney(r.total_cost)}</td>
                  <td data-label="Registro">{r.created_by_name}<br /><span className="muted small">{formatDateTime(r.received_at)}</span></td>
                  <td data-label="Situação"><span className={"badge " + (r.status === "CONFIRMED" ? "badge-ok" : "badge-violet")}>{RECEIPT_STATUS[r.status]}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}</PagedList>
    </section>
  );
}
