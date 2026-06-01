
import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

interface KPICardProps {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  icon?: ReactNode;
  tone?: 'default' | 'success' | 'warning' | 'danger' | 'info';
  className?: string;
  onClick?: () => void;
}

const TONE_DOT: Record<string, string> = {
  default: 'bg-[var(--text-soft)]',
  success: 'bg-[var(--success)]',
  warning: 'bg-[var(--warning)]',
  danger:  'bg-[var(--danger)]',
  info:    'bg-[var(--info)]',
};

export function KPICard({ label, value, sub, icon, tone = 'default', className, onClick }: KPICardProps) {
  return (
    <div
      onClick={onClick}
      className={cn(
        'card p-4 flex flex-col gap-2',
        onClick && 'cursor-pointer hover:border-[var(--border-strong)] transition-colors',
        className,
      )}
    >
      <div className="flex items-center justify-between">
        <span className="label-sm text-[var(--text-muted)]">{label}</span>
        <div className="flex items-center gap-2">
          {icon && <span className="text-[var(--text-soft)]">{icon}</span>}
          <span className={cn('w-2 h-2 rounded-full', TONE_DOT[tone])} />
        </div>
      </div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="text-xs text-[var(--text-soft)]">{sub}</div>}
    </div>
  );
}

