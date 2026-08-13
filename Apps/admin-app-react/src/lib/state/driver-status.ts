import { create } from 'zustand';
import { DRIVER_STATUS_COLOR } from '@/lib/ui/design-tokens';
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';

/**
 * One answer to "is this driver connected?", for every screen that asks.
 *
 * <p>Five surfaces asked it and five answered differently: the drivers page compared to 'ONLINE'
 * and so showed a driver on break as offline; the floating map required coordinates and so showed a
 * connected driver as offline until his first GPS fix; the dispatch map added a staleness test; the
 * routes table read a status frozen at page load. The same driver could be green on one screen and
 * grey on another, which is worse than either being wrong on its own.
 *
 * <p>Statuses also arrive here once. The socket already delivers driver.status_changed to the whole
 * app, so a page that wants a live badge subscribes to nothing: it reads the store.
 */
export type DriverStatus = 'ONLINE' | 'ON_BREAK' | 'OFFLINE';

export function normalizeDriverStatus(raw: string | null | undefined): DriverStatus {
  return raw === 'ONLINE' || raw === 'ON_BREAK' ? raw : 'OFFLINE';
}

/**
 * Connected — reachable right now, break included.
 *
 * <p>A driver on break has his app open and his phone answering; hiding him would tell a dispatcher
 * looking for someone that nobody is there. Whether he should be *disturbed* is a different
 * question, and that is what the amber dot is for.
 */
export function isDriverConnected(status: string | null | undefined): boolean {
  return normalizeDriverStatus(status) !== 'OFFLINE';
}

/** The dot colour, from the design tokens — never a hex written at the call site. */
export function driverStatusDot(status: string | null | undefined): string {
  return DRIVER_STATUS_COLOR[normalizeDriverStatus(status)].dot;
}

export function driverStatusLabel(status: string | null | undefined, t: TranslationSchema): string {
  const key = normalizeDriverStatus(status);
  const labels: Record<DriverStatus, string | undefined> = {
    ONLINE: t.dispatchDeskPage?.driverOnline,
    ON_BREAK: t.dispatchDeskPage?.driverOnBreak,
    OFFLINE: t.dispatchDeskPage?.driverOffline,
  };
  // The design token carries a French label as its own fallback, so a missing key never shows a raw
  // enum name to a user.
  return labels[key] ?? DRIVER_STATUS_COLOR[key].label;
}

type DriverStatusState = {
  /** Only what the socket has reported since load — the fetched payload stays the base truth. */
  live: Record<string, DriverStatus>;
  apply: (driverId: string, status: string) => void;
};

export const useDriverStatusStore = create<DriverStatusState>((set) => ({
  live: {},
  apply: (driverId, status) =>
    set((state) => {
      const next = normalizeDriverStatus(status);
      if (state.live[driverId] === next) return state;      // no re-render for a repeated event
      return { live: { ...state.live, [driverId]: next } };
    }),
}));

/**
 * The status to display: what the socket last said, falling back to what the API returned.
 *
 * <p>Passing the fetched value as {@code fetched} keeps a freshly loaded page correct before any
 * event has arrived, and a long-open page correct after one has.
 */
export function useDriverStatus(driverId: string | null | undefined,
                                fetched: string | null | undefined): DriverStatus {
  const live = useDriverStatusStore((s) => (driverId ? s.live[driverId] : undefined));
  return live ?? normalizeDriverStatus(fetched);
}

/** For lists: resolve many at once without a hook per row. */
export function useDriverStatusResolver(): (driverId: string | null | undefined,
                                           fetched: string | null | undefined) => DriverStatus {
  const live = useDriverStatusStore((s) => s.live);
  return (driverId, fetched) =>
    (driverId ? live[driverId] : undefined) ?? normalizeDriverStatus(fetched);
}
