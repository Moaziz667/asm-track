import type { Delivery } from '@/types';

export function getRowId(item: Delivery): string {
  return String(item.deliveryId ?? item.id ?? '');
}

export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
