import { IconChartAreaFilled, IconTrendingUp, IconTrendingDown } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { useZoneComparison } from './useZoneComparison';
import type { Range } from './useDashboardData';

type Props = {
  range: Range;
  from?: string;
  to?: string;
};

function Delta({ delta }: { delta: number | null }) {
  if (delta == null) return <span className="text-2xs font-mono text-[var(--text-muted)]">—</span>;
  const up = delta >= 0;
  return (
    <span className={`inline-flex items-center gap-0.5 text-2xs font-mono tabular-nums ${up ? 'text-emerald-500' : 'text-red-400'}`}>
      {up ? <IconTrendingUp size={10} strokeWidth={2.5} /> : <IconTrendingDown size={10} strokeWidth={2.5} />}
      {up ? '+' : ''}{delta.toFixed(1)}%
    </span>
  );
}

export default function ZoneDemandCards({ range, from, to }: Props) {
  const t = useT();
  const { zones, isLoading } = useZoneComparison(range, from, to);
  const maxOrders = zones[0]?.orders ?? 1;

  return (
    <div className="border border-[var(--border)] rounded-lg overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconChartAreaFilled size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">
          {t.performancePage.densityByZone || 'Demande par zone'}
        </span>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto p-4 flex flex-col gap-4">
        {isLoading && (
          <div className="flex-1 flex items-center justify-center">
            <span className="text-2xs text-[var(--text-muted)]">Chargement...</span>
          </div>
        )}

        {!isLoading && zones.length === 0 && (
          <div className="flex-1 flex items-center justify-center">
            <span className="text-2xs text-[var(--text-muted)]">Aucune donnée</span>
          </div>
        )}

        {zones.map(z => (
          <div key={z.zoneId} className="flex flex-col gap-1">
            <div className="flex items-baseline justify-between">
              <span className="text-sm font-medium text-[var(--text-primary)]">{z.zoneName}</span>
              <div className="flex items-baseline gap-2">
                <span className="text-base font-bold font-mono tabular-nums text-[var(--text-primary)]">
                  {z.orders.toLocaleString()}
                </span>
                <Delta delta={z.delta} />
              </div>
            </div>
            <div className="h-1.5 rounded-[2px] bg-[var(--border)] overflow-hidden">
              <div
                className="h-full rounded-[2px] transition-all"
                style={{ width: `${(z.orders / maxOrders) * 100}%`, background: z.zoneColor }}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
