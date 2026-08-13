import { driverStatusDot, driverStatusLabel } from '@/lib/state/driver-status';
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import type { DeliveryStatus } from '@/types';

export const REASSIGNABLE_STATUSES: DeliveryStatus[] = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'];
export const REPLANNABLE_STATUSES:  DeliveryStatus[] = ['SCHEDULED', 'PICKED_UP', 'FAILED', 'CANCELLED'];
export const ASSIGNABLE_STATUSES:   DeliveryStatus[] = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'];

// Kept as thin aliases: the colours and labels live in lib/state/driver-status, next to the rule
// that decides what a status means. This file used to hold its own copy of the three hexadecimals
// and a French label frozen beside an i18n one.
export const STATUS_DOT: Record<string, string> = {
  ONLINE: driverStatusDot('ONLINE'),
  ON_BREAK: driverStatusDot('ON_BREAK'),
  OFFLINE: driverStatusDot('OFFLINE'),
};

export function getDriverStatusTip(status: string | undefined, t: TranslationSchema): string {
  return driverStatusLabel(status, t);
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
