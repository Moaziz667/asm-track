import React, { Suspense } from 'react';
import { Link } from 'react-router-dom';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { ReplanModal } from './ReplanModal';
import { AppLoader } from '@/components/AppLoader';
import { Button } from '@/components/ui/button';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AppModal } from '@/components/overlays/AppModal';
import { IconReassign, IconReplan } from '@/components/icons/DispatchIcons';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { ReassignDrawer } from '@/components/overlays/reassign';

import { STATUS_DOT, getDriverStatusTip, REPLANNABLE_STATUSES, REASSIGNABLE_STATUSES } from './constants';
import type { OpsException } from './types';
import { formatMotif, formatComment, formatSuggestion } from './formatters';
import { rowId } from './utils';
import { DispatchDeskProvider, useDispatchDeskContext } from './hooks/useDispatchDeskState';
import { PageFilterBar, type ActiveFilterValue } from '@/components/layout/PageFilterBar';
import { useDepots } from '@/hooks/useDepots';
import { DispatchTabs } from './components/DispatchTabs';
import { formatAddress } from '@/lib/utils/address';

// ── PageFilterBar bridge — reads from dispatch context ───────────────────────

function DispatchPageFilterBar() {
  const {
    t,
    search, setSearch,
    driverId, setDriverId, drivers,
    zoneFilter, setZoneFilter, zones,
    depotFilter, setDepotFilter,
    statusFilter, setStatusFilter,
    period, setPeriod,
    attentionOnly, setAttentionOnly, mutedCount,
    refreshing, doRefresh,
    clearFilters,
  } = useDispatchDeskContext();
  const { data: depots = [] } = useDepots();
  const activeDepots = depots.filter(d => d.isActive);

  const filterAttributes = [
    {
      key: 'status',
      label: t.dispatchDeskPage.filterStatus,
      multi: true,
      options: [
        { value: 'UNSCHEDULED',         label: t.dispatchDeskPage.filterStatusUnscheduled },
        { value: 'SCHEDULED',           label: t.dispatchDeskPage.filterStatusScheduled },
        { value: 'PICKED_UP',           label: t.dispatchDeskPage.filterStatusPickedUp },
        { value: 'IN_TRANSIT',          label: t.dispatchDeskPage.filterStatusInTransit },
        { value: 'DELIVERED',           label: t.dispatchDeskPage.filterStatusDelivered },
        { value: 'PARTIALLY_DELIVERED', label: t.dispatchDeskPage.filterStatusPartial },
        { value: 'CANCELLED',           label: t.dispatchDeskPage.filterStatusCancelled },
        { value: 'FAILED',              label: t.dispatchDeskPage.filterStatusFailed },
      ],
    },
    {
      key: 'driver',
      label: t.dispatchDeskPage.filterDriver,
      multi: true,
      options: drivers.map(d => ({ value: d.id, label: d.name })),
    },
    {
      key: 'zone',
      label: t.dispatchDeskPage.filterZone,
      multi: true,
      options: (zones ?? []).map((z: { name: string }) => ({ value: z.name, label: z.name })),
    },
    {
      key: 'depot',
      label: t.common?.depot ?? 'Dépôt',
      multi: true,
      options: activeDepots.map(d => ({ value: d.name, label: d.name })),
    },
    {
      key: 'period',
      label: t.dispatchDeskPage.filterPeriod,
      options: [
        { value: 'all',   label: t.dispatchDeskPage.periodAll },
        { value: 'day',   label: t.dispatchDeskPage.periodDay },
        { value: 'week',  label: t.dispatchDeskPage.periodWeek },
        { value: 'month', label: t.dispatchDeskPage.periodMonth },
      ],
    },
  ];

  const activeFilters: Record<string, ActiveFilterValue> = {
    ...(statusFilter.length ? { status: statusFilter } : {}),
    ...(driverId.length   ? { driver: driverId } : {}),
    ...(zoneFilter.length ? { zone: zoneFilter } : {}),
    ...(depotFilter.length ? { depot: depotFilter } : {}),
    ...(period !== 'all' ? { period } : {}),
  };

  // Multi keys toggle the clicked value; null clears the whole key. Single keys (period) replace.
  const toggle = (setter: React.Dispatch<React.SetStateAction<string[]>>, arr: string[], v: string) =>
    setter(arr.includes(v) ? arr.filter(x => x !== v) : [...arr, v]);
  const handleFilterChange = (key: string, value: string | null) => {
    if (value === null) {
      if (key === 'driver') setDriverId([]);
      else if (key === 'zone') setZoneFilter([]);
      else if (key === 'depot') setDepotFilter([]);
      else if (key === 'status') setStatusFilter([]);
      else if (key === 'period') setPeriod('all');
      return;
    }
    if (key === 'status') toggle(setStatusFilter, statusFilter, value);
    else if (key === 'driver') toggle(setDriverId, driverId, value);
    else if (key === 'zone')   toggle(setZoneFilter, zoneFilter, value);
    else if (key === 'depot')  toggle(setDepotFilter, depotFilter, value);
    else if (key === 'period') setPeriod(value as Parameters<typeof setPeriod>[0]);
  };

  const hasAny = Object.keys(activeFilters).length > 0 || !!search;

  return (
    <PageFilterBar
      search={search}
      onSearch={setSearch}
      searchPlaceholder={t.dispatchDeskPage.filterQuickSearchPlaceholder}
      attributes={filterAttributes}
      activeFilters={activeFilters}
      onFilterChange={handleFilterChange}
      onRefresh={doRefresh}
      refreshing={refreshing}
      extraActions={
        <>
          {/*
            Always rendered, and pressed-looking when it is filtering.

            It was hidden whenever nothing was being masked, so on a tab with nothing to hide it
            vanished the instant you pressed it — the control disappearing under the cursor, with no
            visible change to explain it. This is a mode a dispatcher is in, not an action that
            sometimes applies; it says which mode, and adds the count only when there is one to add.
          */}
          <button
            type="button"
            aria-pressed={attentionOnly}
            onClick={() => setAttentionOnly(v => !v)}
            className={`h-8 px-2.5 flex items-center gap-1 border rounded text-sm font-[500] transition-colors ${
              attentionOnly
                ? 'border-[var(--brand)]/40 text-[var(--brand)] bg-[var(--brand)]/10'
                : 'border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)]'
            }`}
          >
            {attentionOnly
              ? (mutedCount > 0
                  ? t.dispatchDeskPage.showAllWithCount.replace('{n}', String(mutedCount))
                  : t.dispatchDeskPage.showAttentionOnly)
              : t.dispatchDeskPage.showAll}
          </button>
          {hasAny && (
            <button
              type="button"
              onClick={clearFilters}
              className="h-8 px-2.5 flex items-center gap-1 border border-[var(--border)] rounded text-sm font-[500] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] transition-colors"
            >
              ✕ {t.common?.clear ?? 'Clear'}
            </button>
          )}
        </>
      }
    />
  );
}

// ─────────────────────────────────────────────────────────────────────────────

function DispatchDeskContentInner() {
  const {
    t,
    isReadOnly,
    drivers,
    drawerTargets,
    setDrawerTargets,
    setSelectedIds,
    fetchExceptions,
    fetchAllDeliveries,
    pendingAction,
    actionNote,
    setActionNote,
    replanScheduledAt,
    setReplanScheduledAt,
    confirmAction,
    resetActionState,
    cancelTarget,
    setCancelTarget,
    cancelReason,
    setCancelReason,
    cancelling,
    runCancel,
    failedModalRow,
    setFailedModalRow,
    alertMap,
    runningAction,
    openActionModal,
  } = useDispatchDeskContext();

  // Route-presence comes straight from the fleet DTO's activeRouteId (today's VALIDATED/IN_PROGRESS
  // route), so it's correct regardless of how the deliveries list is filtered.
  const driversWithRoute = React.useMemo(() => {
    const s = new Set<string>();
    for (const d of drivers) if (d.activeRouteId) s.add(d.id);
    return s;
  }, [drivers]);

  return (
    <TooltipProvider>
      <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>

        <DispatchPageFilterBar />

        <div className="flex flex-1 min-h-0 overflow-hidden">
          <div className="flex-1 flex flex-col min-w-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
            <DispatchTabs />
          </div>
        </div>

        {/* ── Overlays ───────────────────────────────────────────────────────── */}

        <ReassignDrawer
          open={drawerTargets.length > 0}
          target={drawerTargets[0] ?? null}
          targets={drawerTargets}
          drivers={drivers}
          driversWithRoute={driversWithRoute}
          onClose={() => setDrawerTargets([])}
          onSuccess={() => {
            // The drawer handles the route-builder redirect itself (new tab). Here we just
            // clear selection and refresh the desk.
            setDrawerTargets([]);
            setSelectedIds(new Set());
            void fetchExceptions(true);
            void fetchAllDeliveries();
          }}
        />

        <ReplanModal
          pendingAction={pendingAction}
          actionNote={actionNote}
          onNoteChange={setActionNote}
          scheduledAt={replanScheduledAt}
          onScheduledAtChange={setReplanScheduledAt}
          onConfirm={confirmAction}
          onCancel={resetActionState}
          loading={!!runningAction}
          formatMotif={formatMotif}
        />

        <ConfirmModal
          open={cancelTarget !== null}
          title={t.dispatchDeskPage.cancelTitle}
          description={t.dispatchDeskPage.cancelDescription}
          variant="danger"
          reasonLabel={t.dispatchDeskPage.cancelReasonLabel}
          reason={cancelReason}
          onReasonChange={setCancelReason}
          confirmLabel={t.dispatchDeskPage.cancelConfirmLabel}
          cancelLabel={t.dispatchDeskPage.keepLabel}
          loading={cancelling}
          onConfirm={() => void runCancel()}
          onCancel={() => { setCancelTarget(null); setCancelReason(''); }}
        />

        {/* Failed delivery detail modal */}
        <AppModal
          opened={failedModalRow !== null}
          onClose={() => setFailedModalRow(null)}
          title={
            <div className="flex items-center gap-2">
              <span className="text-md font-[600]" style={{ color: 'var(--text-primary)' }}>{t.dispatchDeskPage.detailTitle}</span>
              {failedModalRow && <StatusBadge status={failedModalRow.status} size="sm" />}
            </div>
          }
          size="md"
        >
          {failedModalRow && (() => {
            const r      = failedModalRow;
            const id     = rowId(r);
            const alert  = alertMap.get(id);
            const driver = drivers.find(d => d.id === r.driverId);
            const failureMotif   = alert?.motif ?? r.failureReason ?? r.status;
            const failureComment = alert ? formatComment(alert, t) : r.failureReason ? `${t.dispatchDeskPage.reasonLabel} : ${r.failureReason}` : t.dispatchDeskPage.noComment;
            const suggestion     = alert ? formatSuggestion(alert, t) : '';

            return (
              <div className="flex flex-col gap-4">
                {/* Order summary */}
                <div className="rounded p-3" style={{ background: 'var(--app-bg)' }}>
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-xs font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.orderLabel}</span>
                    <Link to={`/deliveries/${id}`} onClick={() => setFailedModalRow(null)}>
                      <span className="text-xs font-[500] hover:underline" style={{ color: 'var(--text-primary)' }}>{t.dispatchDeskPage.openLink}</span>
                    </Link>
                  </div>
                  <p className="text-base font-[600]" style={{ color: 'var(--text-primary)' }}>{r.orderRef ?? r.erpOrderId ?? id.slice(0, 8)}</p>
                  <p className="text-sm font-[500] mt-0.5" style={{ color: 'var(--text-primary)' }}>{r.clientName ?? '—'}</p>
                  {r.clientPhone && <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{r.clientPhone}</p>}
                  {(r.dropoffAddress || r.dropoffCity) && (
                    <p className="text-xs mt-0.5" style={{ color: 'var(--text-muted)' }} title={r.dropoffAddress ?? undefined}>{formatAddress(r.dropoffAddress) || r.dropoffCity}</p>
                  )}
                </div>

                {/* Driver */}
                {r.driverName && (
                  <div className="rounded p-3" style={{ background: 'var(--app-bg)' }}>
                    <p className="text-xs font-[500] mb-1.5" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.driverLabel}</p>
                    <div className="flex items-center gap-2">
                      <div style={{ width: 8, height: 8, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                      <div className="flex-1">
                        <p className="text-sm font-[500]" style={{ color: 'var(--text-primary)' }}>{r.driverName}</p>
                        {r.driverPhone && <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{r.driverPhone}</p>}
                      </div>
                      <span className="text-2xs font-[500] ml-auto" style={{ color: 'var(--text-muted)' }}>
                        {getDriverStatusTip(driver?.onlineStatus, t)}
                      </span>
                    </div>
                  </div>
                )}

                {/* Failure reason */}
                <div
                  className="rounded p-3"
                  style={{
                    background: 'var(--app-bg)',
                    borderLeft: `3px solid ${r.status === 'CANCELLED' ? 'var(--danger)' : 'var(--warning)'}`,
                  }}
                >
                  <div className="mb-1.5">
                    <span
                      className="text-xs font-semibold px-1.5 py-0.5 rounded"
                      style={{
                        color: 'var(--text-primary)',
                        background: r.status === 'CANCELLED' ? 'var(--danger-bg)' : 'var(--warning-bg)',
                      }}
                    >
                      {formatMotif(failureMotif, t)}
                    </span>
                  </div>
                  <p className="text-sm leading-relaxed" style={{ color: 'var(--text-primary)' }}>{failureComment}</p>
                  {suggestion && <p className="text-xs font-[500] mt-2" style={{ color: 'var(--text-muted)' }}>{suggestion}</p>}
                </div>

                {/* Actions */}
                {!isReadOnly && (
                  <div className="flex items-center justify-end gap-2">
                    {REPLANNABLE_STATUSES.includes(r.status) && (
                      <Button
                        size="sm"
                        className="h-7 px-3 text-xs font-bold rounded-md flex items-center gap-1.5"
                        onClick={() => {
                          setFailedModalRow(null);
                          const exc: OpsException = {
                            deliveryId: id,
                            orderId: r.orderId,
                            orderRef: r.orderRef,
                            routeId: r.routeId,
                            routeName: r.routeName,
                            status: r.status,
                            motif: failureMotif,
                            driverId: r.driverId,
                            driverName: r.driverName,
                            clientName: r.clientName,
                            city: r.dropoffCity,
                            zoneName: r.zoneName,
                            severity: 'WARNING',
                            comment: r.failureReason,
                            createdAt: r.createdAt,
                            updatedAt: r.updatedAt
                          };
                          openActionModal('replan', exc);
                        }}
                      >
                        <IconReplan size={14} />
                        {t.dispatchDeskPage.replanButton}
                      </Button>
                    )}
                    {REASSIGNABLE_STATUSES.includes(r.status) && (
                      <Button
                        size="sm"
                        className="h-7 px-3 text-xs font-bold rounded-md flex items-center gap-1.5"
                        onClick={() => {
                          setFailedModalRow(null);
                          setDrawerTargets([{
                            deliveryId: id,
                            orderRef: r.orderRef,
                            clientName: r.clientName,
                            city: r.dropoffCity,
                            status: r.status,
                            driverName: r.driverName,
                            routeId: r.routeId,
                            routeName: r.routeName
                          }]);
                        }}
                      >
                        <IconReassign size={14} />
                        {t.dispatchDeskPage.reassignButton}
                      </Button>
                    )}
                  </div>
                )}
              </div>
            );
          })()}
        </AppModal>
      </div>
    </TooltipProvider>
  );
}

function DispatchDeskContent() {
  return (
    <DispatchDeskProvider>
      <DispatchDeskContentInner />
    </DispatchDeskProvider>
  );
}

export default function ExceptionsPage() {
  return (
    <Suspense fallback={<AppLoader centered height="200px" size="xl" />}>
      <DispatchDeskContent />
    </Suspense>
  );
}
