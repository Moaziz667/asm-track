import React from 'react';
import { useT } from '@/lib/LocaleContext';
import { formatMoney, formatMinutes as fmtMins } from '@/lib/utils';
import { Delivery, ProofOfDelivery } from '@/types';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import {
  IconAlertCircle, IconBan, IconBuildingWarehouse, IconChevronDown,
  IconClock, IconFileText, IconMapPin, IconPencil, IconPhone, IconX,
} from '@tabler/icons-react';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { FailureInfo } from '@/components/data-display/FailureInfo';
import { ItemOutcomeBadge } from '@/components/data-display/ItemOutcomeBadge';
import stopTabsStyles from '@/styles/stop-tabs.module.scss';
import { fmtLong, fmtTimeWindow, mediaSrc } from './helpers';
import { STOP_STATUS, REMOVABLE_STOP_STATUSES } from './constants';
import type { RouteDetail, RouteStop } from './types';

type Tab = 'details' | 'pod';

type StopCardProps = {
  stop: RouteStop;
  delivery: Delivery | undefined;
  pod: ProofOfDelivery | null;
  route: RouteDetail;
  currency: string;
  isActiveRoute: boolean;
  isExpanded: boolean;
  tab: Tab;
  setActiveTab: React.Dispatch<React.SetStateAction<Record<string, Tab>>>;
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
  stop, delivery, pod, route, currency, isActiveRoute, isExpanded, tab,
  setActiveTab, stopRefs, toggleStop, downloadBL,
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
  const orderReference = stop.order?.id ?? delivery?.orderId;
  const erpReference = stop.order?.erpOrderId ?? stop.order?.erpExternalRef ?? delivery?.erpId;
  const canRemove = route.status === 'DRAFT' && REMOVABLE_STOP_STATUSES.has(displayStatus);
  const canCancelStop = isActiveRoute && (displayStatus === 'PENDING' || displayStatus === 'SCHEDULED' || displayStatus === 'ARRIVED' || displayStatus === 'PICKED_UP');
  const canEditWindow = route.status === 'VALIDATED' && (displayStatus === 'PENDING' || displayStatus === 'SCHEDULED');

  const isPickup = stop.stopType === 'PICKUP';

  if (isPickup) {
    const pickupLabel = stop.parcelCount
      ? (t.routeDetailPage?.pickupLoadCount || '{count} colis à charger').replace('{count}', String(stop.parcelCount))
      : (t.routeDetailPage?.pickupTitle || 'Chargement — Dépôt {depot}').replace('{depot}', stop.sourceDepotName || '');
    return (
      <div
        ref={(el) => { if (el) stopRefs.current[stop.id] = el; }}
        className="border border-cyan-600/30 rounded-lg bg-[var(--surface-2)] mb-2 cursor-pointer hover:bg-[var(--surface-hover)]"
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

  return (
    <div
      ref={(el) => { if (el) stopRefs.current[stop.id] = el; }}
      className="border border-[var(--border-color)] rounded-lg bg-[var(--surface-2)] mb-2 cursor-pointer hover:bg-[var(--surface-hover)]"
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
          {displayStatus === 'FAILED' && ((delivery as any)?.failureCode || (delivery as any)?.failReason || (stop as any)?.failureCode || (stop as any)?.failReason) && (
            <div className="mt-1.5">
              <FailureInfo
                code={(delivery as any)?.failureCode ?? (stop as any)?.failureCode}
                reason={(delivery as any)?.failReason ?? (stop as any)?.failReason}
                size="xs"
              />
            </div>
          )}
        </div>
        <div className="flex items-center gap-2 ml-2">
          <p className="text-xs font-mono font-bold">{formatMoney(amount, stop.order?.currency ?? currency)}</p>
          <IconChevronDown size={16} className={`transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
        </div>
      </div>

      {isExpanded && (
        <div className={stopTabsStyles.expandedContainer}>
          {/* Tab Bar */}
          <div className={stopTabsStyles.tabBar}>
            {(['details', 'pod'] as const).map((tb) => (
              <button
                key={tb}
                onClick={(e) => { e.stopPropagation(); setActiveTab(prev => ({ ...prev, [stop.id]: tb })); }}
                className={`${stopTabsStyles.tab} ${tab === tb ? stopTabsStyles.active : ''}`}
              >
                {tb === 'details' && (t.routeBuilderPage.tabDetails)}
                {tb === 'pod' && (t.routeBuilderPage.tabProof)}
              </button>
            ))}
          </div>

          {/* Tab Content */}
          <div className={stopTabsStyles.tabContent}>
            {tab === 'details' && (
              <div>
                {/* Ref Links */}
                {(erpReference || stop.deliveryId) && (
                  <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }} onClick={(e) => e.stopPropagation()}>
                    {erpReference && (
                      <a href={`/deliveries/${stop.deliveryId}`} target="_blank" rel="noreferrer" style={{ textDecoration: 'none' }}>
                        <div style={{ fontSize: 10, fontWeight: 700, fontFamily: 'monospace', padding: '4px 8px', borderRadius: 3, background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' }}>
                          {t.routeDetailPage?.refERP || 'ERP'} · {erpReference}
                        </div>
                      </a>
                    )}
                    {stop.deliveryId && (
                      <a href={`/deliveries/${stop.deliveryId}`} target="_blank" rel="noreferrer" style={{ textDecoration: 'none' }}>
                        <div style={{ fontSize: 10, fontWeight: 700, fontFamily: 'monospace', padding: '4px 8px', borderRadius: 3, background: 'var(--surface-3)', color: 'var(--text-soft)', border: '1px solid var(--border-color)' }}>
                          {t.routeDetailPage?.refDelivery || 'LIV'} · {stop.deliveryId.slice(0, 8).toUpperCase()}
                        </div>
                      </a>
                    )}
                  </div>
                )}

                {/* Info Grid */}
                <div className={stopTabsStyles.infoGrid}>
                  {orderReference && <div className={stopTabsStyles.infoRow}><span className={stopTabsStyles.label}>{t.routeBuilderPage.labelOrder}:</span><span className={stopTabsStyles.value}>{orderReference}</span></div>}
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
                  {stop.notes && <div className={stopTabsStyles.box}><strong>{t.routeBuilderPage.labelNote}:</strong> {stop.notes}</div>}
                </div>

                {/* Items Table */}
                {orderItems && orderItems.length > 0 && (
                  <div>
                    <div style={{ fontSize: 11, fontWeight: 700, marginTop: 12, marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.5px', color: 'var(--text-strong)', opacity: 0.85 }}>
                      {t.routeBuilderPage.sectionArticles} ({orderItems.length})
                    </div>
                    <div className={stopTabsStyles.itemsTableWrapper}>
                      <table>
                        <thead>
                          <tr>
                            <th>{t.routeBuilderPage.labelArticle}</th>
                            <th>{t.routeBuilderPage.labelOrdered}</th>
                            <th>{t.routeBuilderPage.labelDelivered}</th>
                            <th>{t.routeBuilderPage.labelStatus}</th>
                            <th>{t.routeBuilderPage.labelUnitPrice}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {orderItems.map((item, idx) => {
                            const isPostPod  = item.quantityDone != null;
                            const qtyDone    = item.quantityDone ?? item.quantity ?? 0;
                            const qtyPlanned = item.quantity ?? 0;
                            let inferredOutcome = null;
                            if (isPostPod) {
                              if (displayStatus === 'DELIVERED' || displayStatus === 'PARTIALLY_DELIVERED') {
                                inferredOutcome = qtyDone > 0 ? 'DELIVERED' : 'REFUSED';
                              } else if (displayStatus === 'FAILED') {
                                const fCode = (delivery as any)?.failureCode || (delivery as any)?.failReason || (stop as any)?.failureCode || (stop as any)?.failReason;
                                if (fCode === 'REFUSED' || fCode === 'CLIENT_REJECTED') {
                                  inferredOutcome = 'REFUSED';
                                }
                              }
                            }
                            const outcome    = item.outcome ?? inferredOutcome;

                            return (
                              <>
                                <tr key={`${item.name}-${idx}`}>
                                  <td className={stopTabsStyles.articleName}>{item.name}</td>
                                  <td>×{qtyPlanned}</td>
                                  <td style={{ color: isPostPod && qtyDone < qtyPlanned ? '#d97706' : 'var(--text-soft)' }}>
                                    {isPostPod ? `×${qtyDone}` : '—'}
                                  </td>
                                  <td>
                                    {outcome ? <ItemOutcomeBadge outcome={outcome} reason={item.reason} /> : '—'}
                                  </td>
                                  <td>{formatMoney(item.unitPrice ?? item.price, stop.order?.currency ?? currency)}</td>
                                </tr>
                                {item.comment && (
                                  <tr key={`${item.name}-${idx}-comment`} className={stopTabsStyles.commentRow}>
                                    <td colSpan={5} dangerouslySetInnerHTML={{ __html: item.comment }} />
                                  </tr>
                                )}
                              </>
                            );
                          })}
                        </tbody>
                        <tfoot>
                          <tr>
                            <td colSpan={4}>{t.routeBuilderPage.labelTotal}</td>
                            <td>{formatMoney(amount, stop.order?.currency ?? currency)}</td>
                          </tr>
                        </tfoot>
                      </table>
                    </div>
                  </div>
                )}

                {/* Backorder is no longer created here. When a partial delivery syncs, Odoo creates the
                    backorder picking; the operator imports it from the Import page (per-BL import). */}
              </div>
            )}

            {tab === 'pod' && (
              <div>
                {!pod ? (
                  <div className={stopTabsStyles.emptyState}>
                    <IconAlertCircle size={12} />
                    <span>{t.routeBuilderPage.noProof}</span>
                  </div>
                ) : (
                  <div className={stopTabsStyles.podContainer}>
                    <div className={stopTabsStyles.metadata}>
                      <div className={stopTabsStyles.timestamp}>{fmtLong(pod.timestamp)}</div>
                      {(pod.latitude || pod.longitude) && (
                        <div className={stopTabsStyles.coords}>
                          {Number(pod.latitude).toFixed(5)}, {Number(pod.longitude).toFixed(5)}
                        </div>
                      )}
                      {pod.comment && <div className={stopTabsStyles.comment}>{pod.comment}</div>}
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
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {isExpanded && (
        <div className="border-t border-[var(--border-color)] p-2 bg-[var(--surface-2)] flex gap-2">
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
