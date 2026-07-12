import { useState, useMemo, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { analyticsDateParams } from '@/lib/analytics/date-params';
import { useT } from '@/lib/i18n/LocaleContext';
import {
  IconClockFilled, IconChartAreaFilled, IconUsersGroup,
  IconFileAnalytics, IconColumns, IconX, IconFilter,
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { GlobalFilterDrawer } from '@/components/analytics/GlobalFilterDrawer';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { Skeleton } from '@/components/ui/skeleton';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import type { AnalyticsScope } from '@/types';

// ── Types (mirror DriverScorecardResponse) ──────────────────────────────────
type Range = 'last7d' | 'last30d' | 'qtd' | 'custom';

interface Scorecard {
  driverId: string;
  driverName: string;
  volume: number;
  delivered: number;
  failed: number;
  successRate: number;
  onTimeRate: number;
  avgDelayMinutes: number;
  topFailureMotif: string | null;
  deltaVolumePct: number | null;
  deltaSuccessRatePts: number | null;
  trend: Array<{ date: string; total: number; delivered: number }> | null;
}
interface ScorecardResponse { period: string; compared: boolean; drivers: Scorecard[]; }

// ── Helpers ─────────────────────────────────────────────────────────────────
const fmtMinutes = (m: number | null | undefined): string => {
  if (m == null || isNaN(m) || m === 0) return '0 m';
  if (m < 60) return `${Math.round(m)} m`;
  const h = Math.floor(m / 60);
  const r = Math.round(m % 60);
  return r > 0 ? `${h} h ${r} m` : `${h} h`;
};
type Tone = 'success' | 'warning' | 'danger';
const rateTone = (v: number): Tone => (v >= 90 ? 'success' : v >= 75 ? 'warning' : 'danger');
const TONE_TEXT: Record<Tone, string> = { success: 'var(--success)', warning: 'var(--warning)', danger: 'var(--danger)' };

// ── Mini sparkline (inline SVG, no recharts dependency) ──────────────────────
function MiniSpark({ data, color }: { data: number[]; color: string }) {
  if (!data || data.length < 2) return null;
  const max = Math.max(...data), min = Math.min(...data), rng = (max - min) || 1;
  const pts = data.map((v, i) => `${(i / (data.length - 1)) * 100},${18 - ((v - min) / rng) * 14 - 2}`).join(' ');
  return (
    <svg viewBox="0 0 100 20" preserveAspectRatio="none" width="100%" height="18" aria-hidden="true">
      <polygon points={`0,20 ${pts} 100,20`} fill={color} opacity="0.07" />
      <polyline points={pts} fill="none" stroke={color} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

// ── Delta pill ──────────────────────────────────────────────────────────────
function DeltaPill({ delta, goodWhen = 'up', format }: { delta?: number | null; goodWhen?: 'up' | 'down'; format?: (n: number) => string }) {
  if (delta == null || delta === 0) return null;
  const up = delta > 0;
  const good = goodWhen === 'up' ? up : !up;
  const color = good ? 'var(--success)' : 'var(--danger)';
  const txt = format ? format(Math.abs(delta)) : `${Math.abs(delta).toFixed(1)}%`;
  return (
    <span className="text-xs font-[600] inline-flex items-center gap-0.5" style={{ color }}>
      <span aria-hidden="true">{up ? '▲' : '▼'}</span>{txt}
    </span>
  );
}

// ── Enterprise Fleet KPI card (Stripe pattern) ──────────────────────────────
function FleetKpiCard({ label, value, icon, spark, delta, deltaGood, deltaFormat }: {
  label: string; value: string; icon: React.ReactNode;
  spark?: number[]; delta?: number | null; deltaGood?: 'up' | 'down'; deltaFormat?: (n: number) => string;
}) {
  return (
    <div className="border border-[var(--border)] rounded-lg h-full flex flex-col gap-1.5 ps-12 pe-4 py-3 relative">
      <span className="absolute start-3 top-3 text-[var(--text-soft)]">{icon}</span>
      <span className="text-xs font-medium text-[var(--text-muted)]">{label}</span>
      <div className="font-mono font-semibold tabular-nums leading-none text-[var(--text-primary)] tracking-tight" style={{ fontSize: 'clamp(1.5rem, 4cqi, 2rem)' }}>{value}</div>
      {spark && spark.length > 1 && (
        <div className="h-6 w-full opacity-80"><MiniSpark data={spark} color="var(--brand)" /></div>
      )}
      <div className="mt-auto pt-1">
        <DeltaPill delta={delta} goodWhen={deltaGood} format={deltaFormat} />
      </div>
    </div>
  );
}

// ── Comparison row ──────────────────────────────────────────────────────────
function CmpRow({ label, value, tone }: { label: string; value: string; tone?: Tone }) {
  return (
    <div className="flex items-center justify-between py-1.5 border-t text-xs" style={{ borderColor: 'var(--border)' }}>
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <span className="font-mono font-[600]" style={{ color: tone ? TONE_TEXT[tone] : 'var(--text-primary)' }}>{value}</span>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────
export default function PerformancePage() {
  const t = useT();
  const [range, setRange] = useState<Range>('last30d');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [compare, setCompare] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [generatingPdf, setGeneratingPdf] = useState<Set<string>>(new Set());
  const [scope, setScope] = useState<AnalyticsScope>({});
  const [drawerOpen, setDrawerOpen] = useState(false);
  const activeFilterCount = useMemo(() => {
    const scopeCount = Object.values(scope).reduce((n, v) => n + (Array.isArray(v) ? v.length : 0), 0);
    return scopeCount + (range !== 'last30d' ? 1 : 0);
  }, [scope, range]);

  const dateParams = analyticsDateParams(range, customFrom, customTo);

  const scopeParams = useMemo(() => {
    const p: Record<string, string[]> = {};
    if (scope.zone?.length) p.zone = scope.zone;
    if (scope.driverId?.length) p.driverId = scope.driverId;
    if (scope.status?.length) p.status = scope.status;
    if (scope.motif?.length) p.motif = scope.motif;
    if (scope.city?.length) p.city = scope.city;
    if (scope.source?.length) p.source = scope.source;
    if (scope.depot?.length) p.depot = scope.depot;
    return p;
  }, [scope]);

  const { data, isFetching, refetch } = useQuery({
    queryKey: ['driver-scorecards', range, customFrom, customTo, compare, scopeParams],
    queryFn: async () => {
      const res = await api.get<ScorecardResponse>('/api/admin/reports/drivers', { params: { ...dateParams, ...scopeParams, compare } });
      return res.data;
    },
    staleTime: 30_000,
  });

  const drivers = useMemo(() => data?.drivers ?? [], [data]);

  // Fleet KPIs — volume-weighted from the scorecards.
  const fleet = useMemo(() => {
    const vol = drivers.reduce((s, d) => s + d.volume, 0);
    const del = drivers.reduce((s, d) => s + d.delivered, 0);
    // On-time and avg delay only exist for completed deliveries — weight by `delivered`, not total
    // volume, so drivers with unresolved/unmeasured work don't skew the fleet figure.
    const onTime = del ? drivers.reduce((s, d) => s + d.onTimeRate * d.delivered, 0) / del : 100;
    const delay = del ? drivers.reduce((s, d) => s + d.avgDelayMinutes * d.delivered, 0) / del : 0;
    // Fleet-level spark: on-time trend from driver trends (averaged per day)
    const trendMap = new Map<string, { total: number; delivered: number }>();
    drivers.forEach(d => {
      d.trend?.forEach(t => {
        const prev = trendMap.get(t.date) ?? { total: 0, delivered: 0 };
        trendMap.set(t.date, { total: prev.total + t.total, delivered: prev.delivered + t.delivered });
      });
    });
    const fleetTrend = Array.from(trendMap.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([, v]) => v.total > 0 ? (v.delivered / v.total) * 100 : 0);
    // Fleet-level deltas (weighted average of driver deltas)
    const deltaSuccess = vol
      ? drivers.reduce((s, d) => s + (d.deltaSuccessRatePts ?? 0) * d.volume, 0) / vol
      : null;
    return { success: vol ? (del / vol) * 100 : 0, onTime, delay, fleetTrend, deltaSuccess };
  }, [drivers]);

  // Per-driver spark data (on-time rate trend)
  const driverSparks = useMemo(() => {
    const map = new Map<string, number[]>();
    drivers.forEach(d => {
      if (d.trend && d.trend.length > 1) {
        map.set(d.driverId, d.trend.map(t => t.total > 0 ? (t.delivered / t.total) * 100 : 0));
      }
    });
    return map;
  }, [drivers]);

  const toggleSelect = useCallback((id: string) => {
    setSelected(prev => prev.includes(id) ? prev.filter(x => x !== id) : prev.length >= 3 ? prev : [...prev, id]);
  }, []);
  const compared = useMemo(() => drivers.filter(d => selected.includes(d.driverId)), [drivers, selected]);

  const generatePdf = async (d: Scorecard) => {
    const name = d.driverName || t.performancePage.notAssigned;
    if (!d.driverId) { showErrorToast(null, 'errorMissingDriverId'); return; }
    setGeneratingPdf(prev => new Set(prev).add(d.driverId));
    try {
      // Legacy PDF endpoint takes date-only from/to or a `period` preset. A bare custom (no dates) falls
      // back to a valid preset so the export never 400s.
      const pdfParams = range === 'custom'
        ? (customFrom && customTo ? { from: customFrom, to: customTo } : { period: 'last30d' })
        : { period: range };
      const res = await api.get(`/api/admin/reports/drivers/${d.driverId}/performance/pdf`, { params: pdfParams, responseType: 'blob' });
      const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `performance-${name.replace(/\s+/g, '-').toLowerCase()}-${new Date().toISOString().slice(0, 10)}.pdf`;
      link.click();
      URL.revokeObjectURL(url);
      showSuccessToast(t.performancePage.successReportDownloaded.replace('{name}', name));
    } catch {
      showErrorToast(null, 'errorReportGeneration');
    } finally {
      setGeneratingPdf(prev => { const s = new Set(prev); s.delete(d.driverId); return s; });
    }
  };

  const tl = (k: string, fallback: string) => (t.performancePage as Record<string, string>)[k] ?? fallback;

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col overflow-hidden relative" style={{ background: 'var(--app-bg)' }}>
      {/* ── FLOATING ACTION BAR ── */}
      <div className="fixed top-16 right-4 z-40 flex items-center gap-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg px-1.5 py-1">
        <RefreshButton refreshing={isFetching} onClick={() => refetch()} />
        <button
          type="button"
          onClick={() => setDrawerOpen(true)}
          className="relative w-7 h-7 flex items-center justify-center rounded-md transition-colors cursor-pointer"
          style={{ color: activeFilterCount > 0 ? 'var(--brand)' : 'var(--text-muted)' }}
        >
          <IconFilter size={14} />
          {activeFilterCount > 0 && (
            <span className="absolute -top-0.5 -right-0.5 w-3.5 h-3.5 rounded-full bg-[var(--brand)] text-white text-2xs font-bold flex items-center justify-center">
              {activeFilterCount}
            </span>
          )}
        </button>
      </div>
      <GlobalFilterDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        value={scope}
        onChange={setScope}
        resultCount={drivers.length}
        range={range}
        onRangeChange={setRange}
        defaultRange="last30d"
        rangeOptions={[
          { value: 'last7d', label: t.performancePage.periodWeek },
          { value: 'last30d', label: t.performancePage.periodMonth },
          { value: 'qtd', label: tl('periodQuarter', 'Quarter') },
          { value: 'custom', label: tl('periodCustom', 'Custom') },
        ]}
        customFrom={customFrom}
        customTo={customTo}
        onCustomFromChange={setCustomFrom}
        onCustomToChange={setCustomTo}
        compare={compare}
        onCompareChange={setCompare}
        compareLabel={tl('comparePrev', 'vs previous period')}
      />

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1400px] mx-auto p-4 md:p-6 flex flex-col gap-5">

          {/* ── Fleet KPIs ── */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            {!data ? (
              Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-[104px] rounded-lg" />)
            ) : (
              <>
                <FleetKpiCard
                  label={tl('fleetOnTime', 'Fleet on-time')}
                  value={`${fleet.onTime.toFixed(1)}%`}
                  icon={<IconClockFilled size={16} />}
                />
                <FleetKpiCard
                  label={t.performancePage.completionRate}
                  value={`${fleet.success.toFixed(1)}%`}
                  icon={<IconChartAreaFilled size={16} />}
                  spark={fleet.fleetTrend}
                  delta={fleet.deltaSuccess}
                  deltaGood="up"
                />
                <FleetKpiCard
                  label={t.performancePage.avgDelay}
                  value={fmtMinutes(fleet.delay)}
                  icon={<IconUsersGroup size={16} />}
                />
              </>
            )}
          </div>

          {/* ── Leaderboard ── */}
          <div className="border border-[var(--border)] rounded-lg overflow-hidden" style={{ background: 'var(--surface)' }}>
            <div className="flex items-center justify-between px-4 py-3 border-b" style={{ borderColor: 'var(--border)' }}>
              <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>{t.performancePage.driverPerformanceRanking}</span>
              <span className="text-2xs" style={{ color: 'var(--text-muted)' }}>{tl('selectToCompare', 'Select to compare (max 3)')}</span>
            </div>

            {!data ? (
              <div className="p-4 flex flex-col gap-2">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-10 rounded-md" />)}</div>
            ) : drivers.length === 0 ? (
              <div className="py-16 text-center text-xs font-[600]" style={{ color: 'var(--text-muted)' }}>{tl('noDrivers', 'No drivers for the period')}</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-xs" style={{ minWidth: 680 }}>
                  <thead>
                    <tr style={{ color: 'var(--text-muted)' }}>
                      <th className="font-[600] text-left px-3 py-2 w-8"></th>
                      <th className="font-[600] text-left px-2 py-2 w-8">#</th>
                      <th className="font-[600] text-left px-2 py-2">{t.performancePage.actor}</th>
                      <th className="font-[600] text-center px-2 py-2">{t.performancePage.volume}</th>
                      <th className="font-[600] text-center px-2 py-2">{t.performancePage.success}</th>
                      <th className="font-[600] text-center px-2 py-2">{tl('onTime', 'On-time')}</th>
                      <th className="font-[600] text-center px-2 py-2">{t.performancePage.delay}</th>
                      <th className="font-[600] text-left px-2 py-2">{tl('topMotif', 'Top reason')}</th>
                      <th className="font-[600] text-center px-2 py-2">{tl('trend', 'Trend')}</th>
                      <th className="font-[600] text-right px-3 py-2">PDF</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drivers.map((d, idx) => {
                      const tone = rateTone(d.successRate);
                      const isSel = selected.includes(d.driverId);
                      const spark = driverSparks.get(d.driverId);
                      return (
                        <tr
                          key={d.driverId}
                          className="border-t transition-colors"
                          style={{
                            borderColor: 'var(--border)',
                            background: isSel ? 'var(--surface-sunken)' : undefined,
                          }}
                          onMouseEnter={e => { if (!isSel) e.currentTarget.style.background = 'var(--hover-bg)'; }}
                          onMouseLeave={e => { if (!isSel) e.currentTarget.style.background = ''; }}
                        >
                          <td className="px-3 py-2">
                            <input type="checkbox" checked={isSel} onChange={() => toggleSelect(d.driverId)} aria-label={d.driverName} className="accent-[var(--brand)] cursor-pointer" />
                          </td>
                          <td className="px-2 py-2 font-mono" style={{ color: 'var(--text-muted)' }}>{idx + 1}</td>
                          <td className="px-2 py-2">
                            <span className="inline-flex items-center gap-2">
                              <DriverAvatarById driverId={d.driverId} name={d.driverName} size={26} />
                              <span className="font-[600]" style={{ color: 'var(--text-primary)' }}>{d.driverName || t.performancePage.notAssigned}</span>
                            </span>
                          </td>
                          <td className="px-2 py-2 text-center font-mono font-semibold" style={{ color: 'var(--text-primary)' }}>
                            {d.volume}
                            {compare && d.deltaVolumePct != null && (
                              <span className="ms-1 text-2xs font-[600]" style={{ color: d.deltaVolumePct >= 0 ? 'var(--success)' : 'var(--danger)' }}>
                                {d.deltaVolumePct >= 0 ? '+' : ''}{d.deltaVolumePct.toFixed(0)}%
                              </span>
                            )}
                          </td>
                          <td className="px-2 py-2 text-center">
                            <div className="inline-flex flex-col items-center gap-0.5">
                              <span className="inline-flex items-center gap-1">
                                <span className="font-mono font-[600] text-xs" style={{ color: 'var(--text-primary)' }}>{d.successRate.toFixed(0)}%</span>
                                {compare && d.deltaSuccessRatePts != null && d.deltaSuccessRatePts !== 0 && (
                                  <span className="text-2xs font-[600]" style={{ color: d.deltaSuccessRatePts >= 0 ? 'var(--success)' : 'var(--danger)' }}>
                                    {d.deltaSuccessRatePts >= 0 ? '+' : ''}{d.deltaSuccessRatePts.toFixed(0)}
                                  </span>
                                )}
                              </span>
                              <div className="w-10 h-1 bg-[var(--hover-bg)] rounded-full overflow-hidden">
                                <div className="h-full rounded-full" style={{ width: `${Math.min(100, d.successRate)}%`, background: TONE_TEXT[tone] }} />
                              </div>
                            </div>
                          </td>
                          <td className="px-2 py-2 text-center font-mono" style={{ color: 'var(--text-secondary)' }}>{d.onTimeRate.toFixed(0)}%</td>
                          <td className="px-2 py-2 text-center font-mono" style={{ color: 'var(--text-muted)' }}>{fmtMinutes(d.avgDelayMinutes)}</td>
                          <td className="px-2 py-2" style={{ color: 'var(--text-muted)' }}>{d.topFailureMotif ? (t.failureCodes?.[d.topFailureMotif] ?? d.topFailureMotif) : '—'}</td>
                          <td className="px-2 py-2">
                            {spark && spark.length > 1 ? (
                              <div className="w-16 h-5 mx-auto opacity-70"><MiniSpark data={spark} color={TONE_TEXT[tone]} /></div>
                            ) : (
                              <span style={{ color: 'var(--text-soft)' }}>—</span>
                            )}
                          </td>
                          <td className="px-3 py-2 text-right">
                            <button type="button" onClick={() => generatePdf(d)} disabled={generatingPdf.has(d.driverId)}
                              title={t.performancePage.downloadPdfTooltip}
                              className="inline-flex items-center justify-center w-7 h-7 rounded-md border transition-colors hover:bg-[var(--hover-bg)] disabled:opacity-50"
                              style={{ borderColor: 'var(--border)', color: 'var(--text-muted)' }}>
                              <IconFileAnalytics size={14} />
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* ── Side-by-side comparison ── */}
          {compared.length >= 2 && (
            <div className="border border-[var(--border)] rounded-lg overflow-hidden" style={{ background: 'var(--surface)' }}>
              <div className="flex items-center gap-2 px-4 py-3 border-b" style={{ borderColor: 'var(--border)' }}>
                <IconColumns size={14} style={{ color: 'var(--brand)' }} />
                <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>{tl('comparison', 'Side-by-side comparison')}</span>
                <button type="button" onClick={() => setSelected([])} className="ml-auto text-2xs inline-flex items-center gap-1 hover:opacity-70" style={{ color: 'var(--text-muted)' }}>
                  <IconX size={12} /> {tl('clear', 'Clear')}
                </button>
              </div>
              <div className="grid" style={{ gridTemplateColumns: `repeat(${compared.length}, minmax(0,1fr))` }}>
                {compared.map((d, i) => (
                  <div key={d.driverId} className="p-3" style={{ borderRight: i < compared.length - 1 ? '1px solid var(--border)' : undefined }}>
                    <div className="flex items-center gap-2 mb-2">
                      <DriverAvatarById driverId={d.driverId} name={d.driverName} size={24} />
                      <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>{d.driverName}</span>
                    </div>
                    <CmpRow label={t.performancePage.volume} value={String(d.volume)} />
                    <CmpRow label={tl('onTime', 'On-time')} value={`${d.onTimeRate.toFixed(0)}%`} tone={rateTone(d.onTimeRate)} />
                    <CmpRow label={t.performancePage.success} value={`${d.successRate.toFixed(0)}%`} tone={rateTone(d.successRate)} />
                    <CmpRow label={t.performancePage.avgDelay} value={fmtMinutes(d.avgDelayMinutes)} />
                    <CmpRow label={tl('topMotif', 'Top reason')} value={d.topFailureMotif ? (t.failureCodes?.[d.topFailureMotif] ?? d.topFailureMotif) : '—'} />
                  </div>
                ))}
              </div>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
