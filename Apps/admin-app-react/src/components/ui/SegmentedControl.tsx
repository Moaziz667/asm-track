import { cn } from '@/lib/utils';

export interface SegmentOption<T extends string | number> {
  value: T;
  label: string;
  /** Optional trailing count, e.g. tab badges ("Historique · 4"). */
  count?: number;
}

interface Props<T extends string | number> {
  value: T;
  onChange: (value: T) => void;
  options: SegmentOption<T>[];
  ariaLabel?: string;
  className?: string;
}

/**
 * Simple, pro segmented control — the Performance page "Période" style: bare pills, the selected one
 * filled with the subtle hover surface, the rest muted with a hover. Shared so the System Health range
 * picker and the handoff tabs read identically.
 */
export function SegmentedControl<T extends string | number>({
  value, onChange, options, ariaLabel, className,
}: Props<T>) {
  return (
    <div role="group" aria-label={ariaLabel} className={cn('flex items-center gap-1', className)}>
      {options.map(opt => {
        const active = opt.value === value;
        return (
          <button
            key={String(opt.value)}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(opt.value)}
            className={cn(
              'inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-[500] rounded-md transition-colors cursor-pointer',
              active
                ? 'bg-[var(--hover-bg)] text-[var(--text-primary)] font-[600]'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)]/50',
            )}
          >
            {opt.label}
            {opt.count != null && (
              <span
                className={cn(
                  'min-w-[16px] px-1 inline-flex items-center justify-center rounded-full text-2xs font-bold tabular-nums',
                  active ? 'text-[var(--text-primary)]' : 'text-[var(--text-soft)]',
                )}
              >
                {opt.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
