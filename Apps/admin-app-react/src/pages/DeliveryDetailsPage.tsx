
import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Link } from 'react-router-dom';
import { AppModal } from '@/components/overlays/AppModal';
import {
  IconRefresh, IconPackage, IconUser, IconRoute,
  IconMapPin, IconCheck, IconX, IconPhone, IconTruck, IconClock,
  IconPhoto, IconWeight, IconCurrencyDollar,
  IconFileText, IconBuildingWarehouse, IconQuote,
} from '@tabler/icons-react';
import { api } from '@/lib/api';
import { formatMoney } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { useLocaleStore } from '@/lib/i18n';
import { DRIVER_STATUS_COLOR } from '@/lib/design-tokens';
import type { Delivery, TimelineEvent, DeliveryItem, ProofOfDelivery } from '@/types';
import StatusBadge from '@/components/StatusBadge';
import SlaTimeline from '@/components/data-display/SlaTimeline';
import { ItemOutcomeBadge } from '@/components/data-display/ItemOutcomeBadge';

// ── Sub-components ────────────────────────────────────────────────────────────

function StatChip({ icon, label, highlight }: { icon: React.ReactNode; label: string; highlight?: boolean }) {
  return (
    <div className="flex items-center gap-[6px] shrink-0 px-2 py-1">
      <span style={{ color: highlight ? 'var(--brand)' : 'var(--text-muted)', display: 'flex', alignItems: 'center', opacity: highlight ? 1 : 0.7 }}>{icon}</span>
      <span className="text-2xs font-medium" style={{ color: highlight ? 'var(--brand)' : 'var(--text-primary)' }}>{label}</span>
    </div>
  );
}

function Section({ title, icon, children }: { title: string; icon?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="border-b border-[var(--border)] pb-6">
      <div className="flex items-center gap-2 mb-4">
        {icon && <span className="text-[var(--text-muted)] flex items-center opacity-60">{icon}</span>}
        <h3 className="text-xs font-medium text-[var(--text-muted)] tracking-wide">{title}</h3>
      </div>
      <div>{children}</div>
    </div>
  );
}

function InfoRow({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  if (!value) return null;
  return (
    <div className="flex items-center justify-between py-2 border-b border-[var(--border)]/30">
      <span className="text-2xs font-normal text-[var(--text-muted)] shrink-0">{label}</span>
      <span
        className="text-xs font-medium text-[var(--text-primary)] text-right ml-4"
        style={{ fontFamily: mono ? 'monospace' : undefined }}
      >
        {value}
      </span>
    </div>
  );
}

function ColumnHeader({ label }: { label: string }) {
  return (
    <h2 className="text-2xs font-bold uppercase tracking-[0.08em] text-[var(--text-muted)] pb-2 border-b border-[var(--border)]">
      {label}
    </h2>
  );
}

function DriverNote({ comment, driverName, emptyLabel }: { comment?: string | null; driverName?: string | null; emptyLabel: string }) {
  if (!comment) {
    return <p className="text-xs italic text-[var(--text-muted)]">{emptyLabel}</p>;
  }
  return (
    <div className="flex gap-3 rounded-md border border-[var(--border)] bg-[var(--app-bg)] p-4">
      <IconQuote size={18} className="shrink-0 text-[var(--brand)] opacity-60" />
      <div className="flex flex-col gap-2 min-w-0">
        <p className="text-sm leading-relaxed italic text-[var(--text-primary)]">“{comment}”</p>
        {driverName && (
          <span className="text-2xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">— {driverName}</span>
        )}
      </div>
    </div>
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function DeliveryDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();

  const [delivery, setDelivery]     = useState<Delivery | null>(null);
  const [history, setHistory]       = useState<TimelineEvent[]>([]);
  const [pod, setPod]               = useState<ProofOfDelivery | null>(null);
  const [loading, setLoading]       = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [viewerImage, setViewerImage] = useState<string | null>(null);
  const [viewerTitle, setViewerTitle] = useState('');
  const [assignedDriverStatus, setAssignedDriverStatus] = useState<string | null>(null);

  usePageBreadcrumb(
    delivery
      ? [{ label: t.pages.deliveries?.title || 'Suivi des livraisons', href: '/deliveries' }, { label: (delivery.orderRef ?? id.slice(0, 8)).toUpperCase() }]
      : [{ label: t.pages.deliveries?.title || 'Suivi des livraisons', href: '/deliveries' }]
  );

  const fetchAll = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true); else setLoading(true);
    try {
      const [dRes, hRes, podRes] = await Promise.allSettled([
        api.get(`/api/admin/deliveries/${id}`),
        api.get(`/api/admin/deliveries/${id}/history`),
        api.get(`/api/admin/deliveries/${id}/pod`),
      ]);

      if (dRes.status === 'fulfilled') {
        setDelivery(dRes.value.data);
        const embedded = dRes.value.data?.statusHistory;
        if (Array.isArray(embedded) && embedded.length > 0) setHistory(embedded);
      } else showErrorToast(t.apiMessages.errorDeliveryNotFound, t.deliveryPage.notFound);

      if (hRes.status === 'fulfilled') {
        const raw = hRes.value.data;
        if (Array.isArray(raw) && raw.length > 0 && history.length === 0) setHistory(raw);
      }

      if (podRes.status === 'fulfilled' && podRes.value.data) setPod(podRes.value.data);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [id]);

  useEffect(() => { void fetchAll(); }, [fetchAll]);

  useEffect(() => {
    if (!delivery?.driverId) return;
    api.get(`/api/admin/fleet/drivers/${delivery.driverId}`)
      .then(res => setAssignedDriverStatus(res.data?.onlineStatus ?? null))
      .catch(() => {});
  }, [delivery?.driverId]);

  if (loading) return (
    <div className="h-screen flex items-center justify-center bg-[var(--app-bg)]">
      <div className="flex flex-col items-center gap-2">
        <svg className="animate-spin h-6 w-6 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
        </svg>
        <span className="text-[var(--text-muted)] font-semibold text-sm">{t.deliveryPage.loadingFile}</span>
      </div>
    </div>
  );

  if (!delivery) return (
    <div className="h-full flex items-center justify-center bg-[var(--app-bg)]">
      <div className="flex flex-col items-center gap-3">
        <IconX size={32} color="#EF4444" />
        <span className="text-sm text-[var(--text-muted)]">{t.deliveryPage.notFound}</span>
        <button
          type="button"
          className="text-sm text-[var(--text-muted)] hover:text-[var(--text-primary)] underline transition-colors"
          onClick={() => navigate(-1)}
        >
          {t.deliveryPage.returnButton}
        </button>
      </div>
    </div>
  );

  const items: DeliveryItem[] = (delivery as any).items ?? [];
  const orderRef = (delivery.orderRef ?? id.slice(0, 8)).toUpperCase();
  const isCancelled = delivery.status === 'CANCELLED';

  // Delivery time window (start–end) for scheduled orders. Accepts "HH:mm:ss" / "HH:mm".
  const fmtHm = (s?: string | null) => {
    if (!s) return null;
    const m = /(\d{1,2}):(\d{2})/.exec(s);
    return m ? `${m[1].padStart(2, '0')}:${m[2]}` : s;
  };
  const winStart = fmtHm(delivery.timeSlotStartTime);
  const winEnd = fmtHm(delivery.timeSlotEndTime);
  const slotName = delivery.timeSlotName;
  const deliveryWindow = winStart && winEnd
    ? (slotName ? `${slotName} · ${winStart}–${winEnd}` : `${winStart}–${winEnd}`)
    : (slotName ?? null);

  // The driver's own words. For completed deliveries that's the POD comment; a FAILED
  // delivery has no POD — the free text is stored on failReason as "Catalog label — driver
  // comment" (see DispatchService.toDetailResponse → failureComment(failReason)). Take only
  // the part after " — " so the note shows the driver's words; the catalog motif stays in the SLA.
  const failText: string | null = (delivery as any).failureComment ?? (delivery as any).failReason ?? delivery.failureReason ?? null;
  const failComment = failText && failText.includes(' — ') ? failText.slice(failText.indexOf(' — ') + 3).trim() : null;
  const driverNoteText: string | null = pod?.comment ?? failComment ?? null;

  return (
    <div className="h-full overflow-y-auto bg-[var(--app-bg)] dispatch-card" style={{ scrollbarWidth: 'thin' }}>
      <div className="max-w-[960px] mx-auto px-6 py-8 pb-12">
        <div className="flex flex-col gap-6">

          {/* ── Entity header ──────────────────────────────────────────── */}
          <div>
            <div className="flex items-start justify-between gap-4 mb-6">
              <div className="flex flex-col gap-3 min-w-0 flex-1">
                {/* Status row — failure detail intentionally NOT shown here; the SLA
                    timeline (right column) is the single source of truth for it. */}
                <div className="flex items-center gap-2">
                  <StatusBadge status={delivery.status} size="sm" />
                  {isCancelled && (
                    <span className="text-2xs font-medium px-2 py-1 rounded-xs bg-gray-50 text-gray-600 border border-gray-200">{t.deliveryPage.cancelled}</span>
                  )}
                </div>

                {/* Client name */}
                <h1 className="font-semibold text-3xl leading-[1.2] text-[var(--text-primary)] tracking-[-0.01em]">
                  {delivery.clientName ?? t.deliveryPage.unknownClient}
                </h1>

                {/* Meta line */}
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <code className="font-mono font-semibold text-[var(--brand)]">{orderRef}</code>
                  {delivery.clientPhone && (
                    <>
                      <span className="text-[var(--border)]">·</span>
                      <span className="flex items-center gap-1">
                        <IconPhone size={10} style={{ color: 'var(--text-muted)' }} />
                        <span className="text-[var(--text-muted)]">{delivery.clientPhone}</span>
                      </span>
                    </>
                  )}
                  {delivery.createdAt && (
                    <>
                      <span className="text-[var(--border)]">·</span>
                      <span className="text-[var(--text-muted)]">
                        {new Date(delivery.createdAt).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short', year: '2-digit' })}
                      </span>
                    </>
                  )}
                </div>
              </div>

              <button
                type="button"
                className="w-7 h-7 flex items-center justify-center rounded-sm border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors shrink-0"
                onClick={() => fetchAll(true)}
                title={t.deliveryPage.refresh}
                disabled={refreshing}
              >
                <IconRefresh size={13} className={refreshing ? 'animate-spin' : ''} />
              </button>
            </div>

            {/* Stat chips */}
            <div className="flex flex-wrap gap-2">
              {items.length > 0 && (
                <StatChip icon={<IconPackage size={11} />} label={`${items.length} article${items.length > 1 ? 's' : ''}`} />
              )}
              {(delivery as any).totalWeightKg && (
                <StatChip icon={<IconWeight size={11} />} label={`${(delivery as any).totalWeightKg} kg`} />
              )}
              {(delivery as any).totalAmount && (
                <StatChip icon={<IconCurrencyDollar size={11} />} label={formatMoney((delivery as any).totalAmount, (delivery as any).currency)} highlight />
              )}
              {(delivery as any).zoneName && (
                <StatChip icon={<IconMapPin size={11} />} label={(delivery as any).zoneName} />
              )}
              {!((delivery as any).zoneName) && delivery.dropoffCity && (
                <StatChip icon={<IconMapPin size={11} />} label={delivery.dropoffCity} />
              )}
              {delivery.routeId && (
                <Link to={`/routes/${delivery.routeId}`} style={{ textDecoration: 'none' }}>
                  <StatChip icon={<IconRoute size={11} />} label={delivery.routeName ?? t.deliveryPage.seeRoute} />
                </Link>
              )}
            </div>

            {/* Failure motif already shown at the top (FailureInfo) + in the SLA timeline history;
                only the cancellation notice stays here. */}
            {isCancelled && (delivery as any).cancelReason && (
              <div className="px-3 py-2 rounded-xs border border-gray-200 bg-gray-50 flex items-start gap-2">
                <IconX size={12} style={{ color: 'var(--text-muted)', flexShrink: 0, marginTop: 1 }} />
                <span className="text-xs text-[var(--text-muted)]">{(delivery as any).cancelReason}</span>
              </div>
            )}
          </div>

          {/* ── Two-column body: order info (left) · status & tracking (right) ── */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-8 md:gap-0">

            {/* ════ LEFT — Order information ════ */}
            <div className="flex flex-col gap-8 md:pr-10">
              <ColumnHeader label={t.deliveryPage.columnInfo} />

              {/* Client */}
              <Section title={t.deliveryPage.sectionClient} icon={<IconUser size={12} />}>
                <div className="flex flex-col">
                  <InfoRow label={t.deliveryPage.labelName}      value={delivery.clientName} />
                  <InfoRow label={t.deliveryPage.labelPhone}    value={delivery.clientPhone} />
                  <InfoRow label={t.deliveryPage.labelAddress}  value={delivery.dropoffAddress} />
                  <InfoRow label={t.deliveryPage.labelCity}     value={delivery.dropoffCity} />
                  <InfoRow label={t.deliveryPage.labelPostalCode} value={delivery.dropoffPostalCode} />
                  <InfoRow label={t.deliveryPage.labelZone}     value={(delivery as any).zoneName} />
                </div>
              </Section>

              {/* Order */}
              <Section title={t.deliveryPage.sectionOrder} icon={<IconPackage size={12} />}>
                <div className="flex flex-col">
                  <InfoRow label={t.deliveryPage.labelReference}   value={delivery.orderRef} mono />
                  <InfoRow label={t.deliveryPage.labelInternalId}  value={delivery.id} mono />
                  <InfoRow label={t.deliveryPage.labelTotalWeight} value={(delivery as any).totalWeightKg ? `${(delivery as any).totalWeightKg} kg` : null} />
                  <InfoRow label={t.deliveryPage.labelAmount}     value={(delivery as any).totalAmount ? formatMoney((delivery as any).totalAmount, (delivery as any).currency) : null} />
                  <InfoRow label={t.deliveryPage.labelSource}     value={t.sources[(delivery as any).source] ?? (delivery as any).source} />
                  {(delivery as any).odooSyncStatus && (delivery as any).odooSyncStatus !== 'SYNCED' && (
                    <InfoRow label={t.deliveryPage.labelSyncErp} value={t.syncStatus[(delivery as any).odooSyncStatus] ?? (delivery as any).odooSyncStatus} />
                  )}
                  <InfoRow label={t.deliveryPage.labelCreatedAt}  value={delivery.createdAt ? new Date(delivery.createdAt).toLocaleString('fr-FR') : null} />
                  <InfoRow label={t.deliveryPage.labelUpdatedAt}  value={delivery.updatedAt ? new Date(delivery.updatedAt).toLocaleString('fr-FR') : null} />
                </div>
              </Section>

              {/* Engagement / Fulfillment — promised date, delivery window, BL, source depot */}
              <Section title={t.deliveryPage.sectionFulfillment} icon={<IconBuildingWarehouse size={12} />}>
                <div className="flex flex-col">
                  <InfoRow
                    label={t.deliveryPage.scheduledLabel}
                    value={(delivery as any).scheduledAt
                      ? new Date((delivery as any).scheduledAt).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
                        + ((delivery as any).rescheduledAt ? ` · ${t.deliveryPage.rescheduledBadge}` : '')
                      : '—'}
                  />
                  {/* Delivery time window (start–end) for scheduled orders */}
                  <InfoRow label={t.deliveryPage.windowLabel} value={deliveryWindow} />
                  <InfoRow label={t.deliveryPage.blNumberLabel} value={(delivery as any).blNumber} mono />
                  <InfoRow
                    label={t.deliveryPage.sourceDepotLabel}
                    value={(delivery as any).sourceDepotName ?? (delivery as any).warehouseCode}
                  />
                </div>
                {(delivery as any).blNumber && (
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        const res = await api.get(`/api/admin/deliveries/${delivery.id}/bon-livraison`, { responseType: 'blob' });
                        const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
                        window.open(url, '_blank');
                        setTimeout(() => URL.revokeObjectURL(url), 60000);
                      } catch {
                        showErrorToast(null);
                      }
                    }}
                    className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-sm"
                    style={{ background: 'var(--brand)', color: '#fff' }}
                  >
                    <IconFileText size={13} /> {t.deliveryPage.viewBL}
                  </button>
                )}
              </Section>

              {/* Shipments of the same sale order (original + backorder(s) / multi-depot splits) */}
              {Array.isArray((delivery as any).relatedShipments) && (delivery as any).relatedShipments.length > 1 && (
                <Section
                  title={`${t.deliveryPage.relatedShipmentsTitle}${(delivery as any).erpExternalRef ? ' · ' + (delivery as any).erpExternalRef : ''}`}
                  icon={<IconPackage size={12} />}
                >
                  <div className="flex flex-col">
                    {((delivery as any).relatedShipments as Array<{ deliveryId: string; blNumber?: string; status: string; current: boolean }>).map((s) => (
                      s.current ? (
                        <div key={s.deliveryId} className="flex items-center justify-between py-2 border-b border-[var(--border)]/30">
                          <span className="text-xs font-semibold text-[var(--text-primary)] font-mono">{s.blNumber || '—'}</span>
                          <div className="flex items-center gap-2">
                            <StatusBadge status={s.status} size="sm" />
                            <span className="text-2xs text-[var(--text-muted)]">{t.deliveryPage.relatedShipmentCurrent}</span>
                          </div>
                        </div>
                      ) : (
                        <Link key={s.deliveryId} to={`/deliveries/${s.deliveryId}`} className="flex items-center justify-between py-2 border-b border-[var(--border)]/30 hover:bg-[var(--hover-bg)] rounded-sm px-1 -mx-1">
                          <span className="text-xs font-semibold text-[var(--brand)] font-mono">{s.blNumber || '—'}</span>
                          <StatusBadge status={s.status} size="sm" />
                        </Link>
                      )
                    ))}
                  </div>
                </Section>
              )}

              {/* Items table */}
              {items.length > 0 && (
                <Section title={t.deliveryPage.itemsCount.replace('{count}', String(items.length)).replace('{plural}', items.length > 1 ? 's' : '')} icon={<IconPackage size={12} />}>
                  <div className="overflow-x-auto">
                    <table style={{ width: '100%', minWidth: 460, borderCollapse: 'collapse' }}>
                      <thead>
                        <tr style={{ borderBottom: '1px solid var(--border)', background: 'transparent' }}>
                          {[t.deliveryPage.tableDesignation, t.deliveryPage.tableSku, t.deliveryPage.tableQty, t.deliveryPage.tableQtyDone, t.deliveryPage.tableUnitPrice, t.deliveryPage.tableTotal].map(h => (
                            <th key={h} style={{
                              padding: '10px 12px', textAlign: 'left', fontSize: 10,
                              fontWeight: 500, color: 'var(--text-muted)', border: 'none',
                            }}>{h}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {items.map((item, i) => (
                          <tr key={i} style={{ borderBottom: '1px solid var(--border)/30' }} className="hover:bg-[var(--app-bg)]/40 transition-colors">
                            <td style={{ padding: '10px 12px', border: 'none' }}>
                              <span className="text-sm font-medium text-[var(--text-primary)]">{item.name ?? '—'}</span>
                              {item.outcome && item.outcome !== 'DELIVERED' && (
                                <div className="mt-1"><ItemOutcomeBadge outcome={item.outcome} reason={item.reason} /></div>
                              )}
                            </td>
                            <td style={{ padding: '10px 12px', border: 'none' }}>
                              <span className="text-2xs font-mono text-[var(--text-muted)]">{item.sku ?? '—'}</span>
                            </td>
                            <td style={{ padding: '10px 12px', border: 'none' }}>
                              <span className="text-sm text-[var(--text-primary)]">{item.quantity ?? '—'}</span>
                            </td>
                            <td style={{ padding: '10px 12px', border: 'none' }}>
                              <span className="text-sm font-medium" style={{ color: item.quantityDone != null && item.quantityDone < (item.quantity ?? 0) ? '#EF4444' : '#10B981' }}>
                                {item.quantityDone ?? '—'}
                              </span>
                            </td>
                            <td style={{ padding: '10px 12px', border: 'none' }}>
                              <span className="text-xs text-[var(--text-primary)]">{formatMoney(item.unitPrice, (delivery as any).currency)}</span>
                            </td>
                            <td style={{ padding: '10px 12px', border: 'none' }}>
                              <span className="text-xs font-semibold text-[var(--text-primary)]">
                                {item.unitPrice != null && item.quantity != null
                                  ? formatMoney(item.unitPrice * item.quantity, (delivery as any).currency) : '—'}
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Section>
              )}
            </div>{/* ════ /LEFT ════ */}

            {/* ════ RIGHT — Status & tracking (vertical hairline divider) ════ */}
            <div className="flex flex-col gap-8 md:pl-10 md:border-l md:border-[var(--border)]">
              <ColumnHeader label={t.deliveryPage.columnStatus} />

            {/* Driver + Route */}
            {(delivery.driverId || delivery.routeId) && (
              <Section title={t.deliveryPage.sectionDriverRoute} icon={<IconTruck size={12} />}>
                <div className="flex flex-col md:flex-row gap-8">
                  {delivery.driverId && (
                    <div className="flex items-start gap-3">
                      <IconTruck size={14} style={{ color: 'var(--text-muted)', marginTop: 2, opacity: 0.6, flexShrink: 0 }} />
                      <div className="flex flex-col gap-1">
                        <span className="text-2xs font-medium text-[var(--text-muted)] uppercase tracking-wide">{t.deliveryPage.labelDriver}</span>
                        <div className="flex items-center gap-2">
                          {assignedDriverStatus && (() => {
                            const cfg = DRIVER_STATUS_COLOR[assignedDriverStatus as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE;
                            return <div style={{ width: 6, height: 6, borderRadius: '50%', background: cfg.dot, flexShrink: 0 }} />;
                          })()}
                          <span className="text-sm font-semibold text-[var(--text-primary)]">{delivery.driverName ?? '—'}</span>
                          {assignedDriverStatus && (() => {
                            const cfg = DRIVER_STATUS_COLOR[assignedDriverStatus as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE;
                            return <span className="text-2xs font-medium" style={{ color: cfg.text }}>{cfg.label}</span>;
                          })()}
                        </div>
                        {delivery.driverPhone && (
                          <span className="text-2xs text-[var(--text-muted)]">{delivery.driverPhone}</span>
                        )}
                      </div>
                    </div>
                  )}
                  {delivery.driverId && delivery.routeId && (
                    <div className="hidden md:block w-px bg-[var(--border)]/20" />
                  )}
                  {delivery.routeId && (
                    <div className="flex items-start gap-3">
                      <IconRoute size={14} style={{ color: 'var(--text-muted)', marginTop: 2, opacity: 0.6, flexShrink: 0 }} />
                      <div className="flex flex-col gap-1">
                        <span className="text-2xs font-medium text-[var(--text-muted)] uppercase tracking-wide">{t.deliveryPage.labelRoute}</span>
                        <Link to={`/routes/${delivery.routeId}`} style={{ textDecoration: 'none' }}>
                          <span className="text-sm font-semibold text-[var(--brand)] hover:underline cursor-pointer">
                            {delivery.routeName ?? delivery.routeId.slice(0, 8).toUpperCase()}
                          </span>
                        </Link>
                      </div>
                    </div>
                  )}
                </div>
              </Section>
            )}

            {/* SLA journey — single source of truth (phase + health + per-phase event log).
                hidePodComment: the driver's note is rendered once below as a friendly callout. */}
            <Section title={t.deliveryPage.sectionTimeline} icon={<IconClock size={12} />}>
              <SlaTimeline deliveryId={delivery.id || id} variant="detailed" hidePodComment />
            </Section>

            {/* Driver note — the driver's POD comment, shown once, in a friendly callout */}
            <Section title={t.deliveryPage.driverNoteLabel} icon={<IconQuote size={12} />}>
              <DriverNote comment={driverNoteText} driverName={delivery.driverName} emptyLabel={t.deliveryPage.driverNoteEmpty} />
            </Section>

            {/* Proof of delivery — images + capture metadata (the note lives in Driver note above) */}
            <Section title={t.deliveryPage.sectionProof} icon={<IconCheck size={12} />}>
              {!pod ? (
                <div className="flex items-center gap-4">
                  <ProofPlaceholder icon={<IconPhoto size={20} />} label={t.deliveryPage.noPhoto} />
                  <ProofPlaceholder icon={<IconCheck size={20} />} label={t.deliveryPage.noBL} />
                </div>
              ) : (
                <div className="flex flex-col gap-4">
                  <div className="flex flex-wrap items-start gap-6">
                    {(pod.photoUrl || pod.photoBase64) ? (
                      <div
                        className="overflow-hidden border border-[var(--border)] rounded-sm bg-[var(--app-bg)] cursor-zoom-in hover:opacity-80 transition-opacity"
                        onClick={() => { setViewerTitle(t.deliveryPage.proofPhoto); setViewerImage(pod.photoUrl ?? `data:image/jpeg;base64,${pod.photoBase64}`); }}
                      >
                        <img
                          src={pod.photoUrl ?? `data:image/jpeg;base64,${pod.photoBase64}`}
                          alt={t.deliveryPage.proofPhoto}
                          style={{ width: 160, height: 120, objectFit: 'contain', display: 'block' }}
                        />
                      </div>
                    ) : (
                      <ProofPlaceholder icon={<IconPhoto size={20} />} label={t.deliveryPage.noPhoto} />
                    )}
                    {(pod.signatureUrl || pod.signatureBase64) ? (
                      <div
                        className="overflow-hidden border border-[var(--border)] rounded-sm bg-[var(--app-bg)] cursor-zoom-in hover:opacity-80 transition-opacity"
                        onClick={() => { setViewerTitle(t.deliveryPage.proofSignedBL); setViewerImage(pod.signatureUrl ?? `data:image/png;base64,${pod.signatureBase64}`); }}
                      >
                        <img
                          src={pod.signatureUrl ?? `data:image/png;base64,${pod.signatureBase64}`}
                          alt={t.deliveryPage.proofSignedBL}
                          style={{ width: 160, height: 120, objectFit: 'contain', display: 'block', padding: 8 }}
                        />
                      </div>
                    ) : (
                      <ProofPlaceholder icon={<IconCheck size={20} />} label={t.deliveryPage.noBL} />
                    )}
                  </div>
                  {/* Capture metadata: when + where the proof was collected */}
                  {((pod.collectedAt ?? pod.timestamp) || (pod.lat ?? pod.latitude) != null) && (
                    <div className="flex flex-wrap gap-x-10 gap-y-2">
                      {(pod.collectedAt ?? pod.timestamp) && (
                        <div>
                          <span className="text-2xs font-medium text-[var(--text-muted)] uppercase tracking-wide">{t.deliveryPage.collectedAtLabel}</span>
                          <p className="text-xs text-[var(--text-primary)] mt-1">{new Date(pod.collectedAt ?? pod.timestamp!).toLocaleString('fr-FR')}</p>
                        </div>
                      )}
                      {(pod.lat ?? pod.latitude) != null && (
                        <div>
                          <span className="text-2xs font-medium text-[var(--text-muted)] uppercase tracking-wide">{t.deliveryPage.coordinatesLabel}</span>
                          <p className="text-xs font-mono text-[var(--text-primary)] mt-1">{(pod.lat ?? pod.latitude)?.toFixed(5)}, {(pod.lng ?? pod.longitude)?.toFixed(5)}</p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </Section>

            </div>{/* ════ /RIGHT ════ */}
          </div>{/* ── Two-column body */}
        </div>{/* ── Page stack */}
      </div>

      {/* ── Image viewer ─────────────────────────────────────────────────── */}
      <AppModal
        opened={!!viewerImage}
        onClose={() => setViewerImage(null)}
        title={viewerTitle}
        subtitle={t.deliveryPage.sectionProof}
        size="xl"
        zIndex={10000}
      >
        {viewerImage && (
          <img src={viewerImage} alt={viewerTitle} style={{ width: '100%', height: 'auto', maxHeight: '75vh', objectFit: 'contain', display: 'block', borderRadius: 2 }} />
        )}
      </AppModal>
    </div>
  );
}

function ProofPlaceholder({ icon, label }: { icon: React.ReactNode; label: string }) {
  return (
    <div
      className="flex flex-col items-center justify-center gap-2 rounded-sm border border-[var(--border)] border-dashed"
      style={{ width: 160, height: 120 }}
    >
      <span className="text-[var(--text-muted)] opacity-40">{icon}</span>
      <span className="text-2xs text-[var(--text-muted)] font-medium">{label}</span>
    </div>
  );
}
