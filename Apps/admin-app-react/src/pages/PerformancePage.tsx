
import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip,
  ResponsiveContainer, Cell
} from 'recharts';
import { api } from '@/lib/api';
import { useT } from '@/lib/LocaleContext';
import {
  IconChartBar, IconClock, IconTrendingUp, IconUsers,
  IconBolt, IconActivity, IconFileAnalytics, IconCheck
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import { cn } from '@/lib/utils';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { KpiCard } from './DashboardPage';

// ── Types ──────────────────────────────────────────────────────────────────

interface DashboardKpi {
  avgDelayMinutes: number;
  slaComplianceRate: number;
  totalCompleted: number;
  totalOrdersToday: number;
  ordersByZone: Record<string, number>;
  weeklyTrend: Array<{ date: string; count: number }>;
  totalReassigned: number;
  totalReplanned: number;
}

interface TodayStats {
  total: number;
  delivered: number;
  failed: number;
  inTransit: number;
  waiting: number;
  assigned: number;
  successRate: number;
  avgAssignToPickupMinutes: number;
  avgPickupToTransitMinutes: number;
  avgTransitToCompletionMinutes: number;
}

interface AdminStats {
  today: TodayStats;
  byDriver: Array<{
    driverId?: string;
    driverName: string;
    total: number;
    delivered: number;
    failed: number;
    successRate: number;
    avgDelayMinutes: number;
  }>;
  byFailureCode: Array<{ code: string; count: number }>;
  byClient: Array<{ clientName: string; total: number; delivered: number; failed: number; successRate: number }>;
}

type Period = 'day' | 'week' | 'month' | 'all';

// ── Helpers ─────────────────────────────────────────────────────────────────

const normRate = (v: number | undefined | null): number => {
  if (v == null || isNaN(v)) return 0;
  const n = v > 1 ? v : v * 100;
  return Math.min(n, 100);
};

const fmtMinutes = (m: number | undefined | null): string => {
  if (m == null || isNaN(m) || m === 0) return '0 m';
  if (m < 60) return `${Math.round(m)}m`;
  const h = Math.floor(m / 60);
  const r = Math.round(m % 60);
  return r > 0 ? `${h}h${r}m` : `${h}h`;
};

// ── Custom UI Components (Linear Style) ─────────────────────────────────────

function Spinner({ size = 18 }: { size?: number }) {
  return (
    <svg className="animate-spin text-[var(--brand)]" width={size} height={size} viewBox="0 0 24 24" fill="none">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
    </svg>
  );
}

function ProgressBar({ value, color = 'var(--brand)' }: { value: number; color?: string }) {
  return (
    <div className="h-[3px] w-full bg-[var(--hover-bg)] overflow-hidden rounded-full">
      <div
        className="h-full transition-all duration-300 rounded-full"
        style={{
          width: `${Math.min(Math.max(value, 0), 100)}%`,
          backgroundColor: color
        }}
      />
    </div>
  );
}

// ── Main Component ─────────────────────────────────────────────────────────

export default function PerformancePage() {
  const t = useT();
  const [period, setPeriod] = useState<Period>('day');
  const [kpi, setKpi] = useState<DashboardKpi | null>(null);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [generatingPdf, setGeneratingPdf] = useState<Set<string>>(new Set());
  const abortRef = useRef<AbortController | null>(null);

  const loadData = useCallback(async () => {
    abortRef.current?.abort();
    abortRef.current = new AbortController();
    const signal = abortRef.current.signal;
    setLoading(true);
    try {
      const [r1, r2] = await Promise.all([
        api.get<DashboardKpi>(`/api/admin/reports/dashboard?period=${period}`, { signal }),
        api.get<AdminStats>(`/api/admin/reports/kpi?period=${period}`, { signal }),
      ]);
      if (!signal.aborted) {
        setKpi(r1.data);
        setStats(r2.data);
        setLastUpdated(new Date());
      }
    } catch (e: any) {
      if (e?.name === 'AbortError' || e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
      console.error(e);
    } finally {
      if (!signal.aborted) setLoading(false);
    }
  }, [period]);

  useEffect(() => {
    loadData();
    const timer = setInterval(loadData, 60_000);
    return () => { clearInterval(timer); abortRef.current?.abort(); };
  }, [loadData]);

  const generateDriverReport = async (driver: { driverName?: string; driverId?: string }) => {
    const name = driver.driverName || t.performancePage.notAssigned;
    if (!driver.driverId) { showErrorToast(null, 'errorMissingDriverId'); return; }
    setGeneratingPdf(prev => new Set(prev).add(name));
    try {
      const res = await api.get(`/api/admin/reports/drivers/${driver.driverId}/performance/pdf`, {
        params: { period },
        responseType: 'blob',
      });
      const url  = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href  = url;
      link.download = `performance-${name.replace(/\s+/g, '-').toLowerCase()}-${new Date().toISOString().slice(0, 10)}.pdf`;
      link.click();
      URL.revokeObjectURL(url);
      showSuccessToast(t.performancePage.successReportDownloaded.replace('{name}', name));
    } catch {
      showErrorToast(null, 'errorReportGeneration');
    } finally {
      setGeneratingPdf(prev => { const s = new Set(prev); s.delete(name); return s; });
    }
  };

  const trendData = useMemo(() => kpi?.weeklyTrend ?? [], [kpi]);
  const zoneEntries = useMemo(() => Object.entries(kpi?.ordersByZone ?? {}).sort((a,b) => b[1] - a[1]).slice(0, 8), [kpi]);
  const drivers = useMemo(() => [...(stats?.byDriver ?? [])].sort((a,b) => b.total - a.total).slice(0, 15), [stats]);

  const totalCycleMinutes = useMemo(() => {
    if (!stats?.today) return 0;
    const today = stats.today;
    return (today.avgAssignToPickupMinutes || 0) + (today.avgPickupToTransitMinutes || 0) + (today.avgTransitToCompletionMinutes || 0);
  }, [stats]);

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col overflow-hidden" style={{ background: 'var(--app-bg)' }}>
      {/* ── Period bar (replaces title bar) ── */}
      <div className="flex items-center gap-3 px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
        <span className="text-[11px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.performancePage.periodLabel}</span>
        <div className="flex items-center gap-1">
          {(['day', 'week', 'month', 'all'] as Period[]).map(p => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={cn(
                'px-2.5 py-1 text-[11px] font-[500] transition-colors rounded-md cursor-pointer',
                period === p
                  ? 'bg-[var(--hover-bg)] text-[var(--text-primary)] font-[600]'
                  : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)]/50'
              )}
            >
              {{ day: t.performancePage.periodDay, week: t.performancePage.periodWeek, month: t.performancePage.periodMonth, all: t.performancePage.periodAll }[p]}
            </button>
          ))}
        </div>
        {lastUpdated && (
          <span className="text-[10px] font-mono ml-auto" style={{ color: 'var(--text-muted)' }}>
            {t.performancePage.lastUpdated} {lastUpdated.toLocaleTimeString()}
          </span>
        )}
        <RefreshButton refreshing={loading} onClick={loadData} />
      </div>

      {/* ── Scrollable Body Content ── */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1400px] mx-auto p-4 md:p-8">
        <DraggableWidgetGrid
          storageKey="performance"
          items={[
            {
              id: 'kpi-operational-volume',
              defaultLayout: { w: 3, h: 2, x: 0, y: 0, minW: 2, minH: 2 },
              className: 'h-full flex',
              children: (
            <div className="flex-1 w-full flex flex-col h-full bg-white border border-[#eaeded] rounded-lg pl-10 pr-4 py-4 hover:border-[#0972d3]/30 transition-colors">
              <div className="flex items-start justify-between mb-3 shrink-0">
                <span className="text-[12px] font-medium text-[#545b64]">{t.performancePage.operationalVolume}</span>
                <IconBolt size={16} strokeWidth={1.5} className="text-[#545b64]" />
              </div>
              <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[#16191f]">{stats?.today?.total?.toString() ?? "0"}</div>
              <div className="text-[11px] text-[#545b64] mt-1.5 font-normal">{`${stats?.today?.delivered ?? 0} ${t.performancePage.successSlash} / ${stats?.today?.failed ?? 0} ${t.performancePage.failureSlash}`}</div>
            </div>
              )
            },
            {
              id: 'kpi-completion-rate',
              defaultLayout: { w: 3, h: 2, x: 3, y: 0, minW: 2, minH: 2 },
              className: 'h-full flex',
              children: (
            <div className="flex-1 w-full flex flex-col h-full bg-white border border-[#eaeded] rounded-lg pl-10 pr-4 py-4 hover:border-[#0972d3]/30 transition-colors">
              <div className="flex items-start justify-between mb-3 shrink-0">
                <span className="text-[12px] font-medium text-[#545b64]">{t.performancePage.completionRate}</span>
                <IconTrendingUp size={16} strokeWidth={1.5} className="text-[#545b64]" />
              </div>
              <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[#16191f]">{`${normRate(stats?.today?.successRate).toFixed(1)}%`}</div>
              <div className="text-[11px] text-[#545b64] mt-1.5 font-normal">{t.performancePage.deliveryPerformance}</div>
            </div>
              )
            },
            {
              id: 'kpi-avg-delay',
              defaultLayout: { w: 3, h: 2, x: 6, y: 0, minW: 2, minH: 2 },
              className: 'h-full flex',
              children: (
            <div className="flex-1 w-full flex flex-col h-full bg-white border border-[#eaeded] rounded-lg pl-10 pr-4 py-4 hover:border-[#0972d3]/30 transition-colors">
              <div className="flex items-start justify-between mb-3 shrink-0">
                <span className="text-[12px] font-medium text-[#545b64]">{t.performancePage.avgDelay}</span>
                <IconClock size={16} strokeWidth={1.5} className="text-[#545b64]" />
              </div>
              <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[#16191f]">{fmtMinutes(kpi?.avgDelayMinutes)}</div>
              <div className="text-[11px] text-[#545b64] mt-1.5 font-normal">{t.performancePage.basedOnTarget}</div>
            </div>
              )
            },
            {
              id: 'kpi-life-cycle',
              defaultLayout: { w: 3, h: 2, x: 9, y: 0, minW: 2, minH: 2 },
              className: 'h-full flex',
              children: (
            <div className="flex-1 w-full flex flex-col h-full bg-white border border-[#eaeded] rounded-lg pl-10 pr-4 py-4 hover:border-[#0972d3]/30 transition-colors">
              <div className="flex items-start justify-between mb-3 shrink-0">
                <span className="text-[12px] font-medium text-[#545b64]">{t.performancePage.lifeCycle}</span>
                <IconActivity size={16} strokeWidth={1.5} className="text-[#545b64]" />
              </div>
              <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[#16191f]">{fmtMinutes(totalCycleMinutes)}</div>
              <div className="text-[11px] text-[#545b64] mt-1.5 font-normal">{t.performancePage.assignmentToDestination}</div>
            </div>
              )
            },
            {
              id: 'trend-chart',
              defaultLayout: { w: 6, h: 7, x: 0, y: 2, minW: 4, minH: 5 },
              className: '',
              children: (
             <div className="rounded-lg overflow-hidden bg-white border border-[#eaeded] flex flex-col h-full">
               <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[#eaeded]">
                 <div className="flex items-center gap-2">
                   <IconChartBar size={16} style={{ color: 'var(--brand)' }} />
                   <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
                     {t.performancePage.volumeCurve}
                   </span>
                 </div>
                 <span className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
                   {t.performancePage.lastSevenDays}
                 </span>
               </div>
               
               <div className="p-6 h-[300px]">
                  <ResponsiveContainer width="100%" height="100%">
                     <BarChart data={trendData}>
                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />
                        <XAxis
                          dataKey="date"
                          tickFormatter={(v) => v.split('-').slice(1).reverse().join('/')}
                          tick={{ fontSize: 10, fontWeight: 500, fill: 'var(--text-secondary)' }}
                          axisLine={false}
                          tickLine={false}
                        />
                        <YAxis
                          tick={{ fontSize: 10, fontWeight: 500, fill: 'var(--text-secondary)' }}
                          axisLine={false}
                          tickLine={false}
                        />
                        <RechartsTooltip
                          cursor={{ fill: 'var(--hover-bg)' }}
                          contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '2px', padding: '8px 12px', color: 'var(--text-primary)', fontSize: 11 }}
                          labelStyle={{ color: 'var(--text-secondary)', fontSize: '10px', fontWeight: 500, marginBottom: '4px' }}
                          itemStyle={{ color: 'var(--text-primary)', fontSize: '12px', fontWeight: 600, fontFamily: 'monospace' }}
                          formatter={(value) => `${value}`}
                          labelFormatter={() => t.performancePage.volume}
                        />
                        <Bar dataKey="count" fill="var(--brand)" radius={[1, 1, 0, 0]} barSize={24}>
                           {trendData.map((_, i) => (
                             <Cell key={i} fill={i === trendData.length - 1 ? 'var(--brand)' : 'var(--text-secondary)'} />
                           ))}
                        </Bar>
                     </BarChart>
                  </ResponsiveContainer>
               </div>
             </div>
              ),
            },
            {
              id: 'temporal-fragmentation',
              defaultLayout: { w: 6, h: 7, x: 6, y: 2, minW: 4, minH: 5 },
              className: '',
              children: (
             <div className="rounded-lg overflow-hidden flex flex-col justify-between bg-white border border-[#eaeded] h-full">
               <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[#eaeded]">
                 <div className="flex items-center gap-2">
                   <IconActivity size={16} style={{ color: 'var(--brand)' }} />
                   <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
                     {t.performancePage.temporalFragmentation}
                   </span>
                 </div>
                 <span className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
                   {t.performancePage.efficiencyByPhase}
                 </span>
               </div>

               <div className="p-6 flex flex-col gap-4 flex-1 justify-center">
                  {[
                    { label: t.performancePage.driverResponse, sub: t.performancePage.assignmentToPickup, m: stats?.today?.avgAssignToPickupMinutes, color: "var(--brand)" },
                    { label: t.performancePage.depotLoading, sub: t.performancePage.pickupToTransit, m: stats?.today?.avgPickupToTransitMinutes, color: "var(--text-soft)" },
                    { label: t.performancePage.effectiveTransit, sub: t.performancePage.transitToCompletion, m: stats?.today?.avgTransitToCompletionMinutes, color: "var(--text-muted)" }
                  ].map((phase, i) => (
                    <div key={i} className="flex flex-col gap-1.5">
                       <div className="flex items-center justify-between leading-none">
                          <div className="flex flex-col gap-0.5">
                             <span className="text-[11px] font-[500] text-[var(--text-primary)]">{phase.label}</span>
                             <span className="text-[10px] text-[var(--text-muted)]">{phase.sub}</span>
                          </div>
                          <span className="text-[13px] font-[500] font-mono text-[var(--text-primary)]">{fmtMinutes(phase.m)}</span>
                       </div>
                       <ProgressBar value={totalCycleMinutes > 0 ? ((phase.m || 0) / totalCycleMinutes) * 100 : 0} color={phase.color} />
                    </div>
                  ))}
                  <div className="mt-1 p-2.5 bg-[var(--hover-bg)] border border-[var(--border)] rounded-md flex justify-between items-center leading-none">
                     <span className="text-[11px] font-[600] text-[var(--text-secondary)]">{t.performancePage.totalCycleIndex}</span>
                     <span className="text-[14px] font-[600] font-mono text-[var(--brand)]">{fmtMinutes(totalCycleMinutes)}</span>
                  </div>
               </div>
             </div>
              ),
            },
            {
              id: 'distribution-zone',
              defaultLayout: { w: 4, h: 8, x: 0, y: 9, minW: 3, minH: 5 },
              className: '',
              children: (
             <div className="rounded-lg overflow-hidden bg-white border border-[#eaeded] flex flex-col h-full">
               <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[#eaeded]">
                 <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
                   {t.performancePage.densityByZone}
                 </span>
               </div>
               
               <div className="p-5 flex flex-col gap-3.5">
                   {zoneEntries.map(([zone, count], i) => (
                     <div key={zone} className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between">
                           <div className="flex items-center gap-2">
                              <span className="bg-[var(--hover-bg)] border border-[var(--border)] text-[var(--text-secondary)] w-4 h-4 flex items-center justify-center rounded-md font-mono text-[10px] font-[500]">{i+1}</span>
                              <span className="text-[11px] font-[500] text-[var(--text-secondary)] truncate max-w-[120px]">{zone}</span>
                           </div>
                           <span className="text-[11px] font-[500] font-mono text-[var(--text-primary)]">{count}</span>
                        </div>
                        <ProgressBar value={(count / (stats?.today?.total || 1)) * 100} color="var(--brand)" />
                     </div>
                   ))}
               </div>
             </div>
              ),
            },
            {
              id: 'top-drivers',
              defaultLayout: { w: 8, h: 8, x: 4, y: 9, minW: 5, minH: 5 },
              className: '',
              children: (
             <div className="rounded-lg overflow-hidden lg:col-span-2 flex flex-col bg-white border border-[#eaeded] h-full">
               <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[#eaeded]">
                 <div className="flex items-center gap-2">
                   <IconUsers size={16} style={{ color: 'var(--brand)' }} />
                   <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
                     {t.performancePage.driverPerformanceRanking}
                   </span>
                 </div>
               </div>

               <div className="overflow-x-auto">
                 <div className="min-w-[600px] lg:min-w-0">
                   {/* Table Columns Header */}
                   <div
                     className="grid grid-cols-[2fr_1fr_1fr_1fr_80px] px-4 py-3"
                     style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
                   >
                     {[t.performancePage.actor, t.performancePage.volume, t.performancePage.success, t.performancePage.delay, ''].map((h, i) => (
                       <div
                         key={i}
                         className={cn(
                           'text-[11px] font-[600]',
                           i === 1 || i === 2 || i === 3 ? 'text-center' : '',
                           i === 4 ? 'text-right' : ''
                         )}
                         style={{ color: 'var(--text-muted)' }}
                       >
                         {h}
                       </div>
                     ))}
                   </div>

                   {/* Table Rows */}
                   {drivers.map((d, idx) => {
                     const successRateValue = normRate(d.successRate);
                     const badgeColor = successRateValue >= 90 ? '#10B981' : successRateValue >= 75 ? '#F59E0B' : '#EF4444';
                     return (
                       <div
                         key={d.driverId ?? d.driverName ?? idx}
                         className="grid grid-cols-[2fr_1fr_1fr_1fr_80px] px-4 py-3 items-center group transition-colors hover:bg-[var(--hover-bg)]"
                         style={{ borderBottom: '1px solid var(--border)' }}
                       >
                         {/* Driver avatar and name */}
                         <div className="flex items-center gap-3">
                            <div
                              className="w-[30px] h-[30px] flex items-center justify-center rounded-md"
                              style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}
                            >
                              <span className="text-[10px] font-mono font-bold text-[var(--text-secondary)]">
                                {d.driverName ? d.driverName.split(' ').map(n=>n[0]).join('').toUpperCase().slice(0,2) : "D"}
                              </span>
                            </div>
                            <p className="text-[11px] font-bold" style={{ color: 'var(--text-primary)' }}>
                              {d.driverName || t.performancePage.notAssigned}
                            </p>
                         </div>

                         {/* Volume */}
                         <p className="text-[11px] font-semibold text-center font-mono" style={{ color: 'var(--text-primary)' }}>
                           {d.total}
                         </p>

                         {/* Success rate */}
                         <div className="flex justify-center">
                            <span 
                              className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-[600] border font-mono leading-none" 
                              style={{ color: badgeColor, borderColor: `${badgeColor}30`, backgroundColor: `${badgeColor}08` }}
                            >
                               {successRateValue.toFixed(1)}%
                            </span>
                         </div>

                         {/* Delay */}
                         <p className="text-[11px] font-medium text-center font-mono" style={{ color: 'var(--text-muted)' }}>
                           {fmtMinutes(d.avgDelayMinutes)}
                         </p>

                         {/* Actions */}
                         <div className="text-right">
                            <button
                               type="button"
                               disabled={generatingPdf.has(d.driverName)}
                               onClick={() => generateDriverReport(d)}
                               title={t.performancePage.downloadPdfTooltip}
                               className="inline-flex items-center justify-center w-7 h-7 rounded-md border border-[var(--border)] hover:bg-[var(--hover-bg)] text-[var(--text-muted)] hover:text-[var(--text-primary)] disabled:opacity-50 transition-colors"
                               style={{ background: 'var(--app-bg)' }}
                            >
                               {generatingPdf.has(d.driverName) ? (
                                  <Spinner size={14} />
                               ) : (
                                  <IconFileAnalytics size={14} />
                                )}
                            </button>
                         </div>
                       </div>
                     );
                   })}
                 </div>
               </div>
             </div>
              ),
            },
          ]}
        />
        </div>
      </div>
    </div>
  );
}

