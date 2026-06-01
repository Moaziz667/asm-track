
import { Skeleton } from '@/components/ui/skeleton';
import { spacing } from '@/lib/design-tokens';

interface TableSkeletonProps {
  rows?: number;
  columns?: number;
  colWidths?: number[];
}

export function TableSkeleton({ rows = 8, columns = 5, colWidths }: TableSkeletonProps) {
  const widths = colWidths ?? Array.from({ length: columns }, (_, i) => {
    if (i === 0) return 0.5;
    if (i === columns - 1) return 0.4;
    return 0.8;
  });

  return (
    <div style={{ width: '100%' }}>
      {Array.from({ length: rows }).map((_, rowIdx) => (
        <div
          key={rowIdx}
          style={{
            height: spacing.rowHeight,
            display: 'flex',
            alignItems: 'center',
            padding: '0 12px',
            gap: 16,
            borderBottom: '1px solid var(--border)',
            background: rowIdx % 2 === 0 ? 'var(--surface)' : 'var(--app-bg)',
          }}
        >
          {widths.map((w, colIdx) => (
            <div key={colIdx} style={{ flex: w, overflow: 'hidden' }}>
              <Skeleton className="h-3" style={{ width: `${Math.round(40 + (colIdx * 13 + rowIdx * 7) % 40)}%` }} />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

export function LineSkeleton({ width = '60%' }: { width?: string | number }) {
  return <Skeleton className="h-3" style={{ width }} />;
}

export function CardSkeleton({ height = 80 }: { height?: number }) {
  return (
    <div style={{
      height,
      borderRadius: 'var(--radius)',
      border: '1px solid var(--border)',
      background: 'var(--surface)',
      padding: 16,
    }}>
      <div className="flex flex-col gap-2.5">
        <Skeleton className="h-3 w-[40%]" />
        <Skeleton className="h-5 w-[60%]" />
        <Skeleton className="h-3 w-[30%]" />
      </div>
    </div>
  );
}

