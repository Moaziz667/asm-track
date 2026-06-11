

import { useCallback, useEffect, useState } from 'react';
import {
  IconArrowLeft,
  IconCalendar,
  IconCheck,
  IconChevronRight,
  IconClock,
  IconMapPin,
  IconUser,
  IconX,
} from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { FieldInput, FieldTextarea } from '@/components/ui/field';
import { AppDrawer } from './AppDrawer';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { DRIVER_STATUS_COLOR } from '@/lib/design-tokens';
import { cn } from '@/lib/utils';
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
}

interface RouteOption {
  id: string;
  name: string;
  date: string;
  status: 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED';
  driverName?: string;
  stops: RouteStop[];
}

export interface ReassignTarget {
  deliveryId: string;
  orderRef?: string;
  clientName?: string;
  city?: string;
  status: string;
  driverName?: string;
  routeId?: string;
  routeName?: string;
  dropoffLat?: number;
  dropoffLng?: number;
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
                  : <span className="text-[8px] font-bold text-white">{idx}</span>
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
        setOfflineExpanded(false);
      }, 300);
    }
  }, [open]);

  useEffect(() => {
    if (!open || drivers.length === 0) return;
    const from = new Date().toISOString().slice(0, 10);
    const to = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
    setPrefetching(true);
    Promise.all(
      drivers.map(d =>
        api.get(`/api/admin/routes/driver/${d.id}`, { params: { from, to } })
          .then(res => ({ id: d.id, routes: (Array.isArray(res.data) ? res.data : []) as RouteOption[] }))
          .catch(() => ({ id: d.id, routes: [] as RouteOption[] }))
      )
    ).then(results => {
      const map: Record<string, RouteOption[]> = {};
      for (const r of results) map[r.id] = r.routes.filter(rt => rt.status !== 'CLOSED' && rt.status !== 'CANCELLED');
      setPrefetchedRoutes(map);
    }).finally(() => setPrefetching(false));
  }, [open, drivers]);

  const fetchDriverRoutes = useCallback(async (driverId: string) => {
    setLoadingRoutes(true);
    setRoutes([]);
    try {
      const from = new Date().toISOString().slice(0, 10);
      const to = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
      const res = await api.get(`/api/admin/routes/driver/${driverId}`, { params: { from, to } });
      const data: RouteOption[] = Array.isArray(res.data) ? res.data : [];
      let active = data.filter(r => r.status !== 'CLOSED' && r.status !== 'CANCELLED');
      if (isBatch) active = active.filter(r => r.status === 'DRAFT');
      setRoutes(active);
    } catch {
      showErrorToast(undefined, 'errorDriverRoutesLoadFailed');
    } finally {
      setLoadingRoutes(false);
    }
  }, [isBatch]);

  const selectDriver = (driverId: string) => {
    setSelectedDriverId(driverId);
    void fetchDriverRoutes(driverId);
    setStep(2);
  };

  const selectRoute = (route: RouteOption) => {
    setSelectedRoute(route);
    setInsertAfterStopId(null);
    setStep(3);
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

  const submit = async () => {
    if (allTargets.length === 0 || !selectedRoute) return;

    const isDraft = selectedRoute.status === 'DRAFT';
    const isInField = allTargets.some(t => t.status === 'PICKED_UP' || t.status === 'IN_TRANSIT');
    if ((isInField || !isDraft) && !note.trim()) {
      showErrorToast(undefined, 'errorNoteRequired');
      return;
    }

    if (!isBatch && (!startTimeWindow.trim() || !endTimeWindow.trim())) {
      showErrorToast(undefined, 'errorTimeWindowRequired');
      return;
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
  const activeStops = selectedRoute?.stops.filter(s => !STOP_STATUS_DONE.has(s.status)).sort((a, b) => a.stopOrder - b.stopOrder) ?? [];

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
          : `${target?.orderRef || target?.deliveryId?.slice(0, 8).toUpperCase()} • ${target?.clientName}`}
      </span>
    </div>
  );

  return (
    <AppDrawer
      open={open}
      onClose={onClose}
      title={drawerTitle}
      width={520}
      footer={step === 3 ? (
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={() => setStep(2)}>
            {t.reassignDrawer.backButton}
          </Button>
          <Button
            size="sm"
            onClick={submit}
            disabled={submitting || (!isAssignMode && !isBatch && selectedRoute?.status !== 'DRAFT' && !note.trim()) || (!isAssignMode && hasTimeConflict)}
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
      {/* Step indicator + breadcrumb */}
      <div className="mb-5 px-5">
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

        const searched = drivers.filter(d =>
          !driverSearch.trim() || d.name.toLowerCase().includes(driverSearch.toLowerCase())
        );
        const online = sortDrivers(searched.filter(d => d.onlineStatus === 'ONLINE'));
        const onBreak = sortDrivers(searched.filter(d => d.onlineStatus === 'ON_BREAK'));
        const offline = sortDrivers(searched.filter(d => !d.onlineStatus || d.onlineStatus === 'OFFLINE'));

        const onlineWithRoute = online.filter(d => hasActiveRoute(d));
        const onlineWithoutRoute = online.filter(d => !hasActiveRoute(d));

        const DriverCard = ({ driver, dimmed = false }: { driver: Driver; dimmed?: boolean }) => {
          const dist = getDistance(driver);
          const routes = driverRoutes(driver);
          const status = driver.onlineStatus ?? 'OFFLINE';
          const statusCfg = DRIVER_STATUS_COLOR[status as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE;
          const activeStopCount = routes.reduce((sum, r) => sum + r.stops.filter(s => !STOP_STATUS_DONE.has(s.status)).length, 0);
          const totalStopCount = routes.reduce((sum, r) => sum + r.stops.length, 0);

          return (
            <button
              key={driver.id}
              type="button"
              onClick={() => selectDriver(driver.id)}
              className="w-full text-left"
              style={{ opacity: dimmed ? 0.6 : 1 }}
            >
              <div className="p-3 rounded-[4px] border border-[var(--border)] bg-transparent hover:bg-[var(--app-bg)] transition-colors cursor-pointer">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 flex-1 min-w-0">
                    <div className="w-2 h-2 rounded-full shrink-0" style={{ background: statusCfg.dot }} />
                    <div className="flex-1 min-w-0 flex flex-col gap-1">
                      <div className="flex items-center gap-1 flex-wrap">
                        <span className="text-sm font-semibold text-[var(--text-primary)]">{driver.name}</span>
                        {dimmed && (
                          <span className="text-[9px] font-medium px-1.5 py-0.5 rounded-[2px]" style={{ background: statusCfg.bg, color: statusCfg.text }}>
                            {statusCfg.label}
                          </span>
                        )}
                      </div>
                      {routes.length > 0 ? (
                        <div className="flex items-center gap-2 flex-wrap">
                          {routes.slice(0, 1).map(r => (
                            <div key={r.id} className="flex items-center gap-1.5">
                              <span className="text-2xs font-medium text-[var(--text-muted)]">
                                {ROUTE_STATUS_LABEL[r.status] ?? r.status}
                              </span>
                              <span className="text-2xs font-mono text-[var(--text-muted)]">
                                {r.stops.filter(s => STOP_STATUS_DONE.has(s.status)).length}/{r.stops.length}
                              </span>
                            </div>
                          ))}
                          {activeStopCount > 0 && (
                            <span className="text-2xs text-[var(--text-muted)]">
                              {activeStopCount} arrêt{activeStopCount !== 1 ? 's' : ''} {t.reassignDrawer.stopFree}
                            </span>
                          )}
                        </div>
                      ) : prefetching ? (
                        <span className="text-2xs text-[var(--text-muted)]">{t.reassignDrawer.loadingRoutes}</span>
                      ) : (
                        <span className="text-2xs text-[var(--text-muted)]">{t.reassignDrawer.noRoutes}</span>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {dist != null && (
                      <span className="text-xs font-medium text-[var(--text-primary)] font-mono">{dist.toFixed(1)} km</span>
                    )}
                    <IconChevronRight size={14} className="text-[var(--text-muted)] opacity-50" />
                  </div>
                </div>
              </div>
            </button>
          );
        };

        const TierLabel = ({ label }: { label: string }) => (
          <span className="text-2xs font-medium text-[var(--text-muted)] pt-2">{label}</span>
        );

        return (
          <div className="flex flex-col gap-2 px-5 pb-4">
            <FieldInput
              placeholder={t.reassignDrawer.searchPlaceholder}
              value={driverSearch}
              onChange={e => setDriverSearch(e.currentTarget.value)}
              leftSection={<IconUser size={14} />}
            />

            {onlineWithRoute.length > 0 && (
              <>
                <TierLabel label={t.reassignDrawer.onlineWithRoute} />
                {onlineWithRoute.map(d => <DriverCard key={d.id} driver={d} />)}
              </>
            )}

            {onlineWithoutRoute.length > 0 && (
              <>
                <TierLabel label={t.reassignDrawer.onlineNoRoute} />
                <p className="text-2xs text-[var(--text-muted)] px-3 py-2 rounded-[3px] bg-[var(--app-bg)]">
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
                  className="flex items-center gap-1.5 py-2 w-full text-2xs font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                >
                  <span className="w-2 h-2 rounded-full bg-[var(--text-muted)] opacity-40" />
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
                  <p className="text-2xs text-[var(--text-muted)] px-3 py-2 rounded-[3px] bg-[var(--app-bg)]">
                    {t.reassignDrawer.offlineWarning}
                  </p>
                )}
              </>
            )}

            {online.length === 0 && onBreak.length === 0 && !offlineExpanded && offline.length === 0 && (
              <p className="text-sm text-[var(--text-soft)] text-center py-6">{t.reassignDrawer.noDriver}</p>
            )}
          </div>
        );
      })()}

      {/* ─── Step 2: Pick route/date ─────────────────────────────────── */}
      {step === 2 && (
        <div className="flex flex-col gap-4 px-5 pb-4">
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
                    <div className="p-3 rounded-[4px] border border-[var(--border)] bg-transparent hover:bg-[var(--app-bg)] transition-colors cursor-pointer">
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
                              <span className="text-2xs text-[#10B981] font-semibold">{activeCount} actifs</span>
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
        <div className="flex flex-col gap-4 px-5 pb-4">
          {/* Delivery info recap */}
          {isBatch ? (
            <div className="p-3 rounded-[4px] border border-[var(--border)] bg-transparent">
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-[var(--text-primary)]">
                  {allTargets.length} livraisons
                </span>
                {allTargets.slice(0, 5).map(t => (
                  <div key={t.deliveryId} className="flex items-center gap-2">
                    <div className="w-[5px] h-[5px] rounded-full bg-[var(--brand)] shrink-0" />
                    <span className="text-2xs text-[var(--text-primary)] font-semibold">{t.clientName ?? '—'}</span>
                    <span className="text-2xs text-[var(--text-muted)]">{t.city}</span>
                    {t.orderRef && <span className="text-[9px] text-[var(--text-soft)] font-mono">{t.orderRef}</span>}
                  </div>
                ))}
                {allTargets.length > 5 && (
                  <span className="text-2xs text-[var(--text-soft)]">+{allTargets.length - 5} de plus…</span>
                )}
              </div>
            </div>
          ) : (
            <div className="p-3 rounded-[4px] border border-[var(--border)] bg-transparent">
              <div className="flex items-center gap-2">
                <div className="w-1.5 h-1.5 rounded-full bg-[var(--brand)] shrink-0" />
                <div className="flex flex-col gap-0.5 flex-1 min-w-0">
                  <span className="text-xs font-medium text-[var(--text-primary)]">{target?.clientName}</span>
                  <span className="text-2xs text-[var(--text-muted)]">{target?.city} • {target?.orderRef}</span>
                </div>
                <span className="text-[9px] font-bold text-[var(--text-soft)] uppercase">{target?.status}</span>
              </div>
            </div>
          )}

          {/* Time windows */}
          {!isBatch && (
            <div className="flex flex-col gap-2">
              <span className="text-2xs font-black text-[var(--text-primary)] uppercase tracking-[0.05em]">
                {t.reassignDrawer.timeWindowLabel}
              </span>
              <div className="grid grid-cols-2 gap-2">
                <FieldInput
                  label={t.reassignDrawer.timeWindowStart}
                  placeholder="08:00"
                  value={startTimeWindow}
                  onChange={e => setStartTimeWindow(e.currentTarget.value)}
                  leftSection={<IconClock size={12} className={hasTimeConflict ? 'text-[#EF4444]' : ''} />}
                  type="time"
                  error={hasTimeConflict ? ' ' : undefined}
                />
                <FieldInput
                  label={t.reassignDrawer.timeWindowEnd}
                  placeholder="18:00"
                  value={endTimeWindow}
                  onChange={e => setEndTimeWindow(e.currentTarget.value)}
                  leftSection={<IconClock size={12} className={hasTimeConflict ? 'text-[#EF4444]' : ''} />}
                  type="time"
                  error={errStartGtEnd ? t.reassignDrawer.timeWindowError : undefined}
                />
              </div>
              {errStartGtEnd && (
                <div className="px-2.5 py-1.5 bg-[#FEF2F2] border border-[#FECACA] rounded">
                  <span className="text-[9px] font-bold text-[#B91C1C]">
                    ⚠ {t.reassignDrawer.timeWindowErrorDesc}
                  </span>
                </div>
              )}
              {overlaps.length > 0 && (
                <div className="px-2.5 py-1.5 bg-[#FFFBEB] border border-[#FDE68A] rounded flex flex-col gap-0.5">
                  <span className="text-[9px] font-black text-[#B45309]">
                    ⚠ {locale === 'ar'
                      ? `تداخل مع ${overlaps.length} محطة`
                      : locale === 'en'
                        ? `Overlaps with ${overlaps.length} stop${overlaps.length > 1 ? 's' : ''}`
                        : `Chevauchement avec ${overlaps.length} arrêt${overlaps.length > 1 ? 's' : ''}`}
                  </span>
                  {overlaps.slice(0, 3).map(o => (
                    <span key={o.stop.id} className="text-[9px] text-[#B45309]">
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
                <span className="text-2xs font-black text-[var(--text-primary)] uppercase tracking-[0.05em]">
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
                        ? 'border-2 border-[var(--brand)] bg-[#FFF5F3]'
                        : 'border-2 border-dashed border-[#E4E4E7] bg-transparent',
                    )}>
                      {insertAfterStopId === '__start__'
                        ? <IconCheck size={10} className="text-[var(--brand)]" />
                        : <div className="w-2.5 h-2.5 rounded-full border-2 border-dashed border-[var(--brand)] opacity-40" />}
                      <span className={cn(
                        'text-[9px] font-bold uppercase tracking-[0.05em]',
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
                          background: isOverlap ? '#FEF2F2' : '#FAFAFA',
                          borderColor: isOverlap ? '#EF4444' : '#E4E4E7',
                          opacity: STOP_STATUS_DONE.has(stop.status) ? 0.5 : 1,
                        }}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex items-center gap-2 flex-1 min-w-0">
                            <div
                              className="w-5 h-5 rounded-full flex items-center justify-center shrink-0"
                              style={{
                                background: isOverlap ? '#EF444420' : '#E4E4E7',
                                border: isOverlap ? '1px solid #EF4444' : 'none',
                              }}
                            >
                              <span className="text-[8px] font-black" style={{ color: isOverlap ? '#EF4444' : '#71717A' }}>{i + 1}</span>
                            </div>
                            <div className="min-w-0 flex flex-col gap-0.5">
                              <span className="text-2xs font-bold text-[var(--text-primary)] truncate">
                                {stop.clientName || 'Client'}
                              </span>
                              <span className="text-[9px] text-[var(--text-soft)]">{stop.deliveryCity || stop.deliveryAddress}</span>
                            </div>
                          </div>
                          {hasWindow && (
                            <div className="bg-[#F4F4F5] border border-[#E4E4E7] rounded px-1.5 py-0.5 shrink-0">
                              <div className="flex items-center gap-0.5">
                                <IconClock size={9} className="text-[var(--text-muted)]" />
                                <span className="text-[9px] font-semibold text-[var(--text-primary)] font-mono whitespace-nowrap">
                                  {formatTime(stop.startTimeWindow) ?? '??:??'}
                                  {stop.endTimeWindow ? ` → ${formatTime(stop.endTimeWindow)}` : ''}
                                </span>
                              </div>
                            </div>
                          )}
                          {!hasWindow && stop.etaAt && (
                            <div className="flex items-center gap-0.5 shrink-0">
                              <IconClock size={9} className="text-[var(--text-soft)]" />
                              <span className="text-[9px] text-[var(--text-soft)] font-mono">ETA {stop.etaAt.slice(11, 16)}</span>
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
                              ? 'border-2 border-[var(--brand)] bg-[#FFF5F3]'
                              : 'border-2 border-dashed border-[#E4E4E7] bg-transparent',
                          )}>
                            {insertAfterStopId === stop.id
                              ? <IconCheck size={10} className="text-[var(--brand)]" />
                              : <div className="w-2.5 h-2.5 rounded-full border-2 border-dashed border-[var(--brand)] opacity-40" />}
                            <span className={cn(
                              'text-[9px] font-bold uppercase tracking-[0.05em]',
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
                  <div className="px-2.5 py-1.5 rounded border-2 border-dashed border-[var(--brand)] bg-[#FFF5F3] flex items-center gap-1.5">
                    <IconCheck size={10} className="text-[var(--brand)]" />
                    <span className="text-[9px] font-bold text-[var(--brand)] uppercase tracking-[0.05em]">
                      Nouvel arrêt — ajouté en fin de tournée
                    </span>
                  </div>
                )}
              </div>
            );
          })()}

          {/* Notification info */}
          {(selectedRoute.status === 'VALIDATED' || selectedRoute.status === 'IN_PROGRESS') && (
            <div className="p-3 rounded border border-[#A7F3D0] bg-[#ECFDF5] flex items-center gap-2">
              <div className="w-5 h-5 rounded-full bg-[#D1FAE5] flex items-center justify-center shrink-0">
                <IconCheck size={12} className="text-[#059669]" />
              </div>
              <div className="flex flex-col gap-0.5">
                <span className="text-2xs font-bold text-[#065F46]">Notification automatique</span>
                <span className="text-[9px] text-[#047857]">
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

