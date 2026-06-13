import { cn } from '@/lib/utils';
import type { Delivery } from '@/types';

/** Strip the Tunisian admin prefixes ("Gouvernorat ", "Délégation ") for compact display. */
export function cleanTunisianAdminName(name: string | null | undefined): string {
  if (!name) return '';
  if (name.startsWith('Gouvernorat ')) return name.substring('Gouvernorat '.length);
  if (name.startsWith('Délégation ')) return name.substring('Délégation '.length);
  if (name.startsWith('Delegation ')) return name.substring('Delegation '.length);
  return name;
}

export function getRowId(item: Delivery): string {
  return String(item.deliveryId ?? item.id ?? '');
}

export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

/** Inline spinner (replaces Mantine Loader). */
export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={cn('animate-spin h-4 w-4', className)} fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
    </svg>
  );
}
