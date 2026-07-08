// Client-side CSV export of the currently-visible rows — no backend round-trip. Each column is
// declared with a header + an accessor, so callers export exactly what they see (filtered/sorted).

export type CsvColumn<T> = {
  header: string;
  /** Cell value for a row. Return string | number | null/undefined; nullish becomes ''. */
  accessor: (row: T) => string | number | null | undefined;
};

/** Escape a single CSV field per RFC 4180: wrap in quotes if it contains a comma, quote, or newline. */
function escapeCsv(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return '';
  const s = String(value);
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

/**
 * Build a CSV string from rows + columns. Prepends a UTF-8 BOM so Excel opens accented/Arabic
 * text correctly.
 */
export function toCsv<T>(rows: T[], columns: CsvColumn<T>[]): string {
  const head = columns.map(c => escapeCsv(c.header)).join(',');
  const body = rows.map(r => columns.map(c => escapeCsv(c.accessor(r))).join(',')).join('\r\n');
  return '﻿' + head + '\r\n' + body;
}

/** Trigger a browser download of `content` as `filename`. */
export function downloadCsv(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename.endsWith('.csv') ? filename : `${filename}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** Convenience: build + download in one call. Stamps the filename with today's date. */
export function exportCsv<T>(baseName: string, rows: T[], columns: CsvColumn<T>[]): void {
  const date = new Date().toISOString().slice(0, 10);
  downloadCsv(`${baseName}_${date}.csv`, toCsv(rows, columns));
}
