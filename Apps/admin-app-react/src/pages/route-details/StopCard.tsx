import React from 'react';
import { useT } from '@/lib/LocaleContext';
import { formatMoney, formatMinutes as fmtMins } from '@/lib/utils';
import { Delivery, ProofOfDelivery } from '@/types';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import {
  IconBan, IconBuildingWarehouse, IconChevronDown,
  IconClock, IconFileText, IconMapPin, IconPencil, IconPhone, IconX,
} from '@tabler/icons-react';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { ArticlesTable } from '@/components/data-display/ArticlesTable';
import { DriverNote } from '@/components/data-display/DriverNote';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import stopTabsStyles from '@/styles/stop-tabs.module.scss';
import { fmtLong, fmtTimeWindow, mediaSrc } from './helpers';
import { STOP_STATUS, REMOVABLE_STOP_STATUSES } from './constants';
import type { RouteDetail, RouteStop } from './types';

const AUTO_GENERATED_NOTES = new Set([
  'DELIVERY_COMPLETED',
  'DELIVERY_PARTIALLY_DELIVERED',
  'Route started and package auto-picked up',
  'Picked up at depot',
  'Handoff confirmed — package received',
  'Returned to sender — handoff reverted',
  'Moved by dispatch via reassign',
  'Assigned by dispatch from pool',
  'Imported from ERP via Adapter',
  'Route validated and delivery assigned',
  'Driver started transit',
  'Delivery completed',
  'Delivery failed',
  'Delivery cancelled',
  'Driver cancelled, reassigning',
  'Workflow: timeout reset',
]);

function isRealNote(note?: string | null): boolean {
  if (!note) return false;
  return !AUTO_GENERATED_NOTES.has(note.trim());
}

type StopCardProps = {
  stop: RouteStop;
  delivery: Delivery | undefined;
  pod: ProofOfDelivery | null;
  route: RouteDetail;
  currency: string;
  isActiveRoute: boolean;
  isExpanded: boolean;
  stopRefs: React.MutableRefObject<Record<string, HTMLDivElement | null>>;
  toggleStop: (id: string) => void;
  downloadBL: (delivery: Delivery | undefined, pod: ProofOfDelivery | null) => void;
  openEditWindow: (stop: RouteStop, client: string) => void;
  setRemoveStopTarget: (v: { stopId: string; client: string } | null) => void;
  setCancelStopTarget: (v: { stopId: string; client: string; isPickedUp: boolean } | null) => void;
  setCancelStopReason: (v: string) => void;
  setViewerTitle: (v: string) => void;
  setViewerImage: (v: string | null) => void;
};

export function StopCard({
  stop, delivery, pod, route, currency, isActiveRoute, isExpanded,
  stopRefs, toggleStop, downloadBL,
  openEditWindow, setRemoveStopTarget, setCancelStopTarget, setCancelStopReason,
  setViewerTitle, setViewerImage,
}: StopCardProps) {
  const t = useT();
  const displayStatus = (delivery?.status ?? stop.status) as any;
  const sc2 = STOP_STATUS[displayStatus] ?? STOP_STATUS.SCHEDULED;
  const client = stop.order?.clientName ?? delivery?.clientName ?? '—';
  const amount = stop.order?.totalAmount ?? delivery?.totalAmount ?? 0;
  const weight = stop.order?.totalWeightKg ?? delivery?.totalWeightKg ?? 0;
  const addr = stop.deliveryAddress ?? stop.order?.dropoffAddress ?? delivery?.dropoffAddress ?? '—';
  const orderItems = stop.order?.items ?? delivery?.items ?? [];
  const erpReference = stop.order?.erpOrderId ?? stop.order?.erpExternalRef ?? delivery?.erpId;
  const canRemove = route.status === 'DRAFT' && REMOVABLE_STOP_STATUSES.has(displayStatus);
  const canCancelStop = isActiveRoute && (displayStatus === 'PENDING' || displayStatus === 'SCHEDULED' || displayStatus === 'ARRIVED' || displayStatus === 'PICKED_UP');
  const canEditWindow = route.status === 'VALIDATED' && (displayStatus === 'PENDING' || displayStatus === 'SCHEDULED');

  const isPickup = stop.stopType === 'PICKUP';

  // Pull the full delivery detail on expand (same AdminDeliveryDetailResponse the delivery page uses)
  // so the stop renders the SAME Articles table + driver note — the routes/full payload lacks the
  // split failReason/failureComment. Hook stays above the pickup early-return (rules of hooks).
  const deliveryId = delivery?.id ?? stop.deliveryId;
  const { data: stopDetail } = useQuery<any>({
    queryKey: ['stop-delivery-detail', deliveryId],
    enabled: isExpanded && !!deliveryId && !isPickup,
    staleTime: 15000,
    queryFn: async () => (await api.get(`/api/admin/deliveries/${deliveryId}`)).data,
  });

  if (isPickup) {
    const pickupLabel = stop.parcelCount
      ? (t.routeDetailPage?.pickupLoadCount || '{count} colis à charger').replace('{count}', String(stop.parcelCount))
      : (t.routeDetailPage?.pickupTitle || 'Chargement — Dépôt {depot}').replace('{depot}', stop.sourceDepotName || '');
    return (
      <div
        ref={(el) => { if (el) stopRefs.current[stop.id] = el; }}
        className="border border-cyan-600/30 rounded-lg bg-[var(--surface)] mb-2 cursor-pointer hover:bg-[var(--surface-hover)]"
        onClick={() => toggleStop(stop.id)}
      >
        <div className="p-3 flex items-center justify-between">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <div className="w-6 h-6 rounded flex items-center justify-center text-xs font-bold text-white" style={{ background: '#0891B2' }}>
                {stop.stopOrder}
              </div>
              <IconBuildingWarehouse size={16} className="text-cyan-600 shrink-0" />
              <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{stop.sourceDepotName || t.routeBuilderPage.depotLabel}</p>
              <StatusBadge status={displayStatus} size="sm" />
              <SlaHealthBadge health={stop.slaHealth} />
            </div>
            <div className="flex items-center gap-2 text-xs text-[var(--text-muted)]">
              <IconMapPin size={12} />
              <p className="truncate">{pickupLabel}</p>
            </div>
          </div>
          <div className="flex items-center gap-2 ml-2">
            {stop.routeEtaAt && (
              <p className="text-xs font-mono text-cyan-600 font-semibold">
                {stop.routeEtaAt.slice(11, 16)}
              </p>
            )}
            <IconChevronDown size={16} className={`transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
          </div>
        </div>
        {isExpanded && (
          <div className={stopTabsStyles.expandedContainer}>
            <div className={stopTabsStyles.tabContent}>
              <div className="space-y-2 text-xs">
                {stop.completedAt ? (
                  <div className="flex items-center gap-2 text-green-600 font-medium">
                    <IconClock size={14} />
                    {t.routeBuilderPage.loadingConfirm + ' · ' + stop.completedAt.slice(11, 16)}
                  </div>
                ) : stop.routeEtaAt ? (
                  <div className="flex items-center gap-2 text-[var(--text-muted)]">
                    <IconClock size={14} />
                    {t.routeBuilderPage.etaLabel + ' · ' + stop.routeEtaAt.slice(11, 16)}
                  </div>
                ) : null}
                {stop.routeDistanceKm != null && (
                  <p className="text-[var(--text-muted)]">{stop.routeDistanceKm.toFixed(1)} km · {fmtMins(stop.routeDurationMinutes)}</p>
                )}
                {stop.sourceDepotLat && stop.sourceDepotLng && (
                  <a
                    href={`https://www.google.com/maps?q=${stop.sourceDepotLat},${stop.sourceDepotLng}`}
                    target="_blank"
                    rel="noreferrer"
                    className="text-cyan-600 underline"
                    onClick={(e) => e.stopPropagation()}
                  >
                    {t.routeBuilderPage.depotLabel + ' · ' + stop.sourceDepotName}
                  </a>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  // Detail-backed line data (falls back to the routes/full payload until the detail loads).
  const detailItems: any[] = stopDetail?.items ?? orderItems;
  const failCode: string | null = stopDetail?.failureCode ?? (delivery as any)?.failureCode ?? (stop as any)?.failureCode ?? null;
  const failMotif: string | null = stopDetail?.failReason ?? (delivery as any)?.failReason ?? (stop as any)?.failReason ?? null;
  const stopDriverNote: string | null = pod?.comment ?? stopDetail?.failureComment ?? null;
  const stopCurrency = stopDetail?.currency ?? stop.order?.currency ?? currency;

  return (
    <div
      ref={(el) => { if (el) stopRefs.current[stop.id] = el; }}
      className="border border-[var(--border-color)] rounded-lg bg-[var(--surface)] mb-2 cursor-pointer hover:bg-[var(--surface-hover)]"
      onClick={() => toggleStop(stop.id)}
    >
      <div className="p-3 flex items-center justify-between">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <div className="w-6 h-6 rounded flex items-center justify-center text-xs font-bold text-white" style={{ background: sc2.dot }}>
              {stop.stopOrder}
            </div>
            <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{client}</p>
            <StatusBadge status={displayStatus} size="sm" />
          </div>
          <div className="flex items-center gap-2 text-xs text-[var(--text-muted)]">
            <IconMapPin size={12} />
            <p className="truncate">{addr}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 ml-2">
          <p className="text-xs font-mono font-bold">{formatMoney(amount, stop.order?.currency ?? currency)}</p>
          <IconChevronDown size={16} className={`transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
        </div>
      </div>

      {isExpanded && (
        <div className={stopTabsStyles.expandedContainer}>
          {/* One consolidated panel — no tabs. Operational essentials inline (outcome, note, proof);
              the exhaustive record lives on the delivery page (Ouvrir la livraison →). */}
          <div className={stopTabsStyles.tabContent}>
              <div>
                {/* Ref Links */}
                {erpReference && (
                  <div className="flex flex-wrap gap-2 mb-3" onClick={(e) => e.stopPropagation()}>
                    <a href={`/deliveries/${stop.deliveryId}`} target="_blank" rel="noreferrer" className="no-underline">
                      <span className="inline-flex items-center gap-1 px-2 py-1 rounded text-2xs font-semibold font-mono border bg-[var(--warning-bg)] text-[var(--warning)] border-[var(--warning)]/20">
                        {t.routeDetailPage?.refERP || 'ERP'} · {erpReference}
                      </span>
                    </a>
                  </div>
                )}

                {/* Info Grid */}
                <div className={stopTabsStyles.infoGrid}>
                  {erpReference && <div className={stopTabsStyles.infoRow}><span className={stopTabsStyles.label}>{t.routeBuilderPage.labelErpRef}:</span><span className={stopTabsStyles.value}>{erpReference}</span></div>}
                  {stop.order?.clientPhone && (
                    <div className={`${stopTabsStyles.infoRow} ${stopTabsStyles.soft}`}>
                      <IconPhone size={12} style={{ flexShrink: 0 }} />
                      <span>{stop.order.clientPhone}</span>
                    </div>
                  )}
                  <div className={`${stopTabsStyles.infoRow} ${stopTabsStyles.strong}`}>
                    <span className={stopTabsStyles.label}>{t.routeBuilderPage.labelWeight}:</span>
                    <span className={stopTabsStyles.value}>{weight.toFixed(2)} {t.routeBuilderPage.labelUnitKg}</span>
                    {stop.order?.totalQuantity && <span style={{ marginLeft: 8 }}>| {t.routeBuilderPage.labelQty}: {stop.order.totalQuantity}</span>}
                  </div>
                  {stop.order?.source && <div className={stopTabsStyles.infoRow}><span className={stopTabsStyles.label}>{t.routeBuilderPage.labelSource}:</span><span>{stop.order.source}</span></div>}
                  {(stop.routeDistanceKm != null || stop.routeDurationMinutes != null) && (
                    <div className={stopTabsStyles.infoRow}>
                      {stop.routeDistanceKm != null && `${Number(stop.routeDistanceKm).toFixed(1)} km`}
                      {stop.routeDistanceKm && stop.routeDurationMinutes && ` · `}
                      {stop.routeDurationMinutes != null && `${stop.routeDurationMinutes} ${t.routeBuilderPage.labelMin}`}
                    </div>
                  )}
                  {(stop.startTimeWindow || stop.endTimeWindow) && (
                    <div className={stopTabsStyles.infoRow}>
                      <span className={stopTabsStyles.label}>{t.routeBuilderPage.labelWindow}:</span>
                      <span className={stopTabsStyles.value}>{fmtTimeWindow(stop.startTimeWindow)} - {fmtTimeWindow(stop.endTimeWindow)}</span>
                    </div>
                  )}
                  {stop.order?.deliveryInstructions && <div className={stopTabsStyles.box}><strong>{t.routeBuilderPage.labelInstructions}:</strong> {stop.order.deliveryInstructions}</div>}
                  {isRealNote(stop.notes) && <div className={stopTabsStyles.box}><strong>{t.routeBuilderPage.labelNote}:</strong> {stop.notes}</div>}
                </div>

                {/* Driver note — the driver's own words (POD handover comment, else failure comment) */}
                {stopDriverNote && (
                  <div className="mt-3">
                    <div className="text-2xs font-medium text-[var(--text-muted)] mb-2">
                      {t.deliveryPage.driverNoteLabel}
                    </div>
                    <DriverNote comment={stopDriverNote} driverName={delivery?.driverName} emptyLabel={t.deliveryPage.driverNoteEmpty} />
                  </div>
                )}

                {/* Items — the SAME Articles table as the delivery page & dispatch desk (single source
                    of truth for per-line status + failure motif) */}
                {detailItems.length > 0 && (
                  <div>
                    <div className="text-2xs font-medium text-[var(--text-muted)] mt-3 mb-2">
                      {t.routeBuilderPage.sectionArticles} ({detailItems.length})
                    </div>
                    <ArticlesTable
                      items={detailItems}
                      status={displayStatus}
                      failureCode={failCode}
                      failMotif={failMotif}
                      currency={stopCurrency}
                    />
                    <div className="flex justify-end gap-3 mt-2 pe-3 text-xs">
                      <span style={{ color: 'var(--text-muted)' }}>{t.routeBuilderPage.labelTotal}</span>
                      <span className="font-semibold font-mono" style={{ color: 'var(--text-primary)' }}>{formatMoney(amount, stop.order?.currency ?? currency)}</span>
                    </div>
                  </div>
                )}

                {/* Backorder is no longer created here. When a partial delivery syncs, Odoo creates the
                    backorder picking; the operator imports it from the Import page (per-BL import). */}
              </div>

            {/* Preuve de livraison — inline, only when captured */}
            {pod && (
              <div style={{ marginTop: 12 }}>
                <div className="text-2xs font-medium text-[var(--text-muted)] mb-2">
                  {t.routeBuilderPage.tabProof}
                </div>
                  <div className={stopTabsStyles.podContainer}>
                    <div className={stopTabsStyles.metadata}>
                      <div className={stopTabsStyles.timestamp}>{fmtLong(pod.timestamp)}</div>
                      {(pod.latitude || pod.longitude) && (
                        <div className={stopTabsStyles.coords}>
                          {Number(pod.latitude).toFixed(5)}, {Number(pod.longitude).toFixed(5)}
                        </div>
                      )}
                    </div>

                    {(mediaSrc(pod.signatureUrl, pod.signatureBase64) || mediaSrc(pod.photoUrl, pod.photoBase64)) && (
                      <div className={stopTabsStyles.imagesGrid}>
                        {mediaSrc(pod.signatureUrl, pod.signatureBase64) && (
                          <div
                            className={stopTabsStyles.podCard}
                            onClick={(e) => { e.stopPropagation(); setViewerTitle(t.routeBuilderPage.signedBL); setViewerImage(mediaSrc(pod.signatureUrl, pod.signatureBase64) ?? null); }}
                          >
                            <div className={stopTabsStyles.label}>{t.routeBuilderPage.signedBL}</div>
                            <img src={mediaSrc(pod.signatureUrl, pod.signatureBase64) ?? ''} alt={t.routeBuilderPage.signedBL} />
                          </div>
                        )}
                        {mediaSrc(pod.photoUrl, pod.photoBase64) && (
                          <div
                            className={stopTabsStyles.podCard}
                            onClick={(e) => { e.stopPropagation(); setViewerTitle(t.routeBuilderPage.photoLabel); setViewerImage(mediaSrc(pod.photoUrl, pod.photoBase64) ?? null); }}
                          >
                            <div className={stopTabsStyles.label}>{t.routeBuilderPage.photoLabel}</div>
                            <img src={mediaSrc(pod.photoUrl, pod.photoBase64) ?? ''} alt={t.routeBuilderPage.photoLabel} />
                          </div>
                        )}
                      </div>
                    )}

                    {pod && (
                      <div className={stopTabsStyles.actions}>
                        <button onClick={(e) => { e.stopPropagation(); void downloadBL(delivery, pod); }}>
                          <IconFileText size={11} />
                          {t.routeBuilderPage.deliveryNote}
                        </button>
                      </div>
                    )}
                  </div>
              </div>
            )}
          </div>
        </div>
      )}

      {isExpanded && (
        <div className="border-t border-[var(--border-color)] p-2 bg-[var(--surface-sunken)] flex gap-2">
          {canEditWindow && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={(e) => { e.stopPropagation(); openEditWindow(stop, client); }}
                >
                  <IconPencil size={14} className="mr-1" /> {t.routeBuilderPage.windowLabel}
                </Button>
              </TooltipTrigger>
              <TooltipContent side="top">
                {t.routeBuilderPage.editWindow}
              </TooltipContent>
            </Tooltip>
          )}
          {canRemove && (
            <Button
              size="sm"
              variant="destructive"
              onClick={(e) => { e.stopPropagation(); setRemoveStopTarget({ stopId: stop.id, client }); }}
            >
              <IconX size={14} className="mr-1" /> {t.routeBuilderPage.removeStop}
            </Button>
          )}
          {canCancelStop && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={(e) => { e.stopPropagation(); setCancelStopTarget({ stopId: stop.id, client, isPickedUp: stop.status === 'PICKED_UP' }); setCancelStopReason(''); }}
                >
                  <IconBan size={14} className="mr-1" /> {t.routeBuilderPage.removeFromRoute}
                </Button>
              </TooltipTrigger>
              <TooltipContent side="top">
                {t.routeBuilderPage.removeStopTitle}
              </TooltipContent>
            </Tooltip>
          )}
        </div>
      )}
    </div>
  );
}
