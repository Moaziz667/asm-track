import React from 'react';
import { cn } from '@/lib/utils';

/**
 * Shared server-side pagination footer for admin data tables (drivers, vehicles, users, …).
 * Promotes the one-off pagers previously duplicated in the Deliveries and Audit-logs pages into a
 * single design-system primitive: result count + page-size selector + numbered pager with ellipses.
 *
 * `page` is 0-based (matches the Spring `Page.number` the backend returns); the UI shows it 1-based.
 * Renders nothing when there is a single page and nothing to configure, so callers can drop it in
 * unconditionally.
 */
export interface TablePaginationProps {
  /** 0-based current page (Spring `number`). */
  page: number;
  /** Total page count (Spring `totalPages`). */
  totalPages: number;
  /** Total row count across all pages (Spring `totalElements`), shown in the summary when provided. */
  totalElements?: number;
  /** Current page size. */
  size: number;
  onPageChange: (page: number) => void;
  onSizeChange?: (size: number) => void;
  sizeOptions?: number[];
  /** i18n labels (French defaults — the app's primary locale). */
  labels?: { results?: string; page?: string; perPage?: string };
}

export function TablePagination({
  page,
  totalPages,
  totalElements,
  size,
  onPageChange,
  onSizeChange,
  sizeOptions = [25, 50, 100],
  labels,
}: TablePaginationProps) {
  const results = labels?.results ?? 'résultats';
  const pageLabel = labels?.page ?? 'Page';
  const perPage = labels?.perPage ?? '/ page';

  const total = Math.max(1, totalPages);
  const current = page + 1; // 1-based for display
  if (total <= 1 && !onSizeChange) return null;

  const allPages = Array.from({ length: total }, (_, i) => i + 1);
  const visible = allPages.filter((p) => p === 1 || p === total || Math.abs(p - current) <= 2);

  return (
    <div
      className="flex items-center justify-between gap-3 px-4 h-12 shrink-0 border-t border-[var(--border)]"
      style={{ background: 'var(--surface)' }}
    >
      <div className="flex items-center gap-3 text-xs font-medium text-[var(--text-muted)] min-w-0">
        <span className="truncate">
          {totalElements != null ? `${totalElements} ${results} · ` : ''}
          {pageLabel} {current}/{total}
        </span>
        {onSizeChange && (
          <select
            value={size}
            onChange={(e) => onSizeChange(Number(e.target.value))}
            className="h-7 rounded-md border border-[var(--border)] bg-[var(--app-bg)] px-2 text-xs font-medium text-[var(--text-primary)] outline-none focus:border-[var(--brand)] cursor-pointer"
            aria-label={perPage}
          >
            {sizeOptions.map((s) => (
              <option key={s} value={s}>{s} {perPage}</option>
            ))}
          </select>
        )}
      </div>

      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => onPageChange(page - 1)}
          disabled={current <= 1}
          className="w-7 h-7 flex items-center justify-center rounded border text-xs font-bold disabled:opacity-30 hover:bg-[var(--hover-bg)] border-[var(--border)] text-[var(--text-muted)]"
          aria-label="Précédent"
        >
          ‹
        </button>
        {visible.map((p, idx, arr) => (
          <React.Fragment key={p}>
            {idx > 0 && arr[idx - 1] !== p - 1 && (
              <span className="text-xs text-[var(--text-muted)] px-1">…</span>
            )}
            <button
              type="button"
              onClick={() => onPageChange(p - 1)}
              className={cn(
                'w-7 h-7 flex items-center justify-center rounded border text-xs font-bold',
                p === current
                  ? 'bg-[var(--brand)] text-white border-[var(--brand)]'
                  : 'border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]',
              )}
              aria-current={p === current ? 'page' : undefined}
            >
              {p}
            </button>
          </React.Fragment>
        ))}
        <button
          type="button"
          onClick={() => onPageChange(page + 1)}
          disabled={current >= total}
          className="w-7 h-7 flex items-center justify-center rounded border text-xs font-bold disabled:opacity-30 hover:bg-[var(--hover-bg)] border-[var(--border)] text-[var(--text-muted)]"
          aria-label="Suivant"
        >
          ›
        </button>
      </div>
    </div>
  );
}
