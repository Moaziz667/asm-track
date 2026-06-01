import React, { Suspense } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { Link } from 'react-router-dom';
import StatusBadge from '@/components/StatusBadge';
import { ReplanModal } from './ReplanModal';
import { AppLoader } from '@/components/AppLoader';
import { Button } from '@/components/ui/button';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AppModal } from '@/components/overlays/AppModal';
import { IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { IconReassign, IconReplan } from '@/components/icons/DispatchIcons';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { ReassignDrawer } from '@/components/overlays/ReassignDrawer';
import { cn } from '@/lib/utils';

import { STATUS_DOT, STATUS_TIP, REPLANNABLE_STATUSES, REASSIGNABLE_STATUSES } from './constants';
import type { OpsException } from './types';
import { formatMotif, formatComment, formatSuggestion } from './formatters';
import { rowId } from './utils';
import { DispatchDeskProvider, useDispatchDeskContext } from './hooks/useDispatchDeskState';
import { FiltersSidebar } from './components/FiltersSidebar';
import { KPIStrip } from './components/KPIStrip';
import { DispatchTabs } from './components/DispatchTabs';

function DispatchDeskContentInner() {
  const {
    t,
    isReadOnly,
    mobileTab,
    setMobileTab,
    drivers,
    drawerTargets,
    setDrawerTargets,
    selectedIds,
    setSelectedIds,
    fetchExceptions,
    fetchAllDeliveries,
    pendingAction,
    actionNote,
    setActionNote,
    confirmAction,
    resetActionState,
    returnTarget,
    setReturnTarget,
    returnNote,
    setReturnNote,
    confirmingReturn,
    runConfirmReturn,
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

  const router = useRouter();

  return (
    <TooltipProvider>
      <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>

        {/* Mobile tab switcher */}
        <div className="lg:hidden flex shrink-0 border-b" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
          {([['filters', t.dispatchDeskPage.tabFilters], ['list', t.dispatchDeskPage.tabDispatch]] as const).map(([tab, label]) => (
            <button
              key={tab}
              type="button"
              onClick={() => setMobileTab(tab)}
              className={cn(
                'flex-1 h-10 text-[12px] font-[500] transition-colors',
                mobileTab === tab ? 'border-b-2' : ''
              )}
              style={{
                color: mobileTab === tab ? 'var(--brand)' : 'var(--text-muted)',
                borderColor: mobileTab === tab ? 'var(--brand)' : 'transparent',
              }}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex flex-1 min-h-0 overflow-hidden">
          <FiltersSidebar />

          <div
            className={cn(
              'flex-1 flex flex-col min-w-0 overflow-hidden',
              mobileTab === 'list' ? 'flex' : 'hidden lg:flex'
            )}
            style={{ background: 'var(--surface)' }}
          >
            <KPIStrip />
            <DispatchTabs />
          </div>
        </div>

        {/* ── Overlays ───────────────────────────────────────────────────────── */}

        <ReassignDrawer
          open={drawerTargets.length > 0}
          target={drawerTargets[0] ?? null}
          targets={drawerTargets}
          drivers={drivers}
          onClose={() => setDrawerTargets([])}
          onSuccess={(routeId) => {
            setDrawerTargets([]);
            if (routeId && selectedIds.size > 1) {
              setSelectedIds(new Set());
              router(`/route-builder?routeId=${routeId}`);
            } else {
              setSelectedIds(new Set());
              void fetchExceptions(true);
              void fetchAllDeliveries();
            }
          }}
        />

        <ReplanModal
          pendingAction={pendingAction}
          actionNote={actionNote}
          onNoteChange={setActionNote}
          onConfirm={confirmAction}
          onCancel={resetActionState}
          loading={!!runningAction}
          formatMotif={formatMotif}
        />

        <ConfirmModal
          open={returnTarget !== null}
          title={t.dispatchDeskPage.returnTitle}
          description={t.dispatchDeskPage.returnDescription}
          variant="primary"
          reasonLabel={t.dispatchDeskPage.returnNoteLabel}
          reason={returnNote}
          onReasonChange={setReturnNote}
          confirmLabel={t.dispatchDeskPage.returnConfirmLabel}
          cancelLabel={t.actions.cancel}
          loading={confirmingReturn}
          onConfirm={() => void runConfirmReturn()}
          onCancel={() => { setReturnTarget(null); setReturnNote(''); }}
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
              <span className="text-[14px] font-[600]" style={{ color: 'var(--text-primary)' }}>{t.dispatchDeskPage.detailTitle}</span>
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
                    <span className="text-[11px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.orderLabel}</span>
                    <Link to={`/deliveries/${id}`} onClick={() => setFailedModalRow(null)}>
                      <span className="text-[11px] font-[500] hover:underline" style={{ color: 'var(--text-primary)' }}>{t.dispatchDeskPage.openLink}</span>
                    </Link>
                  </div>
                  <p className="text-[13px] font-[600]" style={{ color: 'var(--text-primary)' }}>{r.orderRef ?? r.erpOrderId ?? id.slice(0, 8)}</p>
                  <p className="text-[12px] font-[500] mt-0.5" style={{ color: 'var(--text-primary)' }}>{r.clientName ?? '—'}</p>
                  {r.clientPhone && <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{r.clientPhone}</p>}
                  {(r.dropoffAddress || r.dropoffCity) && (
                    <p className="text-[11px] mt-0.5" style={{ color: 'var(--text-muted)' }}>{r.dropoffAddress ?? r.dropoffCity}</p>
                  )}
                </div>

                {/* Driver */}
                {r.driverName && (
                  <div className="rounded p-3" style={{ background: 'var(--app-bg)' }}>
                    <p className="text-[11px] font-[500] mb-1.5" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.driverLabel}</p>
                    <div className="flex items-center gap-2">
                      <div style={{ width: 8, height: 8, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                      <div className="flex-1">
                        <p className="text-[12px] font-[500]" style={{ color: 'var(--text-primary)' }}>{r.driverName}</p>
                        {r.driverPhone && <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{r.driverPhone}</p>}
                      </div>
                      <span className="text-[10px] font-[500] ml-auto" style={{ color: 'var(--text-muted)' }}>
                        {STATUS_TIP[driver?.onlineStatus ?? 'OFFLINE']}
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
                      className="text-[11px] font-semibold px-1.5 py-0.5 rounded"
                      style={{
                        color: 'var(--text-primary)',
                        background: r.status === 'CANCELLED' ? 'var(--danger-bg)' : 'var(--warning-bg)',
                      }}
                    >
                      {formatMotif(failureMotif, t)}
                    </span>
                  </div>
                  <p className="text-[12px] leading-relaxed" style={{ color: 'var(--text-primary)' }}>{failureComment}</p>
                  {suggestion && <p className="text-[11px] font-[500] mt-2" style={{ color: 'var(--text-muted)' }}>{suggestion}</p>}
                </div>

                {/* Actions */}
                {!isReadOnly && (
                  <div className="flex items-center justify-end gap-2">
                    {REPLANNABLE_STATUSES.includes(r.status) && (
                      <Button
                        size="sm"
                        variant="outline"
                        className="flex items-center gap-1.5"
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
                        className="flex items-center gap-1.5"
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
