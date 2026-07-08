import type { ReactNode } from 'react';
import { IconGitCompare } from '@tabler/icons-react';
import { SegmentedControl, type SegmentOption } from '@/components/ui/SegmentedControl';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';
import { cn } from '@/lib/utils';

/**
 * Shared analytics filter bar — the single control cluster for date range + comparison, used by the
 * Dashboard and the Analyse (driver scorecard) pages. Refactored out of both so the two stay in sync.
 *
 * Style follows the mockup: a segmented preset track, an inline custom date range (shown only for the
 * `custom` preset), an optional "vs previous period" toggle, and a right-aligned slot for page actions
 * (refresh, export…). Tokens only — no hardcoded colours.
 */
export interface AnalyticsFilterBarProps<R extends string> {
  /** Active preset value. `custom` reveals the from/to pickers. */
  range: R;
  onRangeChange: (r: R) => void;
  options: SegmentOption<R>[];
  ariaLabel?: string;

  /** Custom range (ISO yyyy-MM-dd). Provide handlers to enable the `custom` preset. */
  from?: string;
  to?: string;
  onFromChange?: (v: string) => void;
  onToChange?: (v: string) => void;
  fromLabel?: string;
  toLabel?: string;

  /** Optional period-over-period comparison toggle. */
  compare?: boolean;
  onCompareChange?: (v: boolean) => void;
  compareLabel?: string;

  /** Right-aligned actions (refresh, export…). */
  right?: ReactNode;
  className?: string;
}

export function AnalyticsFilterBar<R extends string>({
  range, onRangeChange, options, ariaLabel,
  from, to, onFromChange, onToChange, fromLabel = 'Du', toLabel = 'Au',
  compare, onCompareChange, compareLabel = 'vs période préc.',
  right, className,
}: AnalyticsFilterBarProps<R>) {
  const isCustom = range === ('custom' as R);
  return (
    <div className={cn('flex items-center gap-2 flex-wrap', className)}>
      <SegmentedControl<R> value={range} onChange={onRangeChange} options={options} ariaLabel={ariaLabel} />

      {isCustom && onFromChange && onToChange && (
        <div className="flex items-center gap-1.5">
          <DatePickerPopover value={from || null} onChange={v => onFromChange(v ?? '')} placeholder={fromLabel} />
          <span className="text-xs text-[var(--text-muted)]">→</span>
          <DatePickerPopover value={to || null} onChange={v => onToChange(v ?? '')} placeholder={toLabel} />
        </div>
      )}

      {onCompareChange && (
        <button
          type="button"
          onClick={() => onCompareChange(!compare)}
          aria-pressed={compare}
          className={cn(
            'h-7 inline-flex items-center gap-1.5 px-2.5 rounded-md border text-xs font-semibold transition-colors',
            compare
              ? 'text-[var(--brand)] border-[var(--brand)]/40 bg-[var(--brand-soft)]'
              : 'text-[var(--text-secondary)] border-[var(--border)] hover:bg-[var(--hover-bg)]',
          )}
        >
          <IconGitCompare size={13} /> {compareLabel}
        </button>
      )}

      {right && <div className="ml-auto flex items-center gap-2">{right}</div>}
    </div>
  );
}
