
import { useCallback, useEffect, useMemo, useState, Suspense } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { useT } from '@/lib/LocaleContext';
import {
  IconRefresh, IconPlus, IconCalendar, IconSearch, IconChevronDown,
  IconChevronRight, IconMapPin, IconUser, IconTruck, IconRoute,
  IconCar, IconLock, IconExternalLink, IconPackage, IconWeight, IconClock, IconX, IconAlertTriangle
} from '@tabler/icons-react';
import { showErrorToast } from '@/lib/toast-service';
import { api } from '@/lib/api';
import { useCloseRoute, useCancelRoute } from '@/hooks/useRoutes';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import type { Driver, DeliveryItem } from '@/types';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { EmptyState } from '@/components/feedback/EmptyState';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { cn } from '@/lib/utils';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { AddButton } from '@/components/ui/AddButton';
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

const STATUS_STYLE: Record<string, { color: string; ribbon: string }> = {
  DRAFT:       { color: 'gray',   ribbon: '#A1A1AA' },
  VALIDATED:   { color: 'blue',   ribbon: '#2563EB' },
  IN_PROGRESS: { color: 'orange', ribbon: 'var(--brand)' },
  CLOSED:      { color: 'teal',   ribbon: '#10B981' },
  CANCELLED:   { color: 'red',    ribbon: '#EF4444' },
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
  route, driverName, depotName, onCloseClick, onCancelClick,
}: {
  route:        EnrichedRoute;
  driverName:   string;
  depotName:    string;
  onCloseClick: (route: EnrichedRoute) => void;
  onCancelClick: (route: EnrichedRoute) => void;
}) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const router = useRouter();

  const total    = route.stops.length;
  const done     = route.completedStops;
  const pct      = total > 0 ? Math.round((done / total) * 100) : 0;
  const config   = STATUS_STYLE[route.status] || { color: 'gray', ribbon: '#A1A1AA' };
  const canClose = route.status === 'IN_PROGRESS';
  const canCancel = route.status === 'VALIDATED' || route.status === 'IN_PROGRESS';

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
          {canCancel && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--danger)] hover:bg-[var(--hover-bg)] transition-colors"
                  onClick={() => onCancelClick(route)}
                >
                  <IconX size={14} />
                </button>
              </TooltipTrigger>
              <TooltipContent>{t.routesTablePage.cancelRouteTooltip || 'Annuler la tournée'}</TooltipContent>
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

  // Cancel modal (reason required)
  const [cancelTarget, setCancelTarget] = useState<EnrichedRoute | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const cancelRouteMutation = useCancelRoute();
  const handleCancelRoute = useCallback(async () => {
    if (!cancelTarget || !cancelReason.trim()) return;
    try {
      await cancelRouteMutation.mutateAsync({ routeId: cancelTarget.id, reason: cancelReason.trim() });
      setCancelTarget(null);
      setCancelReason('');
      fetchData();
    } catch { /* toast handled in hook */ }
  }, [cancelTarget, cancelReason, cancelRouteMutation]);

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

  // ── PageFilterBar config ───────────────────────────────────────────────────
  const routeFilterAttributes = [
    { key: 'status', label: t.routesTablePage.filterStatusLabel, options: [
      { value: 'ALL',         label: t.routesTablePage.filterAllRoutes },
      { value: 'IN_PROGRESS', label: t.routesTablePage.filterInProgress },
      { value: 'VALIDATED',   label: t.routesTablePage.filterReady },
      { value: 'CLOSED',      label: t.routesTablePage.filterClosed },
    ]},
    { key: 'date', label: t.routesTablePage.filterPeriodLabel, options: [
      { value: 'TODAY',     label: t.routesTablePage.filterToday },
      { value: 'YESTERDAY', label: t.routesTablePage.filterYesterday },
      { value: 'WEEK',      label: t.routesTablePage.filterWeek },
    ]},
    { key: 'driver', label: t.routesTablePage.filterDriverLabel, options: drivers.map(d => ({ value: d.id, label: d.name })) },
    { key: 'vehicle', label: t.routesTablePage.filterVehicleLabel, options: vehicles.map(v => ({ value: v.id, label: `${v.name} (${v.plate})` })) },
    { key: 'depot', label: t.routesTablePage.filterDepotLabel, options: depots.map(d => ({ value: d.id, label: d.name })) },
    { key: 'zone', label: t.routesTablePage.filterZoneLabel, options: zoneOptions },
  ];
  const routeActiveFilters: Record<string, string> = {
    ...(statusFilter !== 'ALL' && { status: statusFilter }),
    ...(dateFilter   !== 'ALL' && { date: dateFilter }),
    ...(driverFilter  && { driver: driverFilter }),
    ...(vehicleFilter && { vehicle: vehicleFilter }),
    ...(depotFilter   && { depot: depotFilter }),
    ...(zoneFilter    && { zone: zoneFilter }),
  };
  const handleRouteFilterChange = (key: string, value: string | null) => {
    if (key === 'status')  setStatusFilter(value ?? 'ALL');
    if (key === 'date')    setDateFilter(value ?? 'ALL');
    if (key === 'driver')  setDriverFilter(value ?? '');
    if (key === 'vehicle') setVehicleFilter(value ?? '');
    if (key === 'depot')   setDepotFilter(value ?? '');
    if (key === 'zone')    setZoneFilter(value ?? '');
  };

  return (
    <TooltipProvider>
      <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
        <PageFilterBar
          search={clientFilter}
          onSearch={setClientFilter}
          searchPlaceholder={t.routesTablePage.searchPlaceholder}
          attributes={routeFilterAttributes}
          activeFilters={routeActiveFilters}
          onFilterChange={handleRouteFilterChange}
          onRefresh={fetchData}
          refreshing={loading}
          extraActions={
            <AddButton label={t.routesTablePage.newRouteButton} onClick={() => router('/route-builder')} />
          }
        />

        <div className="flex flex-1 min-h-0 overflow-hidden">
          {/* ── Main Slab Registro (full-width, sidebar removed) ── */}
          <div className="flex flex-col flex-1 overflow-hidden min-w-0" style={{ background: 'var(--surface)' }}>
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
                          onCancelClick={setCancelTarget}
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

        {/* Cancel route — reason required + consequences spelled out */}
        <AppModal
          open={cancelTarget !== null}
          onClose={() => { setCancelTarget(null); setCancelReason(''); }}
          title={t.routesTablePage.cancelRouteTitle || 'Annuler la tournée'}
          subtitle={cancelTarget?.name}
          footer={
            <div className="flex items-center justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={() => { setCancelTarget(null); setCancelReason(''); }}>
                {t.routesTablePage.cancelRouteBack || 'Retour'}
              </Button>
              <Button
                size="sm"
                className="bg-[var(--danger)] hover:bg-[var(--danger)]/90 text-white"
                disabled={!cancelReason.trim() || cancelRouteMutation.isPending}
                onClick={handleCancelRoute}
              >
                {cancelRouteMutation.isPending ? (t.routesTablePage.cancelRoutePending || 'Annulation…') : (t.routesTablePage.cancelRouteConfirm || 'Annuler la tournée')}
              </Button>
            </div>
          }
        >
          <div className="flex flex-col gap-4">
            {/* Consequences */}
            <div className="rounded-lg p-3" style={{ background: 'var(--danger-bg, rgba(199,55,47,0.08))', border: '1px solid rgba(199,55,47,0.25)' }}>
              <div className="flex items-center gap-2 mb-2">
                <IconAlertTriangle size={15} className="text-[var(--danger)]" />
                <p className="text-[12px] font-bold text-[var(--text-primary)]">{t.routesTablePage.cancelRouteWhatHappens || 'Ce qui va se passer'}</p>
              </div>
              <ul className="flex flex-col gap-1.5 text-[11.5px] leading-relaxed text-[var(--text-secondary,var(--text-primary))]">
                <li>• {t.routesTablePage.cancelConseqRepool || 'Les arrêts non livrés repassent en planification (les commandes ne sont PAS annulées).'}</li>
                <li>• {t.routesTablePage.cancelConseqTerminal || 'Les arrêts déjà livrés / échoués gardent leur résultat.'}</li>
                <li>• {t.routesTablePage.cancelConseqDriver || 'Le chauffeur est notifié et libéré pour une autre tournée.'}</li>
                <li>• {t.routesTablePage.cancelConseqStatus || 'La tournée passe en « Annulée » (conservée pour l’historique).'}</li>
                <li className="font-semibold text-[var(--danger)]">• {t.routesTablePage.cancelConseqIrreversible || 'Cette action est irréversible.'}</li>
              </ul>
            </div>

            {/* Reason (required) */}
            <div>
              <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1.5">
                {t.routesTablePage.cancelRouteReasonLabel || 'Motif d’annulation'} <span className="text-[var(--danger)]">*</span>
              </label>
              <textarea
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                rows={3}
                maxLength={500}
                placeholder={t.routesTablePage.cancelRouteReasonPlaceholder || 'Expliquez pourquoi cette tournée est annulée…'}
                className="w-full text-[12px] rounded-md px-3 py-2 outline-none focus:ring-1 focus:ring-[var(--brand)] resize-none"
                style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
              />
            </div>
          </div>
        </AppModal>
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

