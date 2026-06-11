
import { ReactNode, InputHTMLAttributes, forwardRef } from 'react';
import { cn } from '@/lib/utils';
import { IconSearch, IconX } from '@tabler/icons-react';

// ── SearchInput ───────────────────────────────────────────────────────────────

interface SearchInputProps extends InputHTMLAttributes<HTMLInputElement> {
  onClear?: () => void;
}

export const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(
  ({ className, value, onClear, ...props }, ref) => (
    <div className="relative flex items-center">
      <IconSearch size={13} className="absolute left-3 text-[var(--text-soft)] pointer-events-none" />
      <input
        ref={ref}
        value={value}
        className={cn(
          'h-8 w-full rounded border border-[var(--border)] bg-[var(--surface)]',
          'pl-8 pr-7 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-soft)]',
          'focus:outline-none focus:border-[var(--brand)] focus:ring-1 focus:ring-[var(--brand)]',
          'transition-colors',
          className,
        )}
        {...props}
      />
      {value && onClear && (
        <button
          type="button"
          onClick={onClear}
          className="absolute right-2.5 flex items-center text-[var(--text-soft)] hover:text-[var(--text-primary)] transition-colors"
        >
          <IconX size={12} />
        </button>
      )}
    </div>
  )
);
SearchInput.displayName = 'SearchInput';

// ── FilterChip ────────────────────────────────────────────────────────────────

interface FilterChipProps {
  label: string;
  active?: boolean;
  count?: number;
  onClick?: () => void;
}

export function FilterChip({ label, active, count, onClick }: FilterChipProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'inline-flex items-center gap-1.5 h-7 px-3 rounded text-xs font-medium transition-colors',
        'border',
        active
          ? 'bg-[var(--brand-soft)] border-[var(--brand)] text-[var(--brand)]'
          : 'bg-[var(--surface)] border-[var(--border)] text-[var(--text-muted)] hover:border-[var(--border-strong)] hover:text-[var(--text-primary)]',
      )}
    >
      {label}
      {count !== undefined && (
        <span className={cn(
          'text-2xs font-semibold tabular-nums',
          active ? 'text-[var(--brand)]' : 'text-[var(--text-soft)]',
        )}>
          {count}
        </span>
      )}
    </button>
  );
}

// ── FilterBar ─────────────────────────────────────────────────────────────────

interface FilterBarProps {
  children: ReactNode;
  className?: string;
}

export function FilterBar({ children, className }: FilterBarProps) {
  return (
    <div className={cn('flex items-center gap-2 flex-wrap', className)}>
      {children}
    </div>
  );
}

// ── FilterBarActions (right-aligned slot) ─────────────────────────────────────

export function FilterBarActions({ children }: { children: ReactNode }) {
  return (
    <div className="ml-auto flex items-center gap-2">
      {children}
    </div>
  );
}

