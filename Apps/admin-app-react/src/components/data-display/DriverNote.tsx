import { IconQuote } from '@tabler/icons-react';

/**
 * The driver's own words on a delivery — the POD handover comment, or the driver's failure comment.
 * Shared by the delivery-details page and the dispatch desk so both read identically. Never fed the
 * admin failure-reason label (that lives in the Articles table's Motif column).
 */
export function DriverNote({ comment, driverName, emptyLabel }: { comment?: string | null; driverName?: string | null; emptyLabel: string }) {
  if (!comment) {
    return <p className="text-xs italic text-[var(--text-muted)]">{emptyLabel}</p>;
  }
  return (
    <div className="flex gap-3 rounded-md border border-[var(--border)] bg-[var(--app-bg)] p-4">
      <IconQuote size={18} className="shrink-0 text-[var(--brand)] opacity-60" />
      <div className="flex flex-col gap-2 min-w-0">
        <p className="text-sm leading-relaxed italic text-[var(--text-primary)]">“{comment}”</p>
        {driverName && (
          <span className="text-2xs font-semibold text-[var(--text-muted)]">— {driverName}</span>
        )}
      </div>
    </div>
  );
}
