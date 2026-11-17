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
import { MyCashPage } from "../features/finance/MyCashPage";
import { CashAccountsPage } from "../features/finance/CashAccountsPage";
import { ClosingPage } from "../features/finance/ClosingPage";
import { ExpensesPage } from "../features/finance/ExpensesPage";
import { ReceivablesPage } from "../features/finance/ReceivablesPage";
import { OpenFinancePage } from "../features/finance/OpenFinancePage";
import { TaxesPage } from "../features/finance/TaxesPage";
import { PayrollPage } from "../features/finance/PayrollPage";
import { DocumentsCenterPage } from "../features/finance/DocumentsCenterPage";
import { EmployeesPage } from "../features/team/EmployeesPage";
import { OrdersPage } from "../features/sales/OrdersPage";
import { OrderFormPage } from "../features/sales/OrderFormPage";
import { OrderDetailPage } from "../features/sales/OrderDetailPage";
import { BranchesPage } from "../features/team/BranchesPage";
import { FleetPage } from "../features/operation/FleetPage";
import { VehicleDetailPage } from "../features/operation/VehicleDetailPage";
import { TripsPage } from "../features/operation/TripsPage";
import { TripDetailPage } from "../features/operation/TripDetailPage";
import { ForbiddenState, PendingModule } from "../components/States";

const IMPLEMENTED: Record<string, () => React.JSX.Element> = {
  "/overview": OverviewPage,
  "/sales/customers": CustomersPage,
  "/operation/stock": StockPage,
  "/operation/receipts": ReceiptsPage,
  "/operation/products": ProductsPage,
  "/operation/suppliers": SuppliersPage,
  "/operation/fleet": FleetPage,
  "/operation/trips": TripsPage,
  "/finance/payables": PayablesPage,
  "/finance/my-cash": MyCashPage,
  "/finance/cash": CashAccountsPage,
  "/finance/closing": ClosingPage,
  "/finance/expenses": ExpensesPage,
  "/finance/receivables": ReceivablesPage,
  "/finance/open-finance": OpenFinancePage,
  "/finance/taxes": TaxesPage,
  "/finance/payroll": PayrollPage,
  "/finance/documents": DocumentsCenterPage,
  "/team/employees": EmployeesPage,
  "/sales/orders": OrdersPage,
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
        <Route path="/sales/orders/new" element={<Guard anyOf={["orders.pickup.create", "orders.delivery.create"]}><OrderFormPage /></Guard>} />
        <Route path="/sales/orders/:id" element={<Guard anyOf={["orders.pickup.view", "orders.delivery.view"]}><OrderDetailPage /></Guard>} />
        <Route path="/operation/receipts/new" element={<Guard anyOf={["inventory.receipts.create"]}><ReceiptFormPage /></Guard>} />
        <Route path="/operation/fleet/:id" element={<Guard anyOf={["vehicles.view"]}><VehicleDetailPage /></Guard>} />
        <Route path="/operation/trips/:id" element={<Guard anyOf={["trips.view"]}><TripDetailPage /></Guard>} />
        <Route path="/operation/receipts/:id" element={<Guard anyOf={["inventory.receipts.view"]}><ReceiptDetailPage /></Guard>} />
        {TABS.filter((t) => !t.subtabs.some((s) => s.path === t.path)).map((t) => (
          <Route key={t.path} path={t.path} element={<Navigate to={t.subtabs[0]!.path} replace />} />
        ))}
        <Route path="*" element={<ForbiddenState />} />
      </Route>
    </Routes>
  );
}
