import { Link } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import {
  IconReload, IconExternalLink, IconArrowRight, IconPackage,
  IconClock, IconUser, IconTruckReturn,
} from '@tabler/icons-react';
import { AppDrawer } from '@/components/overlays/AppDrawer';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { ConditionPill } from '@/components/data-display/ConditionPill';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/utils';
import { tlabel } from '@/lib/i18n/i18n-dict';
import type { useT } from '@/lib/i18n/LocaleContext';
import { NEXT, STATUS_TOKENS, TRANSITION_ICON, type Rma, type RmaStatus } from '@/pages/returns/ReturnsPage';

type Copy = ReturnType<typeof useT>;

interface Props {
  rma: Rma | null;
  open: boolean;
  onClose: () => void;
  statusLabel: (s: RmaStatus) => string;
  busyId: string | null;
  onTransition: (r: Rma, target: RmaStatus) => void;
  onResync: (r: Rma) => void;
  onSaveShipping?: (r: Rma, data: { trackingNumber: string; shippingCarrier: string }) => Promise<void>;
  t: Copy;
}

function fmtDate(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit' });
}

const STATUS_ORDER: Record<RmaStatus, number> = {
  REQUESTED: 0, APPROVED: 1, RECEIVED: 2, RESTOCKED: 3, REJECTED: -1, CANCELLED: -1,
};

/** A small labelled metric — never color-only, never a nested card. */
function Metric({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex flex-col gap-0.5 min-w-0">
      <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>{label}</span>
      <span className={`text-xs font-[600] truncate ${mono ? 'font-mono' : ''}`} style={{ color: 'var(--text-primary)' }}>{value}</span>
    </div>
  );
}

type Step = { label: string; at?: string; by?: string; note?: string; reached: boolean; dotColor: string };

type RmaHistoryRow = {
  id: string; fromStatus?: RmaStatus; toStatus: RmaStatus; note?: string;
  actedByName?: string; actedByRole?: string; createdAt: string;
};

const HISTORY_DOT: Record<string, string> = {
  REQUESTED: 'var(--info)', APPROVED: 'var(--brand)', RECEIVED: 'var(--info)',
  RESTOCKED: 'var(--success)', REJECTED: 'var(--danger)', CANCELLED: 'var(--danger)',
};

/** Real timeline from the server audit trail. Empty for legacy RMAs → caller falls back to synthetic. */
function historySteps(history: RmaHistoryRow[], statusLabel: (s: RmaStatus) => string): Step[] {
  return history.map(h => ({
    label: statusLabel(h.toStatus),
    at: h.createdAt,
    by: h.actedByName || h.actedByRole || undefined,
    note: h.note || undefined,
    reached: true,
    dotColor: HISTORY_DOT[h.toStatus] ?? 'var(--text-muted)',
  }));
}

function lifecycleSteps(rma: Rma, t: Copy): Step[] {
  const c = t.returnsPage;
  const ord = STATUS_ORDER[rma.status];
  if (rma.status === 'REJECTED' || rma.status === 'CANCELLED') {
    return [
      { label: tlabel(c, 'stepRequested') ?? 'Requested', at: rma.createdAt, by: rma.createdBy, reached: true, dotColor: 'var(--info)' },
      {
        label: rma.status === 'REJECTED' ? (tlabel(c, 'stepRejected') ?? 'Rejected') : (tlabel(c, 'stepCancelled') ?? 'Cancelled'),
        by: rma.resolutionNote ?? undefined, reached: true, dotColor: 'var(--danger)',
      },
    ];
  }
  return [
    { label: tlabel(c, 'stepRequested') ?? 'Requested', at: rma.createdAt, by: rma.createdBy, reached: true, dotColor: 'var(--info)' },
    { label: tlabel(c, 'stepApproved') ?? 'Approved', reached: ord >= 1, dotColor: 'var(--brand)' },
    { label: tlabel(c, 'stepReceived') ?? 'Received', at: rma.receivedAt, reached: ord >= 2, dotColor: 'var(--info)' },
    { label: tlabel(c, 'stepRestocked') ?? 'Restocked', at: rma.restockedAt, reached: ord >= 3, dotColor: 'var(--success)' },
  ];
}

export function RmaDetailDrawer({ rma, open, onClose, statusLabel, busyId, onTransition, onResync, onSaveShipping, t }: Props) {
  const ref = rma?.blNumber || rma?.erpOrderId || (rma ? `#${rma.id.slice(0, 8)}` : '');
  const returnValue = rma
    ? rma.items.reduce((s, it) => s + (it.quantity ?? 0) * (Number(it.unitPrice) || 0), 0)
    : 0;
  // Real status timeline from the audit trail; fall back to the synthetic lifecycle for legacy RMAs
  // (created before the history table existed) or while loading.
  const [history, setHistory] = useState<RmaHistoryRow[]>([]);
  useEffect(() => {
    if (!open || !rma?.id) { setHistory([]); return; }
    let cancelled = false;
    api.get<RmaHistoryRow[]>(`/api/admin/returns/${rma.id}/history`)
      .then(res => { if (!cancelled) setHistory(Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (!cancelled) setHistory([]); });
    return () => { cancelled = true; };
  }, [open, rma?.id]);

  const steps = rma
    ? (history.length > 0 ? historySteps(history, statusLabel) : lifecycleSteps(rma, t))
    : [];
  const byLabel = tlabel(t.returnsPage, 'byLabel') ?? 'by';

  // Inbound return-shipment tracking (editable).
  const [ship, setShip] = useState({ trackingNumber: '', shippingCarrier: '' });
  const [savingShip, setSavingShip] = useState(false);
  useEffect(() => {
    setShip({ trackingNumber: rma?.trackingNumber ?? '', shippingCarrier: rma?.shippingCarrier ?? '' });
  }, [rma?.id, rma?.trackingNumber, rma?.shippingCarrier]);
  const shipDirty = !!rma && (ship.trackingNumber !== (rma.trackingNumber ?? '') || ship.shippingCarrier !== (rma.shippingCarrier ?? ''));
  const saveShipping = async () => {
    if (!rma || !onSaveShipping) return;
    setSavingShip(true);
    try { await onSaveShipping(rma, ship); } finally { setSavingShip(false); }
  };

  const title = rma ? (
    <div className="flex items-center gap-2">
      <span className="truncate">{rma.clientName ?? '—'}</span>
    </div>
  ) : '';

  const footer = rma && NEXT[rma.status].length > 0 ? (
    <>
      <Button variant="ghost" size="sm" onClick={onClose}>{t.actions?.close ?? 'Close'}</Button>
      {NEXT[rma.status].map((target) => {
        const Icon = TRANSITION_ICON[target] ?? IconArrowRight;
        const tk = STATUS_TOKENS[target];
        return (
          <Button
            key={target}
            variant="outline"
            size="sm"
            disabled={busyId === rma.id}
            onClick={() => onTransition(rma, target)}
            className="h-8 gap-1.5 px-3 text-xs font-semibold"
            style={{ color: tk.text }}
          >
            <Icon size={13} /> {statusLabel(target)}
          </Button>
        );
      })}
    </>
  ) : undefined;

  return (
    <AppDrawer
      open={open}
      onClose={onClose}
      title={title}
      subtitle={rma ? `${tlabel(t.returnsPage, 'drawerSubtitle') ?? 'Return'} · ${ref}` : undefined}
      width={560}
      footer={footer}
    >
      {rma && (
        <div className="flex flex-col">
          {/* Summary grid */}
          <div className="grid grid-cols-2 gap-x-4 gap-y-3 px-5 py-4 border-b" style={{ borderColor: 'var(--border)' }}>
            <Metric label={tlabel(t.returnsPage, 'colBl') ?? 'BL / Réf ERP'} value={rma.blNumber || rma.erpOrderId || '—'} mono />
            <Metric label={tlabel(t.returnsPage, 'drawerUnits') ?? 'Unités · lignes'} value={`${rma.totalUnits} · ${rma.items.length}`} mono />
            <Metric
              label={tlabel(t.returnsPage, 'drawerValue') ?? 'Returned value'}
              value={returnValue > 0 ? formatMoney(returnValue, 'TND') : '—'}
              mono
            />
            <Metric
              label={tlabel(t.returnsPage, 'drawerCreated') ?? 'Created'}
              value={
                <span className="inline-flex items-center gap-1">
                  <IconUser size={11} stroke={2} style={{ color: 'var(--text-soft)' }} />
                  {rma.createdBy ?? '—'}
                </span>
              }
            />
            {rma.deliveryId && (
              <div className="col-span-2">
                <Link
                  to={`/deliveries/${rma.deliveryId}`}
                  className="inline-flex items-center gap-1 text-xs font-[600] hover:underline"
                  style={{ color: 'var(--brand)' }}
                >
                  <IconTruckReturn size={13} stroke={2} />
                  {tlabel(t.returnsPage, 'drawerViewDelivery') ?? 'View delivery'}
                  <IconExternalLink size={11} stroke={2} />
                </Link>
              </div>
            )}
          </div>

          {/* Returned items — the core of the page, previously hidden behind a count */}
          <div className="px-5 py-4 border-b" style={{ borderColor: 'var(--border)' }}>
            <div className="flex items-center gap-1.5 mb-2.5">
              <IconPackage size={14} stroke={2} style={{ color: 'var(--text-muted)' }} />
              <span className="text-xs font-[700]" style={{ color: 'var(--text-primary)' }}>
                {tlabel(t.returnsPage, 'drawerItemsTitle') ?? 'Returned items'}
              </span>
            </div>
            <table className="w-full border-collapse">
              <thead>
                <tr className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                  <th className="text-left pb-1.5 font-[600]">{tlabel(t.returnsPage, 'colItemSku') ?? 'SKU / Article'}</th>
                  <th className="text-right pb-1.5 font-[600]">{tlabel(t.returnsPage, 'colItemQty') ?? 'Qty'}</th>
                  <th className="text-left pb-1.5 pl-3 font-[600]">{tlabel(t.returnsPage, 'colItemCondition') ?? 'Condition'}</th>
                </tr>
              </thead>
              <tbody>
                {rma.items.map((it, i) => (
                  <tr key={it.id ?? i} className="align-top border-t" style={{ borderColor: 'var(--border)' }}>
                    <td className="py-2 pr-2">
                      <div className="flex flex-col gap-0.5 min-w-0">
                        <span className="text-xs font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
                          {it.name ?? it.sku ?? '—'}
                        </span>
                        <span className="text-2xs font-mono truncate" style={{ color: 'var(--text-soft)' }}>
                          {it.sku ?? '—'}
                          {it.reason ? <span style={{ color: 'var(--text-muted)' }}> · {it.reason}</span> : null}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 text-right text-xs font-mono font-[600] tabular-nums" style={{ color: 'var(--text-primary)' }}>
                      {it.quantity}
                    </td>
                    <td className="py-2 pl-3">
                      <ConditionPill
                        condition={it.condition ?? 'RESELLABLE'}
                        label={it.condition === 'DAMAGED'
                          ? (tlabel(t.returnsPage, 'conditionDamaged') ?? 'Endommagé')
                          : (tlabel(t.returnsPage, 'conditionResellable') ?? 'Revendable')}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Reason + resolution */}
          {(rma.reason || rma.resolutionNote) && (
            <div className="px-5 py-4 border-b flex flex-col gap-3" style={{ borderColor: 'var(--border)' }}>
              {rma.reason && (
                <div className="flex flex-col gap-1">
                  <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                    {tlabel(t.returnsPage, 'colReason') ?? 'Reason'}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{rma.reason}</span>
                </div>
              )}
              {rma.resolutionNote && (
                <div className="flex flex-col gap-1">
                  <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                    {tlabel(t.returnsPage, 'drawerResolution') ?? 'Resolution note'}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{rma.resolutionNote}</span>
                </div>
              )}
            </div>
          )}

          {/* ERP sync triage */}
          {rma.erpSyncStatus && (
            <div className="px-5 py-4 border-b flex items-center justify-between gap-3" style={{ borderColor: 'var(--border)' }}>
              <div className="flex flex-col gap-1 min-w-0">
                <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                  {tlabel(t.returnsPage, 'colSync') ?? 'Sync ERP'}
                </span>
                <div className="flex items-center gap-2">
                  <StatusBadge status={rma.erpSyncStatus} label={tlabel(t.returnsPage?.syncLabels, rma.erpSyncStatus) ?? rma.erpSyncStatus} size="sm" />
                  {rma.erpSyncStatus === 'SYNC_FAILED' && rma.erpSyncError && (
                    <span className="text-2xs truncate" style={{ color: 'var(--danger)' }} title={rma.erpSyncError}>
                      {rma.erpSyncError}
                    </span>
                  )}
                </div>
              </div>
              {rma.erpSyncStatus === 'SYNC_FAILED' && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busyId === rma.id}
                  onClick={() => onResync(rma)}
                  className="h-7 gap-1.5 px-2.5 text-xs font-[600] shrink-0"
                  style={{ color: 'var(--brand)' }}
                >
                  <IconReload size={12} /> {tlabel(t.returnsPage, 'resync') ?? 'Resync'}
                </Button>
              )}
            </div>
          )}

          {/* Lifecycle timeline */}
          <div className="px-5 py-4">
            <div className="flex items-center gap-1.5 mb-3">
              <IconClock size={14} stroke={2} style={{ color: 'var(--text-muted)' }} />
              <span className="text-xs font-[700]" style={{ color: 'var(--text-primary)' }}>
                {tlabel(t.returnsPage, 'drawerTimeline') ?? 'Timeline'}
              </span>
            </div>
            {steps.map((s, i) => (
              <div key={i} className="flex items-start gap-2.5">
                <div className="flex flex-col items-center self-stretch">
                  <span
                    className="rounded-full shrink-0"
                    style={{
                      width: 9, height: 9, marginTop: 3,
                      background: 'var(--surface)',
                      border: `1.5px solid ${s.reached ? s.dotColor : 'var(--border-strong)'}`,
                    }}
                  />
                  {i < steps.length - 1 && <span className="flex-1 w-px my-0.5" style={{ background: 'var(--border)', minHeight: 14 }} />}
                </div>
                <div className="flex flex-col pb-3 min-w-0">
                  <span className="text-xs font-[600]" style={{ color: s.reached ? 'var(--text-primary)' : 'var(--text-soft)' }}>
                    {s.label}
                  </span>
                  {(s.at || s.by) && (
                    <span className="text-2xs font-mono" style={{ color: 'var(--text-muted)' }}>
                      {s.at ? fmtDate(s.at) : ''}{s.by ? `${s.at ? ' · ' : ''}${s.at ? byLabel + ' ' : ''}${s.by}` : ''}
                    </span>
                  )}
                  {s.note && (
                    <span className="text-2xs italic" style={{ color: 'var(--text-soft)', marginTop: 2 }}>
                      “{s.note}”
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>

          {/* Inbound return-shipment tracking */}
          {onSaveShipping && (
            <div className="px-5 py-4 border-t" style={{ borderColor: 'var(--border)' }}>
              <div className="flex items-center gap-1.5 mb-3">
                <IconTruckReturn size={14} stroke={2} style={{ color: 'var(--text-muted)' }} />
                <span className="text-xs font-[700]" style={{ color: 'var(--text-primary)' }}>
                  {tlabel(t.returnsPage, 'drawerShipping') ?? 'Expédition retour'}
                </span>
                {rma?.shippedAt && (
                  <span className="text-2xs font-mono" style={{ color: 'var(--text-muted)' }}>· {fmtDate(rma.shippedAt)}</span>
                )}
              </div>
              <div className="flex flex-col gap-2">
                <input
                  value={ship.shippingCarrier}
                  onChange={(e) => setShip((s) => ({ ...s, shippingCarrier: e.target.value }))}
                  placeholder={tlabel(t.returnsPage, 'shippingCarrier') ?? 'Transporteur'}
                  className="h-8 px-2.5 text-xs rounded border outline-none focus:ring-1 focus:ring-[var(--brand)]"
                  style={{ borderColor: 'var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                />
                <input
                  value={ship.trackingNumber}
                  onChange={(e) => setShip((s) => ({ ...s, trackingNumber: e.target.value }))}
                  placeholder={tlabel(t.returnsPage, 'shippingTracking') ?? 'N° de suivi'}
                  className="h-8 px-2.5 text-xs rounded border outline-none focus:ring-1 focus:ring-[var(--brand)] font-mono"
                  style={{ borderColor: 'var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                />
                <Button
                  variant="outline" size="sm"
                  disabled={!shipDirty || savingShip}
                  onClick={() => void saveShipping()}
                  className="h-7 self-end px-3 text-xs font-semibold"
                >
                  {savingShip ? '…' : (tlabel(t.returnsPage, 'shippingSave') ?? 'Enregistrer')}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </AppDrawer>
  );
}
