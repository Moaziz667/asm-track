import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { Perm, usePermissions } from '@/lib/api/auth';
import { Forbidden } from '@/pages/auth/Forbidden';
import { protectedGate, publicGate } from './authGate';

interface ProtectedRouteProps {
  children: ReactNode;
}

function AuthLoading() {
  return (
    <div style={{ height: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <svg className="animate-spin" style={{ color: 'var(--brand)' }} width="32" height="32" viewBox="0 0 24 24" fill="none">
        <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" strokeOpacity="0.25" />
        <path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
      </svg>
    </div>
  );
}

export const ProtectedRoute = ({ children }: ProtectedRouteProps) => {
  const auth = useAuth();
  const location = useLocation();

  // The rule lives in authGate.ts, where it is unit-tested — notably the case that broke this page:
  // a silent renew leaves isLoading true on a session that is perfectly valid.
  switch (protectedGate(auth.isLoading, auth.isAuthenticated)) {
    case 'wait':
      return <AuthLoading />;
    case 'redirect':
      return <Navigate to="/login" state={{ from: location }} replace />;
    default:
      return <>{children}</>;
  }
};

/**
 * Declarative permission gate for a route. Renders a proper 403 page when the current user lacks the
 * required permission, mirroring the backend RBAC and the nav gating (same perm:* strings). `perm` null
 * means "any authenticated user". Sits inside ProtectedRoute, so the user is already authenticated here.
 */
export const PermRoute = ({ perm, children }: { perm: Perm | null; children: ReactNode }) => {
  const { perms } = usePermissions();
  if (perm && !perms.includes(perm)) return <Forbidden requiredPerm={perm} />;
  return <>{children}</>;
};

export const PublicRoute = ({ children }: ProtectedRouteProps) => {
  const auth = useAuth();

  switch (publicGate(auth.isLoading, auth.isAuthenticated)) {
    case 'wait':
      return <AuthLoading />;
    case 'redirect':
      return <Navigate to="/dashboard" replace />;
    default:
      return <>{children}</>;
  }
};
