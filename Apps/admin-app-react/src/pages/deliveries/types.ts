import type { Delivery } from '@/types';

export type QuickView =
  | 'all' | 'needsPinning' | 'unassigned' | 'inTransit'
  | 'completed' | 'failed' | 'overdue' | 'today' | 'future';

export type DeliveryRow = Delivery & { rowId: string };
