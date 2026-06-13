import type { ColumnDef } from '@/hooks/useColumnSettings';

export const TERMINAL_STATUSES = new Set(['DELIVERED', 'FAILED', 'CANCELLED', 'PARTIALLY_DELIVERED']);

export type DriverCrud = {
  id?: string;
  name: string;
  phone: string;
  email: string;
};

export const DRIVER_COLUMNS: ColumnDef[] = [
  { id: 'driver',   label: 'Chauffeur',  pinned: true },
  { id: 'contact',  label: 'Contact' },
  { id: 'activity', label: 'Activité' },
  { id: 'status',   label: 'Statut' },
];

export const DRIVER_STATUS_COLORS: Record<string, { dot: string; bg: string; text: string; ribbon: string }> = {
  ACTIVE:        { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E', ribbon: '#4CAF82' },
  SUSPENDED:     { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24', ribbon: '#C7372F' },
  PENDING_SETUP: { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10', ribbon: '#C4881A' },
};
