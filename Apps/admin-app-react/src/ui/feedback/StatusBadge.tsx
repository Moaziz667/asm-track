
import * as React from 'react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';

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

const STATUS_STYLES: Record<
  string, 
  { bg: string; text: string; border: string }
> = {
  UNSCHEDULED: {
    bg: 'bg-neutral-500/10 dark:bg-neutral-500/15',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  },
  SCHEDULED: {
    bg: 'bg-state-active/10',
    text: 'text-state-active',
    border: 'border-state-active/20',
  },
  PICKED_UP: {
    bg: 'bg-state-active/10',
    text: 'text-state-active',
    border: 'border-state-active/20',
  },
  IN_TRANSIT: {
    bg: 'bg-state-active/10',
    text: 'text-state-active',
    border: 'border-state-active/20',
  },
  DELIVERED: {
    bg: 'bg-state-success/10',
    text: 'text-state-success',
    border: 'border-state-success/20',
  },
  PARTIALLY_DELIVERED: {
    bg: 'bg-state-warning/10',
    text: 'text-state-warning',
    border: 'border-state-warning/20',
  },
  FAILED: {
    bg: 'bg-state-critical/10',
    text: 'text-state-critical',
    border: 'border-state-critical/20',
  },
  CANCELLED: {
    bg: 'bg-neutral-500/10 dark:bg-neutral-500/15',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  },
  ACTIVE: {
    bg: 'bg-state-success/10',
    text: 'text-state-success',
    border: 'border-state-success/20',
  },
  MUTED: {
    bg: 'bg-neutral-500/10 dark:bg-neutral-500/15',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  },
  MAINTENANCE: {
    bg: 'bg-state-warning/10',
    text: 'text-state-warning',
    border: 'border-state-warning/20',
  },
};

export function StatusBadge({ status, className, size = 'sm' }: StatusBadgeProps) {
  const t = useT();
  const normalized = String(status || '').toUpperCase();
  const style = STATUS_STYLES[normalized] || {
    bg: 'bg-neutral-500/10',
    text: 'text-neutral-600 dark:text-neutral-400',
    border: 'border-neutral-500/20',
  };
  const label = t.statusLabels[normalized as keyof typeof t.statusLabels] ?? String(status || '');

  return (
    <span
      className={cn(
        'inline-flex items-center justify-center font-bold uppercase tracking-wider border rounded-dense-sm select-none',
        style.bg,
        style.text,
        style.border,
        size === 'xs' && 'px-1 py-0.5 text-[9px] h-4.5',
        size === 'sm' && 'px-2 py-0.5 text-2xs h-5',
        size === 'md' && 'px-2.5 py-1 text-xs h-6',
        className
      )}
    >
      {label}
    </span>
  );
}

