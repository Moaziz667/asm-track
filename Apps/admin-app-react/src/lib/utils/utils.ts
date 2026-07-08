import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function resolveOrderRef(item: {
  orderRef?: string | null;
  erpOrderId?: string | null;
  erpExternalRef?: string | null;
  erpId?: string | null;
} | null | undefined): string {
  if (!item) return '—';
  return item.orderRef || item.erpOrderId || item.erpExternalRef || item.erpId || '—';
}

export function shortId(id: string | null | undefined): string {
  if (!id) return '—';
  return id.replace(/-/g, '').substring(0, 8).toUpperCase();
}

export function formatMoney(
  amount: number | string | null | undefined,
  currency = 'TND'
): string {
  if (amount == null || amount === '') return '—';
  const num = typeof amount === 'string' ? parseFloat(amount) : amount;
  if (isNaN(num)) return '—';
  const decimals = currency === 'TND' || currency === 'DT' ? 3 : 2;
  const formatted = num.toLocaleString('fr-FR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
  return `${formatted} ${currency}`;
}

/**
 * Canonical duration/delay formatter: minutes → "5h 33m" / "33m" / "-12m".
 * Signed (negative = early/ahead). Used by route KPIs, the route report card and anywhere a raw
 * minute count would otherwise leak (e.g. "333 min").
 */
export function formatMinutes(mins?: number | null): string {
  if (mins == null) return '0m';
  const a = Math.abs(Math.round(mins));
  const h = Math.floor(a / 60);
  const m = a % 60;
  const sign = mins < 0 ? '-' : '';
  return h > 0 ? `${sign}${h}h ${m}m` : `${sign}${m}m`;
}

// Shared route palette — the same colour identifies a route's legend row, its stop pins and its
// driver car on the live map, so a dispatcher can match them at a glance.
export const ROUTE_PALETTE = ['#5E6AD2', '#2D8A5E', '#D4772C', '#9333EA', '#0891B2', '#DB2777', '#CA8A04', '#4F46E5', '#15803D', '#B45309'];

const ROUTE_COLOR_FALLBACK = '#71717A';

/** Build a stable route→colour map from an ordered route list. Position-based assignment
 *  guarantees adjacent routes never share a colour. Routes beyond the palette length wrap. */
export function createRouteColorMap(routes: { id: string }[]): Map<string, string> {
  const map = new Map<string, string>();
  const n = ROUTE_PALETTE.length;
  routes.forEach((r, i) => map.set(r.id, ROUTE_PALETTE[((i % n) + n) % n]));
  return map;
}

/** Convenience: look up a route's colour from a pre-built map (returns fallback grey when
 *  the route is unknown or the map hasn't been built yet). */
export function routeColorFromMap(map: Map<string, string> | undefined, routeId?: string | null): string {
  if (!routeId) return ROUTE_COLOR_FALLBACK;
  return map?.get(routeId) ?? ROUTE_COLOR_FALLBACK;
}
