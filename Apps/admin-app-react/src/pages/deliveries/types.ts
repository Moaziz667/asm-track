import type { Delivery } from '@/types';

/** The quick views, as one list so the URL parser and the type cannot drift apart. */
export const QUICK_VIEWS = [
  'all', 'needsPinning', 'unassigned', 'inTransit',
  'completed', 'failed', 'overdue', 'today', 'future', 'returns', 'priority',
] as const;

export type QuickView = (typeof QUICK_VIEWS)[number];

export type DeliveryRow = Delivery & { rowId: string };
