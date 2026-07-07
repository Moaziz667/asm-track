import type { ColumnDef } from '@/hooks/useColumnSettings';

export const DELIVERY_COLUMNS: ColumnDef[] = [
  { id: 'ref',       label: 'Référence',  pinned: true },
  { id: 'client',    label: 'Client / Adresse', pinned: true },
  { id: 'scheduled', label: 'Programmée' },
  { id: 'status',    label: 'Statut' },
  { id: 'driver',    label: 'Chauffeur' },
  { id: 'zone',      label: 'Zone' },
];

export const DELIVERY_ROW_H = { compact: 'h-10', comfortable: 'h-14', spacious: 'h-20' } as const;

export const DELIVERY_STATUSES: Array<{ value: string; label: string }> = [
  { value: 'UNSCHEDULED', label: '' },
  { value: 'SCHEDULED', label: '' },
  { value: 'PICKED_UP', label: '' },
  { value: 'IN_TRANSIT', label: '' },
  { value: 'AWAITING_HANDOFF', label: '' },
  { value: 'DELIVERED', label: '' },
  { value: 'PARTIALLY_DELIVERED', label: '' },
  { value: 'FAILED', label: '' },
  { value: 'CANCELLED', label: '' },
];
