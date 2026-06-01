
import { useCallback, useEffect, useMemo, useState, Suspense } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { useT } from '@/lib/LocaleContext';
import {
  IconRefresh, IconPlus, IconCalendar, IconSearch, IconChevronDown,
  IconChevronRight, IconMapPin, IconUser, IconTruck, IconRoute,
  IconCar, IconLock, IconExternalLink, IconPackage, IconWeight, IconClock
} from '@tabler/icons-react';
import { showErrorToast } from '@/lib/toast-service';
import { api } from '@/lib/api';
import { useCloseRoute } from '@/hooks/useRoutes';
import type { Driver, DeliveryItem } from '@/types';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { EmptyState } from '@/components/feedback/EmptyState';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { cn } from '@/lib/utils';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';
import StatusBadge from '@/components/StatusBadge';

// ─── Types ────────────────────────────────────────────────────────────────────

type DeliveryDetail = {
  id: string;
  deliveryId: string;
  stopOrder?: number;
  status?: string;
  clientName?: string;
  dropoffAddress?: string;
  dropoffCity?: string;
  totalWeightKg?: number;
  erpId?: string;
  orderId?: string;
  priority?: string;
  items?: DeliveryItem[];
};

type RouteItem = {
  id: string;
  name: string;
  driverId: string;
  vehicleId?: string;
  depotId?: string;
  date: string;
  status: 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED' | string;
  stops: DeliveryDetail[];
  detectedZoneLabel?: string;
};

type VehicleItem = { id: string; name: string; plate: string };
type DepotItem   = { id: string; name: string; latitude: number; longitude: number };

type EnrichedRoute = RouteItem & {
  zoneLabel:      string;
  clientNames:    string[];
  completedStops: number;
};

// ─── Constants ────────────────────────────────────────────────────────────────

const DONE_STATUSES = new Set(['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED', 'FAILED_ATTEMPT', 'COMPLETED', 'PARTIAL']);

const STATUS_CONFIG: Record<string, { label: string; color: string; ribbon: string }> = {
  DRAFT:       { label: 'Brouillon', color: 'gray',    ribbon: '#A1A1AA' },
  VALIDATED:   { label: 'Planifié',  color: 'blue',    ribbon: '#2563EB' },
  IN_PROGRESS: { label: 'En route',  color: 'orange',  ribbon: 'var(--brand)' },
  CLOSED:      { label: 'Livré',     color: 'teal',    ribbon: '#10B981' },
  CANCELLED:   { label: 'Annulé',    color: 'red',     ribbon: '#EF4444' },
};

// ─── Sub-components ───────────────────────────────────────────────────────────

function StopDetailRow({ stop, index }: { stop: DeliveryDetail; index: number }) {
  const t = useT();
  const isDone   = stop.status ? DONE_STATUSES.has(stop.status) : false;
  const odooRef  = stop.erpId || null;
  const shortId  = (stop.orderId || stop.deliveryId || '').replace(/-/g, '').slice(0, 8).toUpperCase();
  return (
    <div className={cn(
      "grid grid-cols-[30px_1fr_1.5fr_1fr_80px_100px] gap-4 items-center px-6 py-2 border-b border-[var(--border)]",
      isDone ? "bg-[var(--surface)] opacity-60" : "bg-[var(--surface)]"
    )}>
      <span className="text-[10px] font-[700] text-[var(--text-muted)]">{index + 1}</span>

      <div className="flex flex-col gap-0">
        <span className="text-[11px] font-[600] text-[var(--text-primary)] truncate">{stop.clientName || '—'}</span>
        <span className="text-[11px] font-[700] font-mono text-[var(--text-muted)]">#{shortId}</span>
      </div>

      <div className="flex items-center gap-1 flex-nowrap">
        <IconMapPin size={10} className="text-[var(--text-muted)] shrink-0" />
        <span className="text-[10px] font-[500] text-[var(--text-soft)] truncate">
          {[stop.dropoffAddress, stop.dropoffCity]
            .filter(Boolean)
            .join(', ')
            .replace('Address not provided', t.routesTablePage.addressNotProvided) || '—'}
        </span>
      </div>

      <div>
        {odooRef && (
          <div className="flex items-center gap-1">
            <span className="text-[11px] font-[600] text-[var(--brand)] tracking-tighter">ERP</span>
            <span className="text-[10px] font-[700] font-mono text-[var(--text-primary)]">{odooRef}</span>
          </div>
        )}
      </div>

      <span className="text-[10px] font-[600] text-[var(--text-soft)] text-right tabular-nums">
        {stop.totalWeightKg != null ? `${stop.totalWeightKg.toFixed(1)}kg` : '—'}
      </span>

      <div className="text-right">
        <StatusBadge status={stop.status || 'PENDING'} size="sm" />
      </div>
    </div>
  );
}

function RouteRow({
  route, driverName, depotName, onCloseClick,
}: {
  route:        EnrichedRoute;
  driverName:   string;
  depotName:    string;
  onCloseClick: (route: EnrichedRoute) => void;
}) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const router = useRouter();

  const total    = route.stops.length;
  const done     = route.completedStops;
  const pct      = total > 0 ? Math.round((done / total) * 100) : 0;
  const config   = STATUS_CONFIG[route.status] || { label: route.status, color: 'gray', ribbon: '#A1A1AA' };
  const canClose = route.status === 'IN_PROGRESS';

  return (
    <div className="border-b border-[var(--border)]">
      <div
        className={cn(
          "grid grid-cols-[8px_200px_100px_1fr_150px_120px_110px_90px] gap-4 items-center h-[52px] cursor-pointer group transition-colors",
          expanded ? "bg-[var(--surface)]" : "hover:bg-[var(--surface)]"
        )}
        onClick={() => setExpanded(!expanded)}
      >
        {/* Ribbon */}
        <div className="w-[3px] h-4 rounded-r-[1px]" style={{ backgroundColor: config.ribbon }} />

        {/* Tournée */}
        <div className="flex flex-col gap-0">
          <span className="text-[11px] font-[700] text-[var(--text-primary)] tracking-tight">{route.name}</span>
          <span className="text-[11px] font-[600] text-[var(--text-muted)]">ID: {route.id?.slice(0, 8) ?? 'N/A'}</span>
        </div>

        {/* Date */}
        <div className="flex items-center gap-1">
          <IconCalendar size={12} className="text-[var(--text-muted)]" />
          <span className="text-[11px] font-[600] text-[var(--text-soft)]">{route.date}</span>
        </div>

        {/* Zone & Depot */}
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded border border-[var(--border)] bg-[var(--surface)] text-[var(--text-primary)] uppercase tracking-widest font-bold">
            {route.zoneLabel || 'Zone ?'}
          </span>
          <span className="text-[10px] font-[600] text-[var(--text-muted)] truncate italic">{depotName}</span>
        </div>

        {/* Chauffeur */}
        <div className="flex items-center gap-1.5">
          <div className="w-1.5 h-1.5 rounded-full bg-gray-400 shrink-0" />
          <span className="text-[11px] font-[600] text-[var(--text-primary)] truncate max-w-[120px]">{driverName || t.routesTablePage.notAssigned}</span>
        </div>

        {/* Progression */}
        <div className="flex flex-col gap-1 w-[100px]">
          <div className="flex items-center justify-between gap-1">
            <span className="text-[11px] font-[700] font-mono text-[var(--text-soft)]">{done}/{total}</span>
            <span className="text-[11px] font-[700] text-[var(--text-muted)]">{pct}%</span>
          </div>
          <div className="h-[2px] w-full bg-[var(--surface)] rounded overflow-hidden">
            <div
              className={cn(
                "h-full rounded transition-all",
                route.status === 'CLOSED' ? "bg-emerald-500" : "bg-[var(--brand)]"
              )}
              style={{ width: `${pct}%` }}
            />
          </div>
        </div>

        {/* Statut */}
        <div>
          <StatusBadge status={route.status} size="sm" />
        </div>

        {/* Actions */}
        <div className="flex items-center gap-1 flex-nowrap justify-end pr-4" onClick={(e) => e.stopPropagation()}>
          {canClose && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors"
                  onClick={() => onCloseClick(route)}
                >
                  <IconLock size={14} />
                </button>
              </TooltipTrigger>
              <TooltipContent>{t.routesTablePage.closeRouteTooltip}</TooltipContent>
            </Tooltip>
          )}
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => router(`/routes/${route.id}`)}
          >
            <IconExternalLink size={14} />
          </button>
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => setExpanded(!expanded)}
          >
            {expanded ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
          </button>
        </div>
      </div>

      {/* Expansion Details */}
      {expanded && (
        <div className="bg-[var(--surface)] border-t border-[var(--border)]">
          <div className="grid grid-cols-[30px_1fr_1.5fr_1fr_80px_100px] gap-4 px-6 py-1.5 bg-[var(--surface)]">
            {[
              t.routesTablePage.headerIndex,
              t.routesTablePage.headerClient,
              t.routesTablePage.headerAddress,
              t.routesTablePage.headerERP,
              t.routesTablePage.headerWeight,
              t.routesTablePage.headerDeliveryStatus,
            ].map((h) => (
              <span key={h} className="text-[11px] font-[600] text-[var(--text-muted)]">{h}</span>
            ))}
          </div>
          {route.stops.length === 0 ? (
            <div className="py-5 text-center italic text-[var(--text-muted)] text-xs">{t.routesTablePage.noActiveStops}</div>
          ) : (
            route.stops
              .sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0))
              .map((stop, i) => <StopDetailRow key={stop.id || stop.deliveryId} stop={stop} index={i} />)
          )}
        </div>
      )}
    </div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────

function RoutesTablePageContent() {
  const t = useT();
  usePageBreadcrumb([{ label: t.pages.routes?.title || 'Tournées' }]);
  const router = useRouter();

  useEffect(() => {
    const role = getCurrentRole();
    if (role !== 'UNKNOWN' && !canDispatch(role)) router('/dashboard', { replace: true });
  }, [router]);

  const [loading, setLoading]   = useState(true);
  const [routes, setRoutes]     = useState<EnrichedRoute[]>([]);
  const [drivers, setDrivers]   = useState<Driver[]>([]);
  const [vehicles, setVehicles] = useState<VehicleItem[]>([]);
  const [depots, setDepots]     = useState<DepotItem[]>([]);

  // Filters
  const [statusFilter,  setStatusFilter]  = useState('ALL');
  const [dateFilter,    setDateFilter]    = useState('ALL');
  const [zoneFilter,    setZoneFilter]    = useState('');
  const [driverFilter,  setDriverFilter]  = useState('');
  const [vehicleFilter, setVehicleFilter] = useState('');
  const [depotFilter,   setDepotFilter]   = useState('');
  const [clientFilter,  setClientFilter]  = useState('');

  // Close modal
  const [closeTarget, setCloseTarget] = useState<EnrichedRoute | null>(null);
  const closeRouteMutation = useCloseRoute();

  const fetchData = useCallback(async () => {
    setLoading(true);
    const isManager = getCurrentRole() === 'MANAGER';
    try {
      const [routesRes, driversRes, vehiclesRes, depotsRes] = await Promise.all([
        api.get('/api/admin/routes'),
        isManager ? Promise.resolve({ data: [] }) : api.get('/api/admin/fleet/drivers'),
        isManager ? Promise.resolve({ data: [] }) : api.get('/api/admin/vehicles'),
        isManager ? Promise.resolve({ data: [] }) : api.get('/api/v1/depots/active').catch(() => ({ data: [] })),
      ]);

      const baseRoutes: RouteItem[] = Array.isArray(routesRes.data) ? routesRes.data : [];
      setDrivers(Array.isArray(driversRes.data) ? driversRes.data : []);
      setVehicles(Array.isArray(vehiclesRes.data) ? vehiclesRes.data : []);
      setDepots(Array.isArray(depotsRes.data) ? depotsRes.data : []);

      const details = await Promise.allSettled(
        baseRoutes.map(async (route) => {
          const full = await api.get(`/api/admin/routes/${route.id}/full`).catch(() => ({ data: null }));
          const data = full.data as any;
          const zoneLabel = data?.detectedZoneLabel ?? route.detectedZoneLabel ?? data?.city ?? '';

          const mapStop = (s: any): DeliveryDetail => {
            const d = s.delivery ?? {};
            const o = d.order ?? s.order ?? {};
            return {
              id:            s.id ?? s.stopId ?? '',
              deliveryId:    s.deliveryId ?? d.id ?? '',
              stopOrder:     s.stopOrder ?? s.sequenceOrder ?? 0,
              status:        d.status ?? s.deliveryStatus ?? s.status ?? '',
              clientName:    o.clientName ?? d.clientName ?? s.clientName ?? '',
              dropoffAddress:o.dropoffAddress ?? d.dropoffAddress ?? s.deliveryAddress ?? '',
              dropoffCity:   o.dropoffCity ?? d.dropoffCity ?? s.deliveryCity ?? '',
              totalWeightKg: o.totalWeightKg ?? d.totalWeightKg ?? 0,
              erpId:         o.erpOrderId ?? o.erpId ?? d.erpId ?? '',
              orderId:       o.id ?? d.orderId ?? '',
              priority:      o.priority ?? d.priority ?? '',
              items:         o.items ?? d.items ?? s.items ?? [],
            };
          };

          const rawStops: DeliveryDetail[]    = Array.isArray(data?.stops)       ? data.stops.map(mapStop)       : [];
          const legacyStops: DeliveryDetail[] = Array.isArray(data?.legacyStops) ? data.legacyStops.map(mapStop) : [];
          const allStops = rawStops.length > 0 ? rawStops : legacyStops;

          const clientNames    = allStops.map((s) => s.clientName).filter((n): n is string => Boolean(n));
          const completedStops = allStops.filter((s) => s.status && DONE_STATUSES.has(s.status)).length;

          return {
            ...route,
            stops: allStops,
            zoneLabel,
            clientNames: [...new Set(clientNames.map((n) => n.trim()))],
            completedStops,
          } as EnrichedRoute;
        }),
      );

      setRoutes(
        details.map((result, idx) => {
          if (result.status === 'fulfilled') return result.value;
          const r = baseRoutes[idx];
          return { ...r, zoneLabel: r.detectedZoneLabel ?? '', clientNames: [], completedStops: 0, stops: [] } as EnrichedRoute;
        }),
      );
    } catch {
      showErrorToast(null, t.routesTablePage.loadError);
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => { void fetchData(); }, [fetchData]);

  const handleCloseRoute = async () => {
    if (!closeTarget) return;
    try {
      await closeRouteMutation.mutateAsync(closeTarget.id);
      setCloseTarget(null);
      await fetchData();
    } catch {
      // Error handled by mutation hook
    }
  };

  const closingRoute = closeRouteMutation.isPending;

  const filteredRoutes = useMemo(() => {
    const q = clientFilter.trim().toLowerCase();
    const now = new Date();
    const todayStr     = now.toISOString().split('T')[0];
    const yesterday    = new Date(now); yesterday.setDate(now.getDate() - 1);
    const yesterdayStr = yesterday.toISOString().split('T')[0];
    const weekAgo = new Date(now); weekAgo.setDate(now.getDate() - 7);

    return routes.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;
      if (dateFilter !== 'ALL') {
        if (dateFilter === 'TODAY' && r.date !== todayStr) return false;
        if (dateFilter === 'YESTERDAY' && r.date !== yesterdayStr) return false;
        if (dateFilter === 'WEEK' && new Date(r.date) < weekAgo) return false;
      }
      if (zoneFilter    && r.zoneLabel !== zoneFilter)                return false;
      if (driverFilter  && r.driverId  !== driverFilter)              return false;
      if (vehicleFilter && (r.vehicleId  ?? '') !== vehicleFilter)    return false;
      if (depotFilter   && (r.depotId    ?? '') !== depotFilter)      return false;
      if (q && !r.clientNames.join(' ').toLowerCase().includes(q))   return false;
      return true;
    });
  }, [routes, statusFilter, dateFilter, zoneFilter, driverFilter, vehicleFilter, depotFilter, clientFilter]);

  const grouped = useMemo(() => {
    const map = new Map<string, EnrichedRoute[]>();
    [...filteredRoutes]
      .sort((a, b) => b.date.localeCompare(a.date))
      .forEach((r) => {
        if (!map.has(r.date)) map.set(r.date, []);
        map.get(r.date)!.push(r);
      });
    return [...map.entries()];
  }, [filteredRoutes]);

  const stats = useMemo(() => {
    const total     = routes.length;
    const active    = routes.filter(r => r.status === 'IN_PROGRESS').length;
    const validated = routes.filter(r => r.status === 'VALIDATED').length;
    return { total, active, validated };
  }, [routes]);

  const hasAdvancedFilters = dateFilter !== 'ALL' || driverFilter || vehicleFilter || depotFilter || zoneFilter || clientFilter;

  const zoneOptions = useMemo(
    () => [...new Set(routes.map(r => r.zoneLabel).filter(Boolean))].map(z => ({ value: z, label: z })),
    [routes]
  );

  const [mobileTab, setMobileTab] = useState<'filters' | 'list'>('list');

  return (
    <TooltipProvider>
      <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
        {/* Mobile Tab Bar */}
        <div className="lg:hidden flex shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
          {([['filters', t.routesTablePage.tabFilters], ['list', t.routesTablePage.tabList]] as const).map(([tab, label]) => (
            <button
              key={tab}
              type="button"
              onClick={() => setMobileTab(tab)}
              className={cn(
                'flex-1 h-10 text-[12px] font-bold tracking-wide transition-colors',
                mobileTab === tab
                  ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]'
                  : 'text-[var(--text-muted)]'
              )}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex flex-1 min-h-0 overflow-hidden">
          {/* ── Rail Utilitaire Gauche ────────────────── */}
          <div className={cn(
            'lg:w-[280px] lg:border-r border-[var(--border)] bg-[var(--surface)] shrink-0 overflow-y-auto flex flex-col',
            mobileTab === 'filters' ? 'flex w-full' : 'hidden lg:flex'
          )}>
            {/* Header */}
            <div className="p-5 border-b border-[var(--border)]">
              <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.routesTablePage.pageSubtitle}</span>
              <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
                {t.routesTablePage.pageTitle} <span className="text-[var(--brand)]">{t.routesTablePage.pageTitleBrand}</span>
              </h1>
            </div>

            {/* New Route + Search */}
            <div className="flex flex-col gap-3 p-5 border-b border-[var(--border)]">
              <button
                type="button"
                className="w-full h-9 bg-[var(--brand)] hover:opacity-90 text-white font-bold text-[11px] rounded-[2px] flex items-center justify-center gap-2 transition-opacity"
                onClick={() => router('/route-builder')}
              >
                <IconPlus size={14} />
                {t.routesTablePage.newRouteButton}
              </button>
              <FieldInput
                placeholder={t.routesTablePage.searchPlaceholder}
                leftSection={<IconSearch size={14} className="text-[var(--text-muted)]" />}
                value={clientFilter}
                onChange={(e) => setClientFilter(e.target.value)}
                className="h-9 text-[11px]"
              />
            </div>

            {/* Status pills + Advanced Filters */}
            <div className="flex-1 overflow-y-auto">
              <div className="flex flex-col gap-0 p-2.5">
                <p className="px-3 mb-2 text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.filterStatusLabel}</p>
                {[
                  { id: 'ALL',         label: t.routesTablePage.filterAllRoutes,    icon: <IconRoute size={14} />,   count: routes.length },
                  { id: 'IN_PROGRESS', label: t.routesTablePage.filterInProgress,   icon: <IconClock size={14} />,   count: stats.active },
                  { id: 'VALIDATED',   label: t.routesTablePage.filterReady,        icon: <IconPackage size={14} />, count: stats.validated },
                  { id: 'CLOSED',      label: t.routesTablePage.filterClosed,       icon: <IconLock size={14} />,    count: routes.filter(r => r.status === 'CLOSED').length },
                ].map((pill) => (
                  <button
                    type="button"
                    key={pill.id}
                    onClick={() => setStatusFilter(pill.id)}
                    className={cn(
                      "px-4 py-3 rounded-[2px] transition-all flex items-center justify-between group",
                      statusFilter === pill.id
                        ? "bg-[var(--surface)] border-l-2 border-[var(--brand)]"
                        : "hover:bg-[var(--surface)] border-l-2 border-transparent"
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <span style={{ color: statusFilter === pill.id ? 'var(--brand)' : 'var(--text-soft)' }}>{pill.icon}</span>
                      <span className={cn(
                        "text-[11px] font-[600]",
                        statusFilter === pill.id ? "text-[var(--text-primary)]" : "text-[var(--text-soft)]"
                      )}>{pill.label}</span>
                    </div>
                    <span className="text-[10px] font-[800] font-mono text-[var(--text-muted)]">{pill.count}</span>
                  </button>
                ))}

                {/* Divider */}
                <div className="h-px bg-[var(--border)] my-3.5" />

                <p className="px-3 mb-2 text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.advancedFiltersLabel}</p>

                <div className="flex flex-col gap-2 px-2.5">
                  {/* Période */}
                  <div>
                    <p className="text-[8px] font-[800] uppercase text-[var(--text-soft)] mb-1 ml-1">{t.routesTablePage.filterPeriodLabel}</p>
                    <FieldSelect
                      placeholder={t.routesTablePage.filterAllDates}
                      value={dateFilter}
                      onChange={(e) => setDateFilter(e.currentTarget.value || 'ALL')}
                      options={[
                        { value: 'ALL',       label: t.routesTablePage.filterAllDates },
                        { value: 'TODAY',     label: t.routesTablePage.filterToday },
                        { value: 'YESTERDAY', label: t.routesTablePage.filterYesterday },
                        { value: 'WEEK',      label: t.routesTablePage.filterWeek },
                      ]}
                      className="h-8 text-[10px]"
                    />
                  </div>

                  {/* Chauffeur */}
                  <div>
                    <p className="text-[8px] font-[800] uppercase text-[var(--text-soft)] mb-1 ml-1">{t.routesTablePage.filterDriverLabel}</p>
                    <FieldSelect
                      placeholder={t.routesTablePage.filterAllDrivers}
                      value={driverFilter}
                      onChange={(e) => setDriverFilter(e.currentTarget.value)}
                      options={[{ value: '', label: t.routesTablePage.filterAllDrivers }, ...drivers.map(d => ({ value: d.id, label: d.name }))]}
                      className="h-8 text-[10px]"
                    />
                  </div>

                  {/* Véhicule */}
                  <div>
                    <p className="text-[8px] font-[800] uppercase text-[var(--text-soft)] mb-1 ml-1">{t.routesTablePage.filterVehicleLabel}</p>
                    <FieldSelect
                      placeholder={t.routesTablePage.filterAllVehicles}
                      value={vehicleFilter}
                      onChange={(e) => setVehicleFilter(e.currentTarget.value)}
                      options={[{ value: '', label: t.routesTablePage.filterAllVehicles }, ...vehicles.map(v => ({ value: v.id, label: `${v.name} (${v.plate})` }))]}
                      className="h-8 text-[10px]"
                    />
                  </div>

                  {/* Dépôt */}
                  <div>
                    <p className="text-[8px] font-[800] uppercase text-[var(--text-soft)] mb-1 ml-1">{t.routesTablePage.filterDepotLabel}</p>
                    <FieldSelect
                      placeholder={t.routesTablePage.filterAllDepots}
                      value={depotFilter}
                      onChange={(e) => setDepotFilter(e.currentTarget.value)}
                      options={[{ value: '', label: t.routesTablePage.filterAllDepots }, ...depots.map(d => ({ value: d.id, label: d.name }))]}
                      className="h-8 text-[10px]"
                    />
                  </div>

                  {/* Zone */}
                  <div>
                    <p className="text-[8px] font-[800] uppercase text-[var(--text-soft)] mb-1 ml-1">{t.routesTablePage.filterZoneLabel}</p>
                    <FieldSelect
                      placeholder={t.routesTablePage.filterAllZones}
                      value={zoneFilter}
                      onChange={(e) => setZoneFilter(e.currentTarget.value)}
                      options={[{ value: '', label: t.routesTablePage.filterAllZones }, ...zoneOptions]}
                      className="h-8 text-[10px]"
                    />
                  </div>

                  {hasAdvancedFilters && (
                    <button
                      type="button"
                      className="mt-1 h-7 text-[11px] font-bold text-red-500 hover:bg-red-50 rounded transition-colors"
                      onClick={() => {
                        setDateFilter('ALL'); setDriverFilter('');
                        setVehicleFilter(''); setDepotFilter('');
                        setZoneFilter(''); setClientFilter('');
                      }}
                    >
                      {t.routesTablePage.clearFiltersButton}
                    </button>
                  )}
                </div>
              </div>
            </div>

            {/* Refresh footer */}
            <div className="p-5 border-t border-[var(--border)] bg-[var(--surface)] shrink-0">
              <button
                type="button"
                className="w-full font-bold text-[11px] text-[var(--text-soft)] flex items-center justify-center gap-2 h-8 rounded hover:bg-[var(--hover-bg)] transition-colors"
                onClick={() => fetchData()}
              >
                <IconRefresh size={14} className={loading ? 'animate-spin' : ''} />
                {t.routesTablePage.refreshButton}
              </button>
            </div>
          </div>

          {/* ── Main Slab Registro ────────────────────────── */}
          <div className={cn(
            'flex flex-col flex-1 bg-[var(--surface)] overflow-hidden min-w-0',
            mobileTab === 'list' ? 'flex' : 'hidden lg:flex'
          )}>
            <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
              <div className="min-w-[1000px] lg:min-w-0">
                {/* Table header */}
                <div className="sticky top-0 bg-[var(--surface)] z-10 grid grid-cols-[8px_200px_100px_1fr_150px_120px_110px_90px] gap-4 items-center h-[44px] px-0 border-b border-[var(--border)]">
                  <div className="w-[8px]" />
                  <span className="text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerRoute}</span>
                  <span className="text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerScheduled}</span>
                  <span className="text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerZoneDepot}</span>
                  <span className="text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerDriver}</span>
                  <span className="text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerStops}</span>
                  <span className="text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerStatus}</span>
                  <span className="text-[11px] font-[600] text-[var(--text-muted)] text-right pr-6">{t.routesTablePage.headerActions}</span>
                </div>

                {loading ? (
                  Array.from({ length: 15 }).map((_, i) => (
                    <div key={i} className="h-[52px] border-b border-[var(--border)] animate-pulse bg-[var(--surface)]/50" />
                  ))
                ) : grouped.length === 0 ? (
                  <EmptyState icon={<IconRoute size={32} />} message={t.empty.routes} />
                ) : (
                  grouped.map(([date, dateRoutes]) => (
                    <div key={date}>
                      <div className="py-0.5 px-1.5 bg-[var(--surface)] border-b border-[var(--border)]">
                        <div className="flex items-center gap-1.5">
                          <IconCalendar size={10} className="text-[var(--text-muted)]" />
                          <span className="text-[11px] font-[600] text-[var(--text-soft)]">{date}</span>
                          <span className="text-[10px] font-[600] text-[var(--text-muted)]">· {dateRoutes.length} {t.routesTablePage.movementLabel}{dateRoutes.length > 1 ? 's' : ''}</span>
                        </div>
                      </div>
                      {dateRoutes.map((route) => (
                        <RouteRow
                          key={route.id}
                          route={route}
                          driverName={drivers.find(d => d.id === route.driverId)?.name || ''}
                          depotName={depots.find(d => d.id === route.depotId)?.name || ''}
                          onCloseClick={setCloseTarget}
                        />
                      ))}
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>
        </div>

        <ConfirmModal
          open={closeTarget !== null}
          title={t.routesTablePage.closeRouteTitle}
          description={t.routesTablePage.closeRouteDescription.replace('{routeName}', closeTarget?.name || '')}
          variant="primary"
          confirmLabel={t.routesTablePage.closeRouteConfirm}
          cancelLabel={t.routesTablePage.closeRouteCancel}
          loading={closingRoute}
          onConfirm={handleCloseRoute}
          onCancel={() => setCloseTarget(null)}
        />
      </div>
    </TooltipProvider>
  );
}

export default function RoutesTablePage() {
  return (
    <Suspense fallback={
      <div className="flex items-center justify-center h-screen">
        <svg className="animate-spin h-10 w-10 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
        </svg>
      </div>
    }>
      <RoutesTablePageContent />
    </Suspense>
  );
}

