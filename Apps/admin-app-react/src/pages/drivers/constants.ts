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
  ACTIVE:        { dot: 'var(--success)', bg: 'var(--success-bg)', text: 'var(--success)', ribbon: 'var(--success)' },
  SUSPENDED:     { dot: 'var(--danger)',  bg: 'var(--danger-bg)',  text: 'var(--danger)',  ribbon: 'var(--danger)' },
  PENDING_SETUP: { dot: 'var(--warning)', bg: 'var(--warning-bg)', text: 'var(--warning)', ribbon: 'var(--warning)' },
};
