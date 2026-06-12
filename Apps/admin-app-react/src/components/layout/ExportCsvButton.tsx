import { ExcelIcon } from '@/components/icons/ExcelIcon';
import { exportCsv, type CsvColumn } from '@/lib/csv';
import { useT } from '@/lib/LocaleContext';

/**
 * Exports the rows it's given (the caller passes the already-filtered/visible list) to a CSV
 * download. Icon-only (Excel glyph) with a tooltip — keeps the toolbar clean. Lives in
 * PageFilterBar's extraActions slot. Disabled when there's nothing to export.
 */
export function ExportCsvButton<T>({
  baseName, rows, columns, className,
}: {
  baseName: string;
  rows: T[];
  columns: CsvColumn<T>[];
  className?: string;
}) {
  const t = useT() as any;
  const disabled = rows.length === 0;
  const tip = disabled
    ? (t.actions?.nothingToExport ?? 'Rien à exporter')
    : (t.actions?.exportToExcel ?? t.actions?.exportCsv ?? 'Exporter vers Excel');
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={() => exportCsv(baseName, rows, columns)}
      title={tip}
      aria-label={tip}
      className={
        className ??
        'h-7 w-7 inline-flex items-center justify-center border border-[var(--border)] rounded hover:border-[var(--border-strong)] hover:bg-[var(--hover-bg)] transition-colors disabled:opacity-40 disabled:cursor-not-allowed'
      }
    >
      <ExcelIcon size={16} />
    </button>
  );
}
