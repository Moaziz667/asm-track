import { IconDownload } from '@tabler/icons-react';
import { exportCsv, type CsvColumn } from '@/lib/csv';
import { useT } from '@/lib/LocaleContext';

/**
 * Exports the rows it's given (the caller passes the already-filtered/visible list) to a CSV
 * download. Lives in PageFilterBar's extraActions slot. Disabled when there's nothing to export.
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
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={() => exportCsv(baseName, rows, columns)}
      title={disabled ? (t.actions?.nothingToExport ?? 'Rien à exporter') : undefined}
      className={
        className ??
        'h-7 px-2.5 inline-flex items-center gap-1.5 border border-[var(--border)] rounded text-xs font-bold text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] transition-colors disabled:opacity-40 disabled:cursor-not-allowed'
      }
    >
      <IconDownload size={14} /> {t.actions?.exportCsv ?? 'Export CSV'}
    </button>
  );
}
