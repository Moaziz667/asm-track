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
