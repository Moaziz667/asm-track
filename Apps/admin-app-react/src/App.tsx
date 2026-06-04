import { createBrowserRouter, RouterProvider, Navigate, Outlet } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import Providers from './Providers';
import AppShell from '@/components/AppShell';
import { ProtectedRoute, PublicRoute } from './components/auth/ProtectedRoute';
import { PublicLayout } from './layouts/PublicLayout';

// Public Pages
import LoginPage from './pages/LoginPage';
import CallbackPage from './pages/CallbackPage';
import TrackDeliveryPage from './pages/TrackDeliveryPage';

// Protected Pages
import DashboardPage from './pages/DashboardPage';
import DeliveriesPage from './pages/DeliveriesPage';
import DeliveryDetailsPage from './pages/DeliveryDetailsPage';
import RoutesTablePage from './pages/RoutesTablePage';
import DriversPage from './pages/DriversPage';
import VehiclesPage from './pages/VehiclesPage';
import DepotsPage from './pages/DepotsPage';
import CompaniesPage from './pages/CompaniesPage';
import OperationsPage from './pages/OperationsPage';
import PerformancePage from './pages/PerformancePage';
import SettingsPage from './pages/SettingsPage';
import ErpIntegrationPage from './pages/ErpIntegrationPage';
import AuditLogsPage from './pages/AuditLogsPage';
import ImportPage from './pages/ImportPage';
import NotificationsPage from './pages/NotificationsPage';
import ErrorBoundary from './components/ErrorBoundary';

// Lazy Loaded Heavy Pages
const DispatchDeskPage = lazy(() => import('./pages/dispatch-desk/DispatchDeskPage'));
const RouteBuilderPage = lazy(() => import('./pages/route-builder/RouteBuilderPage'));
const RouteDetailsPage = lazy(() => import('./pages/RouteDetailsPage'));
const ZonesPage = lazy(() => import('./pages/ZonesPage'));

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

const AppShellLayout = () => {
  return (
    <Providers>
      <AppShell><Outlet /></AppShell>
    </Providers>
  );
};

const PublicProvidersLayout = () => {
  return (
    <Providers>
      <PublicLayout />
    </Providers>
  );
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
      { path: "/dashboard", element: <DashboardPage /> },
      { path: "/deliveries", element: <DeliveriesPage /> },
      { path: "/deliveries/:id", element: <DeliveryDetailsPage /> },
      { path: "/dispatch-desk", element: <LazyLoad><DispatchDeskPage /></LazyLoad> },
      { path: "/routes-table", element: <RoutesTablePage /> },
      { path: "/route-builder", element: <LazyLoad><RouteBuilderPage /></LazyLoad> },
      { path: "/routes/:id", element: <LazyLoad><RouteDetailsPage /></LazyLoad> },
      { path: "/drivers", element: <DriversPage /> },
      { path: "/vehicles", element: <VehiclesPage /> },
      { path: "/depots", element: <DepotsPage /> },
      { path: "/companies", element: <CompaniesPage /> },
      { path: "/operations", element: <OperationsPage /> },
      { path: "/performance", element: <PerformancePage /> },
      { path: "/settings", element: <SettingsPage /> },
      { path: "/settings/erp", element: <ErpIntegrationPage /> },
      { path: "/audit-logs", element: <AuditLogsPage /> },
      { path: "/import", element: <ImportPage /> },
      { path: "/notifications", element: <NotificationsPage /> },
      { path: "/zones", element: <LazyLoad><ZonesPage /></LazyLoad> },
    ]
  },
  { path: "*", element: <Navigate to="/dashboard" replace /> }
]);

function App() {
  return (
    <ErrorBoundary>
      <RouterProvider router={router} />
    </ErrorBoundary>
  );
}

export default App;

