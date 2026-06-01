import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { safeStorage } from '@/lib/storage';

interface ProtectedRouteProps {
  children: ReactNode;
}

export const ProtectedRoute = ({ children }: ProtectedRouteProps) => {
  const location = useLocation();
  const token = safeStorage.getItem('access_token');
  // Additionally, you can check user data here.
  const hasUser = safeStorage.getItem('admin_user') || safeStorage.getItem('admin_name') || safeStorage.getItem('role');

  if (!token && !hasUser) {
    // Redirect them to the /login page, but save the current location they were
    // trying to go to when they were redirected. This allows us to send them
    // along to that page after they login, which is a nicer user experience
    // than dropping them off on the home page.
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <>{children}</>;
};

export const PublicRoute = ({ children }: ProtectedRouteProps) => {
  const token = safeStorage.getItem('access_token');
  const hasUser = safeStorage.getItem('admin_user') || safeStorage.getItem('admin_name') || safeStorage.getItem('role');

  if (token || hasUser) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
};
