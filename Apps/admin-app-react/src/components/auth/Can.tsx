import { ReactNode } from 'react';
import { Perm, usePermissions } from '@/lib/api/auth';

/**
 * Renders children only when the current user holds the required permission(s) — the UI mirror of the
 * backend RBAC. Use it to hide action controls the user can't perform (create/edit/delete), so we never
 * show a button that would just 403. `any` switches from "needs all" to "needs at least one".
 *
 *   <Can perm="perm:driver:manage"><Button>Invite driver</Button></Can>
 *   <Can perm={['perm:route:manage', 'perm:dispatch:operate']} any>…</Can>
 */
export function Can({
  perm,
  any = false,
  fallback = null,
  children,
}: {
  perm: Perm | Perm[];
  any?: boolean;
  fallback?: ReactNode;
  children: ReactNode;
}) {
  const { perms } = usePermissions();
  const required = Array.isArray(perm) ? perm : [perm];
  const ok = any ? required.some((p) => perms.includes(p)) : required.every((p) => perms.includes(p));
  return <>{ok ? children : fallback}</>;
}
