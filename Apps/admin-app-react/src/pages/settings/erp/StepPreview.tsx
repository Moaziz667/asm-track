import { useEffect, useState } from 'react';
import { IconArrowRight, IconRefresh, IconAlertTriangle } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import {
  getOrderPreview, getPendingOrders, PREVIEW_FIELD_MAP,
  type FieldMapping, type OrderPreview, type PendingOrderSummary,
} from '@/lib/api/erpIntegration';

/**
 * What the mapping actually produces, on one of the customer's real orders.
 *
 * This is the honest version of a "dry run": it only reads. Calling a read-only check
 * "SAFE TO EXECUTE" would promise something no read can establish — the same over-claim that let two
 * capabilities ship unresolvable. What it does prove is the part an integrator actually gets wrong:
 * pointing a field at the wrong place. A blank where a name should be is obvious here and invisible
 * three weeks later in a failed delivery.
 */
export function StepPreview({ mappings, copy }: { mappings: FieldMapping[]; copy: Record<string, string> }) {
  const [orders, setOrders] = useState<PendingOrderSummary[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [preview, setPreview] = useState<OrderPreview | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoadingList(true);
    getPendingOrders(40, true)
      .then((list) => {
        if (!alive) return;
        setOrders(list);
        if (list.length > 0) setSelected((s) => s || list[0].erpOrderId);
      })
      .catch(() => alive && setError(copy.listError))
      .finally(() => alive && setLoadingList(false));
    return () => { alive = false; };
  }, [copy.listError]);

  const load = (id: string) => {
    if (!id) return;
    setLoadingPreview(true);
    setError(null);
    getOrderPreview(id)
      .then(setPreview)
      .catch(() => setError(copy.previewError))
      .finally(() => setLoadingPreview(false));
  };

  useEffect(() => { if (selected) load(selected); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [selected]);

  const mappedBy = new Map(mappings.filter((m) => m.canonicalField).map((m) => [m.canonicalField!, m.sourcePath]));

  if (loadingList) return <Skeleton className="h-[420px] w-full rounded-lg" />;

  if (orders.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-[var(--border)] p-8 text-center">
        <p className="text-sm text-[var(--text-secondary)]">{copy.noOrders}</p>
        <p className="mt-1 text-xs text-[var(--text-muted)]">{copy.noOrdersHint}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 flex-1 min-w-[220px]">
          <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
            {copy.pickOrder}
          </span>
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className={cn(
              'h-8 rounded-lg px-2 text-xs font-mono',
              'bg-[var(--surface)] border border-[var(--border-strong)] text-[var(--text-primary)]',
              'hover:border-[var(--brand)] focus:border-[var(--brand)] transition-colors duration-150',
              'focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] focus-visible:ring-offset-1',
            )}
          >
            {orders.map((o) => (
              <option key={o.erpOrderId} value={o.erpOrderId}>
                {o.erpOrderId}{o.customerName ? ` — ${o.customerName}` : ''}
              </option>
            ))}
          </select>
        </label>
        <Button size="sm" variant="outline" onClick={() => load(selected)} disabled={loadingPreview}>
          <IconRefresh size={15} className={loadingPreview ? 'animate-spin' : undefined} />
          {copy.reload}
        </Button>
      </div>

      {error && (
        <div
          className="flex items-start gap-2 rounded-lg p-3 text-xs"
          style={{ background: 'color-mix(in srgb, var(--danger) 8%, transparent)', color: 'var(--danger)' }}
        >
          <IconAlertTriangle size={15} className="shrink-0 mt-px" /> {error}
        </div>
      )}

      {loadingPreview && !preview ? (
        <Skeleton className="h-[360px] w-full rounded-lg" />
      ) : preview ? (
        <>
          <section className="rounded-lg border border-[var(--border)] overflow-hidden">
            <header className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] gap-2 px-4 py-2 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
              <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                {copy.colSource}
              </span>
              <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                {copy.colResult}
              </span>
            </header>
            <ul className="divide-y divide-[var(--border)]">
              {PREVIEW_FIELD_MAP.map(({ canonical, key }) => {
                const raw = preview[key];
                const source = mappedBy.get(canonical);
                const empty = raw === null || raw === undefined || raw === '';
                return (
                  <li
                    key={canonical}
                    className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] items-baseline gap-2 px-4 py-2"
                  >
                    <div className="min-w-0">
                      <p className="font-mono text-2xs text-[var(--text-muted)] truncate">
                        {source ?? copy.defaultSource}
                      </p>
                      <p className="text-2xs text-[var(--text-soft)] truncate">{canonical}</p>
                    </div>
                    <div className="flex items-baseline gap-1.5 min-w-0">
                      <IconArrowRight size={12} className="shrink-0 text-[var(--text-soft)] self-center" />
                      {empty ? (
                        <span className="text-xs italic text-[var(--warning)]">{copy.emptyValue}</span>
                      ) : (
                        <span className="text-base text-[var(--text-primary)] truncate">{String(raw)}</span>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          </section>

          {preview.customFields && Object.keys(preview.customFields).length > 0 && (
            <section className="rounded-lg border border-[var(--border)] overflow-hidden">
              <header className="px-4 py-2 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
                <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                  {copy.extrasPreview}
                </span>
              </header>
              <ul className="divide-y divide-[var(--border)]">
                {Object.entries(preview.customFields).map(([k, v]) => (
                  <li key={k} className="flex items-baseline gap-2 px-4 py-2">
                    <span className="text-base text-[var(--text-secondary)] min-w-[160px] truncate">{k}</span>
                    <span className="text-base text-[var(--text-primary)] truncate">{String(v)}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {preview.items && preview.items.length > 0 && (
            <section className="rounded-lg border border-[var(--border)] overflow-hidden">
              <header className="px-4 py-2 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
                <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                  {copy.linesPreview}
                </span>
              </header>
              <ul className="divide-y divide-[var(--border)]">
                {preview.items.map((it, i) => (
                  <li key={i} className="flex items-baseline gap-3 px-4 py-2">
                    <span className="font-mono text-2xs text-[var(--text-muted)] min-w-[96px]">{it.sku ?? '—'}</span>
                    <span className="text-base text-[var(--text-primary)] flex-1 min-w-0 truncate">{it.name ?? '—'}</span>
                    <span className="font-mono text-xs text-[var(--text-secondary)] tabular-nums">{it.quantity ?? 0}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      ) : null}
    </div>
  );
}
