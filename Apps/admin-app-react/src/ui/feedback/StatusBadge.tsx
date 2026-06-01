
import * as React from 'react';
import { cn } from '@/lib/utils';

export type BadgeStatus = 
  | 'UNSCHEDULED' 
  | 'SCHEDULED' 
  | 'PICKED_UP' 
  | 'IN_TRANSIT' 
  | 'DELIVERED' 
  | 'PARTIALLY_DELIVERED' 
  | 'FAILED' 
  | 'CANCELLED'
  | 'ACTIVE'
  | 'MUTED'
  | 'MAINTENANCE';

interface StatusBadgeProps {
  status: BadgeStatus | string;
  className?: string;
  size?: 'xs' | 'sm' | 'md';
}

const STATUS_CONFIGS: Record<
  string, 
  { label: string; bg: string; text: string; border: string }
> = {
  // Logistics States
  UNSCHEDULED: {
    label: 'Non planifié',
    bg: 'bg-neutral-500/10 dark:bg-neutral-500/15',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  },
  SCHEDULED: {
    label: 'Planifié',
    bg: 'bg-state-active/10',
    text: 'text-state-active',
    border: 'border-state-active/20',
  },
  PICKED_UP: {
    label: 'Ramassé',
    bg: 'bg-state-active/10',
    text: 'text-state-active',
    border: 'border-state-active/20',
  },
  IN_TRANSIT: {
    label: 'En route',
    bg: 'bg-state-active/10',
    text: 'text-state-active',
    border: 'border-state-active/20',
  },
  DELIVERED: {
    label: 'Livré',
    bg: 'bg-state-success/10',
    text: 'text-state-success',
    border: 'border-state-success/20',
  },
  PARTIALLY_DELIVERED: {
    label: 'Partielle',
    bg: 'bg-state-warning/10',
    text: 'text-state-warning',
    border: 'border-state-warning/20',
  },
  FAILED: {
    label: 'Échec',
    bg: 'bg-state-critical/10',
    text: 'text-state-critical',
    border: 'border-state-critical/20',
  },
  CANCELLED: {
    label: 'Annulé',
    bg: 'bg-neutral-500/10 dark:bg-neutral-500/15',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  },

  // Fleet/Device States
  ACTIVE: {
    label: 'En service',
    bg: 'bg-state-success/10',
    text: 'text-state-success',
    border: 'border-state-success/20',
  },
  MUTED: {
    label: 'Hors service',
    bg: 'bg-neutral-500/10 dark:bg-neutral-500/15',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  },
  MAINTENANCE: {
    label: 'Maintenance',
    bg: 'bg-state-warning/10',
    text: 'text-state-warning',
    border: 'border-state-warning/20',
  },
};

export function StatusBadge({ status, className, size = 'sm' }: StatusBadgeProps) {
  const normalized = String(status || '').toUpperCase();
  const config = STATUS_CONFIGS[normalized] || {
    label: String(status || ''),
    bg: 'bg-neutral-500/10',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  };

  return (
    <span
      className={cn(
        'inline-flex items-center justify-center font-bold uppercase tracking-wider border rounded-dense-sm select-none',
        config.bg,
        config.text,
        config.border,
        size === 'xs' && 'px-1 py-0.5 text-[9px] h-4.5',
        size === 'sm' && 'px-2 py-0.5 text-[10px] h-5',
        size === 'md' && 'px-2.5 py-1 text-[11px] h-6',
        className
      )}
    >
      {config.label}
    </span>
  );
}

