import { useEffect, useMemo, useState } from 'react';
import {
  IconAlertTriangle, IconArrowLeft, IconArrowRight, IconArrowsExchange, IconBolt,
  IconChevronLeft, IconChevronRight, IconExternalLink, IconMapPin, IconPackage, IconPlus, IconSearch,
} from '@tabler/icons-react';
import { AppDrawer } from '../AppDrawer';
import StatusBadge from '@/components/StatusBadge';
import { FieldInput } from '@/components/ui/field';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import type { Driver } from '@/types';
import type { ReassignTarget, RouteData, NearestInfo, Cfg } from './types';
import { ACTIVE_ROUTE, REMOVED, haversineKm, hhmm, toMin, toLocalTime, winLabel, insertPseudo, predictSlot, conflictAt, loadDriverRoute } from './helpers';
import { Timeline } from './Timeline';
import { NoteField, BatchProgress, DriverRow, PresenceDot, Section } from './parts';

export type { ReassignTarget } from './types';

interface Props {
  open: boolean;
  target: ReassignTarget | null;
  targets?: ReassignTarget[];
  drivers: Driver[];
  driversWithRoute?: Set<string>;
  onClose: () => void;
  onSuccess: (routeId?: string) => void;
}

/**
 * Assign / reassign a delivery (or a batch) to a driver. One column, two phases:
 *   1. Pick a driver (OSRM-ranked, presence + GPS + route as text — no status dots).
 *   2. Placement, decided by ONE pivot — does the driver own an active (IN_PROGRESS/VALIDATED) route?
 *        • yes → choose position + window (prefilled for reassign), conflicts blocked client-side, and
 *                the stop is placed into that route via `targetRouteId` (backend schedules it correctly);
 *        • no  → no config; the backend auto-creates a draft and we open the route builder in a new tab.
 * Batch into an active route is configured one-by-one (a stepper): each delivery is conflict-checked
 * against the route plus the siblings already placed before it.
 */
export function ReassignDrawer({ open, target, targets, drivers, driversWithRoute, onClose, onSuccess }: Props) {
  const t = useT();
  const isBatch = (targets?.length ?? 0) > 1;
  const allTargets = useMemo(() => (isBatch ? targets! : (target ? [target] : [])), [isBatch, targets, target]);
  const inField = allTargets.some(x => x.status === 'PICKED_UP' || x.status === 'IN_TRANSIT');
  const isAssign = allTargets.length > 0 && allTargets.every(x => !x.driverName);
  const hasRouteHint = (id: string) => driversWithRoute?.has(id) ?? false;
  const fromDriver = allTargets.find(x => x.driverName)?.driverName ?? null;

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [nearest, setNearest] = useState<Record<string, NearestInfo>>({});
  const [summaries, setSummaries] = useState<Record<string, RouteData | null>>({});
  const [route, setRoute] = useState<RouteData | null>(null);
  const [routeLoading, setRouteLoading] = useState(false);
  const [offlineOpen, setOfflineOpen] = useState(false);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [cfg, setCfg] = useState<Record<string, Cfg>>({});
  const [stepIndex, setStepIndex] = useState(0);

  const patchCfg = (id: string, patch: Partial<Cfg>) =>
    setCfg(prev => ({ ...prev, [id]: { ...(prev[id] ?? { start: '', end: '', order: null, touched: false }), ...patch } }));

  // Reset everything shortly after close (keeps the slide-out clean).
  useEffect(() => {
    if (open) return;
    const id = setTimeout(() => {
      setSelectedId(null); setSearch(''); setNearest({}); setSummaries({}); setRoute(null); setRouteLoading(false);
      setOfflineOpen(false); setNote(''); setSubmitting(false); setServerError(null); setCfg({}); setStepIndex(0);
    }, 300);
    return () => clearTimeout(id);
  }, [open]);

  // Seed per-delivery config from each delivery's own client slot (editable).
  useEffect(() => {
    if (!open) return;
    setCfg(prev => {
      const next = { ...prev };
      for (const x of allTargets) {
        if (!next[x.deliveryId]) next[x.deliveryId] = { start: hhmm(x.timeSlotStartTime), end: hhmm(x.timeSlotEndTime), order: null, touched: false };
      }
      return next;
    });
  }, [open, allTargets]);

  // OSRM road-proximity ranking (single target only).
  useEffect(() => {
    if (!open || isBatch || !target?.deliveryId) { setNearest({}); return; }
    let alive = true;
    api.get(`/api/admin/ops/exceptions/${target.deliveryId}/nearest-drivers`, { params: { limit: 8 } })
      .then(res => {
        if (!alive) return;
        const map: Record<string, NearestInfo> = {};
        (Array.isArray(res.data) ? res.data : []).forEach((r: { driverId: string; etaSeconds: number | null; distanceMeters: number | null }, i: number) => {
          map[r.driverId] = { etaSeconds: r.etaSeconds, distanceMeters: r.distanceMeters, rank: i };
        });
        setNearest(map);
      })
      .catch(() => { if (alive) setNearest({}); });
    return () => { alive = false; };
  }, [open, isBatch, target?.deliveryId]);

  // Prefetch every candidate's route so the picker rows show the route name + status badge. We can't
  // pre-filter by `activeRouteId` — that flag only counts VALIDATED/IN_PROGRESS, so a driver whose only
  // route is a DRAFT would be missed. loadDriverRoute returns drafts too, so the badge reflects them.
  useEffect(() => {
    if (!open) return;
    let alive = true;
    Promise.all(drivers.map(d => loadDriverRoute(d.id).then(r => [d.id, r] as const).catch(() => [d.id, null] as const)))
      .then(entries => { if (alive) setSummaries(Object.fromEntries(entries)); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, drivers]);

  // Load the selected driver's route into the placement phase (reuse the prefetched summary if present).
  useEffect(() => {
    if (!selectedId) { setRoute(null); return; }
    setServerError(null); setStepIndex(0);
    const cached = summaries[selectedId];
    if (cached !== undefined) { setRoute(cached); return; }
    let alive = true;
    setRouteLoading(true);
    loadDriverRoute(selectedId).then(r => { if (alive) setRoute(r); }).finally(() => { if (alive) setRouteLoading(false); });
    return () => { alive = false; };
  }, [selectedId, summaries]);

  // ── Phase 1: driver list ────────────────────────────────────────────────────────
  const needDist = !isBatch && (allTargets[0]?.status === 'PICKED_UP' || allTargets[0]?.status === 'IN_TRANSIT')
    && allTargets[0]?.dropoffLat != null && allTargets[0]?.dropoffLng != null;
  const havKm = (d: Driver) => needDist && d.currentLat != null && d.currentLng != null
    ? haversineKm(d.currentLat, d.currentLng, allTargets[0].dropoffLat!, allTargets[0].dropoffLng!) : null;

  const currentNames = new Set(allTargets.map(x => x.driverName).filter(Boolean));
  const searched = useMemo(() => drivers.filter(d =>
    (!search.trim() || d.name.toLowerCase().includes(search.toLowerCase())) && !currentNames.has(d.name)
  ), [drivers, search, currentNames]);

  const recommendedId = useMemo(() => {
    let best: string | null = null, r = Infinity;
    for (const d of searched) { const n = nearest[d.id]; if (n && n.rank < r) { r = n.rank; best = d.id; } }
    return best;
  }, [searched, nearest]);
  const rank = (a: Driver, b: Driver) => {
    const na = nearest[a.id]?.etaSeconds ?? null, nb = nearest[b.id]?.etaSeconds ?? null;
    if (na != null && nb != null) return na - nb;
    const da = havKm(a), db = havKm(b);
    if (da != null && db != null) return da - db;
    return Number(hasRouteHint(b.id)) - Number(hasRouteHint(a.id));
  };
  const recommended = recommendedId ? searched.find(d => d.id === recommendedId) ?? null : null;
  const online = searched.filter(d => d.onlineStatus === 'ONLINE' && d.id !== recommendedId).sort(rank);
  const onBreak = searched.filter(d => d.onlineStatus === 'ON_BREAK' && d.id !== recommendedId).sort(rank);
  const offlineAll = searched.filter(d => (!d.onlineStatus || d.onlineStatus === 'OFFLINE') && d.id !== recommendedId);
  // "On a route" means an EXECUTABLE route (IN_PROGRESS/VALIDATED). A draft is planning, not a tournée,
  // so a draft-only driver stays in the offline bucket (their row still shows the draft badge).
  const isActiveRoute = (id: string) => { const s = summaries[id]?.status; return !!s && ACTIVE_ROUTE.has(s); };
  const enTournee = offlineAll.filter(d => isActiveRoute(d.id)).sort(rank);
  const offline = offlineAll.filter(d => !isActiveRoute(d.id)).sort(rank);

  // ── Phase 2: placement ──────────────────────────────────────────────────────────
  const selectedDriver = selectedId ? drivers.find(d => d.id === selectedId) ?? null : null;
  const hasActiveRoute = !!route && ACTIVE_ROUTE.has(route.status);
  const routeStops = useMemo(
    // Keep PICKUP stops in the list so the sequence prediction/conflict math matches the backend
    // (which counts them as real stops) and the dispatcher keeps the depot-load as a visual landmark.
    // The pickup renders as a greyed, non-selectable context row (see StopRow) — never a drop target.
    () => (route?.stops ?? []).filter(s => !REMOVED.has(s.status)).sort((a, b) => a.stopOrder - b.stopOrder),
    [route],
  );

  const curTarget = allTargets[stepIndex] ?? null;
  const curId = curTarget?.deliveryId ?? '';
  const curCfg = cfg[curId] ?? { start: '', end: '', order: null, touched: false };

  // Progressive working list: route stops + the siblings placed in earlier steps.
  const workingStops = useMemo(() => {
    let list = routeStops;
    for (let i = 0; i < stepIndex; i++) {
      const x = allTargets[i]; const c = cfg[x.deliveryId];
      if (!c || c.order == null) continue;
      list = insertPseudo(list, c.order, {
        id: `batch-${x.deliveryId}`, deliveryId: x.deliveryId, stopOrder: c.order, status: 'PENDING',
        startTimeWindow: c.start ? `${c.start}:00` : undefined, endTimeWindow: c.end ? `${c.end}:00` : undefined,
        clientName: x.clientName, orderRef: x.orderRef,
      });
    }
    return list;
  }, [routeStops, stepIndex, allTargets, cfg]);

  const startMin = toMin(curCfg.start || null), endMin = toMin(curCfg.end || null);
  const prediction = useMemo(() => predictSlot(workingStops, startMin, endMin), [workingStops, startMin, endMin]);
  const effectiveOrder = curCfg.touched && curCfg.order != null ? curCfg.order : prediction.order;

  const activeConflict = useMemo(() => conflictAt(workingStops, effectiveOrder, startMin, endMin), [workingStops, effectiveOrder, startMin, endMin]);

  const conflictText = activeConflict.kind === 'past' ? t.configureInsertion.warnPast
    : activeConflict.kind === 'prev' ? t.configureInsertion.warnOverlapPrev.replace('{time}', activeConflict.time ?? '')
    : activeConflict.kind === 'next' ? t.configureInsertion.warnOverlapNext.replace('{time}', activeConflict.time ?? '') : null;

  const targetWeight = allTargets.reduce((w, x) => w + (x.totalWeightKg ?? 0), 0);
  const overloadKg = route?.payloadKg != null ? (route.currentLoadKg ?? 0) + targetWeight - route.payloadKg : null;

  // Commit the current step's chosen order so later steps see it, then advance.
  const commitStep = () => patchCfg(curId, { order: effectiveOrder, touched: true });
  const goNext = () => { commitStep(); setStepIndex(i => Math.min(i + 1, allTargets.length - 1)); };
  const goPrev = () => setStepIndex(i => Math.max(i - 1, 0));

  const post = (deliveryId: string, body: Record<string, unknown>) =>
    api.post(`/api/admin/ops/exceptions/${deliveryId}/reassign`, body);

  // Active route → place each delivery with its own position + window.
  const submitPlacement = async () => {
    if (!route) return;
    commitStep();
    setSubmitting(true); setServerError(null);
    const baseNote = note.trim() || (inField ? t.reassignDrawer.inFieldDefaultNote : '');
    let ok = 0, fail = 0, lastMsg: string | null = null;
    for (const x of allTargets) {
      const c = cfg[x.deliveryId] ?? { start: '', end: '', order: null, touched: false };
      const order = x.deliveryId === curId ? effectiveOrder : (c.order ?? undefined);
      const body: Record<string, unknown> = { driverId: selectedId, note: baseNote, targetRouteId: route.id };
      if (order != null) body.insertAtOrder = order;
      if (c.start) body.startTimeWindow = toLocalTime(c.start);
      if (c.end) body.endTimeWindow = toLocalTime(c.end);
      try { await post(x.deliveryId, body); ok++; }
      catch (err: unknown) { fail++; const data = (err as { response?: { data?: { message?: string } } })?.response?.data; if (data?.message) lastMsg = data.message; }
    }
    setSubmitting(false);
    if (ok > 0) { if (fail === 0) showSuccessToast('successReassignToActive'); else showErrorToast(undefined, 'errorReassignPartialSuccess'); onSuccess(route.id); onClose(); }
    else setServerError(lastMsg ?? t.configureInsertion.genericError);
  };

  // No active route → create the draft (driver-only), then open the route builder in a new tab.
  const submitDraft = async () => {
    if (!selectedId) return;
    setSubmitting(true); setServerError(null);
    const baseNote = note.trim() || (inField ? t.reassignDrawer.inFieldDefaultNote : '');
    let ok = 0, fail = 0, routeId: string | undefined, lastMsg: string | null = null;
    for (const x of allTargets) {
      try { const r = await post(x.deliveryId, { driverId: selectedId, note: baseNote }); routeId = r.data?.routeId ?? routeId; ok++; }
      catch (err: unknown) { fail++; const data = (err as { response?: { data?: { message?: string } } })?.response?.data; if (data?.message) lastMsg = data.message; }
    }
    setSubmitting(false);
    if (ok > 0) {
      if (fail === 0) showSuccessToast('successReassignToActive');
      if (routeId) window.open(`/route-builder?routeId=${routeId}`, '_blank', 'noopener');
      onSuccess(routeId); onClose();
    } else setServerError(lastMsg ?? t.configureInsertion.genericError);
  };

  // ── Title / header ────────────────────────────────────────────────────────────────
  const modeTag = isAssign
    ? <span className="inline-flex items-center gap-1.5 text-2xs font-medium text-[var(--text-secondary)]"><IconPlus size={14} />{t.reassignDrawer.assignVerb}</span>
    : <span className="inline-flex items-center gap-1.5 text-2xs font-medium text-[var(--warning)]"><IconArrowsExchange size={14} />{t.reassignDrawer.reassignTitle}</span>;

  const title = selectedDriver ? (
    <div className="flex items-center gap-2 min-w-0">
      <button type="button" onClick={() => setSelectedId(null)}
        className="shrink-0 -ms-1 flex items-center gap-0.5 text-2xs font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors">
        <IconChevronLeft size={15} />{t.assignFlow.backToDrivers}
      </button>
      <span className="text-[var(--text-soft)]">·</span>
      <span className="text-sm font-semibold text-[var(--text-primary)] truncate">{selectedDriver.name}</span>
    </div>
  ) : (
    <div className="flex flex-col gap-0.5 min-w-0">
      <div className="flex items-center gap-2">
        {modeTag}
        {allTargets.length > 1 && <span className="text-2xs text-[var(--text-muted)]">· {t.configureInsertion.batchCount.replace('{n}', String(allTargets.length))}</span>}
      </div>
      <span className="text-xs font-normal text-[var(--text-muted)] truncate">
        {isBatch
          ? (fromDriver ? `${fromDriver} →` : allTargets.map(x => x.clientName ?? x.city).filter(Boolean).slice(0, 3).join(', '))
          : `${target?.orderRef || target?.erpOrderId || target?.deliveryId?.slice(0, 8).toUpperCase()} · ${target?.clientName ?? ''}${fromDriver ? ` · ${fromDriver} →` : ''}`}
      </span>
    </div>
  );

  // ── Footer (placement only) ──────────────────────────────────────────────────────
  const isLast = stepIndex >= allTargets.length - 1;
  const blocked = submitting || !!activeConflict.kind;
  const footer = selectedDriver && !routeLoading ? (
    <div className="w-full">
      {(conflictText || serverError) && (
        <div className="flex items-start gap-2 mb-2.5 text-2xs leading-relaxed" style={{ color: serverError ? 'var(--danger)' : 'var(--warning)' }}>
          <IconAlertTriangle size={13} className="shrink-0 mt-0.5" />
          <span>{serverError ?? conflictText}{activeConflict.kind && !serverError ? ` ${t.assignFlow.blockedByConflict}` : ''}</span>
        </div>
      )}
      {!hasActiveRoute ? (
        <button type="button" disabled={submitting} onClick={submitDraft}
          className="w-full h-9 px-5 rounded-md bg-[var(--brand)] text-white text-sm font-semibold hover:opacity-90 transition-opacity disabled:opacity-50 inline-flex items-center justify-center gap-1.5">
          <IconExternalLink size={15} />{submitting ? t.configureInsertion.submitting : t.assignFlow.createAndOpen}
        </button>
      ) : (
        <div className="flex items-center justify-between gap-3">
          {isBatch ? (
            <button type="button" disabled={stepIndex === 0} onClick={goPrev}
              className="h-9 px-3.5 rounded-md border border-[var(--border)] text-sm font-medium text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] disabled:opacity-40 inline-flex items-center gap-1.5">
              <IconArrowLeft size={15} />{t.assignFlow.stepPrev}
            </button>
          ) : <span className="text-2xs text-[var(--text-muted)]">{t.configureInsertion.landsAt.replace('{pos}', String(effectiveOrder)).replace('{total}', String(workingStops.length + 1))}</span>}
          {isBatch && !isLast ? (
            <button type="button" disabled={blocked} onClick={goNext}
              className="h-9 px-5 rounded-md bg-[var(--brand)] text-white text-sm font-semibold hover:opacity-90 disabled:opacity-50 inline-flex items-center gap-1.5">
              {t.assignFlow.stepNext}<IconArrowRight size={15} />
            </button>
          ) : (
            <button type="button" disabled={blocked} onClick={submitPlacement}
              className="h-9 px-5 rounded-md bg-[var(--brand)] text-white text-sm font-semibold hover:opacity-90 disabled:opacity-50">
              {submitting ? t.configureInsertion.submitting : isBatch ? t.assignFlow.confirmAll.replace('{n}', String(allTargets.length)) : t.assignFlow.confirmOne}
            </button>
          )}
        </div>
      )}
    </div>
  ) : undefined;

  return (
    <AppDrawer open={open} onClose={onClose} title={title} width="520px" footer={footer}>
      {!selectedDriver ? (
        /* ── Phase 1: pick a driver ── */
        <div className="flex flex-col h-full min-h-0">
          <div className="px-4 pt-4 pb-2 shrink-0">
            {/* Reassign context: where the delivery sits right now (current driver + route + status). */}
            {!isAssign && !isBatch && fromDriver && (
              <div className="flex items-center gap-2 mb-3 px-3 py-2 rounded-lg" style={{ background: 'var(--surface-sunken)' }}>
                <IconArrowsExchange size={14} className="text-[var(--text-muted)] shrink-0" />
                <span className="text-2xs text-[var(--text-muted)] shrink-0">{t.assignFlow.currentlyLabel}</span>
                <span className="text-xs font-semibold text-[var(--text-primary)] truncate">{fromDriver}</span>
                {target?.routeName && <span className="text-2xs text-[var(--text-muted)] truncate">· {target.routeName}</span>}
                {target?.routeStatus && <span className="ms-auto shrink-0"><StatusBadge status={target.routeStatus} size="sm" /></span>}
              </div>
            )}
            {isAssign && (
              <div className="flex items-start gap-2 mb-3 px-3 py-2 rounded-lg text-2xs leading-relaxed text-[var(--text-secondary)]" style={{ background: 'var(--surface-sunken)' }}>
                <IconBolt size={14} className="text-[var(--brand)] shrink-0 mt-0.5" />
                <span>{t.reassignDrawer.assignDraftHint}</span>
              </div>
            )}
            <FieldInput placeholder={t.reassignDrawer.searchPlaceholder} value={search} onChange={e => setSearch(e.currentTarget.value)} leftSection={<IconSearch size={14} />} />
          </div>
          <div className="flex-1 overflow-y-auto min-h-0 px-4 pb-4">
            {recommended && (
              <Section label={t.reassignDrawer.recommendedLabel} icon={<IconBolt size={12} className="text-[var(--brand)]" />}>
                <DriverRow driver={recommended} hero hasRoute={hasRouteHint(recommended.id)} summary={summaries[recommended.id]} nearest={nearest} havKm={havKm} onSelect={setSelectedId} t={t} />
              </Section>
            )}
            {online.length > 0 && <Section label={t.reassignDrawer.inService}>{online.map(d => <DriverRow key={d.id} driver={d} hasRoute={hasRouteHint(d.id)} summary={summaries[d.id]} nearest={nearest} havKm={havKm} onSelect={setSelectedId} t={t} />)}</Section>}
            {onBreak.length > 0 && <Section label={t.reassignDrawer.onBreak}>{onBreak.map(d => <DriverRow key={d.id} driver={d} hasRoute={hasRouteHint(d.id)} summary={summaries[d.id]} nearest={nearest} havKm={havKm} onSelect={setSelectedId} t={t} />)}</Section>}
            {enTournee.length > 0 && <Section label={t.reassignDrawer.enTournee}>{enTournee.map(d => <DriverRow key={d.id} driver={d} hasRoute summary={summaries[d.id]} nearest={nearest} havKm={havKm} onSelect={setSelectedId} t={t} />)}</Section>}
            {offline.length > 0 && (
              <div className="mt-3">
                <button type="button" onClick={() => setOfflineOpen(v => !v)} className="flex items-center gap-1.5 py-2 w-full text-2xs font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] border-t border-[var(--border)]">
                  <span>{offlineOpen ? t.reassignDrawer.hideOffline : t.reassignDrawer.showOffline} {offline.length} {t.reassignDrawer.offlineLabel}</span>
                  <IconChevronRight size={12} className="ml-auto" style={{ transform: offlineOpen ? 'rotate(90deg)' : 'none' }} />
                </button>
                {offlineOpen && offline.map(d => <DriverRow key={d.id} driver={d} dimmed hasRoute={hasRouteHint(d.id)} summary={summaries[d.id]} nearest={nearest} havKm={havKm} onSelect={setSelectedId} t={t} />)}
              </div>
            )}
            {searched.length === 0 && <p className="text-sm text-[var(--text-soft)] text-center py-8">{t.reassignDrawer.noDriver}</p>}
          </div>
        </div>
      ) : routeLoading ? (
        <div className="flex-1 flex items-center justify-center py-16"><p className="text-sm text-[var(--text-soft)]">{t.configureInsertion.loading}</p></div>
      ) : !hasActiveRoute ? (
        /* ── Phase 2a: no active route → draft + redirect ── */
        <div className="px-5 py-5 flex flex-col gap-4">
          <div className="flex items-center gap-3">
            <span className="relative shrink-0">
              <DriverAvatarById driverId={selectedDriver.id} name={selectedDriver.name} size={38} />
              <PresenceDot status={selectedDriver.onlineStatus} />
            </span>
            <div className="min-w-0">
              <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{selectedDriver.name}</p>
              <p className="text-2xs text-[var(--text-muted)]">{route ? `${route.name} · ${t.reassignDrawer.routeDraft}` : t.reassignDrawer.noRoutes}</p>
            </div>
          </div>
          <div className="flex items-start gap-2.5 px-3.5 py-3 rounded-xl" style={{ background: 'var(--surface-sunken)' }}>
            <IconMapPin size={16} className="text-[var(--brand)] shrink-0 mt-0.5" />
            <div>
              <p className="text-sm font-semibold text-[var(--text-primary)] mb-0.5">{t.assignFlow.newRouteTitle}</p>
              <p className="text-xs leading-relaxed text-[var(--text-secondary)]">{t.assignFlow.newRouteBody}</p>
            </div>
          </div>
          <NoteField t={t} inField={inField} note={note} setNote={setNote} />
        </div>
      ) : (
        /* ── Phase 2b: active route → position + window (stepper for batch) ── */
        <div className="px-5 py-4 flex flex-col">
          <div className="flex items-center gap-3 mb-3">
            <span className="relative shrink-0">
              <DriverAvatarById driverId={selectedDriver.id} name={selectedDriver.name} size={36} />
              <PresenceDot status={selectedDriver.onlineStatus} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{route!.name}</p>
              <p className="text-2xs text-[var(--text-muted)]">
                {(t.statusLabels[route!.status as keyof typeof t.statusLabels] ?? route!.status)} · {t.configureInsertion.stopsCount.replace('{n}', String(routeStops.length))}
              </p>
            </div>
            {route!.payloadKg != null && (
              <div className="text-end shrink-0">
                <p className="text-2xs text-[var(--text-muted)]">{t.configureInsertion.capacityLabel}</p>
                <p className={cn('text-xs font-semibold tabular-nums inline-flex items-center gap-1', overloadKg != null && overloadKg > 0 ? 'text-[var(--danger)]' : 'text-[var(--text-secondary)]')}>
                  <IconPackage size={12} />{(route!.currentLoadKg ?? 0) + targetWeight}/{route!.payloadKg} kg
                </p>
              </div>
            )}
          </div>

          {isBatch && <BatchProgress targets={allTargets} stepIndex={stepIndex} cfg={cfg} t={t} />}

          {curTarget && (
            <>
              <div className="flex items-baseline justify-between mb-3 mt-1">
                <span className="text-sm font-semibold text-[var(--text-primary)] truncate">
                  {curTarget.orderRef || curTarget.deliveryId.slice(0, 8).toUpperCase()} · {curTarget.clientName ?? ''}
                </span>
                {curTarget.city && <span className="text-2xs text-[var(--text-muted)] shrink-0 ms-2">{curTarget.city}</span>}
              </div>

              <div className="flex gap-3 mb-4">
                <FieldInput label={t.configureInsertion.windowStart} hint={t.configureInsertion.windowStartHint} type="time" value={curCfg.start} onChange={e => patchCfg(curId, { start: e.currentTarget.value })} wrapperClassName="w-32" />
                <FieldInput label={t.configureInsertion.windowEnd} hint={t.configureInsertion.windowEndHint} type="time" value={curCfg.end} onChange={e => patchCfg(curId, { end: e.currentTarget.value })} wrapperClassName="w-32" />
              </div>

              <p className="text-2xs font-semibold uppercase tracking-wide text-[var(--text-muted)] mb-1.5">{t.assignFlow.positionLabel}</p>
              <Timeline
                stops={workingStops} effectiveOrder={effectiveOrder} target={curTarget}
                winLabelText={winLabel(curCfg.start && `${curCfg.start}:00`, curCfg.end && `${curCfg.end}:00`)}
                onPick={(o) => patchCfg(curId, { order: o, touched: true })} t={t}
              />
              <p className="text-2xs text-[var(--text-muted)] mt-2 flex items-center gap-1">
                <IconBolt size={11} className="text-[var(--brand)]" />
                {curCfg.touched ? t.configureInsertion.overrideActive : t.configureInsertion.autoPlaced} · {t.configureInsertion.overrideHint}
              </p>

              <NoteField t={t} inField={inField} note={note} setNote={setNote} />
            </>
          )}
        </div>
      )}
    </AppDrawer>
  );
}
