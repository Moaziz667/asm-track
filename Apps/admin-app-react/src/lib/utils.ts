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
export function routeColor(routeId?: string | null): string {
  if (!routeId) return '#71717A';
  let h = 0;
  for (let i = 0; i < routeId.length; i++) h = (h * 31 + routeId.charCodeAt(i)) >>> 0;
  return ROUTE_PALETTE[h % ROUTE_PALETTE.length];
}

/** Distinct colour by position — adjacent routes never collide (unlike the hash-based
 *  routeColor). Use when the full ordered route list is available. */
export function routeColorByIndex(index: number): string {
  const n = ROUTE_PALETTE.length;
  return ROUTE_PALETTE[((index % n) + n) % n];
}
