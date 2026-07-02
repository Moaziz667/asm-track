export function rowId(d: { id?: string; deliveryId?: string }): string {
  return d.deliveryId ?? d.id ?? '';
}

/** A delivery is "pinned" once it has dropoff coordinates. Unpinned deliveries can't be routed
 *  (no navigation, no geofence) so they are flagged + non-selectable for assignment until pinned. */
export function isPinned(d: { dropoffLat?: number | null; dropoffLng?: number | null }): boolean {
  return d.dropoffLat != null && d.dropoffLng != null;
}

// ── Queue sorting ────────────────────────────────────────────────────────────
export type QueueSortMode = 'sla' | 'route' | 'severity' | 'status' | 'date';

type SortableRow = {
  routeId?: string;
  routeName?: string;
  alert?: { severity?: string; slaHealth?: string };
  delivery: { status?: string; createdAt?: string | null; slaHealth?: string; slaWorstHealth?: string };
};

const sevRank = (s?: string) => (s === 'CRITICAL' ? 0 : s === 'WARNING' ? 1 : s ? 2 : 3);

// SLA risk ranking — the dispatcher's first question is "what's about to breach?", so breached/
// overdue sorts to the very top, then at-risk, then everything healthy. Falls back to the worst
// PAST phase health (so a failed-but-was-late delivery still ranks as urgent), then the alert.
const slaRank = (row: SortableRow): number => {
  const h = row.delivery.slaHealth ?? row.alert?.slaHealth;
  const worst = row.delivery.slaWorstHealth;
  const pick = (h && h !== 'NONE') ? h : worst;
  switch (pick) {
    case 'BREACHED': case 'LATE': return 0;
    case 'AT_RISK':               return 1;
    case 'ON_TRACK':              return 2;
    case 'MET':                   return 3;
    default:                      return 4; // NONE / unknown
  }
};
const STATUS_ORDER = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'PARTIALLY_DELIVERED', 'FAILED', 'DELIVERED', 'CANCELLED'];
const statusRank = (s?: string) => { const i = STATUS_ORDER.indexOf(s ?? ''); return i < 0 ? 99 : i; };

/** Sort the unified queue by the chosen mode. `route` puts assigned-to-route rows first. */
export function sortQueue<T extends SortableRow>(items: T[], mode: QueueSortMode): T[] {
  const arr = [...items];
  switch (mode) {
    case 'sla':
      // SLA risk first (breached → at-risk → healthy), then severity, then route grouping.
      return arr.sort((a, b) => slaRank(a) - slaRank(b)
        || sevRank(a.alert?.severity) - sevRank(b.alert?.severity)
        || (a.routeName ?? '').localeCompare(b.routeName ?? ''));
    case 'severity':
      return arr.sort((a, b) => sevRank(a.alert?.severity) - sevRank(b.alert?.severity)
        || (a.routeName ?? '').localeCompare(b.routeName ?? ''));
    case 'status':
      return arr.sort((a, b) => statusRank(a.delivery.status) - statusRank(b.delivery.status)
        || sevRank(a.alert?.severity) - sevRank(b.alert?.severity));
    case 'date':
      return arr.sort((a, b) => new Date(b.delivery.createdAt ?? 0).getTime() - new Date(a.delivery.createdAt ?? 0).getTime());
    case 'route':
    default:
      // Assigned-to-route first (routes before "non assigné"), grouped by route, then by severity.
      return arr.sort((a, b) => {
        const aHas = a.routeId ? 0 : 1, bHas = b.routeId ? 0 : 1;
        if (aHas !== bHas) return aHas - bHas;
        return (a.routeName ?? '').localeCompare(b.routeName ?? '')
          || sevRank(a.alert?.severity) - sevRank(b.alert?.severity);
      });
  }
}

export function getWeekStart(): string {
  const d = new Date();
  d.setDate(d.getDate() - d.getDay() + 1);
  return d.toISOString().slice(0, 10);
}

export function getMonthStart(): string {
  const d = new Date();
  d.setDate(1);
  return d.toISOString().slice(0, 10);
}

export function sortByRoute<T extends { routeName?: string; routeId?: string }>(
  items: T[],
  secondaryScore: (item: T) => number,
): T[] {
  return [...items].sort((a, b) => {
    const rA = a.routeName ?? '';
    const rB = b.routeName ?? '';
    if (rA !== rB) return rA.localeCompare(rB);
    return secondaryScore(a) - secondaryScore(b);
  });
}
