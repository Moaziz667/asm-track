import { IconTrendingUp, IconTrendingDown } from '@tabler/icons-react';
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

const onTimeColor = (pct: number) => (pct >= 95 ? 'var(--success)' : pct >= 85 ? 'var(--warning)' : 'var(--danger)');

export default function ZoneDemandCards({ range, from, to }: Props) {
  const t = useT();
  const { zones, isLoading } = useZoneComparison(range, from, to);

  return (
    <div className="border border-[var(--border)] rounded-lg overflow-hidden flex flex-col h-full">
      <div className="px-5 py-3 flex items-center justify-between gap-2 border-b border-[var(--border)] shrink-0">
        <span className="text-xs font-semibold text-[var(--text-primary)]">
          {t.dashboardPage.zoneOverviewTitle || t.performancePage.densityByZone || 'Vue par zone'}
        </span>
      </div>

      {/* Column header */}
      <div className="grid grid-cols-[1fr_auto_auto_auto] gap-x-4 px-5 py-2 border-b border-[var(--border)] shrink-0 text-3xs font-semibold text-[var(--text-soft)]">
        <span>{t.dashboardPage.zoneColZone || 'Zone'}</span>
        <span className="text-end tabular-nums w-14">{t.dashboardPage.zoneColOnTime || 'On-time'}</span>
        <span className="text-end tabular-nums w-12">{t.dashboardPage.zoneColOrders || 'Cmd.'}</span>
        <span className="text-end tabular-nums w-12">{t.dashboardPage.zoneColDelayed || 'Retard'}</span>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto">
        {isLoading && (
          <div className="flex items-center justify-center py-8">
            <span className="text-2xs text-[var(--text-muted)]">Chargement...</span>
          </div>
        )}

        {!isLoading && zones.length === 0 && (
          <div className="flex items-center justify-center py-8">
            <span className="text-2xs text-[var(--text-muted)]">{t.dashboardPage.noData || 'Aucune donnée'}</span>
          </div>
        )}

        {zones.map(z => (
          <div key={z.zoneId} className="grid grid-cols-[1fr_auto_auto_auto] gap-x-4 items-center px-5 py-2 border-b border-[var(--border)] last:border-0 hover:bg-[var(--hover-bg)] transition-colors">
            <div className="flex items-center gap-2 min-w-0">
              <span className="w-2 h-2 rounded-sm shrink-0" style={{ background: z.zoneColor }} />
              <span className="text-xs font-medium text-[var(--text-primary)] truncate" title={z.zoneName}>{z.zoneName}</span>
            </div>
            <span className="text-end w-14 text-xs font-mono font-semibold tabular-nums" style={{ color: onTimeColor(z.onTimePct) }}>
              {z.onTimePct.toFixed(1)}%
            </span>
            <div className="text-end w-12 flex flex-col items-end leading-none gap-0.5">
              <span className="text-xs font-mono tabular-nums text-[var(--text-primary)]">{z.orders.toLocaleString()}</span>
              <Delta delta={z.delta} />
            </div>
            <span className="text-end w-12 text-xs font-mono tabular-nums" style={{ color: z.delayed > 0 ? 'var(--danger)' : 'var(--text-muted)' }}>
              {z.delayed}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
