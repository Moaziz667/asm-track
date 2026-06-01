import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { api } from '@/lib/api';
import { AdminOpsOverview, DashboardStats, DeliveryStatus } from '@/types';
import { showErrorToast } from '@/lib/toast-service';
import { useNotificationsState } from '@/components/AlertsProvider';
import { cn } from '@/lib/utils';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import {
  IconPackage, IconChartBar, IconUser, IconRoute, IconRefresh, IconArrowUpRight, 
  IconAlertTriangle, IconCheck, IconTruck, IconMapPin, IconShield,
  IconInbox, IconDots, IconArrowRight, IconTable, IconLayoutKanban
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';

const capitalize = (s: string) => s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : '';

// Status colors for the Kanban cards
const STATUS_COLOR_MAP: Record<DeliveryStatus, string> = {
  UNSCHEDULED:          '#C4881A',
  SCHEDULED:            '#5E6AD2',
  PICKED_UP:            '#2594B8',
  IN_TRANSIT:           '#D4772C',
  DELIVERED:            '#4CAF82',
  PARTIALLY_DELIVERED:  '#7B6FCC',
  FAILED:               '#C7372F',
  CANCELLED:            '#8A8F98',
};

// Status styles for high-density table rows
const STATUS_ROW_COLOR_MAP: Record<DeliveryStatus, { text: string; bg: string; border: string }> = {
  UNSCHEDULED:          { text: '#A06D10', bg: 'rgba(196,136,26,0.09)',  border: 'rgba(196,136,26,0.15)' },
  SCHEDULED:            { text: '#4C56B8', bg: 'rgba(94,106,210,0.09)',  border: 'rgba(94,106,210,0.15)' },
  PICKED_UP:            { text: '#1A7A9A', bg: 'rgba(37,148,184,0.09)',  border: 'rgba(37,148,184,0.15)' },
  IN_TRANSIT:           { text: '#B05A18', bg: 'rgba(212,119,44,0.09)',  border: 'rgba(212,119,44,0.15)' },
  DELIVERED:            { text: '#2D8A5E', bg: 'rgba(76,175,130,0.09)',  border: 'rgba(76,175,130,0.15)' },
  PARTIALLY_DELIVERED:  { text: '#6055A8', bg: 'rgba(123,111,204,0.09)', border: 'rgba(123,111,204,0.15)' },
  FAILED:               { text: '#A52B24', bg: 'rgba(199,55,47,0.09)',   border: 'rgba(199,55,47,0.15)' },
  CANCELLED:            { text: '#6B7280', bg: 'rgba(138,143,152,0.08)', border: 'rgba(138,143,152,0.15)' },
};

const DISPATCH_STATUSES: DeliveryStatus[] = [
  'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'FAILED', 'DELIVERED',
];

export default function DashboardPage() {
  const t = useT();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [ops, setOps] = useState<AdminOpsOverview | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [period, setPeriod] = useState<'day' | 'week' | 'month' | 'all'>('all');
  const [viewMode, setViewMode] = useState<'office' | 'kanban'>('office');
  // useNotificationsState — reads count only, does NOT re-render on action context updates
  const { notifications: ctxAlerts } = useNotificationsState();
  const { locale } = useLocaleStore();

  const getStatusConfig = (status: DeliveryStatus): { label: string; color: string } => ({
    label: t.statusLabels[status] || status,
    color: STATUS_COLOR_MAP[status],
  });

  const fetchData = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    try {
      const [sR, oR] = await Promise.all([
        api.get('/api/admin/deliveries/stats', { params: { period } }),
        api.get('/api/admin/ops/overview', { params: { period, limit: 1000 } }),
      ]);
      setStats(sR.data);
      setOps(oR.data ?? null);
    } catch {
      if (!silent) showErrorToast(null, t.dashboardPage.syncError);
    } finally {
      setRefreshing(false);
    }
  }, [period, t]);

  useEffect(() => {
    fetchData();
    // Load preferred view mode from localStorage on mount
    const cachedMode = localStorage.getItem('asm_dashboard_view');
    if (cachedMode === 'office' || cachedMode === 'kanban') {
      setViewMode(cachedMode);
    }
  }, [fetchData]);

  const handleViewChange = (mode: 'office' | 'kanban') => {
    setViewMode(mode);
    localStorage.setItem('asm_dashboard_view', mode);
  };

  const today = stats?.today;
  const exceptionsCount = (ops?.sla?.totalBreaches ?? 0) + (ctxAlerts?.length ?? 0);
  const slaPercent = today?.total ? Math.round((today.delivered / today.total) * 100) : 100;

  // Flatten active deliveries from lanes for the table
  const activeDeliveries = useMemo(() => {
    const list = ops?.lanes?.flatMap(l => l.items || []) || [];
    const unique = Array.from(new Map(list.map(item => [item.deliveryId || item.orderRef, item])).values());
    return unique.slice(0, 8);
  }, [ops]);

  // Kanban lane map calculation
  const laneMap = useMemo(() => {
    return DISPATCH_STATUSES.reduce((acc, s) => {
      const srv = ops?.lanes?.find(l => l.status === s);
      acc[s] = { count: srv?.count ?? 0, items: srv?.items ?? [] };
      return acc;
    }, {} as Record<DeliveryStatus, { count: number; items: any[] }>);
  }, [ops]);

  return (
    <div className="w-full flex flex-col bg-[var(--app-bg)] min-h-[calc(100vh-56px)] select-none animate-fadeIn">
      
      {/* ── HEADER PANEL ── */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0 shadow-2xs">
        <div className="px-6 py-4 flex items-center justify-between gap-6 max-w-[1800px] mx-auto">
          <div className="flex flex-col text-left">
            <h1 className="text-[13.5px] font-bold text-[var(--text-primary)] leading-tight tracking-tight">
              {t.pages.dashboard.title || 'Tableau de Bord'}
            </h1>
            <span className="text-[11px] text-[var(--text-muted)] mt-1 font-medium">
              {t.pages.dashboard.subtitle || 'Supervision administrative et indicateurs opérationnels'}
            </span>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            {/* View Mode selector (Pro Toggle) */}
            <div className="flex items-center gap-1.5 mr-2">
              <button
                type="button"
                onClick={() => handleViewChange('office')}
                className={cn(
                  "px-3 py-1 text-[11px] font-bold transition-all rounded-full cursor-pointer flex items-center gap-1 h-7 active:scale-[0.95]",
                  viewMode === 'office'
                    ? "bg-background text-foreground border border-border shadow-2xs font-semibold"
                    : "text-muted-foreground hover:text-foreground bg-transparent"
                )}
              >
                <IconTable size={12} />
                {locale === 'ar' ? 'الجدول' : 'Tableau'}
              </button>
              <button
                type="button"
                onClick={() => handleViewChange('kanban')}
                className={cn(
                  "px-3 py-1 text-[11px] font-bold transition-all rounded-full cursor-pointer flex items-center gap-1 h-7 active:scale-[0.95]",
                  viewMode === 'kanban'
                    ? "bg-background text-foreground border border-border shadow-2xs font-semibold"
                    : "text-muted-foreground hover:text-foreground bg-transparent"
                )}
              >
                <IconLayoutKanban size={12} />
                Kanban
              </button>
            </div>

            {/* Period selector */}
            <div className="flex items-center gap-1 mr-1">
              {(['day', 'week', 'month', 'all'] as const).map((p) => {
                const labelMap = {
                  day: t.dashboardPage.periodDay,
                  week: t.dashboardPage.periodWeek,
                  month: t.dashboardPage.periodMonth,
                  all: t.dashboardPage.periodAll,
                };
                const active = period === p;
                return (
                  <button
                    key={p}
                    type="button"
                    onClick={() => setPeriod(p)}
                    className={cn(
                      "px-3 py-1 text-[11px] font-bold transition-all rounded-full cursor-pointer h-7 flex items-center justify-center active:scale-[0.95]",
                      active
                        ? "bg-background text-foreground border border-border shadow-2xs font-semibold"
                        : "text-muted-foreground hover:text-foreground bg-transparent"
                    )}
                  >
                    {labelMap[p]}
                  </button>
                );
              })}
            </div>

            <RefreshButton refreshing={refreshing} onClick={() => fetchData()} />
          </div>
        </div>
      </div>



      {/* ── MAIN VIEW CONTENT SWITCHER ── */}
      {viewMode === 'office' ? (
        /* ── OFFICE DESK LAYOUT ── */
        <div className="px-6 py-6 w-full max-w-[1800px] mx-auto grid grid-cols-1 lg:grid-cols-3 gap-6 flex-1 animate-fadeIn">
          
          {/* LEFT COLUMN: structured classic operational table (2/3 width) */}
          <div className="lg:col-span-2 flex flex-col bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-2xs overflow-hidden">
            
            <div className="p-4 border-b border-[var(--border)] flex items-center justify-between bg-[var(--app-bg)]/25">
              <span className="text-[11.5px] font-bold tracking-tight text-[var(--text-muted)]">
                {locale === 'ar' ? 'العمليات النشطة الأخيرة' : 'Suivi des Opérations Récentes'}
              </span>
              <button
                onClick={() => window.open('/deliveries', '_blank')}
                className="text-[11px] font-bold text-primary hover:underline flex items-center gap-1 cursor-pointer"
              >
                Voir tout <IconArrowUpRight size={12} />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto overflow-x-auto max-h-[350px]" style={{ scrollbarWidth: 'thin' }}>
              <table className="w-full text-left border-collapse font-sans text-xs">
                <thead>
                  <tr className="border-b border-[var(--border)] bg-[var(--surface)] sticky top-0 z-10 shadow-3xs">
                    <th className="p-3 font-semibold text-[var(--text-muted)] bg-[var(--surface)]">Référence</th>
                    <th className="p-3 font-semibold text-[var(--text-muted)] bg-[var(--surface)]">Client</th>
                    <th className="p-3 font-semibold text-[var(--text-muted)] bg-[var(--surface)]">Chauffeur</th>
                    <th className="p-3 font-semibold text-[var(--text-muted)] bg-[var(--surface)]">Tournée</th>
                    <th className="p-3 font-semibold text-[var(--text-muted)] bg-[var(--surface)]">Statut</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border)]">
                  {activeDeliveries.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="p-12 text-center text-muted-foreground opacity-60">
                        <IconAlertTriangle className="mx-auto mb-2 opacity-50" size={24} />
                        Aucune opération active à afficher.
                      </td>
                    </tr>
                  ) : (
                    activeDeliveries.map((item: any, idx: number) => {
                      const st = item.status as DeliveryStatus;
                      const config = STATUS_ROW_COLOR_MAP[st] || { text: 'var(--text-muted)', bg: 'var(--hover-bg)', border: 'var(--border)' };
                      const label = t.statusLabels[st] || st;

                      return (
                        <tr 
                          key={idx} 
                          onClick={() => window.open(`/deliveries/${item.deliveryId}`, '_blank')}
                          className="hover:bg-[var(--hover-bg)]/40 transition-colors duration-100 cursor-pointer"
                        >
                          <td className="p-3 font-mono font-semibold text-primary truncate max-w-[120px]">
                            {item.orderRef}
                          </td>
                          <td className="p-3 font-semibold text-[var(--text-primary)] max-w-[160px] truncate">
                            {item.clientName || 'Client inconnu'}
                          </td>
                          <td className="p-3 text-[var(--text-secondary)] font-medium">
                            {item.driverName ? (
                              <span className="flex items-center gap-1.5">
                                <span className="w-4 h-4 rounded-full bg-[var(--hover-bg)] flex items-center justify-center text-[9px] shrink-0 font-semibold">
                                  {item.driverName.substring(0, 2).toUpperCase()}
                                </span>
                                <span className="truncate max-w-[100px]">{item.driverName}</span>
                              </span>
                            ) : (
                              <span className="text-[var(--text-soft)] italic">Non assigné</span>
                            )}
                          </td>
                          <td className="p-3 text-[var(--text-secondary)]">
                            {item.routeName || item.routeRef ? (
                              <span className="flex items-center gap-1 font-medium truncate max-w-[110px]">
                                <IconRoute size={12} className="text-[var(--text-soft)] shrink-0" />
                                <span>{item.routeName || item.routeRef}</span>
                              </span>
                            ) : (
                              <span className="text-[var(--text-soft)] italic">-</span>
                            )}
                          </td>
                          <td className="p-3">
                            <span 
                              className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold border"
                              style={{
                                color: config.text,
                                backgroundColor: config.bg,
                                borderColor: config.border
                              }}
                            >
                              {label}
                            </span>
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {/* RIGHT COLUMN: structured classic charts & analytical panels (1/3 width) */}
          <div className="flex flex-col gap-6">
            
            {/* Driver Performance Bar Chart Card */}
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-2xs p-4 text-left flex flex-col h-[260px]">
              <span className="text-[11.5px] font-bold tracking-tight text-[var(--text-muted)] mb-4 block">
                {t.dashboardPage.driverPerformanceTitle || 'Rendement par Chauffeur'}
              </span>
              <div className="flex-1 min-h-0">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={stats?.byDriver ?? []} barGap={5} barCategoryGap="42%">
                    <CartesianGrid strokeDasharray="2 3" vertical={false} stroke="var(--border)" />
                    <XAxis
                      dataKey="driverName"
                      tick={{ fontSize: 9, fill: 'var(--text-soft)', fontWeight: 600 }}
                      axisLine={false}
                      tickLine={false}
                      dy={6}
                      tickFormatter={capitalize}
                    />
                    <YAxis
                      tick={{ fontSize: 9, fill: 'var(--text-soft)' }}
                      axisLine={false}
                      tickLine={false}
                      width={18}
                    />
                    <Tooltip
                      contentStyle={{
                        background: 'var(--surface)',
                        border: '1px solid var(--border)',
                        borderRadius: '4px',
                        padding: '6px 10px',
                        fontSize: 10,
                        color: 'var(--text-primary)',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.05)',
                      }}
                      cursor={{ fill: 'var(--hover-bg)' }}
                    />
                    <Bar name="Total" dataKey="total" fill="var(--border-strong)" barSize={12} radius={[2, 2, 0, 0]} />
                    <Bar name="Livrées" dataKey="delivered" fill="var(--text-primary)" barSize={12} radius={[2, 2, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>

            {/* Service Quality Card (SLA Scorecard) */}
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-2xs p-5 text-left flex flex-col gap-4">
              <span className="text-[11.5px] font-bold tracking-tight text-[var(--text-muted)]">
                {t.dashboardPage.serviceQualityTitle || 'Contrôle Qualité de Service'}
              </span>

              <div className="flex items-baseline gap-2">
                <span
                  className="font-mono text-3xl font-bold leading-none tabular-nums"
                  style={{ color: slaPercent >= 90 ? '#2D8A5E' : slaPercent >= 70 ? '#D4772C' : '#C7372F' }}
                >
                  {slaPercent}%
                </span>
                <span className="text-[10.5px] font-semibold text-[var(--text-soft)] tracking-tight">
                  {t.dashboardPage.slaRateLabel || 'taux SLA'}
                </span>
              </div>

              {/* Micro Progress Bar */}
              <div className="w-full h-1.5 bg-[var(--hover-bg)] rounded-full overflow-hidden border border-[var(--border)]">
                <div
                  className="h-full rounded-full transition-all duration-700"
                  style={{
                    width: `${slaPercent}%`,
                    backgroundColor: slaPercent >= 90 ? '#4CAF82' : slaPercent >= 70 ? '#D4772C' : '#C7372F',
                  }}
                />
              </div>

              {/* Scorecard Detailed Breakdown */}
              <div className="flex flex-col gap-2.5 pt-1">
                <div className="flex items-center justify-between text-xs border-b border-[var(--border)]/40 pb-2">
                  <span className="text-[var(--text-muted)] font-medium">{t.dashboardPage.statsCompleted || 'Complétées'}</span>
                  <span className="font-mono font-bold text-[var(--text-primary)]">
                    {today?.delivered ?? 0}
                  </span>
                </div>
                <div className="flex items-center justify-between text-xs border-b border-[var(--border)]/40 pb-2">
                  <span className="text-[var(--text-muted)] font-medium">{t.dashboardPage.statsInProgress || 'En cours'}</span>
                  <span className="font-mono font-bold text-[var(--text-primary)]">
                    {today?.inTransit ?? 0}
                  </span>
                </div>
                <div className="flex items-center justify-between text-xs">
                  <span className="text-[var(--text-muted)] font-medium">{t.dashboardPage.statsExceptions || 'Exceptions'}</span>
                  <span
                    className="font-mono font-bold"
                    style={{ color: exceptionsCount > 0 ? '#C7372F' : 'var(--text-muted)' }}
                  >
                    {exceptionsCount}
                  </span>
                </div>
              </div>

            </div>

          </div>

        </div>
      ) : (
        /* ── KANBAN VIEW ── */
        <div className="px-6 pb-6 w-full max-w-[1800px] mx-auto flex flex-col flex-1 min-h-0 overflow-hidden animate-fadeIn">
          <div className="pt-4 pb-2 flex items-center justify-between">
            <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              {t.dashboardPage.dispatchFlowTitle || 'Flux de Dispatch'}
            </span>
            <button
              type="button"
              onClick={() => window.open('/route-builder', '_blank')}
              className="group flex items-center gap-1 text-[11.5px] font-bold text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors cursor-pointer"
            >
              {t.dashboardPage.plannerButton || 'Planificateur'} <IconArrowRight size={12} className="group-hover:translate-x-0.5 transition-transform" />
            </button>
          </div>

          {/* Kanban board columns container */}
          <div className="overflow-x-auto border border-[var(--border)] rounded-lg bg-[var(--surface)] shadow-2xs">
            <div
              className="flex flex-nowrap items-stretch"
              style={{ height: 'calc(100vh - 180px)', minHeight: 580 }}
            >
              {DISPATCH_STATUSES.map(status => {
                const config = getStatusConfig(status);
                const data = laneMap[status];
                const items = data?.items ?? [];

                return (
                  <div
                    key={status}
                    className="border-r border-[var(--border)] last:border-r-0 flex flex-col bg-[var(--app-bg)]/35"
                    style={{ width: 268, flexShrink: 0, height: '100%' }}
                  >
                    {/* Column Header */}
                    <div className="flex-none px-3 py-2.5 flex items-center justify-between border-b border-[var(--border)] bg-[var(--surface)]">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span
                          className="px-2 py-0.5 rounded-full text-[10px] font-bold leading-none tracking-tight"
                          style={{
                            backgroundColor: `${config.color}15`,
                            color: config.color,
                          }}
                        >
                          {config.label}
                        </span>
                        <span className="text-[10.5px] font-mono font-bold text-[var(--text-soft)]">
                          {data.count}
                        </span>
                      </div>
                      <button
                        type="button"
                        className="w-5 h-5 flex items-center justify-center rounded text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer"
                      >
                        <IconDots size={13} />
                      </button>
                    </div>

                    {/* Cards container */}
                    <div className="flex-1 overflow-y-auto p-2.5 flex flex-col gap-2" style={{ scrollbarWidth: 'thin' }}>
                      {items.length === 0 ? (
                        <div className="flex flex-col items-center justify-center flex-1 py-12 gap-2 opacity-45">
                          <IconInbox size={18} stroke={1.2} className="text-[var(--text-soft)]" />
                          <span className="text-[10px] font-bold text-[var(--text-soft)]">{t.dashboardPage.emptyState || 'Vide'}</span>
                        </div>
                      ) : (
                        items.map((d: any, idx: number) => {
                          const isLot = d.isLot || d.deliveriesCount > 1 || d.orderRef?.startsWith('LOT');
                          return isLot
                            ? <LotCard key={idx} d={d} status={status} color={config.color} />
                            : <DeliveryCard key={idx} d={d} status={status} color={config.color} />;
                        })
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

    </div>
  );
}

// ── KPI scorecards card component ──
interface KpiCardProps {
  title: string;
  value: string | number;
  subtitle: string;
  Icon: React.ComponentType<{ size?: number; className?: string; style?: React.CSSProperties }>;
  color: string;
}

export function KpiCard({ title, value, subtitle, Icon, color }: KpiCardProps) {
  const isCssVar = color.startsWith('var(');
  const iconBg = isCssVar ? 'var(--hover-bg)' : `${color}12`;
  
  return (
    <div className="bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-2xs p-4 flex items-center justify-between text-left">
      <div className="flex flex-col min-w-0">
        <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
          {title}
        </span>
        <span className="font-mono text-2xl font-bold mt-1 text-[var(--text-primary)] leading-tight tabular-nums">
          {value}
        </span>
        <span className="text-[10.5px] font-semibold text-[var(--text-soft)] mt-1.5 truncate">
          {subtitle}
        </span>
      </div>
      <div 
        className="w-9 h-9 rounded-md flex items-center justify-center shrink-0 shadow-3xs"
        style={{ backgroundColor: iconBg, color }}
      >
        <Icon size={18} />
      </div>
    </div>
  );
}

// ── Single delivery card ──
function DeliveryCard({ d, status, color }: { d: any; status: DeliveryStatus; color: string }) {
  const t = useT();
  const handleClick = () => {
    let url = '';
    if (status === 'UNSCHEDULED') url = '/route-builder';
    else if (status === 'DELIVERED' || status === 'PARTIALLY_DELIVERED') url = `/deliveries/${d.deliveryId}`;
    else if (d.routeId) url = `/routes/${d.routeId}`;
    else url = `/deliveries/${d.deliveryId}`;
    window.open(url, '_blank');
  };

  const routeLabel = d.routeName || d.routeRef;

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3 rounded-md bg-[var(--surface)] cursor-pointer focus:outline-none transition-colors hover:brightness-[0.97] shadow-[0_1px_2px_rgba(0,0,0,0.04)]"
      style={{
        border: '1px solid var(--border)',
        borderLeft: `3px solid ${color}`,
      }}
    >
      <div className="mb-1.5 flex items-center justify-between">
        <span className="font-mono text-[9.5px] font-bold tracking-tight" style={{ color }}>
          {d.orderRef}
        </span>
      </div>

      <p className="text-[12px] font-bold text-[var(--text-primary)] leading-snug mb-2.5 truncate">
        {d.clientName || t.dashboardPage.unknownClient}
      </p>

      <div className="flex flex-wrap gap-1">
        {d.driverName && (
          <Chip icon={<IconUser size={9} />} label={d.driverName} />
        )}
        {routeLabel && (
          <Chip icon={<IconRoute size={9} />} label={routeLabel} />
        )}
      </div>
    </button>
  );
}

// ── Lot (multi-delivery) card ──
function LotCard({ d, status, color }: { d: any; status: DeliveryStatus; color: string }) {
  const t = useT();
  const deliveries = d.subDeliveries || [];
  const totalCount = d.deliveriesCount || deliveries.length;
  const completedCount = deliveries.filter((x: any) => x.status === 'DELIVERED').length;
  const progress = totalCount > 0 ? (completedCount / totalCount) * 100 : 0;

  const handleClick = () => {
    const url = status === 'UNSCHEDULED' ? '/route-builder' : `/deliveries?lot=${d.orderRef}`;
    window.open(url, '_blank');
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3 rounded-md bg-[var(--surface)] cursor-pointer focus:outline-none transition-colors hover:brightness-[0.97] shadow-[0_1px_2px_rgba(0,0,0,0.04)]"
      style={{
        border: '1px solid var(--border)',
        borderLeft: `3px solid ${color}`,
      }}
    >
      <div className="mb-1.5 flex items-center gap-1 justify-between">
        <div className="flex items-center gap-1">
          <IconPackage size={9} style={{ color }} />
          <span className="font-mono text-[9.5px] font-bold tracking-tight" style={{ color }}>
            {d.orderRef || 'LOT'}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-0.5 mb-2.5 min-w-0">
        {deliveries.slice(0, 2).map((x: any, i: number) => (
          <p key={i} className="text-[12px] font-bold text-[var(--text-primary)] leading-snug truncate">
            {x.clientName}
          </p>
        ))}
        {deliveries.length > 2 && (
          <p className="text-[10px] font-semibold text-[var(--text-soft)] mt-0.5">
            {t.dashboardPage.moreDeliveries.replace('{count}', String(deliveries.length - 2))}
          </p>
        )}
      </div>

      <div className="flex items-center gap-2">
        <div className="flex-1 h-0.5 bg-[var(--border)] rounded-full overflow-hidden">
          <div
            className="h-full transition-all duration-500"
            style={{ width: `${progress}%`, backgroundColor: color }}
          />
        </div>
        <span className="text-[9.5px] font-bold font-mono tabular-nums shrink-0" style={{ color }}>
          {completedCount}/{totalCount}
        </span>
      </div>
    </button>
  );
}

// ── Metadata chip ──
function Chip({ icon, label, muted }: { icon: React.ReactNode; label: string; muted?: boolean }) {
  return (
    <span
      className="inline-flex items-center gap-1 text-[9.5px] font-semibold px-1.5 py-0.5 rounded-[3px] border border-[var(--border)] bg-[var(--hover-bg)] leading-none"
      style={{ color: muted ? 'var(--text-soft)' : 'var(--text-muted)' }}
    >
      {icon}
      <span className="truncate max-w-[96px]">{label}</span>
    </span>
  );
}
