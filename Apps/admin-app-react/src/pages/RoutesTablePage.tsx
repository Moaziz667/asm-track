
import { useCallback, useEffect, useMemo, useState, Suspense } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { useT } from '@/lib/LocaleContext';
import {
  IconRefresh, IconPlus, IconCalendar, IconSearch, IconChevronDown,
  IconChevronRight, IconMapPin, IconUser, IconTruck, IconRoute,
  IconCar, IconLock, IconExternalLink, IconPackage, IconWeight, IconClock, IconX, IconAlertTriangle,
  IconDots
} from '@tabler/icons-react';
import { showErrorToast } from '@/lib/toast-service';
import { api } from '@/lib/api';
import { useQuery } from '@tanstack/react-query';
import { useCloseRoute, useCancelRoute } from '@/hooks/useRoutes';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import type { Driver, DeliveryItem } from '@/types';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { EmptyState } from '@/components/feedback/EmptyState';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { cn } from '@/lib/utils';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { ExportCsvButton } from '@/components/layout/ExportCsvButton';
import { AddButton } from '@/components/ui/AddButton';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';
import StatusBadge from '@/components/StatusBadge';
import { useIsMobile } from '@/hooks/use-mobile';

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
  stopType?: 'PICKUP' | 'DELIVERY';
  sourceDepotName?: string;
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
  /** Stop count from the paged list summary (collapsed row shows this without fetching /full). */
  totalStops?: number;
  city?: string;
};

/** Maps one stop from the route `/full` payload to the flat DeliveryDetail the expand row renders. */
function mapFullStop(s: any): DeliveryDetail {
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
    stopType:      s.stopType,
    sourceDepotName: s.sourceDepotName ?? s.depotName ?? '',
  };
}

/** Date-filter pill → {from,to} server params (Africa/Tunis-agnostic ISO day strings). */
function dateRangeFor(filter: string): { from?: string; to?: string } {
  const iso = (d: Date) => d.toISOString().split('T')[0];
  const today = new Date();
  if (filter === 'TODAY') return { from: iso(today), to: iso(today) };
  if (filter === 'YESTERDAY') { const y = new Date(today); y.setDate(today.getDate() - 1); return { from: iso(y), to: iso(y) }; }
  if (filter === 'WEEK') { const w = new Date(today); w.setDate(today.getDate() - 7); return { from: iso(w), to: iso(today) }; }
  return {};
}

/**
 * Lazy-loads a route's stop detail from /full only once its row is expanded — replaces the old
 * fetch-/full-for-every-route fan-out. Collapsed rows render from the list summary (counts) alone.
 */
function useRouteStops(route: EnrichedRoute, expanded: boolean) {
  const [stops, setStops] = useState<DeliveryDetail[]>(route.stops ?? []);
  const [loaded, setLoaded] = useState((route.stops ?? []).length > 0);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!expanded || loaded || loading) return;
    setLoading(true);
    api.get(`/api/admin/routes/${route.id}/full`)
      .then((res) => {
        const data = res.data ?? {};
        const raw = Array.isArray(data.stops) ? data.stops.map(mapFullStop) : [];
        const legacy = Array.isArray(data.legacyStops) ? data.legacyStops.map(mapFullStop) : [];
        setStops(raw.length > 0 ? raw : legacy);
        setLoaded(true);
      })
      .catch(() => setLoaded(true))
      .finally(() => setLoading(false));
  }, [expanded, loaded, loading, route.id]);
  return { stops, loadingStops: loading };
}

type VehicleItem = { id: string; name: string; plate: string };
type DepotItem   = { id: string; name: string; latitude: number; longitude: number };

type EnrichedRoute = RouteItem & {
  zoneLabel:      string;
  clientNames:    string[];
  completedStops: number;
  failedStops:    number;
  partialStops:   number;
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
      <span className="text-2xs font-[700] text-[var(--text-muted)]">{index + 1}</span>

      <div className="flex flex-col gap-0">
        <span className="text-xs font-[600] text-[var(--text-primary)] truncate">
          {stop.stopType === 'PICKUP' ? t.routesTablePage.pickupLoad : (stop.clientName || '—')}
        </span>
        <span className="text-xs font-[700] font-mono text-[var(--text-muted)]">
          {stop.stopType === 'PICKUP' ? t.routesTablePage.pickupHint : `#${shortId}`}
        </span>
      </div>

      <div className="flex items-center gap-1 flex-nowrap">
        <IconMapPin size={10} className="text-[var(--text-muted)] shrink-0" />
        <span className="text-2xs font-[500] text-[var(--text-soft)] truncate">
          {stop.stopType === 'PICKUP'
            ? (stop.sourceDepotName || '—')
            : ([stop.dropoffAddress, stop.dropoffCity]
                .filter(Boolean)
                .join(', ')
                .replace('Address not provided', t.routesTablePage.addressNotProvided) || '—')}
        </span>
      </div>

      <div>
        {odooRef && (
          <div className="flex items-center gap-1">
            <span className="text-xs font-[600] text-[var(--brand)] tracking-tighter">ERP</span>
            <span className="text-2xs font-[700] font-mono text-[var(--text-primary)]">{odooRef}</span>
          </div>
        )}
      </div>

      <span className="text-2xs font-[600] text-[var(--text-soft)] text-right tabular-nums">
        {stop.totalWeightKg != null ? `${stop.totalWeightKg.toFixed(1)}kg` : '—'}
      </span>

      <div className="text-right">
        <StatusBadge status={stop.status || 'PENDING'} size="sm" />
      </div>
    </div>
  );
}

function RouteRow({
  route, driverName, driverOnline, depotName, onCloseClick, onCancelClick,
}: {
  route:        EnrichedRoute;
  driverName:   string;
  driverOnline: boolean;
  depotName:    string;
  onCloseClick: (route: EnrichedRoute) => void;
  onCancelClick: (route: EnrichedRoute) => void;
}) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const router = useRouter();

  const { stops, loadingStops } = useRouteStops(route, expanded);
  const total    = route.totalStops ?? stops.length;
  // Progression counts every terminal stop as done — delivered, partial AND failed (échec) —
  // matching the per-route page's progress bar so an all-attempted route reads 100%.
  const done     = (route.completedStops ?? 0) + (route.failedStops ?? 0) + (route.partialStops ?? 0);
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
          <span className="text-xs font-[700] text-[var(--text-primary)] tracking-tight">{route.name}</span>
        </div>

        {/* Date */}
        <div className="flex items-center gap-1">
          <IconCalendar size={12} className="text-[var(--text-muted)]" />
          <span className="text-xs font-[600] text-[var(--text-soft)]">{route.date}</span>
        </div>

        {/* Zone & Depot */}
        <div className="flex items-center gap-2">
          <span className="text-2xs font-semibold px-1.5 py-0.5 rounded border border-[var(--border)] bg-[var(--surface)] text-[var(--text-primary)] uppercase tracking-widest font-bold">
            {route.zoneLabel || 'Zone ?'}
          </span>
          <span className="text-2xs font-[600] text-[var(--text-muted)] truncate italic">{depotName}</span>
        </div>

        {/* Chauffeur */}
        <div className="flex items-center gap-1.5">
          <div
            className={cn('w-1.5 h-1.5 rounded-full shrink-0', driverName && driverOnline ? 'bg-emerald-500' : 'bg-gray-400')}
            title={driverName ? (driverOnline ? (t.driversPage.statusOnline ?? 'En ligne') : (t.driversPage.statusOffline ?? 'Hors ligne')) : undefined}
          />
          <span className="text-xs font-[600] text-[var(--text-primary)] truncate max-w-[120px]">{driverName || t.routesTablePage.notAssigned}</span>
        </div>

        {/* Progression */}
        <div className="flex flex-col gap-1 w-[100px]">
          <div className="flex items-center justify-between gap-1">
            <span className="text-xs font-[700] font-mono text-[var(--text-soft)]">{done}/{total}</span>
            <span className="text-xs font-[700] text-[var(--text-muted)]">{pct}%</span>
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
        <div className="flex items-center gap-1.5 flex-nowrap justify-end pr-4" onClick={(e) => e.stopPropagation()}>
          <Tooltip>
            <TooltipTrigger render={
              <button
                type="button"
                aria-label={t.routesTablePage.viewRouteDetails || 'Détails de la tournée'}
                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)] transition-colors"
                onClick={() => router(`/routes/${route.id}`)}
              />
            }>
              <IconExternalLink size={14} />
            </TooltipTrigger>
            <TooltipContent>{t.routesTablePage.viewRouteDetails || 'Détails de la tournée'}</TooltipContent>
          </Tooltip>
          
          {(canClose || canCancel) && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)] transition-colors cursor-pointer"
                >
                  <IconDots size={14} />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-48 bg-[var(--surface)] border border-[var(--border)] shadow-lg rounded-md p-1 z-50">
                {canClose && (
                  <DropdownMenuItem
                    onClick={() => onCloseClick(route)}
                    className="text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5"
                  >
                    <IconLock size={13} /> {t.routesTablePage.closeRouteTooltip || 'Fermer la tournée'}
                  </DropdownMenuItem>
                )}
                {canCancel && (
                  <DropdownMenuItem
                    onClick={() => onCancelClick(route)}
                    className="text-xs font-semibold text-[var(--danger)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5"
                  >
                    <IconX size={13} /> {t.routesTablePage.cancelRouteTooltip || 'Annuler la tournée'}
                  </DropdownMenuItem>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          )}

          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)] transition-colors"
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
              <span key={h} className="text-xs font-[600] text-[var(--text-muted)]">{h}</span>
            ))}
          </div>
          {loadingStops ? (
            <div className="py-5 text-center italic text-[var(--text-muted)] text-xs">…</div>
          ) : stops.length === 0 ? (
            <div className="py-5 text-center italic text-[var(--text-muted)] text-xs">{t.routesTablePage.noActiveStops}</div>
          ) : (
            stops
              .sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0))
              .map((stop, i) => <StopDetailRow key={stop.id || stop.deliveryId} stop={stop} index={i} />)
          )}
        </div>
      )}
    </div>
  );
}

function RouteMobileCard({
  route, driverName, driverOnline, depotName, onCloseClick, onCancelClick,
}: {
  route:        EnrichedRoute;
  driverName:   string;
  driverOnline: boolean;
  depotName:    string;
  onCloseClick: (route: EnrichedRoute) => void;
  onCancelClick: (route: EnrichedRoute) => void;
}) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const router = useRouter();

  const { stops, loadingStops } = useRouteStops(route, expanded);
  const total    = route.totalStops ?? stops.length;
  // Progression counts every terminal stop as done — delivered, partial AND failed (échec) —
  // matching the per-route page's progress bar so an all-attempted route reads 100%.
  const done     = (route.completedStops ?? 0) + (route.failedStops ?? 0) + (route.partialStops ?? 0);
  const pct      = total > 0 ? Math.round((done / total) * 100) : 0;
  const config   = STATUS_STYLE[route.status] || { color: 'gray', ribbon: '#A1A1AA' };
  const canClose = route.status === 'IN_PROGRESS';
  const canCancel = route.status === 'VALIDATED' || route.status === 'IN_PROGRESS';

  return (
    <div className="p-4 rounded-md border border-[var(--border)] bg-[var(--surface)] hover:border-[var(--border-strong)] transition-all flex flex-col gap-3 shadow-xs relative">
      {/* Ribbon */}
      <div className="absolute left-0 top-0 bottom-0 w-[4px] rounded-l-md" style={{ backgroundColor: config.ribbon }} />

      {/* Header Row: Route Name & ID & Status */}
      <div className="flex items-start justify-between min-w-0 ps-1">
        <div className="flex flex-col min-w-0">
          <span className="text-sm font-bold text-[var(--text-primary)] truncate">{route.name}</span>
          <span className="text-3xs font-[600] text-[var(--text-muted)] font-mono">ID: {route.id?.slice(0, 8) ?? 'N/A'}</span>
        </div>
        <StatusBadge status={route.status} size="sm" />
      </div>

      {/* Date, Zone & Depot Row */}
      <div className="flex flex-wrap items-center gap-2 text-2xs ps-1 text-[var(--text-soft)]">
        <div className="flex items-center gap-1">
          <IconCalendar size={11} className="text-[var(--text-muted)] shrink-0" />
          <span className="font-[600]">{route.date}</span>
        </div>
        <span className="text-3xs font-semibold px-1.5 py-0.5 rounded border border-[var(--border)] bg-[var(--surface-sunken)] text-[var(--text-primary)] uppercase tracking-wider font-bold">
          {route.zoneLabel || 'Zone ?'}
        </span>
        {depotName && (
          <span className="text-2xs text-[var(--text-muted)] truncate max-w-[150px] italic">
            {depotName}
          </span>
        )}
      </div>

      {/* Driver & Progression Row */}
      <div className="flex items-center justify-between border-t border-[var(--border)] pt-2.5 ps-1">
        <div className="flex items-center gap-1.5 min-w-0">
          <div
            className={cn('w-1.5 h-1.5 rounded-full shrink-0', driverName && driverOnline ? 'bg-emerald-500' : 'bg-gray-400')}
            title={driverName ? (driverOnline ? (t.driversPage.statusOnline ?? 'En ligne') : (t.driversPage.statusOffline ?? 'Hors ligne')) : undefined}
          />
          <span className="text-2xs font-[600] text-[var(--text-primary)] truncate max-w-[150px]">
            {driverName || t.routesTablePage.notAssigned}
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span className="text-2xs font-[700] font-mono text-[var(--text-soft)]">{done}/{total}</span>
          <span className="text-2xs font-[700] text-[var(--text-muted)]">{pct}%</span>
        </div>
      </div>

      {/* Progress Bar */}
      <div className="h-[3px] w-full bg-[var(--surface-sunken)] rounded overflow-hidden ps-1">
        <div
          className={cn(
            "h-full rounded transition-all",
            route.status === 'CLOSED' ? "bg-emerald-500" : "bg-[var(--brand)]"
          )}
          style={{ width: `${pct}%` }}
        />
      </div>

      {/* Actions Strip */}
      <div className="flex items-center gap-1.5 justify-end border-t border-[var(--border)] pt-2.5" onClick={(e) => e.stopPropagation()}>
        {canClose && (
          <Tooltip>
            <TooltipTrigger render={
              <button
                type="button"
                aria-label={t.routesTablePage.closeRouteTooltip}
                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--brand)] hover:bg-[var(--hover-bg)]"
                onClick={() => onCloseClick(route)}
              />
            }>
              <IconLock size={13} />
            </TooltipTrigger>
            <TooltipContent>{t.routesTablePage.closeRouteTooltip}</TooltipContent>
          </Tooltip>
        )}
        {canCancel && (
          <Tooltip>
            <TooltipTrigger render={
              <button
                type="button"
                aria-label={t.routesTablePage.cancelRouteTooltip || 'Annuler la tournée'}
                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--danger)] hover:bg-[var(--hover-bg)]"
                onClick={() => onCancelClick(route)}
              />
            }>
              <IconX size={13} />
            </TooltipTrigger>
            <TooltipContent>{t.routesTablePage.cancelRouteTooltip || 'Annuler la tournée'}</TooltipContent>
          </Tooltip>
        )}
        <Tooltip>
          <TooltipTrigger render={
            <button
              type="button"
              aria-label={t.routesTablePage.viewRouteDetails || 'Ouvrir les détails'}
              className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]"
              onClick={() => router(`/routes/${route.id}`)}
            />
          }>
            <IconExternalLink size={13} />
          </TooltipTrigger>
          <TooltipContent>{t.routesTablePage.viewRouteDetails || 'Ouvrir les détails'}</TooltipContent>
        </Tooltip>
        <button
          type="button"
          className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]"
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? <IconChevronDown size={13} /> : <IconChevronRight size={13} />}
        </button>
      </div>

      {/* Expanded Stops Timeline */}
      {expanded && (
        <div className="bg-[var(--surface-sunken)] border-t border-[var(--border)] mt-2 -mx-4 -mb-4 p-3 rounded-b-md flex flex-col gap-2">
          <span className="text-3xs uppercase font-bold text-[var(--text-muted)] tracking-wider">Arrêts de la tournée</span>
          {loadingStops ? (
            <div className="py-3 text-center italic text-[var(--text-muted)] text-xs">…</div>
          ) : stops.length === 0 ? (
            <div className="py-3 text-center italic text-[var(--text-muted)] text-xs">{t.routesTablePage.noActiveStops}</div>
          ) : (
            stops
              .sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0))
              .map((stop, i) => {
                const isDone = stop.status ? DONE_STATUSES.has(stop.status) : false;
                const odooRef = stop.erpId || null;
                const shortId = (stop.orderId || stop.deliveryId || '').replace(/-/g, '').slice(0, 8).toUpperCase();
                return (
                  <div key={stop.id || stop.deliveryId} className={cn(
                    "flex flex-col gap-1.5 p-2.5 rounded border border-[var(--border)] bg-[var(--surface)]",
                    isDone ? "opacity-60" : ""
                  )}>
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5">
                        <span className="w-4 h-4 rounded-full bg-[var(--surface-sunken)] flex items-center justify-center text-2xs font-bold text-[var(--text-muted)]">{i + 1}</span>
                        <span className="text-xs font-bold text-[var(--text-primary)]">
                          {stop.stopType === 'PICKUP' ? t.routesTablePage.pickupLoad : (stop.clientName || '—')}
                        </span>
                      </div>
                      <StatusBadge status={stop.status || 'PENDING'} size="sm" />
                    </div>
                    <div className="flex items-center gap-1 text-2xs text-[var(--text-soft)]">
                      <IconMapPin size={10} className="text-[var(--text-muted)] shrink-0" />
                      <span className="truncate">
                        {stop.stopType === 'PICKUP'
                          ? (stop.sourceDepotName || '—')
                          : ([stop.dropoffAddress, stop.dropoffCity]
                              .filter(Boolean)
                              .join(', ')
                              .replace('Address not provided', t.routesTablePage.addressNotProvided) || '—')}
                      </span>
                    </div>
                    <div className="flex items-center justify-between gap-2 border-t border-[var(--border)] pt-1.5">
                      <span className="text-3xs font-mono font-semibold text-[var(--text-muted)]">{stop.stopType === 'PICKUP' ? t.routesTablePage.pickupHint : `#${shortId}`}</span>
                      <div className="flex items-center gap-2">
                        {odooRef && <span className="text-3xs font-mono text-[var(--brand)]">ERP: {odooRef}</span>}
                        {stop.totalWeightKg != null && <span className="text-3xs font-mono font-bold text-[var(--text-muted)]">{stop.totalWeightKg.toFixed(1)}kg</span>}
                      </div>
                    </div>
                  </div>
                );
              })
          )}
        </div>
      )}
    </div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────

function RoutesTablePageContent() {
  const isMobile = useIsMobile();
  const t = useT();
  usePageBreadcrumb([{ label: t.pages.routes?.title || 'Tournées' }]);
  const router = useRouter();

  useEffect(() => {
    const role = getCurrentRole();
    if (role !== 'UNKNOWN' && !canDispatch(role)) router('/dashboard', { replace: true });
  }, [router]);

  // ── Filters + pagination (all server-side; the list endpoint is paginated + filtered) ──
  const [statusFilter,  setStatusFilter]  = useState('ALL');
  const [dateFilter,    setDateFilter]    = useState('ALL');
  const [zoneFilter,    setZoneFilter]    = useState('');
  const [driverFilter,  setDriverFilter]  = useState('');
  const [vehicleFilter, setVehicleFilter] = useState('');
  const [depotFilter,   setDepotFilter]   = useState('');
  const [clientFilter,  setClientFilter]  = useState('');
  const [page, setPage] = useState(0);
  const PAGE_SIZE = 25;
  // Debounce the client/route search → server `q`; reset to the first page on a new search.
  const [debouncedClient, setDebouncedClient] = useState('');
  useEffect(() => {
    const id = setTimeout(() => { setDebouncedClient(clientFilter); setPage(0); }, 300);
    return () => clearTimeout(id);
  }, [clientFilter]);

  // Routes table data via React Query — server-side paginated + filtered. Collapsed rows render from
  // the list summary (counts/zone); the rich per-stop detail is lazy-loaded via /full on row-expand.
  const routeRange = dateRangeFor(dateFilter);
  const { data: routesData, isLoading: loading, refetch: fetchData } = useQuery({
    queryKey: ['routes-table', page, statusFilter, dateFilter, driverFilter, vehicleFilter, depotFilter, zoneFilter, debouncedClient],
    staleTime: 30_000,
    queryFn: async () => {
      const isManager = getCurrentRole() === 'MANAGER';
      const params: Record<string, string | number> = { page, size: PAGE_SIZE };
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (driverFilter)  params.driverId = driverFilter;
      if (vehicleFilter) params.vehicleId = vehicleFilter;
      if (depotFilter)   params.depotId = depotFilter;
      if (zoneFilter)    params.city = zoneFilter; // zone label maps to the route's operational city
      if (routeRange.from) params.from = routeRange.from;
      if (routeRange.to)   params.to = routeRange.to;
      if (debouncedClient.trim()) params.q = debouncedClient.trim();
      try {
        const [routesRes, driversRes, vehiclesRes, depotsRes] = await Promise.all([
          api.get('/api/admin/routes/page', { params }),
          isManager ? Promise.resolve({ data: [] }) : api.get('/api/admin/fleet/drivers'),
          isManager ? Promise.resolve({ data: [] }) : api.get('/api/admin/vehicles'),
          isManager ? Promise.resolve({ data: [] }) : api.get('/api/v1/depots/active').catch(() => ({ data: [] })),
        ]);

        const pageData = routesRes.data ?? {};
        const content: any[] = Array.isArray(pageData.content) ? pageData.content : [];
        const enriched: EnrichedRoute[] = content.map((r) => ({
          ...r,
          stops: [],                                  // lazy-loaded on expand (kills the old /full fan-out)
          totalStops: r.totalStops ?? 0,
          completedStops: r.completedStops ?? 0,
          failedStops: r.failedStops ?? 0,
          partialStops: r.partialStops ?? 0,
          zoneLabel: r.detectedZoneLabel ?? r.city ?? '',
          clientNames: [],
        }));

        return {
          routes: enriched,
          totalPages: Math.max(1, Number(pageData.totalPages ?? 1)),
          totalElements: Number(pageData.totalElements ?? content.length),
          drivers: (Array.isArray(driversRes.data) ? driversRes.data : []) as Driver[],
          vehicles: (Array.isArray(vehiclesRes.data) ? vehiclesRes.data : []) as VehicleItem[],
          depots: (Array.isArray(depotsRes.data) ? depotsRes.data : []) as DepotItem[],
        };
      } catch {
        showErrorToast(null, t.routesTablePage.loadError);
        return { routes: [] as EnrichedRoute[], totalPages: 1, totalElements: 0, drivers: [] as Driver[], vehicles: [] as VehicleItem[], depots: [] as DepotItem[] };
      }
    },
  });
  const routes = routesData?.routes ?? [];
  const totalPages = routesData?.totalPages ?? 1;
  const totalElements = routesData?.totalElements ?? 0;
  const drivers = routesData?.drivers ?? [];
  const vehicles = routesData?.vehicles ?? [];
  const depots = routesData?.depots ?? [];

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

  // Filtering happens server-side (status/date/driver/vehicle/depot/zone/q), so the loaded page is
  // already the filtered result — no client-side re-filtering (which would only filter one page).
  const filteredRoutes = routes;

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
    setPage(0); // any filter change resets to the first page of the server-side result
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
            <div className="flex items-center gap-1.5">
              <ExportCsvButton
                baseName="tournees"
                rows={filteredRoutes}
                columns={[
                  { header: 'Tournée', accessor: r => r.name },
                  { header: 'Chauffeur', accessor: r => drivers.find(d => d.id === r.driverId)?.name ?? '' },
                  { header: 'Arrêts', accessor: r => r.totalStops ?? r.stops?.length ?? 0 },
                  { header: 'Statut', accessor: r => r.status },
                  { header: 'Zone', accessor: r => (r as { zoneLabel?: string }).zoneLabel },
                  { header: 'Date', accessor: r => r.date },
                ]}
              />
              <AddButton label={t.routesTablePage.newRouteButton} onClick={() => router('/route-builder')} />
            </div>
          }
        />

        <div className="flex flex-1 min-h-0 overflow-hidden">
          {/* ── Main Slab Registro (full-width, sidebar removed) ── */}
          <div className="flex flex-col flex-1 overflow-hidden min-w-0" style={{ background: 'var(--surface)' }}>
            <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
              {isMobile ? (
                <div className="flex flex-col gap-4 p-4">
                  {loading ? (
                    Array.from({ length: 5 }).map((_, i) => (
                      <div key={i} className="h-32 border border-[var(--border)] rounded-md animate-pulse bg-[var(--surface)]/50" />
                    ))
                  ) : grouped.length === 0 ? (
                    <EmptyState icon={<IconRoute size={32} />} message={t.empty.routes} />
                  ) : (
                    grouped.map(([date, dateRoutes]) => (
                      <div key={date} className="flex flex-col gap-3">
                        <div className="py-1 px-1 flex items-center gap-1.5 border-b border-[var(--border)]">
                          <IconCalendar size={12} className="text-[var(--text-muted)]" />
                          <span className="text-xs font-[600] text-[var(--text-soft)]">{date}</span>
                          <span className="text-2xs font-[600] text-[var(--text-muted)]">· {dateRoutes.length} {t.routesTablePage.movementLabel}{dateRoutes.length > 1 ? 's' : ''}</span>
                        </div>
                        {dateRoutes.map((route) => (
                          <RouteMobileCard
                            key={route.id}
                            route={route}
                            driverName={drivers.find(d => d.id === route.driverId)?.name || ''}
                            driverOnline={drivers.find(d => d.id === route.driverId)?.onlineStatus === 'ONLINE'}
                            depotName={depots.find(d => d.id === route.depotId)?.name || ''}
                            onCloseClick={setCloseTarget}
                            onCancelClick={setCancelTarget}
                          />
                        ))}
                      </div>
                    ))
                  )}
                </div>
              ) : (
                <div className="min-w-[1000px] lg:min-w-0">
                  {/* Table header */}
                  <div className="sticky top-0 bg-[var(--surface)] z-10 grid grid-cols-[8px_200px_100px_1fr_150px_120px_110px_90px] gap-4 items-center h-[44px] px-0 border-b border-[var(--border)]">
                    <div className="w-[8px]" />
                    <span className="text-xs font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerRoute}</span>
                    <span className="text-xs font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerScheduled}</span>
                    <span className="text-xs font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerZoneDepot}</span>
                    <span className="text-xs font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerDriver}</span>
                    <span className="text-xs font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerStops}</span>
                    <span className="text-xs font-[600] text-[var(--text-muted)]">{t.routesTablePage.headerStatus}</span>
                    <span className="text-xs font-[600] text-[var(--text-muted)] text-right pr-6">{t.routesTablePage.headerActions}</span>
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
                            <span className="text-xs font-[600] text-[var(--text-soft)]">{date}</span>
                            <span className="text-2xs font-[600] text-[var(--text-muted)]">· {dateRoutes.length} {t.routesTablePage.movementLabel}{dateRoutes.length > 1 ? 's' : ''}</span>
                          </div>
                        </div>
                        {dateRoutes.map((route) => (
                          <RouteRow
                            key={route.id}
                            route={route}
                            driverName={drivers.find(d => d.id === route.driverId)?.name || ''}
                            driverOnline={drivers.find(d => d.id === route.driverId)?.onlineStatus === 'ONLINE'}
                            depotName={depots.find(d => d.id === route.depotId)?.name || ''}
                            onCloseClick={setCloseTarget}
                            onCancelClick={setCancelTarget}
                          />
                        ))}
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>

            {/* Pagination footer — server-side paged */}
            {(totalPages > 1 || page > 0) && (
              <div className="flex items-center justify-between px-4 py-2.5 border-t border-[var(--border)] shrink-0" style={{ background: 'var(--surface)' }}>
                <span className="text-xs text-[var(--text-muted)]">
                  {(t.deliveriesPage?.pageLabel ?? 'Page')} {page + 1} / {totalPages} · {totalElements}
                </span>
                <div className="flex items-center gap-1.5">
                  <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} className="h-7 px-3 text-xs font-[700]">
                    {t.deliveriesPage?.prevButton ?? 'Précédent'}
                  </Button>
                  <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)} className="h-7 px-3 text-xs font-[700]">
                    {t.deliveriesPage?.nextButton ?? 'Suivant'}
                  </Button>
                </div>
              </div>
            )}
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
                <p className="text-sm font-bold text-[var(--text-primary)]">{t.routesTablePage.cancelRouteWhatHappens || 'Ce qui va se passer'}</p>
              </div>
              <ul className="flex flex-col gap-1.5 text-xs leading-relaxed text-[var(--text-secondary,var(--text-primary))]">
                <li>• {t.routesTablePage.cancelConseqRepool || 'Les arrêts non livrés repassent en planification (les commandes ne sont PAS annulées).'}</li>
                <li>• {t.routesTablePage.cancelConseqTerminal || 'Les arrêts déjà livrés / échoués gardent leur résultat.'}</li>
                <li>• {t.routesTablePage.cancelConseqDriver || 'Le chauffeur est notifié et libéré pour une autre tournée.'}</li>
                <li>• {t.routesTablePage.cancelConseqStatus || 'La tournée passe en « Annulée » (conservée pour l’historique).'}</li>
                <li className="font-semibold text-[var(--danger)]">• {t.routesTablePage.cancelConseqIrreversible || 'Cette action est irréversible.'}</li>
              </ul>
            </div>

            {/* Reason (required) */}
            <div>
              <label className="block text-xs font-semibold text-[var(--text-muted)] mb-1.5">
                {t.routesTablePage.cancelRouteReasonLabel || 'Motif d’annulation'} <span className="text-[var(--danger)]">*</span>
              </label>
              <textarea
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                rows={3}
                maxLength={500}
                placeholder={t.routesTablePage.cancelRouteReasonPlaceholder || 'Expliquez pourquoi cette tournée est annulée…'}
                className="w-full text-sm rounded-md px-3 py-2 outline-none focus:ring-1 focus:ring-[var(--brand)] resize-none"
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

