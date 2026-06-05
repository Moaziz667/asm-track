import { createBrowserRouter, RouterProvider, Navigate, Outlet } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import Providers from './Providers';
import AppShell from '@/components/AppShell';
import { ProtectedRoute, PublicRoute, RoleRoute } from './components/auth/ProtectedRoute';
import { PublicLayout } from './layouts/PublicLayout';
import type { AdminRole } from '@/lib/auth';

// Public Pages
import LoginPage from './pages/LoginPage';
import CallbackPage from './pages/CallbackPage';
import TrackDeliveryPage from './pages/TrackDeliveryPage';

// Protected Pages (light, loaded eagerly)
import DashboardPage from './pages/DashboardPage';
import DeliveriesPage from './pages/DeliveriesPage';
import NotificationsPage from './pages/NotificationsPage';
import ErrorBoundary from './components/ErrorBoundary';
import NotFound from './pages/NotFound';

// Lazy-loaded pages (heavy deps: maps, charts, xlsx, drag-and-drop, editors)
const DeliveryDetailsPage = lazy(() => import('./pages/DeliveryDetailsPage'));
const RoutesTablePage = lazy(() => import('./pages/RoutesTablePage'));
const DriversPage = lazy(() => import('./pages/DriversPage'));
const VehiclesPage = lazy(() => import('./pages/VehiclesPage'));
const DepotsPage = lazy(() => import('./pages/DepotsPage'));
const CompaniesPage = lazy(() => import('./pages/CompaniesPage'));
const OperationsPage = lazy(() => import('./pages/OperationsPage'));
const PerformancePage = lazy(() => import('./pages/PerformancePage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const ErpIntegrationPage = lazy(() => import('./pages/ErpIntegrationPage'));
const AuditLogsPage = lazy(() => import('./pages/AuditLogsPage'));
const ImportPage = lazy(() => import('./pages/ImportPage'));
const DispatchDeskPage = lazy(() => import('./pages/dispatch-desk/DispatchDeskPage'));
const RouteBuilderPage = lazy(() => import('./pages/route-builder/RouteBuilderPage'));
const RouteDetailsPage = lazy(() => import('./pages/RouteDetailsPage'));
const ZonesPage = lazy(() => import('./pages/ZonesPage'));

// ── Role groups (mirrors lib/auth capability predicates) ─────────────────────
const ALL: AdminRole[] = ['ADMIN', 'DISPATCHER', 'MANAGER'];
const DISPATCH: AdminRole[] = ['ADMIN', 'DISPATCHER'];
const ADMIN_ONLY: AdminRole[] = ['ADMIN'];

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

/** Compose role gating + lazy Suspense for a protected page element. */
const guard = (allow: AdminRole[], element: React.ReactNode) => (
  <RoleRoute allow={allow}>
    <LazyLoad>{element}</LazyLoad>
  </RoleRoute>
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
      { path: "/dashboard", element: guard(ALL, <DashboardPage />) },
      { path: "/deliveries", element: guard(DISPATCH, <DeliveriesPage />) },
      { path: "/deliveries/:id", element: guard(DISPATCH, <DeliveryDetailsPage />) },
      { path: "/dispatch-desk", element: guard(DISPATCH, <DispatchDeskPage />) },
      { path: "/routes-table", element: guard(DISPATCH, <RoutesTablePage />) },
      { path: "/route-builder", element: guard(DISPATCH, <RouteBuilderPage />) },
      { path: "/routes/:id", element: guard(DISPATCH, <RouteDetailsPage />) },
      { path: "/drivers", element: guard(DISPATCH, <DriversPage />) },
      { path: "/vehicles", element: guard(DISPATCH, <VehiclesPage />) },
      { path: "/depots", element: guard(DISPATCH, <DepotsPage />) },
      { path: "/companies", element: guard(ADMIN_ONLY, <CompaniesPage />) },
      { path: "/operations", element: guard(ALL, <OperationsPage />) },
      { path: "/performance", element: guard(ALL, <PerformancePage />) },
      { path: "/settings", element: guard(ADMIN_ONLY, <SettingsPage />) },
      { path: "/settings/erp", element: guard(ADMIN_ONLY, <ErpIntegrationPage />) },
      { path: "/audit-logs", element: guard(ADMIN_ONLY, <AuditLogsPage />) },
      { path: "/import", element: guard(DISPATCH, <ImportPage />) },
      { path: "/notifications", element: guard(ALL, <NotificationsPage />) },
      { path: "/zones", element: guard(DISPATCH, <ZonesPage />) },
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

