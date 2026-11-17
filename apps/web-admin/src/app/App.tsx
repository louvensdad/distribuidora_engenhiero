import { useEffect } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router";
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
import { CategoriesPage } from "../features/reports/CategoriesPage";
import { CustomersRankingPage } from "../features/reports/CustomersRankingPage";
import { GoalsPage } from "../features/reports/GoalsPage";
import { ReportPage } from "../features/reports/ReportPage";
import { ExpensesPage } from "../features/finance/ExpensesPage";
import { ReceivablesPage } from "../features/finance/ReceivablesPage";
import { OpenFinancePage } from "../features/finance/OpenFinancePage";
import { TaxesPage } from "../features/finance/TaxesPage";
import { InvoicesPage } from "../features/finance/InvoicesPage";
import { IntegrationsPage } from "../features/finance/IntegrationsPage";
import { PayrollPage } from "../features/finance/PayrollPage";
import { DocumentsCenterPage } from "../features/finance/DocumentsCenterPage";
import { EmployeesPage } from "../features/team/EmployeesPage";
import { EmployeesRegistryPage } from "../features/team/EmployeesRegistryPage";
import { EmployeeDetailPage, NewEmployeePage } from "../features/team/EmployeeDetailPage";
import { PositionsPage } from "../features/team/PositionsPage";
import { OrdersPage } from "../features/sales/OrdersPage";
import { OrderFormPage } from "../features/sales/OrderFormPage";
import { OrderDetailPage } from "../features/sales/OrderDetailPage";
import { BranchesPage } from "../features/team/BranchesPage";
import { FleetPage } from "../features/operation/FleetPage";
import { VehicleDetailPage } from "../features/operation/VehicleDetailPage";
import { TripsPage } from "../features/operation/TripsPage";
import { TripDetailPage } from "../features/operation/TripDetailPage";
import { ForbiddenState, NotFoundState, PendingModule } from "../components/States";
import { ApprovalsPage } from "../features/central/ApprovalsPage";
import { SectorsPage } from "../features/central/SectorsPage";
import { SearchPage } from "../features/central/SearchPage";
import { takeReturn } from "../session/returnPath";

const IMPLEMENTED: Record<string, () => React.JSX.Element> = {
  "/overview": OverviewPage,
  "/overview/sectors": SectorsPage,
  "/overview/approvals": ApprovalsPage,
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
  "/reports": ReportPage,
  "/reports/categories": CategoriesPage,
  "/reports/goals": GoalsPage,
  "/reports/customers": CustomersRankingPage,
  "/finance/expenses": ExpensesPage,
  "/finance/receivables": ReceivablesPage,
  "/finance/open-finance": OpenFinancePage,
  "/finance/taxes": TaxesPage,
  "/finance/invoices": InvoicesPage,
  "/finance/integrations": IntegrationsPage,
  "/finance/payroll": PayrollPage,
  "/finance/documents": DocumentsCenterPage,
  "/team/employees": EmployeesRegistryPage,
  "/team/positions": PositionsPage,
  "/team/absences": EmployeesPage,
  "/sales/orders": OrdersPage,
  "/team/branches": BranchesPage,
  "/team/people": PeoplePage,
  "/team/access": AccessMapPage,
  "/team/audit": AuditPage,
};

export function App() {
  const { state, can } = useSession();
  if (state.kind !== "ready") return <EntryPage state={state} />;
  return <ReadyApp can={can} />;
}

function ReadyApp({ can }: { can: (c: string) => boolean }) {
  useReturnAfterLogin();
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
        <Route path="/team/employees/new" element={<Guard anyOf={["users.edit_draft"]}><NewEmployeePage /></Guard>} />
        <Route path="/team/employees/:id" element={<Guard anyOf={["users.view"]}><EmployeeDetailPage /></Guard>} />
        <Route path="/operation/receipts/:id" element={<Guard anyOf={["inventory.receipts.view"]}><ReceiptDetailPage /></Guard>} />
        {TABS.filter((t) => !t.subtabs.some((s) => s.path === t.path)).map((t) => (
          <Route key={t.path} path={t.path} element={<Navigate to={t.subtabs[0]!.path} replace />} />
        ))}
        <Route path="/search" element={<SearchPage />} />
        <Route path="*" element={<NotFoundState />} />
      </Route>
    </Routes>
  );
}

/** Depois de entrar de novo, volta ao endereço em que a pessoa estava (link profundo preservado; só caminho interno). */
function useReturnAfterLogin() {
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    const back = takeReturn();
    if (back && back !== location.pathname + location.search + location.hash) navigate(back, { replace: true });
    // Só na montagem (logo após a entrada).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}
