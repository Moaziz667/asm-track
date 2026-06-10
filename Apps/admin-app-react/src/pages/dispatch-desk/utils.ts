export function rowId(d: { id?: string; deliveryId?: string }): string {
  return d.deliveryId ?? d.id ?? '';
}

// ── Queue sorting ────────────────────────────────────────────────────────────
export type QueueSortMode = 'route' | 'severity' | 'status' | 'date';

type SortableRow = {
  routeId?: string;
  routeName?: string;
  alert?: { severity?: string };
  delivery: { status?: string; createdAt?: string | null };
};

const sevRank = (s?: string) => (s === 'CRITICAL' ? 0 : s === 'WARNING' ? 1 : s ? 2 : 3);
const STATUS_ORDER = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'PARTIALLY_DELIVERED', 'FAILED', 'DELIVERED', 'CANCELLED'];
const statusRank = (s?: string) => { const i = STATUS_ORDER.indexOf(s ?? ''); return i < 0 ? 99 : i; };

/** Sort the unified queue by the chosen mode. `route` puts assigned-to-route rows first. */
export function sortQueue<T extends SortableRow>(items: T[], mode: QueueSortMode): T[] {
  const arr = [...items];
  switch (mode) {
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
