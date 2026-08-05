
import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Link } from 'react-router-dom';
import { AppModal } from '@/components/overlays/AppModal';
import {
  IconRefresh, IconPackage, IconUser, IconRoute,
  IconMapPin, IconCheck, IconX, IconPhone, IconTruck, IconClock,
  IconPhoto, IconWeight, IconCurrencyDollar, IconCash,
  IconFileText, IconBuildingWarehouse, IconQuote, IconPackageExport, IconReceipt,
} from '@tabler/icons-react';
import { api } from '@/lib/api';
import { formatMoney } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { usePageBreadcrumb } from '@/lib/ui/breadcrumb';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { DRIVER_STATUS_COLOR } from '@/lib/ui/design-tokens';
import type { Delivery, TimelineEvent, DeliveryItem, ProofOfDelivery } from '@/types';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import SlaTimeline from '@/components/data-display/SlaTimeline';
import { ArticlesTable } from '@/components/data-display/ArticlesTable';
import { DriverNote } from '@/components/data-display/DriverNote';
import { ScrollArea } from '@/components/ui/scroll-area';
import { CreateReturnModal } from '@/components/returns/CreateReturnModal';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { formatAddress } from '@/lib/utils/address';

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
  const [returnOpen, setReturnOpen] = useState(false);
  const [assignedDriverStatus, setAssignedDriverStatus] = useState<string | null>(null);
  const [invoiceOpen, setInvoiceOpen] = useState(false);
  const [invoicing, setInvoicing] = useState(false);
  const [invoiceRef, setInvoiceRef] = useState<string | null>(null);

  const submitInvoice = useCallback(async () => {
    setInvoicing(true);
    try {
      const res = await api.post(`/admin/deliveries/${id}/invoice`);
      const ref = res.data?.invoiceRef ?? null;
      setInvoiceRef(ref);
      showSuccessToast(`Facture créée : ${ref}`);
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
        ?? "Facture non créée — à créer manuellement dans l'ERP.";
      showErrorToast(msg);
    } finally {
      setInvoicing(false);
    }
  }, [id]);

  const downloadInvoicePdf = useCallback(async () => {
    if (!invoiceRef) return;
    try {
      const res = await api.get(`/admin/deliveries/${id}/invoice-pdf`, { params: { ref: invoiceRef }, responseType: 'blob' });
      const url = URL.createObjectURL(res.data as Blob);
      const a = document.createElement('a');
      a.href = url; a.download = `${invoiceRef}.pdf`;
      document.body.appendChild(a); a.click(); a.remove();
      URL.revokeObjectURL(url);
    } catch {
      showErrorToast("PDF indisponible — ouvrez la facture dans l'ERP.");
    }
  }, [id, invoiceRef]);

  usePageBreadcrumb(
    delivery
      ? [{ label: t.pages.deliveries?.title || 'Suivi des livraisons', href: '/deliveries' }, { label: (delivery.erpOrderId ?? delivery.erpExternalRef ?? delivery.orderRef ?? id.slice(0, 8)).toUpperCase() }]
      : [{ label: t.pages.deliveries?.title || 'Suivi des livraisons', href: '/deliveries' }]
  );

  const fetchAll = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true); else setLoading(true);
    try {
      const [dRes, hRes, podRes] = await Promise.allSettled([
        api.get(`/admin/deliveries/${id}`),
        api.get(`/admin/deliveries/${id}/history`),
        api.get(`/admin/deliveries/${id}/pod`),
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
    api.get(`/admin/fleet/drivers/${delivery.driverId}`)
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

  const items: DeliveryItem[] = delivery.items ?? [];

  // Rendered as text whatever the ERP returned: a mapped value can be a number, a date or a
  // boolean, and InfoRow hides anything that came back empty.
  const customFields: Array<[string, string]> = Object.entries(delivery.customFields ?? {})
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => [k, String(v)]);
  // Human ERP reference — mirrors the backend Order.resolveRef() priority (erpOrderId →
  // erpExternalRef → uuid) so the header/breadcrumb never fall back to a bare UUID when the ERP id exists.
  const orderRef = (delivery.erpOrderId ?? delivery.erpExternalRef ?? delivery.orderRef ?? id.slice(0, 8)).toUpperCase();
  // ADR-033 — a return collection is identified by its own RMA ref (RET-00001); the order ref becomes the origin link.
  const isReturn = (delivery as { kind?: string }).kind === 'RETURN_PICKUP';
  const rmaNumber = (delivery as { rmaNumber?: string }).rmaNumber;
  const originalDeliveryId = (delivery as { originalDeliveryId?: string }).originalDeliveryId;
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

  // Note du livreur = the driver's own words: the POD handover comment, else the driver's failure
  // comment (now persisted on its own field — no longer parsed out of the flattened failReason).
  const driverNoteText: string | null = pod?.comment ?? delivery.failureComment ?? null;
  // Admin failure-reason label (motif), shown per line in the Articles table's Motif tooltip.
  const failMotif: string | null = delivery.failReason ?? delivery.failureReason ?? null;

  return (
    <div className="h-full flex flex-col bg-[var(--app-bg)] dispatch-card">
      <ScrollArea className="flex-1 min-h-0">
      <div className="px-6 py-8 pb-12">
        <div className="flex flex-col gap-6">

          {/* ── Entity header ──────────────────────────────────────────── */}
          <div>
            <div className="flex items-start justify-between gap-4 mb-6">
              <div className="flex flex-col gap-3 min-w-0 flex-1">
                {/* Status row — failure detail intentionally NOT shown here; the SLA
                    timeline (right column) is the single source of truth for it. */}
                <div className="flex items-center gap-2">
                  <StatusBadge status={delivery.status} size="sm" />
                  {isReturn && (
                    <StatusBadge status="RETURN_PICKUP" label={t.deliveriesPage?.returnPickupBadge ?? 'Retour'} size="sm" />
                  )}
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
                  <code className="font-mono font-semibold text-[var(--brand)]">{isReturn && rmaNumber ? rmaNumber : orderRef}</code>
                  {isReturn && rmaNumber && (
                    <>
                      <span className="text-[var(--border)]">·</span>
                      {originalDeliveryId ? (
                        <Link
                          to={`/deliveries/${originalDeliveryId}`}
                          className="font-mono text-[var(--text-muted)] hover:text-[var(--brand)] transition-colors"
                          title={orderRef}
                        >
                          ↩ {orderRef}
                        </Link>
                      ) : (
                        <span className="font-mono text-[var(--text-muted)]" title={orderRef}>↩ {orderRef}</span>
                      )}
                    </>
                  )}
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

              <div className="flex items-center gap-2 shrink-0">
                {(delivery.status === 'DELIVERED' || delivery.status === 'PARTIALLY_DELIVERED') && !delivery.hasOpenReturn && (
                  <button
                    type="button"
                    onClick={() => setReturnOpen(true)}
                    className="h-7 inline-flex items-center gap-1.5 px-2.5 rounded-sm border border-[var(--border)] text-xs font-semibold text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] transition-colors"
                    title={t.returnsPage?.newReturn ?? 'Créer un retour'}
                  >
                    <IconPackageExport size={13} /> {t.returnsPage?.newReturn ?? 'Créer un retour'}
                  </button>
                )}
                {(delivery.status === 'DELIVERED' || delivery.status === 'PARTIALLY_DELIVERED') && (
                  <button
                    type="button"
                    onClick={() => { setInvoiceRef(null); setInvoiceOpen(true); }}
                    className="h-7 inline-flex items-center gap-1.5 px-2.5 rounded-sm border border-[var(--border)] text-xs font-semibold text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] transition-colors"
                    title="Créer + valider la facture dans l'ERP — ASM déclenche, l'ERP calcule (TVA, comptes)"
                  >
                    <IconReceipt size={13} /> Créer facture
                  </button>
                )}
                <button
                  type="button"
                  className="w-7 h-7 flex items-center justify-center rounded-sm border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
                  onClick={() => fetchAll(true)}
                  title={t.deliveryPage.refresh}
                  disabled={refreshing}
                >
                  <IconRefresh size={13} className={refreshing ? 'animate-spin' : ''} />
                </button>
              </div>
            </div>

            {/* Stat chips */}
            <div className="flex flex-wrap gap-2">
              {items.length > 0 && (
                <StatChip icon={<IconPackage size={11} />} label={`${items.length} article${items.length > 1 ? 's' : ''}`} />
              )}
              {delivery.priority === 'HIGH' && (
                // First in the row and coloured, but no heavier than the chips beside it: this is one
                // delivery, so the word is worth its width — on a list it would not be, which is why
                // the lists get the dot alone.
                <span
                  className="flex items-center gap-[6px] shrink-0 px-2 py-1 text-2xs font-medium"
                  style={{ color: 'var(--danger)' }}
                >
                  <span className="inline-block h-1.5 w-1.5 rounded-full" style={{ background: 'var(--danger)' }} />
                  {t.deliveryPage.priorityHigh}
                </span>
              )}
              {delivery.totalWeightKg && (
                <StatChip icon={<IconWeight size={11} />} label={`${delivery.totalWeightKg} kg`} />
              )}
              {delivery.totalAmount && (
                <StatChip icon={<IconCurrencyDollar size={11} />} label={formatMoney(delivery.totalAmount, delivery.currency)} highlight />
              )}
              {/* The list badge stays wordless about the sum — a dispatcher planning a round only
                  needs to know cash is involved. Here it carries the amount: this is the screen
                  someone opens when a driver rings about what to collect. */}
              {delivery.codRequired && (
                <span
                  className="flex items-center gap-[6px] shrink-0 rounded-md px-2 py-1 text-2xs font-semibold
                             text-[var(--success)] bg-[var(--success)]/10 ring-1 ring-[var(--success)]/25"
                >
                  <IconCash size={11} />
                  {delivery.codAmount != null
                    ? `${t.deliveriesPage.codBadge} · ${formatMoney(delivery.codAmount, delivery.currency)}`
                    : t.deliveriesPage.codBadge}
                </span>
              )}
              {delivery.zoneName && (
                <StatChip icon={<IconMapPin size={11} />} label={delivery.zoneName} />
              )}
              {!(delivery.zoneName) && delivery.dropoffCity && (
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
            {isCancelled && delivery.cancelReason && (
              <div className="px-3 py-2 rounded-xs border border-gray-200 bg-gray-50 flex items-start gap-2">
                <IconX size={12} style={{ color: 'var(--text-muted)', flexShrink: 0, marginTop: 1 }} />
                <span className="text-xs text-[var(--text-muted)]">{delivery.cancelReason}</span>
              </div>
            )}
          </div>

          {/* ── Two-column body: order info (left ~65%) · status & tracking (sidebar ~35%) ── */}
          <div className="grid grid-cols-1 lg:grid-cols-[1fr_380px] gap-8">

            {/* ════ LEFT — Order information ════ */}
            <div className="flex flex-col gap-8">
              <ColumnHeader label={t.deliveryPage.columnInfo} />

              {/* Client */}
              <Section title={t.deliveryPage.sectionClient} icon={<IconUser size={12} />}>
                <div className="flex flex-col">
                  <InfoRow label={t.deliveryPage.labelName}      value={delivery.clientName} />
                  <InfoRow label={t.deliveryPage.labelPhone}    value={delivery.clientPhone} />
                  <InfoRow label={t.deliveryPage.labelAddress}  value={formatAddress(delivery.dropoffAddress)} />
                  <InfoRow label={t.deliveryPage.labelCity}     value={delivery.dropoffCity} />
                  <InfoRow label={t.deliveryPage.labelPostalCode} value={delivery.dropoffPostalCode} />
                  <InfoRow label={t.deliveryPage.labelZone}     value={delivery.zoneName} />
                </div>

                {/* Not an InfoRow: this is a sentence the driver will act on, and the row layout
                    right-aligns and truncates it. The admin was the only place it did not appear —
                    the driver's app has shown it all along, so a dispatcher on the phone was the
                    one person who could not see what the delivery actually asks for. */}
                {delivery.deliveryInstructions && (
                  <div
                    className="mt-3 flex items-start gap-2 rounded-sm px-3 py-2"
                    style={{
                      background: 'var(--surface-sunken)',
                      borderInlineStart: '2px solid var(--brand)',
                    }}
                  >
                    <IconQuote size={13} className="shrink-0 mt-0.5 text-[var(--text-muted)]" />
                    <div className="min-w-0">
                      <p className="text-2xs font-medium text-[var(--text-muted)]">
                        {t.deliveryPage.deliveryInstructions}
                      </p>
                      <p className="text-xs text-[var(--text-primary)] leading-relaxed">
                        {delivery.deliveryInstructions}
                      </p>
                    </div>
                  </div>
                )}
              </Section>

              {/* Order */}
              <Section title={t.deliveryPage.sectionOrder} icon={<IconPackage size={12} />}>
                <div className="flex flex-col">
                  <InfoRow label={t.deliveryPage.labelReference}   value={orderRef} mono />
                  <InfoRow label={t.deliveryPage.labelInternalId}  value={delivery.id} mono />
                  <InfoRow label={t.deliveryPage.labelTotalWeight} value={delivery.totalWeightKg ? `${delivery.totalWeightKg} kg` : null} />
                  <InfoRow label={t.deliveryPage.labelAmount}     value={delivery.totalAmount ? formatMoney(delivery.totalAmount, delivery.currency) : null} />
                  <InfoRow label={t.deliveryPage.labelSource}     value={tlabel(t.sources, delivery.source) ?? delivery.source} />
                  {delivery.erpSyncStatus && delivery.erpSyncStatus !== 'SYNCED' && (
                    <InfoRow label={t.deliveryPage.labelSyncErp} value={t.syncStatus[delivery.erpSyncStatus] ?? delivery.erpSyncStatus} />
                  )}
                  <InfoRow label={t.deliveryPage.labelCreatedAt}  value={delivery.createdAt ? new Date(delivery.createdAt).toLocaleString('fr-FR') : null} />
                  <InfoRow label={t.deliveryPage.labelUpdatedAt}  value={delivery.updatedAt ? new Date(delivery.updatedAt).toLocaleString('fr-FR') : null} />
                </div>
              </Section>

              {/* Shipments of the same sale order (original + backorder(s) / multi-depot splits) */}
              {Array.isArray(delivery.relatedShipments) && delivery.relatedShipments.length > 1 && (
                <Section
                  title={`${t.deliveryPage.relatedShipmentsTitle}${delivery.erpExternalRef ? ' · ' + delivery.erpExternalRef : ''}`}
                  icon={<IconPackage size={12} />}
                >
                  <div className="flex flex-col">
                    {(delivery.relatedShipments ?? []).map((s) => (
                      s.current ? (
                        <div key={s.deliveryId} className="flex items-center justify-between py-2 border-b border-[var(--border)]/30">
                          <span className="text-xs font-semibold text-[var(--text-primary)] font-mono">{s.blNumber || s.erpOrderId || '—'}</span>
                          <div className="flex items-center gap-2">
                            <StatusBadge status={s.status} size="sm" />
                            <span className="text-2xs text-[var(--text-muted)]">{t.deliveryPage.relatedShipmentCurrent}</span>
                          </div>
                        </div>
                      ) : (
                        <Link key={s.deliveryId} to={`/deliveries/${s.deliveryId}`} className="flex items-center justify-between py-2 border-b border-[var(--border)]/30 hover:bg-[var(--hover-bg)] rounded-sm px-1 -mx-1">
                          <span className="text-xs font-semibold text-[var(--brand)] font-mono">{s.blNumber || s.erpOrderId || '—'}</span>
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
                  <ArticlesTable
                    items={items}
                    status={delivery.status}
                    failureCode={delivery.failureCode}
                    failMotif={failMotif}
                    currency={delivery.currency}
                  />
                </Section>
              )}

              {/* Engagement / Fulfillment — promised date, delivery window, BL, source depot */}
              <Section title={t.deliveryPage.sectionFulfillment} icon={<IconBuildingWarehouse size={12} />}>
                <div className="flex flex-col">
                  <InfoRow
                    label={t.deliveryPage.scheduledLabel}
                    value={delivery.scheduledAt
                      ? new Date(delivery.scheduledAt).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
                        + (delivery.rescheduledAt ? ` · ${t.deliveryPage.rescheduledBadge}` : '')
                      : '—'}
                  />
                  {/* Delivery time window (start–end) for scheduled orders */}
                  <InfoRow label={t.deliveryPage.windowLabel} value={deliveryWindow} />
                  <InfoRow label={t.deliveryPage.blNumberLabel} value={delivery.blNumber} mono />
                  <InfoRow
                    label={t.deliveryPage.sourceDepotLabel}
                    value={delivery.sourceDepotName ?? delivery.warehouseCode}
                  />
                </div>
                {delivery.blNumber && (
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        const res = await api.get(`/admin/deliveries/${delivery.id}/bon-livraison`, { responseType: 'blob' });
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

              {/* ERP values with no ASM equivalent — last, because nothing here is actionable */}
              {customFields.length > 0 && (
                <Section title={t.deliveryPage.sectionCustomFields} icon={<IconReceipt size={12} />}>
                  <p className="text-2xs text-[var(--text-muted)] mb-2">
                    {t.deliveryPage.customFieldsHint}
                  </p>
                  <div className="flex flex-col">
                    {customFields.map(([key, value]) => (
                      <InfoRow key={key} label={key} value={value} />
                    ))}
                  </div>
                </Section>
              )}
            </div>{/* ════ /LEFT ════ */}

            {/* ════ RIGHT — Status & tracking (sticky sidebar) ════ */}
            <div className="flex flex-col gap-8 lg:sticky lg:top-8 lg:self-start">
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
                          <DriverAvatarById driverId={delivery.driverId} name={delivery.driverName ?? undefined} size={24} />
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
              <SlaTimeline deliveryId={delivery.id || id} variant="detailed" hidePodComment hideItemOutcomes hideFailureContext />
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
      </ScrollArea>

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

      {/* Contextual return — pre-scoped to this delivery, no search */}
      <CreateReturnModal
        open={returnOpen}
        onClose={() => setReturnOpen(false)}
        onCreated={() => setReturnOpen(false)}
        prefillDeliveryId={delivery.id || id}
      />

      {/* Manual ERP invoice — admin triggers, the ERP prices/taxes it */}
      <AppModal
        opened={invoiceOpen}
        onClose={() => setInvoiceOpen(false)}
        title={invoiceRef ? 'Facture créée' : 'Créer la facture'}
        subtitle="Facturation ERP"
        size="md"
      >
        {invoiceRef ? (
          <div className="flex flex-col gap-3 text-sm">
            <p className="text-[var(--text-secondary)]">La facture a été créée et validée dans l'ERP :</p>
            <div className="px-3 py-2 rounded-sm bg-[var(--app-bg)] border border-[var(--border)] font-mono text-[var(--text-primary)]">{invoiceRef}</div>
            <p className="text-2xs text-[var(--text-muted)]">Générée par l'ERP (TVA, comptes, montants).</p>
            <div className="flex justify-end gap-2">
              <button type="button" onClick={() => setInvoiceOpen(false)} className="h-8 px-3 rounded-sm border border-[var(--border)] text-xs font-semibold hover:bg-[var(--hover-bg)]">Fermer</button>
              <button type="button" onClick={downloadInvoicePdf} className="h-8 px-3 rounded-sm bg-[var(--text-primary)] text-[var(--surface)] text-xs font-semibold hover:opacity-90 inline-flex items-center gap-1.5"><IconReceipt size={13} /> Télécharger PDF</button>
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-4 text-sm">
            <p className="text-[var(--text-secondary)]">Ceci va <b>créer et valider</b> la facture dans l'ERP pour cette livraison. ASM déclenche seulement — l'ERP calcule la TVA, les comptes et les montants depuis sa configuration.</p>
            <p className="text-2xs text-[var(--text-muted)]">Un retour éventuel (avoir) reste géré dans l'ERP.</p>
            <div className="flex justify-end gap-2">
              <button type="button" onClick={() => setInvoiceOpen(false)} disabled={invoicing} className="h-8 px-3 rounded-sm border border-[var(--border)] text-xs font-semibold hover:bg-[var(--hover-bg)] disabled:opacity-50">Annuler</button>
              <button type="button" onClick={submitInvoice} disabled={invoicing} className="h-8 px-3 rounded-sm bg-[var(--text-primary)] text-[var(--surface)] text-xs font-semibold hover:opacity-90 disabled:opacity-50">{invoicing ? 'Création…' : 'Créer la facture'}</button>
            </div>
          </div>
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
