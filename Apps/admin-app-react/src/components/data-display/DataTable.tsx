
import { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { TableSkeleton } from '@/components/feedback/TableSkeleton';
import { EmptyState } from '@/components/feedback/EmptyState';
import { useT } from '@/lib/LocaleContext';

export interface Column<T> {
  key: string;
  label: ReactNode;
  width?: number | string;
  align?: 'left' | 'center' | 'right';
  render: (row: T, index: number) => ReactNode;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  loading?: boolean;
  skeletonRows?: number;
  emptyMessage?: string;
  emptyHint?: string;
  emptyIcon?: ReactNode;
  onRowClick?: (row: T) => void;
  rowKey: (row: T) => string;
  className?: string;
  stickyHeader?: boolean;
}

export function DataTable<T>({
  columns,
  data,
  loading = false,
  skeletonRows = 8,
  emptyMessage,
  emptyHint,
  emptyIcon,
  onRowClick,
  rowKey,
  className,
  stickyHeader = false,
}: DataTableProps<T>) {
  const t = useT();
  const resolvedEmptyMessage = emptyMessage ?? t.empty.generic;
  return (
    <div className={cn('pro-table-container', className)}>
      {/* Header */}
      <div className={cn('pro-table-header', stickyHeader && 'sticky top-0 z-10')}>
        <div className="flex">
          {columns.map((col) => (
            <div
              key={col.key}
              className="pro-table-th"
              style={{
                width: col.width,
                flex: col.width ? undefined : 1,
                textAlign: col.align ?? 'left',
              }}
            >
              {col.label}
            </div>
          ))}
        </div>
      </div>

      {loading && <TableSkeleton rows={skeletonRows} columns={columns.length} />}

      {!loading && data.length === 0 && (
        <EmptyState icon={emptyIcon} message={resolvedEmptyMessage} hint={emptyHint} />
      )}

      {!loading && data.map((row, i) => (
        <div
          key={rowKey(row)}
          className={cn(
            'flex border-b border-[var(--border)] last:border-0',
            onRowClick && 'cursor-pointer hover:bg-[var(--hover-bg)] transition-colors',
          )}
          onClick={() => onRowClick?.(row)}
        >
          {columns.map((col) => (
            <div
              key={col.key}
              className="pro-table-td"
              style={{
                width: col.width,
                flex: col.width ? undefined : 1,
                textAlign: col.align ?? 'left',
              }}
            >
              {col.render(row, i)}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

