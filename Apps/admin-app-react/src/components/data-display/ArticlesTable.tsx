import { formatMoney } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { ItemOutcomeBadge } from '@/components/data-display/ItemOutcomeBadge';
import { TooltipProvider } from '@/components/ui/tooltip';

/**
 * The order-lines table shown on the delivery-details page AND the dispatch desk — single source of
 * truth for per-line status so the two surfaces can't drift. The "Motif" column is the per-line
 * exception only (delivered-clean / not-yet-actioned lines stay blank):
 *   • an explicit per-item outcome (refused / damaged / missing) wins;
 *   • else, on a wholly-FAILED delivery, every line carries the delivery-level failure motif
 *     (failureCode badge + full admin label in the tooltip) — never "Partielle";
 *   • else a short line reads MISSING (with a captured reason) or PARTIAL.
 */
type ArticleSegment = { disposition?: string; quantity?: number; reasonCode?: string; reasonLabel?: string };
type ArticleItem = {
  name?: string; sku?: string; quantity?: number; quantityDone?: number;
  reasonLabel?: string; outcome?: string; reason?: string; unitPrice?: number;
  segments?: ArticleSegment[];
};

export function ArticlesTable({ items, status, failureCode, failMotif, currency }: {
  items: ArticleItem[];
  status?: string | null;
  failureCode?: string | null;
  /** Admin failure-reason label, shown in the line tooltip on a failed delivery. */
  failMotif?: string | null;
  currency?: string | null;
}) {
  const t = useT();
  const deliveryFailed = status === 'FAILED';
  const actioned = ['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED'].includes(status ?? '');
  const headers = [
    t.deliveryPage.tableDesignation, t.deliveryPage.tableSku, t.deliveryPage.tableQty,
    t.deliveryPage.tableQtyDone, t.deliveryPage.tableReason, t.deliveryPage.tableUnitPrice, t.deliveryPage.tableTotal,
  ];
  return (
    <TooltipProvider>
      <div className="overflow-x-auto">
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--border)', background: 'transparent' }}>
              {headers.map(h => (
                <th key={h} style={{ padding: '10px 12px', textAlign: 'left', fontSize: 10, fontWeight: 500, color: 'var(--text-muted)', border: 'none' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {items.map((item, i) => {
              const short = item.quantityDone != null && item.quantityDone < (item.quantity ?? 0);
              const reasonLabel = item.reasonLabel as string | undefined;
              const perItemOutcome = item.outcome && item.outcome !== 'DELIVERED' ? item.outcome : null;
              const deliveryFailLine = deliveryFailed && !perItemOutcome;
              const lineStatus = perItemOutcome
                ? perItemOutcome
                : !actioned ? null
                : deliveryFailLine ? (failureCode ?? 'FAILED')
                : short && reasonLabel ? 'MISSING'
                : short ? 'PARTIAL'
                : null;
              // Per-unit breakdown (WMS): show every non-delivered disposition with its qty + motif.
              const segs: ArticleSegment[] | null = Array.isArray(item.segments) ? item.segments : null;
              const shortSegs = segs
                ? segs.filter(sg => sg && sg.disposition && sg.disposition !== 'DELIVERED' && (sg.quantity ?? 0) > 0)
                : null;
              return (
                <tr key={i} style={{ borderBottom: '1px solid var(--border)/30' }} className="hover:bg-[var(--app-bg)]/40 transition-colors">
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    <span className="text-sm font-medium text-[var(--text-primary)]">{item.name ?? '—'}</span>
                  </td>
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    <span className="text-2xs font-mono text-[var(--text-muted)]">{item.sku ?? '—'}</span>
                  </td>
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    <span className="text-sm text-[var(--text-primary)]">{item.quantity ?? '—'}</span>
                  </td>
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    <span className="text-sm font-medium" style={{ color: !actioned ? 'var(--text-muted)' : short ? 'var(--danger)' : 'var(--success)' }}>
                      {actioned ? (item.quantityDone ?? '—') : '—'}
                    </span>
                  </td>
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    {shortSegs && shortSegs.length > 0 ? (
                      <div className="flex flex-col gap-1 items-start">
                        {shortSegs.map((sg, si) => (
                          <span key={si} className="inline-flex items-center gap-1">
                            <ItemOutcomeBadge outcome={sg.disposition} reason={sg.reasonCode} reasonLabel={sg.reasonLabel} reasonInTooltip />
                            <span className="text-2xs font-mono text-[var(--text-muted)]">×{sg.quantity}</span>
                          </span>
                        ))}
                      </div>
                    ) : lineStatus == null ? (
                      <span className="text-xs text-[var(--text-muted)]">—</span>
                    ) : (
                      <ItemOutcomeBadge
                        outcome={lineStatus}
                        reason={deliveryFailLine ? null : item.reason}
                        reasonLabel={deliveryFailLine ? failMotif : reasonLabel}
                        reasonInTooltip />
                    )}
                  </td>
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    <span className="text-xs text-[var(--text-primary)]">{formatMoney(item.unitPrice, currency ?? undefined)}</span>
                  </td>
                  <td style={{ padding: '10px 12px', border: 'none' }}>
                    <span className="text-xs font-semibold text-[var(--text-primary)]">
                      {item.unitPrice != null && item.quantity != null ? formatMoney(item.unitPrice * item.quantity, currency ?? undefined) : '—'}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </TooltipProvider>
  );
}
