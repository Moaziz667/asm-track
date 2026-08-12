import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export const OPS_SETTINGS_QUERY_KEY = ['ops-settings'] as const;

/**
 * Tenant-scoped operational thresholds (the same key/value store the Settings page edits).
 *
 * Screens that count down against a backend deadline must read it from here rather than hard-code
 * it: the handoff countdown used to assume 60 minutes, so raising
 * {@code ops.handoff.auto-cancel-minutes} would have left the dispatcher watching a timer that
 * expired while the backend still considered the transfer live.
 */
export function useOpsSettings() {
  const query = useQuery<Record<string, string>>({
    queryKey: OPS_SETTINGS_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Record<string, string>>('/admin/reports/settings');
      return res.data && typeof res.data === 'object' ? res.data : {};
    },
    retry: 1,
    staleTime: 5 * 60_000,
  });

  /** The stored value, or `fallback` when unset, blank or not a positive number. */
  const getInt = (key: string, fallback: number): number => {
    const raw = query.data?.[key];
    const n = raw != null && raw !== '' ? Number(raw) : NaN;
    return Number.isFinite(n) && n > 0 ? n : fallback;
  };

  return { ...query, getInt };
}

/** Defaults mirror `handoff.sla.*` in the delivery service's application.yml. */
export const HANDOFF_PENDING_KEY = 'ops.handoff.pending-minutes';
export const HANDOFF_PENDING_DEFAULT = 15;
export const HANDOFF_AUTO_CANCEL_KEY = 'ops.handoff.auto-cancel-minutes';
export const HANDOFF_AUTO_CANCEL_DEFAULT = 60;
