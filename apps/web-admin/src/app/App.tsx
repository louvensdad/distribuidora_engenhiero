import { Navigate, Route, Routes } from "react-router";
import { Shell } from "../layout/Shell";
import { useSession } from "../session/SessionContext";
import { TABS, firstAllowedPath } from "./navigation";
import { Guard } from "./Guard";
import { EntryPage } from "../features/entry/EntryPage";
import { OverviewPage } from "../features/overview/OverviewPage";
import { CustomersPage } from "../features/sales/CustomersPage";
import { PeoplePage } from "../features/team/PeoplePage";
import { AccessMapPage } from "../features/team/AccessMapPage";
import { AuditPage } from "../features/team/AuditPage";
import { StockPage } from "../features/operation/StockPage";
import { ReceiptsPage } from "../features/operation/ReceiptsPage";
import { ReceiptFormPage } from "../features/operation/ReceiptFormPage";
import { ReceiptDetailPage } from "../features/operation/ReceiptDetailPage";
import { ProductsPage } from "../features/operation/ProductsPage";
import { SuppliersPage } from "../features/operation/SuppliersPage";
import { PayablesPage } from "../features/finance/PayablesPage";
import { BranchesPage } from "../features/team/BranchesPage";
import { ForbiddenState, PendingModule } from "../components/States";

const IMPLEMENTED: Record<string, () => React.JSX.Element> = {
  "/overview": OverviewPage,
  "/sales/customers": CustomersPage,
  "/operation/stock": StockPage,
  "/operation/receipts": ReceiptsPage,
  "/operation/products": ProductsPage,
  "/operation/suppliers": SuppliersPage,
  "/finance/payables": PayablesPage,
  "/team/branches": BranchesPage,
  "/team/people": PeoplePage,
  "/team/access": AccessMapPage,
  "/team/audit": AuditPage,
};

export function App() {
  const { state, can } = useSession();
  if (state.kind !== "ready") return <EntryPage state={state} />;
  const home = firstAllowedPath(can);

  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={home ? <Navigate to={home} replace /> : <ForbiddenState />} />
        {TABS.flatMap((t) => t.subtabs).map((s) => {
          const Page = IMPLEMENTED[s.path];
          return (
            <Route key={s.path} path={s.path} element={
              <Guard anyOf={s.anyOf}>
                {Page ? <Page /> : <PendingModule title={s.label} phase={s.pendingPhase ?? "futura"} />}
              </Guard>
            } />
          );
        })}
        <Route path="/operation/receipts/new" element={<Guard anyOf={["inventory.receipts.create"]}><ReceiptFormPage /></Guard>} />
        <Route path="/operation/receipts/:id" element={<Guard anyOf={["inventory.receipts.view"]}><ReceiptDetailPage /></Guard>} />
        {TABS.filter((t) => !t.subtabs.some((s) => s.path === t.path)).map((t) => (
          <Route key={t.path} path={t.path} element={<Navigate to={t.subtabs[0]!.path} replace />} />
        ))}
        <Route path="*" element={<ForbiddenState />} />
      </Route>
    </Routes>
  );
}
