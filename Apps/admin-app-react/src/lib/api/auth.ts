import { AdminRole, AdminUser } from '@/types';
export type { AdminRole } from '@/types';
import { safeStorage } from '@/lib/storage';
import { useEffect, useState } from 'react';
import { jwtDecode } from 'jwt-decode';

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
    } catch {
      // A malformed cached payload is not worth failing over — treat it as absent.
    }
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

// ── Permission model (perm:*) ───────────────────────────────────────────────
// Mirrors the backend exactly: the token carries perm:* composite-role members and the UI gates on
// the same strings the gateway/services enforce. One source of truth = the Keycloak role composites.
export type Perm =
  | 'perm:report:view' | 'perm:audit:view'
  | 'perm:route:view' | 'perm:route:manage' | 'perm:route:validate'
  | 'perm:delivery:view' | 'perm:delivery:manage'
  | 'perm:driver:view' | 'perm:driver:manage'
  | 'perm:dispatch:operate'
  | 'perm:cash:view' | 'perm:cash:manage'
  | 'perm:erp:sync' | 'perm:erp:config'
  | 'perm:user:manage' | 'perm:company:manage' | 'perm:settings:manage';

export function getCurrentPerms(): string[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = safeStorage.getItem('perms');
    if (raw) return JSON.parse(raw);
    // Fallback for sessions established before this key existed (or before the next silent renew):
    // decode perm:* straight from the stored access token so gating is correct on first paint.
    const token = safeStorage.getItem('access_token');
    if (!token) return [];
    const decoded = jwtDecode<{ realm_access?: { roles?: string[] } }>(token);
    return (decoded.realm_access?.roles ?? []).filter((r) => r.startsWith('perm:'));
  } catch {
    return [];
  }
}

export function hasPerm(perm: Perm): boolean {
  return getCurrentPerms().includes(perm);
}

export function hasAnyPerm(...perms: Perm[]): boolean {
  const mine = getCurrentPerms();
  return perms.some((p) => mine.includes(p));
}

/**
 * Reactive permissions. Re-reads on login/renew (syncSession fires 'perms-changed') and on cross-tab
 * storage changes, so nav + action gating stay live without a full reload.
 */
export function usePermissions(): { perms: string[]; has: (p: Perm) => boolean; hasAny: (...p: Perm[]) => boolean } {
  const [perms, setPerms] = useState<string[]>(() => getCurrentPerms());
  useEffect(() => {
    const refresh = () => setPerms(getCurrentPerms());
    refresh();
    window.addEventListener('perms-changed', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      window.removeEventListener('perms-changed', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, []);
  return {
    perms,
    has: (p: Perm) => perms.includes(p),
    hasAny: (...p: Perm[]) => p.some((x) => perms.includes(x)),
  };
}

