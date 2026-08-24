import { createBrowserRouter, RouterProvider, Navigate, Outlet } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import Providers from './Providers';
import AppShell from '@/components/AppShell';
import { ProtectedRoute, PublicRoute, PermRoute } from './components/auth/ProtectedRoute';
import { PublicLayout } from './layouts/PublicLayout';
import type { Perm } from '@/lib/api/auth';

// Public Pages
import LoginPage from './pages/LoginPage';
import CallbackPage from './pages/CallbackPage';
import TrackDeliveryPage from './pages/track-delivery/TrackDeliveryPage';

// Protected Pages (light, loaded eagerly)
import DashboardPage from './pages/dashboard/DashboardPage';
import DeliveriesPage from './pages/deliveries/DeliveriesPage';
import NotificationsPage from './pages/notifications/NotificationsPage';
import ErrorBoundary from './components/ErrorBoundary';
import NotFound from './pages/NotFound';

// Lazy-loaded pages (heavy deps: maps, charts, xlsx, drag-and-drop, editors)
const DeliveryDetailsPage = lazy(() => import('./pages/deliveries/DeliveryDetailsPage'));
const RoutesTablePage = lazy(() => import('./pages/routes-table/RoutesTablePage'));
const DriversPage = lazy(() => import('./pages/drivers/DriversPage'));
const VehiclesPage = lazy(() => import('./pages/vehicles/VehiclesPage'));
const DepotsPage = lazy(() => import('./pages/depots/DepotsPage'));
const SchedulePage = lazy(() => import('./pages/schedule/SchedulePage'));
const OverviewCalendarPage = lazy(() => import('./pages/overview/OverviewCalendarPage'));
const ReturnsPage = lazy(() => import('./pages/returns/ReturnsPage'));
const CashDeskPage = lazy(() => import('./pages/cash/CashDeskPage'));
const FailureReasonsPage = lazy(() => import('./pages/failure-reasons/FailureReasonsPage'));
const SystemHealthPage = lazy(() => import('./pages/system-health/SystemHealthPage'));
const PerformancePage = lazy(() => import('./pages/performance/PerformancePage'));
const SettingsPage = lazy(() => import('./pages/settings/SettingsPage'));
const ErpIntegrationPage = lazy(() => import('./pages/settings/ErpIntegrationPage'));
const AuditLogsPage = lazy(() => import('./pages/audit-logs/AuditLogsPage'));
const ImportPage = lazy(() => import('./pages/import/ImportPage'));
const DispatchDeskPage = lazy(() => import('./pages/dispatch-desk/DispatchDeskPage'));
const RouteBuilderPage = lazy(() => import('./pages/route-builder/RouteBuilderPage'));
const RouteDetailsPage = lazy(() => import('./pages/route-details/RouteDetailsPage'));
const ZonesPage = lazy(() => import('./pages/zones/ZonesPage'));

// Per-route "any authenticated" marker — pages with no specific view permission.
const ANY = null;

const LazyLoad = ({ children }: { children: React.ReactNode }) => (
  <Suspense
    fallback={
      <div className="flex items-center justify-center h-[300px] w-full">
        <svg className="animate-spin h-6 w-6 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
        </svg>
      </div>
    }
  >
    {children}
  </Suspense>
);

/** Compose permission gating + per-route crash isolation + lazy Suspense for a protected page element.
 *  The ErrorBoundary is inside PermRoute and around Suspense, so a crash (render OR lazy-load) in one
 *  page shows the fallback card within the shell instead of white-screening the whole app; the
 *  sidebar/topnav stay usable and the user can navigate away. `perm` null = any authenticated user. */
const guard = (perm: Perm | null, element: React.ReactNode) => (
  <PermRoute perm={perm}>
    <ErrorBoundary>
      <LazyLoad>{element}</LazyLoad>
    </ErrorBoundary>
  </PermRoute>
);

const AppShellLayout = () => {
  return <AppShell><Outlet /></AppShell>;
};

const PublicProvidersLayout = () => {
  return <PublicLayout />;
};

const router = createBrowserRouter([
  {
    element: <PublicRoute><PublicProvidersLayout /></PublicRoute>,
    children: [
      { path: "/login", element: <LoginPage /> },
      { path: "/callback", element: <CallbackPage /> },
    ]
  },
  {
    // Tracking pages are public but might require their own specific layout (no sidebar)
    element: <PublicProvidersLayout />,
    children: [
      { path: "/track/:deliveryId", element: <TrackDeliveryPage /> }
    ]
  },
  {
    element: (
      <ProtectedRoute>
        <AppShellLayout />
      </ProtectedRoute>
    ),
    children: [
      { path: "/", element: <Navigate to="/dashboard" replace /> },
      { path: "/dashboard", element: guard(ANY, <DashboardPage />) },
      { path: "/deliveries", element: guard('perm:delivery:view', <DeliveriesPage />) },
      { path: "/deliveries/:id", element: guard('perm:delivery:view', <DeliveryDetailsPage />) },
      { path: "/dispatch-desk", element: guard('perm:dispatch:operate', <DispatchDeskPage />) },
      { path: "/routes-table", element: guard('perm:route:view', <RoutesTablePage />) },
      { path: "/route-builder", element: guard('perm:route:manage', <RouteBuilderPage />) },
      { path: "/routes/:id", element: guard('perm:route:view', <RouteDetailsPage />) },
      { path: "/drivers", element: guard('perm:driver:view', <DriversPage />) },
      { path: "/vehicles", element: guard('perm:driver:view', <VehiclesPage />) },
      { path: "/depots", element: guard('perm:route:view', <DepotsPage />) },
      { path: "/schedule", element: guard('perm:dispatch:operate', <SchedulePage />) },
      { path: "/overview", element: guard('perm:report:view', <OverviewCalendarPage />) },
      { path: "/performance", element: guard('perm:report:view', <PerformancePage />) },
      { path: "/settings", element: guard('perm:settings:manage', <SettingsPage />) },
      { path: "/settings/erp", element: guard('perm:settings:manage', <ErpIntegrationPage />) },
      { path: "/audit-logs", element: guard('perm:audit:view', <AuditLogsPage />) },
      { path: "/import", element: guard('perm:erp:sync', <ImportPage />) },
      { path: "/notifications", element: guard(ANY, <NotificationsPage />) },
      { path: "/zones", element: guard('perm:route:view', <ZonesPage />) },
      { path: "/returns", element: guard('perm:dispatch:operate', <ReturnsPage />) },
      // Reading the cash desk is supervision, not dispatch: a manager must see the money in
      // circulation without being able to plan a route. Settling a remittance stays behind
      // perm:cash:manage, enforced by the gateway on the same paths.
      { path: "/cash", element: guard('perm:cash:view', <CashDeskPage />) },
      { path: "/failure-reasons", element: guard('perm:settings:manage', <FailureReasonsPage />) },
      { path: "/system-health", element: guard('perm:settings:manage', <SystemHealthPage />) },
      { path: "*", element: <NotFound /> },
    ]
  },
  { path: "*", element: <NotFound /> }
]);

function App() {
  return (
    <ErrorBoundary>
      <Providers>
        <RouterProvider router={router} />
      </Providers>
    </ErrorBoundary>
  );
}

export default App;

