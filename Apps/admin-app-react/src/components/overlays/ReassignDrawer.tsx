

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  IconArrowLeft,
  IconCalendar,
  IconCheck,
  IconChevronRight,
  IconChevronDown,
  IconClock,
  IconMapPin,
  IconUser,
  IconX,
  IconAdjustments,
  IconBolt,
  IconNote,
} from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { FieldInput, FieldTextarea } from '@/components/ui/field';
import { AppDrawer } from './AppDrawer';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { DRIVER_STATUS_COLOR } from '@/lib/design-tokens';
import { cn, formatMoney } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { Driver } from '@/types';

// ── Types ──────────────────────────────────────────────────────────────────────

interface RouteStop {
  id: string;
  stopOrder: number;
  clientName?: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  status: string;
  etaAt?: string;
  startTimeWindow?: string;
  endTimeWindow?: string;
  stopType?: string;   // DELIVERY | PICKUP — pickups are depot loads, not insertion positions
}

interface RouteOption {
  id: string;
  name: string;
  date: string;
  status: 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED';
  driverName?: string;
  stops: RouteStop[];
  payloadKg?: number;
  currentLoadKg?: number;
}

export interface ReassignTarget {
  deliveryId: string;
  orderRef?: string;
  erpOrderId?: string;
  clientName?: string;
  city?: string;
  status: string;
  driverName?: string;
  routeId?: string;
  routeName?: string;
  routeStatus?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  // Existing delivery time slot (the client's créneau) — used to pre-fill the window picker.
  timeSlotStartTime?: string;
  timeSlotEndTime?: string;
  // Fallbacks when there's no HH:mm slot: a named slot, or the requested delivery date.
  timeSlotName?: string;
  requestedDeliveryDate?: string;
  // Decision context shown in the drawer recap (#4).
  totalWeightKg?: number;
  totalAmount?: number;
  currency?: string;
  itemsCount?: number;
  priority?: string;
  scheduledAt?: string;
  dropoffAddress?: string;
}

interface Props {
  open: boolean;
  target: ReassignTarget | null;
  targets?: ReassignTarget[];
  drivers: Driver[];
  onClose: () => void;
  onSuccess: (routeId?: string) => void;
}

// ── Helpers ────────────────────────────────────────────────────────────────────

const ROUTE_STATUS_COLOR: Record<string, string> = {
  DRAFT: '#6366F1',
  VALIDATED: '#10B981',
  IN_PROGRESS: '#F08734',
  CLOSED: '#71717A',
  CANCELLED: '#EF4444',
};

const STOP_STATUS_DONE = new Set(['COMPLETED', 'FAILED', 'PARTIAL', 'REMOVED_CANCELLED', 'REMOVED_REPLANNED']);

function formatTime(t?: string) {
  if (!t) return null;
  return t.slice(0, 5);
}

function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

interface NearestInfo { etaSeconds: number | null; distanceMeters: number | null; source: string; rank: number; }

function formatEta(seconds?: number | null): string | null {
  if (seconds == null) return null;
  const m = Math.round(seconds / 60);
  if (m < 1) return '<1 min';
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}`;
}
function formatKm(meters?: number | null): string | null {
  if (meters == null) return null;
  return meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(1)} km`;
}
// GPS-fix recency shown as text (no status dots — see .ai/anti-slop.md). fresh ≤ 5 min.
function formatSeen(iso?: string | null): { text: string; fresh: boolean } | null {
  if (!iso) return null;
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (mins < 1) return { text: 'MAJ à l’instant', fresh: true };
  if (mins < 60) return { text: `${mins <= 5 ? 'MAJ' : 'GPS'} ${mins} min`, fresh: mins <= 5 };
  const h = Math.floor(mins / 60);
  return { text: `GPS ${h} h`, fresh: false };
}

function groupByDate(routes: RouteOption[]) {
  const map = new Map<string, RouteOption[]>();
  for (const r of routes) {
    const list = map.get(r.date) ?? [];
    list.push(r);
    map.set(r.date, list);
  }
  return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
}

// ── Sub-components ─────────────────────────────────────────────────────────────

function StepIndicator({ step }: { step: 1 | 2 | 3 }) {
  const t = useT();
  const steps = [t.reassignDrawer.stepDriver, t.reassignDrawer.stepRoute, t.reassignDrawer.stepConfigure];
  return (
    <div className="flex items-center gap-0 mb-4 pt-2">
      {steps.map((label, i) => {
        const idx = i + 1;
        const done = idx < step;
        const active = idx === step;
        return (
          <div key={label} className="flex items-center gap-0">
            <div className="flex items-center gap-1.5">
              <div
                className="w-5 h-5 rounded-full flex items-center justify-center shrink-0"
                style={{ background: done || active ? 'var(--brand)' : 'var(--text-muted)' }}
              >
                {done
                  ? <IconCheck size={11} color="white" stroke={2.5} />
                  : <span className="text-2xs font-bold text-white">{idx}</span>
                }
              </div>
              <span className={cn(
                'text-xs font-medium',
                active ? 'font-semibold text-[var(--text-primary)]' : done ? 'text-[var(--text-muted)]' : 'text-[var(--text-muted)]',
              )}>
                {label}
              </span>
            </div>
            {i < steps.length - 1 && (
              <div className="w-5 h-px mx-2 shrink-0" style={{ background: 'var(--border)' }} />
            )}
          </div>
        );
      })}
    </div>
  );
}

function MetaChip({ children, danger }: { children: ReactNode; danger?: boolean }) {
  return (
    <span
      className="text-2xs font-[600] px-1.5 py-0.5 rounded-xs"
      style={{
        background: danger ? 'var(--danger-bg)' : 'var(--hover-bg)',
        color: danger ? 'var(--danger)' : 'var(--text-muted)',
      }}
    >
      {children}
    </span>
  );
}

function AdvChip({ icon, label }: { icon: ReactNode; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-2xs font-medium px-2 py-1 rounded-md text-[var(--text-secondary)]" style={{ background: 'var(--surface-sunken)' }}>
      {icon}{label}
    </span>
  );
}

// ── Main Component ─────────────────────────────────────────────────────────────

export function ReassignDrawer({ open, target, targets, drivers, onClose, onSuccess }: Props) {
  const t = useT();
  const ROUTE_STATUS_LABEL: Record<string, string> = {
    DRAFT: t.reassignDrawer.routeDraft,
    VALIDATED: t.reassignDrawer.routeValidated,
    IN_PROGRESS: t.reassignDrawer.routeInProgress,
    CLOSED: t.reassignDrawer.routeClosed,
    CANCELLED: t.reassignDrawer.routeCancelled,
  };
  const isBatch = (targets?.length ?? 0) > 1;
  const allTargets = isBatch ? targets! : (target ? [target] : []);
  const isAssignMode = allTargets.length > 0 && allTargets.every(t => t.status === 'UNSCHEDULED');
  const [step, setStep] = useState<1 | 2 | 3>(1);

  const [selectedDriverId, setSelectedDriverId] = useState('');
  const [driverSearch, setDriverSearch] = useState('');
  const [offlineExpanded, setOfflineExpanded] = useState(false);
  const [prefetchedRoutes, setPrefetchedRoutes] = useState<Record<string, RouteOption[]>>({});
  const [prefetching, setPrefetching] = useState(false);

  const [routes, setRoutes] = useState<RouteOption[]>([]);
  const [loadingRoutes, setLoadingRoutes] = useState(false);
  const [selectedRoute, setSelectedRoute] = useState<RouteOption | null>(null);

  const [insertAfterStopId, setInsertAfterStopId] = useState<string | null>(null);
  const [startTimeWindow, setStartTimeWindow] = useState('');
  const [endTimeWindow, setEndTimeWindow] = useState('');
  const [note, setNote] = useState('');
  const [acknowledgeOverload, setAcknowledgeOverload] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) {
      setTimeout(() => {
        setStep(1);
        setSelectedDriverId('');
        setDriverSearch('');
        setRoutes([]);
        setSelectedRoute(null);
        setInsertAfterStopId(null);
        setStartTimeWindow('');
        setEndTimeWindow('');
        setNote('');
        setAcknowledgeOverload(false);
        setOfflineExpanded(false);
      }, 300);
    }
  }, [open]);

  // Pre-fill the window from the delivery's existing slot (the créneau) as a suggested,
  // editable default — the dispatcher rarely needs to retype it. Non-blocking: if left
  // empty the backend inherits the current stop's window.
  useEffect(() => {
    if (open && target && !isBatch) {
      const s = target.timeSlotStartTime?.slice(0, 5);
      const e = target.timeSlotEndTime?.slice(0, 5);
      if (s) setStartTimeWindow(prev => prev || s);
      if (e) setEndTimeWindow(prev => prev || e);
    }
  }, [open, target, isBatch]);

  // A driver's assignable routes = upcoming window (today → +30d) UNION every IN_PROGRESS route. A route
  // that's running was likely created on a prior day, so a date-only window would hide it (and make the
  // driver look idle). Merge + dedupe so a currently-driving driver still shows their live route.
  const loadDriverRoutes = useCallback(async (driverId: string): Promise<RouteOption[]> => {
    const from = new Date().toISOString().slice(0, 10);
    const to = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
    const [upcomingRes, runningRes] = await Promise.all([
      api.get(`/api/admin/routes/driver/${driverId}`, { params: { from, to } }).catch(() => ({ data: [] })),
      api.get('/api/admin/routes', { params: { driverId, status: 'IN_PROGRESS' } }).catch(() => ({ data: [] })),
    ]);
    const a = (Array.isArray(upcomingRes.data) ? upcomingRes.data : []) as RouteOption[];
    const b = (Array.isArray(runningRes.data) ? runningRes.data : []) as RouteOption[];
    const byId = new Map<string, RouteOption>();
    [...a, ...b].forEach(r => byId.set(r.id, r));
    return Array.from(byId.values());
  }, []);

  useEffect(() => {
    if (!open || drivers.length === 0) return;
    setPrefetching(true);
    Promise.all(
      drivers.map(d => loadDriverRoutes(d.id).then(routes => ({ id: d.id, routes })).catch(() => ({ id: d.id, routes: [] as RouteOption[] })))
    ).then(results => {
      const map: Record<string, RouteOption[]> = {};
      for (const r of results) map[r.id] = r.routes.filter(rt => rt.status !== 'CLOSED' && rt.status !== 'CANCELLED');
      setPrefetchedRoutes(map);
    }).finally(() => setPrefetching(false));
  }, [open, drivers, loadDriverRoutes]);

  const fetchDriverRoutes = useCallback(async (driverId: string) => {
    setLoadingRoutes(true);
    setRoutes([]);
    try {
      const data = await loadDriverRoutes(driverId);
      let active = data.filter(r => r.status !== 'CLOSED' && r.status !== 'CANCELLED');
      if (isBatch) active = active.filter(r => r.status === 'DRAFT');
      setRoutes(active);
    } catch {
      showErrorToast(undefined, 'errorDriverRoutesLoadFailed');
    } finally {
      setLoadingRoutes(false);
    }
  }, [isBatch, loadDriverRoutes]);

  const selectDriver = (driverId: string) => {
    setSelectedDriverId(driverId);
    void fetchDriverRoutes(driverId);
    setStep(2);
  };

  const selectRoute = (route: RouteOption) => {
    setSelectedRoute(route);
    setInsertAfterStopId(null);
    setAcknowledgeOverload(false);
    setStep(3);
  };

  // Road-proximity ranking (OSRM) for the recommended quick-pick — single-target reassign only.
  const [nearest, setNearest] = useState<Record<string, NearestInfo>>({});
  useEffect(() => {
    if (!open || isBatch || !target?.deliveryId) { setNearest({}); return; }
    let alive = true;
    api.get(`/api/admin/ops/exceptions/${target.deliveryId}/nearest-drivers`, { params: { limit: 8 } })
      .then(res => {
        if (!alive) return;
        const map: Record<string, NearestInfo> = {};
        (Array.isArray(res.data) ? res.data : []).forEach((r: { driverId: string; etaSeconds: number | null; distanceMeters: number | null; source: string }, i: number) => {
          map[r.driverId] = { etaSeconds: r.etaSeconds, distanceMeters: r.distanceMeters, source: r.source, rank: i };
        });
        setNearest(map);
      })
      .catch(() => { if (alive) setNearest({}); });
    return () => { alive = false; };
  }, [open, isBatch, target?.deliveryId]);

  // Quick path: reassign straight to a driver (server auto-resolves/creates the route, window + position
  // inherited). In-field parcels need a handover note, so route those through the detailed flow instead.
  const quickReassign = async (driverId: string) => {
    const inField = allTargets.some(x => x.status === 'PICKED_UP' || x.status === 'IN_TRANSIT');
    if (inField) { selectDriver(driverId); return; }
    setSubmitting(true);
    try {
      let ok = 0, fail = 0;
      for (const x of allTargets) {
        try { await api.post(`/api/admin/ops/exceptions/${x.deliveryId}/reassign`, { driverId, note: '' }); ok++; }
        catch { fail++; }
      }
      if (ok > 0 && fail === 0) showSuccessToast('successReassignToActive');
      else if (ok > 0) showErrorToast(undefined, 'errorReassignPartialSuccess');
      else showErrorToast(undefined, 'errorReassignFailed');
      onSuccess();
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  const locale = useLocaleStore(s => s.locale);
  const localeMap: Record<string, string> = { FR: 'fr-FR', EN: 'en-US', AR: 'ar-SA' };

  const formatDate = (dateStr: string) => {
    try {
      const d = new Date(dateStr + 'T00:00:00');
      return d.toLocaleDateString(localeMap[locale] || 'fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
    } catch {
      return dateStr;
    }
  };

  const toLocalTime = (t: string) =>
    t.trim().length === 5 ? `${t.trim()}:00` : t.trim();

  // Capacity preview: the selected route's current load + the parcel(s) being moved vs payload.
  const parcelWeightKg = allTargets.reduce((s, x) => s + (x.totalWeightKg ?? 0), 0);
  const capacityInfo = (selectedRoute && selectedRoute.payloadKg != null && selectedRoute.payloadKg > 0)
    ? (() => {
        const capacity = selectedRoute.payloadKg as number;
        const current = selectedRoute.currentLoadKg ?? 0;
        const newLoad = current + parcelWeightKg;
        return { capacity, current, newLoad, pct: Math.round((newLoad / capacity) * 100), over: newLoad > capacity };
      })()
    : null;

  const submit = async () => {
    if (allTargets.length === 0 || !selectedRoute) return;

    const isDraft = selectedRoute.status === 'DRAFT';
    const isInField = allTargets.some(t => t.status === 'PICKED_UP' || t.status === 'IN_TRANSIT');
    if ((isInField || !isDraft) && !note.trim()) {
      showErrorToast(undefined, 'errorNoteRequired');
      return;
    }

    // Overload is a soft-block: the dispatcher must explicitly tick "force" to proceed.
    if (capacityInfo?.over && !acknowledgeOverload) {
      showErrorToast(undefined, 'errorOverloadAck');
      return;
    }

    // Window is optional: pre-filled from the slot and editable. If left empty the backend
    // inherits the delivery's current window — so we no longer hard-block on it. BUT if both
    // ends are set, an inverted/zero window (start ≥ end, e.g. 08:00 → 07:55) is always invalid —
    // hard-block it here regardless of assign/reassign mode so we never push a nonsensical window.
    {
      const s = parseHHMM(startTimeWindow);
      const e = parseHHMM(endTimeWindow);
      if (s !== null && e !== null && s >= e) {
        showErrorToast(t.reassignDrawer.timeWindowErrorDesc);
        return;
      }
    }

    setSubmitting(true);
    try {
      let insertAtOrder: number | undefined;
      if (!isBatch) {
        if (insertAfterStopId === '__start__') {
          insertAtOrder = 1;
        } else if (insertAfterStopId) {
          const sortedTarget = selectedRoute.stops
            .filter(s => !STOP_STATUS_DONE.has(s.status))
            .sort((a, b) => a.stopOrder - b.stopOrder);
          const idx = sortedTarget.findIndex(s => s.id === insertAfterStopId);
          if (idx >= 0) insertAtOrder = idx + 2;
        }
      }

      const basePayload: Record<string, unknown> = {
        driverId: selectedDriverId,
        targetRouteId: selectedRoute.id,
        note: note.trim(),
      };
      if (insertAtOrder !== undefined) basePayload.insertAtOrder = insertAtOrder;
      if (startTimeWindow) basePayload.startTimeWindow = toLocalTime(startTimeWindow);
      if (endTimeWindow) basePayload.endTimeWindow = toLocalTime(endTimeWindow);
      if (acknowledgeOverload) basePayload.acknowledgeOverload = true;

      let successCount = 0;
      let failCount = 0;
      for (const t of allTargets) {
        try {
          await api.post(`/api/admin/ops/exceptions/${t.deliveryId}/reassign`, basePayload);
          successCount++;
        } catch {
          failCount++;
        }
      }

      if (successCount > 0 && failCount === 0) {
        // All succeeded
        if (selectedRoute.status === 'DRAFT') {
          showSuccessToast('successReassignToDraft');
        } else {
          showSuccessToast('successReassignToActive');
        }
      } else if (successCount > 0 && failCount > 0) {
        // Partial success
        showErrorToast(undefined, 'errorReassignPartialSuccess');
      } else if (failCount > 0) {
        // All failed
        showErrorToast(undefined, 'errorReassignFailed');
      }

      onSuccess(selectedRoute.id);
      onClose();
    } catch (err: any) {
      showErrorToast(err, 'errorReassignFailed');
    } finally {
      setSubmitting(false);
    }
  };

  const selectedDriver = drivers.find(d => d.id === selectedDriverId);
  const dateGroups = groupByDate(routes);
  // Exclude depot PICKUP stops — they're loading operations, not insertion positions for a delivery.
  const activeStops = selectedRoute?.stops.filter(s => !STOP_STATUS_DONE.has(s.status) && s.stopType !== 'PICKUP').sort((a, b) => a.stopOrder - b.stopOrder) ?? [];

  function parseHHMM(t: string): number | null {
    const parts = t.trim().split(':');
    if (parts.length < 2) return null;
    const h = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    if (isNaN(h) || isNaN(m)) return null;
    return h * 60 + m;
  }

  const newStart = parseHHMM(startTimeWindow);
  const newEnd = parseHHMM(endTimeWindow);
  const errStartGtEnd = newStart !== null && newEnd !== null && newStart >= newEnd;

  type Overlap = { stop: RouteStop; stopIdx: number };
  const overlaps: Overlap[] = [];
  if (newStart !== null && newEnd !== null && !errStartGtEnd) {
    activeStops.forEach((s, idx) => {
      const a = parseHHMM(s.startTimeWindow ?? '');
      const b = parseHHMM(s.endTimeWindow ?? '');
      if (a === null || b === null) return;
      if (newStart < b && a < newEnd) overlaps.push({ stop: s, stopIdx: idx });
    });
  }

  const hasTimeConflict = errStartGtEnd;
  const overlapStopIds = new Set(overlaps.map(o => o.stop.id));

  const drawerTitle = (
    <div className="flex flex-col gap-1">
      <span className="text-md font-semibold text-[var(--text-primary)]">
        {isAssignMode
          ? isBatch
            ? t.reassignDrawer.assignBatchTitle.replace('{count}', String(allTargets.length))
            : t.reassignDrawer.assignTitle
          : isBatch
            ? t.reassignDrawer.batchTitle.replace('{count}', String(allTargets.length))
            : t.reassignDrawer.reassignTitle}
      </span>
      <span className="text-xs font-normal text-[var(--text-muted)]">
        {isBatch
          ? allTargets.map(t => t.city ?? t.clientName).filter(Boolean).slice(0, 3).join(', ')
          : `${target?.orderRef || target?.erpOrderId || target?.deliveryId?.slice(0, 8).toUpperCase()} • ${target?.clientName}`}
      </span>
    </div>
  );

  return (
    <AppDrawer
      open={open}
      onClose={onClose}
      title={drawerTitle}
      width={680}
      footer={step === 1 ? (
        <div className="flex items-center gap-2 w-full text-2xs text-[var(--text-muted)]">
          <IconBolt size={14} className="text-[var(--brand)] shrink-0" />
          <span>{t.reassignDrawer.pickerHint}</span>
        </div>
      ) : step === 3 ? (
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={() => setStep(2)}>
            {t.reassignDrawer.backButton}
          </Button>
          <Button
            size="sm"
            onClick={submit}
            disabled={submitting || (!isAssignMode && !isBatch && selectedRoute?.status !== 'DRAFT' && !note.trim()) || hasTimeConflict || (!!capacityInfo?.over && !acknowledgeOverload)}
          >
            {submitting && (
              <svg className="animate-spin -ml-0.5 mr-1.5 h-3 w-3" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
              </svg>
            )}
            {isBatch ? t.reassignDrawer.confirmBatch.replace('{count}', String(allTargets.length)) : t.reassignDrawer.confirmReassign}
          </Button>
        </div>
      ) : undefined}
    >
      {/* Wizard chrome only for the detailed path (steps 2-3). Step 1 is the single-screen picker. */}
      {step > 1 && (
      <div className="mb-5 px-6 pt-2">
        <StepIndicator step={step} />
        {step > 1 && selectedDriver && (
          <div className="flex items-center gap-1 mt-2 text-xs">
            <button
              type="button"
              onClick={() => { setStep(1); setSelectedDriverId(''); setRoutes([]); setSelectedRoute(null); }}
              className="flex items-center gap-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
            >
              <IconArrowLeft size={12} />
              <span className="font-medium">{t.reassignDrawer.changeDriver}</span>
            </button>
            <span className="text-[var(--border)]">·</span>
            <span className="font-medium text-[var(--text-primary)]">{selectedDriver.name}</span>
            {step === 3 && selectedRoute && (
              <>
                <span className="text-[var(--border)]">/</span>
                <button
                  type="button"
                  onClick={() => { setStep(2); setSelectedRoute(null); }}
                  className="font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                >
                  {selectedRoute.name}
                </button>
              </>
            )}
          </div>
        )}
      </div>
      )}

      {/* ─── Step 1: Pick driver ─────────────────────────────────────── */}
      {step === 1 && (() => {
        const needsDistance = allTargets.length > 0 &&
          (allTargets[0].status === 'PICKED_UP' || allTargets[0].status === 'IN_TRANSIT') &&
          allTargets[0].dropoffLat != null && allTargets[0].dropoffLng != null;

        const getDistance = (driver: Driver) => {
          if (!needsDistance || driver.currentLat == null || driver.currentLng == null) return null;
          return haversineKm(driver.currentLat, driver.currentLng, allTargets[0].dropoffLat!, allTargets[0].dropoffLng!);
        };

        const driverRoutes = (d: Driver) => prefetchedRoutes[d.id] ?? [];
        const hasActiveRoute = (d: Driver) => driverRoutes(d).length > 0;

        const sortDrivers = (list: Driver[]) => {
          return [...list].sort((a, b) => {
            if (needsDistance) {
              const da = getDistance(a), db = getDistance(b);
              if (da != null && db != null) return da - db;
            }
            return driverRoutes(a).length - driverRoutes(b).length;
          });
        };

        // Exclude the driver(s) currently holding the delivery being reassigned — reassigning to the same
        // driver is a no-op. (Assign mode has no current driver, so this excludes nothing.)
        const currentDriverNames = new Set(allTargets.map(x => x.driverName).filter(Boolean));
        const searched = drivers.filter(d =>
          (!driverSearch.trim() || d.name.toLowerCase().includes(driverSearch.toLowerCase())) &&
          !currentDriverNames.has(d.name)
        );
        // Recommended = closest driver by road (OSRM rank). Excluded from the tiers below to avoid a dup.
        const recommendedId = (() => {
          let best: string | null = null, bestRank = Infinity;
          for (const d of searched) {
            const n = nearest[d.id];
            if (n && n.rank < bestRank) { bestRank = n.rank; best = d.id; }
          }
          return best;
        })();
        const recommended = recommendedId ? searched.find(d => d.id === recommendedId) ?? null : null;

        const online = sortDrivers(searched.filter(d => d.onlineStatus === 'ONLINE' && d.id !== recommendedId));
        const onBreak = sortDrivers(searched.filter(d => d.onlineStatus === 'ON_BREAK' && d.id !== recommendedId));
        const offline = sortDrivers(searched.filter(d => (!d.onlineStatus || d.onlineStatus === 'OFFLINE') && d.id !== recommendedId));

        const onlineWithRoute = online.filter(d => hasActiveRoute(d));
        const onlineWithoutRoute = online.filter(d => !hasActiveRoute(d));

        // Clean row — NO status dots (see .ai/anti-slop.md): status + GPS recency are TEXT in tone tokens.
        const DriverCard = ({ driver, dimmed = false, recommended: isRec = false }: { driver: Driver; dimmed?: boolean; recommended?: boolean }) => {
          const near = nearest[driver.id];
          const hav = getDistance(driver);
          const etaTxt = formatEta(near?.etaSeconds);
          const kmTxt = formatKm(near?.distanceMeters) ?? (hav != null ? `${hav.toFixed(1)} km` : null);
          const proximity = [etaTxt, kmTxt].filter(Boolean).join(' · ');
          const routes = driverRoutes(driver);
          const status = driver.onlineStatus ?? 'OFFLINE';
          const statusCfg = DRIVER_STATUS_COLOR[status as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE;
          const activeStopCount = routes.reduce((sum, r) => sum + r.stops.filter(s => !STOP_STATUS_DONE.has(s.status)).length, 0);
          const seen = formatSeen(driver.lastLocationAt);
          const routeTxt = routes.length > 0
            ? `${ROUTE_STATUS_LABEL[routes[0].status] ?? routes[0].status} · ${routes[0].stops.filter(s => STOP_STATUS_DONE.has(s.status)).length}/${routes[0].stops.length}${activeStopCount > 0 ? ` · ${activeStopCount} ${t.reassignDrawer.stopFree}` : ''}`
            : prefetching ? t.reassignDrawer.loadingRoutes : t.reassignDrawer.noRoutes;

          const Sub = (
            <div className="flex items-center gap-x-1.5 gap-y-0 flex-wrap text-2xs">
              <span className="font-medium" style={{ color: statusCfg.text }}>{statusCfg.label}</span>
              {seen && <><span className="text-[var(--text-soft)]">·</span><span style={{ color: seen.fresh ? 'var(--success)' : 'var(--text-muted)' }}>{seen.text}</span></>}
              <span className="text-[var(--text-soft)]">·</span><span className="text-[var(--text-muted)] tabular-nums">{routeTxt}</span>
            </div>
          );
          const ReassignBtn = (
            <button
              type="button"
              onClick={e => { e.stopPropagation(); void quickReassign(driver.id); }}
              disabled={submitting}
              className={cn(
                'h-8 px-3.5 rounded-md text-xs font-semibold transition-colors disabled:opacity-50 shrink-0',
                isRec
                  ? 'bg-[var(--brand)] text-white hover:opacity-90'
                  : 'border border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)]',
              )}
            >
              {t.reassignDrawer.assignAction ?? 'Réaffecter'}
            </button>
          );

          if (isRec) {
            return (
              <div
                onClick={() => selectDriver(driver.id)}
                className="rounded-xl p-3.5 flex items-center gap-3.5 cursor-pointer transition-colors hover:bg-[var(--hover-bg)]"
                style={{ background: 'var(--surface-sunken)' }}
              >
                <DriverAvatarById driverId={driver.id} name={driver.name} size={42} />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-bold text-[var(--text-primary)] truncate">{driver.name}</p>
                  <div className="mt-0.5">{Sub}</div>
                  {proximity && <p className="mt-1 text-sm font-semibold text-[var(--text-primary)] tabular-nums">{proximity}</p>}
                </div>
                {ReassignBtn}
              </div>
            );
          }
          return (
            <div
              onClick={() => selectDriver(driver.id)}
              className="flex items-center gap-3 py-2.5 px-1 -mx-1 border-t border-[var(--border)] cursor-pointer transition-colors hover:bg-[var(--app-bg)]"
              style={{ opacity: dimmed ? 0.6 : 1 }}
            >
              <DriverAvatarById driverId={driver.id} name={driver.name} size={34} />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{driver.name}</p>
                <div className="mt-0.5">{Sub}</div>
              </div>
              {proximity && <span className="text-xs font-medium text-[var(--text-secondary)] tabular-nums whitespace-nowrap shrink-0">{proximity}</span>}
              {ReassignBtn}
              <IconChevronRight size={15} className="text-[var(--text-muted)] shrink-0" />
            </div>
          );
        };

        const TierLabel = ({ label }: { label: string }) => (
          <span className="text-2xs font-medium text-[var(--text-muted)] pt-2">{label}</span>
        );

        return (
          <div className="flex flex-col gap-2 px-6 pb-5">
            <FieldInput
              placeholder={t.reassignDrawer.searchPlaceholder}
              value={driverSearch}
              onChange={e => setDriverSearch(e.currentTarget.value)}
              leftSection={<IconUser size={14} />}
            />

            {recommended && (
              <>
                <TierLabel label={t.reassignDrawer.recommendedLabel ?? 'Recommandé'} />
                <DriverCard driver={recommended} recommended />
              </>
            )}

            {onlineWithRoute.length > 0 && (
              <>
                <TierLabel label={t.reassignDrawer.onlineWithRoute} />
                {onlineWithRoute.map(d => <DriverCard key={d.id} driver={d} />)}
              </>
            )}

            {onlineWithoutRoute.length > 0 && (
              <>
                <TierLabel label={t.reassignDrawer.onlineNoRoute} />
                <p className="text-2xs text-[var(--text-muted)] px-3 py-2 rounded-xs bg-[var(--app-bg)]">
                  {t.reassignDrawer.autoRouteCreated}
                </p>
                {onlineWithoutRoute.map(d => <DriverCard key={d.id} driver={d} />)}
              </>
            )}

            {onBreak.length > 0 && (
              <>
                <TierLabel label={t.reassignDrawer.onBreak} />
                {onBreak.map(d => <DriverCard key={d.id} driver={d} />)}
              </>
            )}

            {offline.length > 0 && (
              <>
                <button
                  type="button"
                  onClick={() => setOfflineExpanded(v => !v)}
                  className="flex items-center gap-1.5 py-2 w-full text-2xs font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors border-t border-[var(--border)]"
                >
                  <span>
                    {offlineExpanded ? t.reassignDrawer.hideOffline : t.reassignDrawer.showOffline} {offline.length} {t.reassignDrawer.offlineLabel}
                  </span>
                  <IconChevronRight
                    size={12}
                    className="ml-auto opacity-50 transition-transform duration-150"
                    style={{ transform: offlineExpanded ? 'rotate(90deg)' : 'none' }}
                  />
                </button>
                {offlineExpanded && offline.map(d => <DriverCard key={d.id} driver={d} dimmed />)}
                {offlineExpanded && (
                  <p className="text-2xs text-[var(--text-muted)] px-3 py-2 rounded-xs bg-[var(--app-bg)]">
                    {t.reassignDrawer.offlineWarning}
                  </p>
                )}
              </>
            )}

            {online.length === 0 && onBreak.length === 0 && !offlineExpanded && offline.length === 0 && (
              <p className="text-sm text-[var(--text-soft)] text-center py-6">{t.reassignDrawer.noDriver}</p>
            )}

            {/* Advanced options — collapsed by default (progressive disclosure). The detailed route /
                window / stop-position flow is reached by clicking a driver row instead of "Réaffecter". */}
            {!isBatch && (
              <div className="border-t border-[var(--border)] mt-1 pt-3">
                <button
                  type="button"
                  onClick={() => setAdvancedOpen(v => !v)}
                  className="flex items-center justify-between w-full"
                >
                  <span className="flex items-center gap-2 text-sm font-semibold text-[var(--text-primary)]">
                    <IconAdjustments size={16} className="text-[var(--text-secondary)]" />
                    {t.reassignDrawer.advancedOptions}
                  </span>
                  <IconChevronDown size={15} className="text-[var(--text-muted)] transition-transform" style={{ transform: advancedOpen ? 'rotate(180deg)' : 'none' }} />
                </button>
                {advancedOpen && (
                  <div className="mt-2.5 ps-6 flex flex-col gap-2">
                    <div className="flex flex-wrap gap-1.5">
                      <AdvChip icon={<IconClock size={12} />} label={t.reassignDrawer.timeWindowInherited ?? 'Créneau conservé'} />
                      <AdvChip icon={<IconMapPin size={12} />} label={t.reassignDrawer.chipAddedEnd ?? 'Ajouté en fin'} />
                      <AdvChip icon={<IconNote size={12} />} label={t.reassignDrawer.chipNoNote ?? 'Sans note'} />
                    </div>
                    <p className="text-2xs leading-relaxed text-[var(--text-muted)]">{t.reassignDrawer.advancedHint}</p>
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })()}

      {/* ─── Step 2: Pick route/date ─────────────────────────────────── */}
      {step === 2 && (
        <div className="flex flex-col gap-4 px-6 pb-5">
          {loadingRoutes && (
            <div className="flex items-center justify-center gap-2 py-8">
              <svg className="animate-spin h-4 w-4 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
              </svg>
              <span className="text-xs text-[var(--text-muted)]">{t.reassignDrawer.loadingRoutesStep2}</span>
            </div>
          )}

          {!loadingRoutes && routes.length === 0 && (
            <div className="p-6 rounded border border-[var(--border)] text-center flex flex-col items-center gap-2">
              <IconCalendar size={32} className="text-[var(--border)]" />
              <p className="text-sm font-semibold text-[var(--text-muted)]">
                {t.reassignDrawer.noActiveRoutes}
              </p>
              <p className="text-2xs text-[var(--text-soft)]">
                {t.reassignDrawer.noActiveRoutesDesc}
              </p>
            </div>
          )}

          {!loadingRoutes && dateGroups.map(([date, dayRoutes]) => (
            <div key={date} className="flex flex-col gap-2">
              <div className="flex items-center gap-2">
                <span className="text-2xs font-medium text-[var(--text-muted)]">
                  {formatDate(date)}
                </span>
                <span className="text-2xs text-[var(--text-muted)]">•</span>
                <span className="text-2xs text-[var(--text-muted)]">{t.reassignDrawer.routeCount.replace('{count}', String(dayRoutes.length)).replace('{plural}', dayRoutes.length > 1 ? 's' : '')}</span>
              </div>

              {dayRoutes.map(route => {
                const activeCount = route.stops.filter(s => !STOP_STATUS_DONE.has(s.status)).length;
                const doneCount = route.stops.filter(s => STOP_STATUS_DONE.has(s.status)).length;
                return (
                  <button
                    key={route.id}
                    type="button"
                    onClick={() => selectRoute(route)}
                    className="w-full text-left"
                  >
                    <div className="p-3 rounded-sm border border-[var(--border)] bg-transparent hover:bg-[var(--app-bg)] transition-colors cursor-pointer">
                      <div className="flex items-start justify-between">
                        <div className="flex flex-col gap-1 flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-bold text-[var(--text-primary)] truncate">{route.name}</span>
                            <span
                              className="text-2xs px-1.5 py-0.5 rounded shrink-0"
                              style={{ background: `${ROUTE_STATUS_COLOR[route.status]}20`, color: ROUTE_STATUS_COLOR[route.status] }}
                            >
                              {ROUTE_STATUS_LABEL[route.status] ?? route.status}
                            </span>
                          </div>
                          <div className="flex items-center gap-2">
                            <div className="flex items-center gap-0.5">
                              <IconMapPin size={10} className="text-[var(--text-soft)]" />
                              <span className="text-2xs text-[var(--text-muted)]">{route.stops.length} arrêts total</span>
                            </div>
                            {activeCount > 0 && (
                              <span className="text-2xs text-[var(--success)] font-semibold">{activeCount} actifs</span>
                            )}
                            {doneCount > 0 && (
                              <span className="text-2xs text-[var(--text-soft)]">{doneCount} faits</span>
                            )}
                          </div>
                        </div>
                        <IconChevronRight size={14} className="text-[var(--text-soft)] shrink-0 mt-0.5" />
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      )}

      {/* ─── Step 3: Configure stop ───────────────────────────────────── */}
      {step === 3 && selectedRoute && (
        <div className="flex flex-col gap-4 px-6 pb-5">
          {/* Delivery info recap */}
          {isBatch ? (
            <div className="p-3 rounded-sm border border-[var(--border)] bg-transparent">
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-[var(--text-primary)]">
                  {allTargets.length} livraisons
                </span>
                {allTargets.slice(0, 5).map(t => (
                  <div key={t.deliveryId} className="flex items-center gap-2">
                    <div className="w-[5px] h-[5px] rounded-full bg-[var(--brand)] shrink-0" />
                    <span className="text-2xs text-[var(--text-primary)] font-semibold">{t.clientName ?? '—'}</span>
                    <span className="text-2xs text-[var(--text-muted)]">{t.city}</span>
                    {(t.orderRef || t.erpOrderId) && <span className="text-2xs text-[var(--text-soft)] font-mono">{t.orderRef ?? t.erpOrderId}</span>}
                  </div>
                ))}
                {allTargets.length > 5 && (
                  <span className="text-2xs text-[var(--text-soft)]">+{allTargets.length - 5} de plus…</span>
                )}
              </div>
            </div>
          ) : (
            <div className="p-3 rounded-sm border border-[var(--border)] bg-transparent">
              <div className="flex items-center gap-2">
                <div className="w-1.5 h-1.5 rounded-full bg-[var(--brand)] shrink-0" />
                <div className="flex flex-col gap-0.5 flex-1 min-w-0">
                  <span className="text-xs font-medium text-[var(--text-primary)]">{target?.clientName}</span>
                  <span className="text-2xs text-[var(--text-muted)]">{target?.city} • {target?.orderRef ?? target?.erpOrderId ?? target?.deliveryId?.slice(0, 8).toUpperCase()}</span>
                </div>
                <span className="text-2xs font-bold text-[var(--text-soft)] uppercase">{target?.status}</span>
              </div>
              {/* Decision context: weight / amount / items / priority / scheduled (#4) */}
              <div className="flex flex-wrap items-center gap-1.5 mt-2">
                {target?.totalWeightKg != null && <MetaChip>{target.totalWeightKg} kg</MetaChip>}
                {target?.totalAmount != null && target.totalAmount > 0 && (
                  <MetaChip>{formatMoney(target.totalAmount, target.currency ?? 'TND')}</MetaChip>
                )}
                {target?.itemsCount != null && target.itemsCount > 0 && <MetaChip>{target.itemsCount} art.</MetaChip>}
                {target?.priority && target.priority.toUpperCase() !== 'NORMAL' && <MetaChip danger>{target.priority}</MetaChip>}
                {target?.scheduledAt && (
                  <MetaChip>{new Date(target.scheduledAt).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })}</MetaChip>
                )}
              </div>
              {target?.dropoffAddress && (
                <p className="text-2xs text-[var(--text-muted)] mt-1.5 truncate">{target.dropoffAddress}</p>
              )}
            </div>
          )}

          {/* Capacity bar (#1/#5): current load + parcel(s) vs vehicle payload */}
          {capacityInfo && (
            <div
              className="flex flex-col gap-2 p-3 rounded-sm border"
              style={{
                borderColor: capacityInfo.over ? 'var(--danger)' : 'var(--border)',
                background: capacityInfo.over ? 'var(--danger-bg)' : 'transparent',
              }}
            >
              <div className="flex items-center justify-between">
                <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                  {t.reassignDrawer.capacityLabel}
                </span>
                <span className="text-xs font-bold" style={{ color: capacityInfo.over ? 'var(--danger)' : 'var(--text-primary)' }}>
                  {Math.round(capacityInfo.newLoad)} / {capacityInfo.capacity} kg ({capacityInfo.pct}%)
                </span>
              </div>
              <div className="w-full h-2 rounded-full overflow-hidden" style={{ background: 'var(--hover-bg)' }}>
                <div
                  className="h-full rounded-full transition-all"
                  style={{
                    width: `${Math.min(capacityInfo.pct, 100)}%`,
                    background: capacityInfo.over ? 'var(--danger)' : capacityInfo.pct > 80 ? 'var(--warning)' : 'var(--brand)',
                  }}
                />
              </div>
              {capacityInfo.over && (
                <label className="flex items-center gap-2 cursor-pointer mt-0.5 select-none">
                  <input
                    type="checkbox"
                    checked={acknowledgeOverload}
                    onChange={e => setAcknowledgeOverload(e.currentTarget.checked)}
                  />
                  <span className="text-2xs font-bold" style={{ color: 'var(--danger)' }}>
                    {t.reassignDrawer.capacityForce}
                  </span>
                </label>
              )}
            </div>
          )}

          {/* Time windows — the on-time reference (client créneau). Inherited if untouched. */}
          {!isBatch && (
            <div className="flex flex-col gap-3 rounded-lg border border-[var(--border)] bg-[var(--surface)] p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="flex items-center justify-center w-8 h-8 rounded-md bg-[var(--brand-bg)] shrink-0">
                    <IconClock size={16} className="text-[var(--brand)]" />
                  </span>
                  <div className="min-w-0">
                    <p className="text-sm font-bold text-[var(--text-primary)] leading-tight">{t.reassignDrawer.timeWindowLabel}</p>
                    <p className="text-2xs text-[var(--text-muted)]">{t.reassignDrawer.timeWindowSub ?? 'Référence de ponctualité'}</p>
                  </div>
                </div>
                {!startTimeWindow && !endTimeWindow && (
                  <span className="text-2xs font-semibold px-2 py-1 rounded-md shrink-0" style={{ background: 'var(--surface-sunken)', color: 'var(--text-muted)' }}>
                    {t.reassignDrawer.timeWindowInherited ?? 'Créneau actuel conservé'}
                  </span>
                )}
              </div>
              <p className="text-2xs leading-relaxed text-[var(--text-muted)]">{t.reassignDrawer.timeWindowHint}</p>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.reassignDrawer.timeWindowStart}
                  placeholder="08:00"
                  value={startTimeWindow}
                  onChange={e => setStartTimeWindow(e.currentTarget.value)}
                  leftSection={<IconClock size={14} className={hasTimeConflict ? 'text-[var(--danger)]' : ''} />}
                  type="time"
                  error={hasTimeConflict ? ' ' : undefined}
                />
                <FieldInput
                  label={t.reassignDrawer.timeWindowEnd}
                  placeholder="18:00"
                  value={endTimeWindow}
                  onChange={e => setEndTimeWindow(e.currentTarget.value)}
                  leftSection={<IconClock size={14} className={hasTimeConflict ? 'text-[var(--danger)]' : ''} />}
                  type="time"
                  error={errStartGtEnd ? t.reassignDrawer.timeWindowError : undefined}
                />
              </div>
              {errStartGtEnd && (
                <div className="px-2.5 py-1.5 bg-[var(--danger-bg)] border border-[var(--danger)] rounded">
                  <span className="text-2xs font-bold text-[var(--danger)]">
                    ⚠ {t.reassignDrawer.timeWindowErrorDesc}
                  </span>
                </div>
              )}
              {overlaps.length > 0 && (
                <div className="px-2.5 py-1.5 bg-[var(--warning-bg)] border border-[var(--warning)] rounded flex flex-col gap-0.5">
                  <span className="text-2xs font-black text-[var(--warning)]">
                    ⚠ {locale === 'ar'
                      ? `تداخل مع ${overlaps.length} محطة`
                      : locale === 'en'
                        ? `Overlaps with ${overlaps.length} stop${overlaps.length > 1 ? 's' : ''}`
                        : `Chevauchement avec ${overlaps.length} arrêt${overlaps.length > 1 ? 's' : ''}`}
                  </span>
                  {overlaps.slice(0, 3).map(o => (
                    <span key={o.stop.id} className="text-2xs text-[var(--warning)]">
                      {locale === 'ar' ? '• محطة ' : locale === 'en' ? '• Stop ' : '• Arrêt '}{o.stopIdx + 1} — {o.stop.clientName || 'Client'} ({formatTime(o.stop.startTimeWindow)} → {formatTime(o.stop.endTimeWindow)})
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Stop position picker */}
          {!isBatch && activeStops.length > 0 && (() => {
            const canPickPosition = selectedRoute.status === 'DRAFT' || selectedRoute.status === 'VALIDATED';
            return (
              <div className="flex flex-col gap-2">
                <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                  Arrêts de la tournée
                </span>
                <p className="text-2xs text-[var(--text-muted)]">
                  {canPickPosition
                    ? 'Cliquez sur un emplacement pour positionner le nouvel arrêt.'
                    : 'Tournée en cours — le nouvel arrêt sera ajouté en fin de tournée.'}
                </p>

                {canPickPosition && (
                  <button
                    type="button"
                    onClick={() => setInsertAfterStopId('__start__')}
                    className="w-full text-left"
                  >
                    <div className={cn(
                      'px-2.5 py-1.5 rounded flex items-center gap-1.5',
                      insertAfterStopId === '__start__'
                        ? 'border-2 border-[var(--brand)] bg-[var(--danger-bg)]'
                        : 'border-2 border-dashed border-[var(--border)] bg-transparent',
                    )}>
                      {insertAfterStopId === '__start__'
                        ? <IconCheck size={10} className="text-[var(--brand)]" />
                        : <div className="w-2.5 h-2.5 rounded-full border-2 border-dashed border-[var(--brand)] opacity-40" />}
                      <span className={cn(
                        'text-2xs font-bold uppercase tracking-[0.05em]',
                        insertAfterStopId === '__start__' ? 'text-[var(--brand)]' : 'text-[var(--text-soft)]',
                      )}>
                        Insérer en premier
                      </span>
                    </div>
                  </button>
                )}

                {activeStops.map((stop, i) => {
                  const isOverlap = overlapStopIds.has(stop.id);
                  const hasWindow = stop.startTimeWindow || stop.endTimeWindow;
                  return (
                    <div key={stop.id} className="flex flex-col gap-0">
                      <div
                        className="px-2.5 py-2 rounded border"
                        style={{
                          background: isOverlap ? 'var(--danger-bg)' : 'var(--surface)',
                          borderColor: isOverlap ? 'var(--danger)' : 'var(--border)',
                          opacity: STOP_STATUS_DONE.has(stop.status) ? 0.5 : 1,
                        }}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex items-center gap-2 flex-1 min-w-0">
                            <div
                              className="w-5 h-5 rounded-full flex items-center justify-center shrink-0"
                              style={{
                                background: isOverlap ? 'var(--danger-bg)' : 'var(--hover-bg)',
                                border: isOverlap ? '1px solid var(--danger)' : 'none',
                              }}
                            >
                              <span className="text-2xs font-black" style={{ color: isOverlap ? 'var(--danger)' : 'var(--text-muted)' }}>{i + 1}</span>
                            </div>
                            <div className="min-w-0 flex flex-col gap-0.5">
                              <span className="text-2xs font-bold text-[var(--text-primary)] truncate">
                                {stop.clientName || 'Client'}
                              </span>
                              <span className="text-2xs text-[var(--text-soft)]">{stop.deliveryCity || stop.deliveryAddress}</span>
                            </div>
                          </div>
                          {hasWindow && (
                            <div className="bg-[var(--hover-bg)] border border-[var(--border)] rounded px-1.5 py-0.5 shrink-0">
                              <div className="flex items-center gap-0.5">
                                <IconClock size={9} className="text-[var(--text-muted)]" />
                                <span className="text-2xs font-semibold text-[var(--text-primary)] font-mono whitespace-nowrap">
                                  {formatTime(stop.startTimeWindow) ?? '??:??'}
                                  {stop.endTimeWindow ? ` → ${formatTime(stop.endTimeWindow)}` : ''}
                                </span>
                              </div>
                            </div>
                          )}
                          {!hasWindow && stop.etaAt && (
                            <div className="flex items-center gap-0.5 shrink-0">
                              <IconClock size={9} className="text-[var(--text-soft)]" />
                              <span className="text-2xs text-[var(--text-soft)] font-mono">ETA {stop.etaAt.slice(11, 16)}</span>
                            </div>
                          )}
                        </div>
                      </div>

                      {canPickPosition && (
                        <button
                          type="button"
                          onClick={() => setInsertAfterStopId(stop.id)}
                          className="my-0.5 w-full text-left"
                        >
                          <div className={cn(
                            'px-2.5 py-1 rounded flex items-center gap-1.5',
                            insertAfterStopId === stop.id
                              ? 'border-2 border-[var(--brand)] bg-[var(--danger-bg)]'
                              : 'border-2 border-dashed border-[var(--border)] bg-transparent',
                          )}>
                            {insertAfterStopId === stop.id
                              ? <IconCheck size={10} className="text-[var(--brand)]" />
                              : <div className="w-2.5 h-2.5 rounded-full border-2 border-dashed border-[var(--brand)] opacity-40" />}
                            <span className={cn(
                              'text-2xs font-bold uppercase tracking-[0.05em]',
                              insertAfterStopId === stop.id ? 'text-[var(--brand)]' : 'text-[var(--text-soft)]',
                            )}>
                              Insérer après l'arrêt {i + 1}
                            </span>
                          </div>
                        </button>
                      )}
                    </div>
                  );
                })}

                {selectedRoute.status === 'IN_PROGRESS' && (
                  <div className="px-2.5 py-1.5 rounded border-2 border-dashed border-[var(--brand)] bg-[var(--danger-bg)] flex items-center gap-1.5">
                    <IconCheck size={10} className="text-[var(--brand)]" />
                    <span className="text-2xs font-bold text-[var(--brand)] uppercase tracking-[0.05em]">
                      Nouvel arrêt — ajouté en fin de tournée
                    </span>
                  </div>
                )}
              </div>
            );
          })()}

          {/* Notification info */}
          {(selectedRoute.status === 'VALIDATED' || selectedRoute.status === 'IN_PROGRESS') && (
            <div className="p-3 rounded border border-[var(--success)] bg-[var(--success-bg)] flex items-center gap-2">
              <div className="w-5 h-5 rounded-full bg-[var(--success-bg)] flex items-center justify-center shrink-0">
                <IconCheck size={12} className="text-[var(--success)]" />
              </div>
              <div className="flex flex-col gap-0.5">
                <span className="text-2xs font-bold text-[var(--success)]">Notification automatique</span>
                <span className="text-2xs text-[var(--success)]">
                  Le chauffeur sera notifié en temps réel via l'application mobile.
                </span>
              </div>
            </div>
          )}

          {/* Note field */}
          <FieldTextarea
            label={`${t.reassignDrawer.noteForDriver} ${selectedRoute.status !== 'DRAFT' ? t.reassignDrawer.noteRequired : t.reassignDrawer.noteOptional}`}
            placeholder={selectedRoute.status !== 'DRAFT' ? t.reassignDrawer.notePlaceholder : t.reassignDrawer.noteInternalPlaceholder}
            value={note}
            onChange={e => setNote(e.currentTarget.value)}
            rows={3}
          />
        </div>
      )}
    </AppDrawer>
  );
}

