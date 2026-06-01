import { AdminRole, AdminUser } from '@/types';
import { safeStorage } from '@/lib/storage';

const ROLL_KEYS = ['token_role', 'admin_role', 'role', 'user_role'];
const USER_DATA_KEY = 'admin_user';
const ADMIN_NAME_KEY = 'admin_name';

function normalizeRole(value: string | null): AdminRole {
  const normalized = (value ?? '').toUpperCase();
  if (normalized === 'ADMIN' || normalized === 'DISPATCHER' || normalized === 'MANAGER') {
    return normalized;
  }
  return 'UNKNOWN';
}

export function getCurrentRole(): AdminRole {
  if (typeof window === 'undefined') return 'UNKNOWN';

  for (const key of ROLL_KEYS) {
    const role = normalizeRole(safeStorage.getItem(key));
    if (role !== 'UNKNOWN') return role;
  }

  return 'UNKNOWN';
}

export function getCurrentUser(): AdminUser | null {
  if (typeof window === 'undefined') return null;
  
  // Try main user data object
  const data = safeStorage.getItem(USER_DATA_KEY);
  if (data) {
    try {
      return JSON.parse(data);
    } catch (e) {}
  }

  // Try individual fields as fallback
  const fallbackName = safeStorage.getItem(ADMIN_NAME_KEY);
  if (fallbackName) {
    return {
      id: 'fallback',
      name: fallbackName,
      email: '',
      role: getCurrentRole(),
      createdAt: new Date().toISOString()
    } as AdminUser;
  }

  return null;
}

export function isReadOnlyRole(role: AdminRole): boolean {
  return role === 'MANAGER';
}

export function canManageSettings(role: AdminRole): boolean {
  return role === 'ADMIN';
}

export function canImportErp(role: AdminRole): boolean {
  return role === 'ADMIN' || role === 'DISPATCHER';
}

export function canDispatch(role: AdminRole): boolean {
  return role === 'ADMIN' || role === 'DISPATCHER';
}

export function canManageRoutes(role: AdminRole): boolean {
  return role === 'ADMIN' || role === 'DISPATCHER';
}

export function canManageMasterData(role: AdminRole): boolean {
  return role === 'ADMIN' || role === 'DISPATCHER';
}

export function canViewReadOnly(role: AdminRole): boolean {
  return role === 'ADMIN' || role === 'DISPATCHER' || role === 'MANAGER';
}



/**
 * Hook de protection de page par role.
 * Redirige vers /dashboard si le role ne satisfait pas le check.
 */
export function useRoleGuard(checkFn: (role: AdminRole) => boolean): { allowed: boolean; loading: boolean } {
  if (typeof window === 'undefined') return { allowed: false, loading: true };
  const role = getCurrentRole();
  if (role === 'UNKNOWN') return { allowed: false, loading: true };
  if (!checkFn(role)) {
    window.location.href = '/dashboard';
    return { allowed: false, loading: false };
  }
  return { allowed: true, loading: false };
}
