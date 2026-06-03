import type { DeliveryStatus } from '@/types';

export const REASSIGNABLE_STATUSES: DeliveryStatus[] = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP'];
export const REPLANNABLE_STATUSES:  DeliveryStatus[] = ['SCHEDULED', 'PICKED_UP', 'FAILED', 'CANCELLED'];
export const ASSIGNABLE_STATUSES:   DeliveryStatus[] = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'];

export const STATUS_DOT: Record<string, string> = {
  ONLINE:   '#10B981',
  ON_BREAK: '#F59E0B',
  OFFLINE:  '#9CA3AF',
};

export const STATUS_TIP: Record<string, string> = {
  ONLINE: 'En service', ON_BREAK: 'En pause', OFFLINE: 'Hors ligne',
};

export function getDriverStatusTip(status: string | undefined, t: any): string {
  const map: Record<string, string> = {
    ONLINE: t.dispatchDeskPage.driverOnline,
    ON_BREAK: t.dispatchDeskPage.driverOnBreak,
    OFFLINE: t.dispatchDeskPage.driverOffline,
  };
  return map[status ?? 'OFFLINE'] ?? t.dispatchDeskPage.driverOffline;
}

// Pastel severity chips driven by themed tokens so they adapt to light AND
// dark mode (AWS-console style). Each token already has light/dark values.
export const SEVERITY_CHIP: Record<'CRITICAL' | 'WARNING' | 'INFO', { accent: string; text: string; bg: string }> = {
  CRITICAL: { accent: 'var(--danger)',    text: 'var(--danger)',     bg: 'var(--danger-bg)' },
  WARNING:  { accent: 'var(--warning)',   text: 'var(--warning)',    bg: 'var(--warning-bg)' },
  INFO:     { accent: 'var(--text-soft)', text: 'var(--text-muted)', bg: 'var(--hover-bg)' },
};

export const RIBBON: Record<string, string> = {
  DELIVERED:           '#10B981',
  IN_TRANSIT:          '#F97316',
  PICKED_UP:           '#F97316',
  SCHEDULED:           '#2563EB',
  UNSCHEDULED:         '#6366F1',
  FAILED:              '#EF4444',
  CANCELLED:           '#9CA3AF',
  PARTIALLY_DELIVERED: '#8B5CF6',
};

export const INPUT_STYLES = {
  input: {
    height: 36,
    borderRadius: 'var(--radius)',
    border: '1px solid var(--border-color)',
    background: 'var(--surface-2)',
    color: 'var(--text-strong)',
    fontSize: '12px',
  },
};
