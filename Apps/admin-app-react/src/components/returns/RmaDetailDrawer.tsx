import { Link } from 'react-router-dom';
import {
  IconReload, IconExternalLink, IconArrowRight, IconPackage,
  IconAlertTriangle, IconClock, IconUser, IconTruckReturn,
} from '@tabler/icons-react';
import { AppDrawer } from '@/components/overlays/AppDrawer';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/utils';
import { NEXT, STATUS_TOKENS, TRANSITION_ICON, type Rma, type RmaStatus } from '@/pages/ReturnsPage';

interface Props {
  rma: Rma | null;
  open: boolean;
  onClose: () => void;
  statusLabel: (s: RmaStatus) => string;
  busyId: string | null;
  onTransition: (r: Rma, target: RmaStatus) => void;
  onResync: (r: Rma) => void;
  t: any;
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
      <span className="text-2xs font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>{label}</span>
      <span className={`text-xs font-[600] truncate ${mono ? 'font-mono' : ''}`} style={{ color: 'var(--text-primary)' }}>{value}</span>
    </div>
  );
}

function ConditionPill({ condition, t }: { condition?: string; t: any }) {
  const damaged = condition === 'DAMAGED';
  const label = damaged
    ? (t.returnsPage?.conditionDamaged ?? 'Endommagé')
    : (t.returnsPage?.conditionResellable ?? 'Revendable');
  return (
    <span
      className="inline-flex items-center gap-1 text-2xs font-[600] px-1.5 py-0.5 rounded-[var(--radius)]"
      style={{
        background: damaged ? 'var(--danger-bg)' : 'var(--success-bg)',
        color: damaged ? 'var(--danger)' : 'var(--success)',
      }}
    >
      {damaged && <IconAlertTriangle size={10} stroke={2.5} />}
      {label}
    </span>
  );
}

type Step = { label: string; at?: string; by?: string; reached: boolean; dotColor: string };

function lifecycleSteps(rma: Rma, t: any): Step[] {
  const c = t.returnsPage ?? {};
  const ord = STATUS_ORDER[rma.status];
  if (rma.status === 'REJECTED' || rma.status === 'CANCELLED') {
    return [
      { label: c.stepRequested ?? 'Demandé', at: rma.createdAt, by: rma.createdBy, reached: true, dotColor: 'var(--info)' },
      {
        label: rma.status === 'REJECTED' ? (c.stepRejected ?? 'Rejeté') : (c.stepCancelled ?? 'Annulé'),
        by: rma.resolutionNote ?? undefined, reached: true, dotColor: 'var(--danger)',
      },
    ];
  }
  return [
    { label: c.stepRequested ?? 'Demandé', at: rma.createdAt, by: rma.createdBy, reached: true, dotColor: 'var(--info)' },
    { label: c.stepApproved ?? 'Approuvé', reached: ord >= 1, dotColor: 'var(--brand)' },
    { label: c.stepReceived ?? 'Reçu', at: rma.receivedAt, reached: ord >= 2, dotColor: 'var(--info)' },
    { label: c.stepRestocked ?? 'Restocké', at: rma.restockedAt, reached: ord >= 3, dotColor: 'var(--success)' },
  ];
}

export function RmaDetailDrawer({ rma, open, onClose, statusLabel, busyId, onTransition, onResync, t }: Props) {
  const ref = rma?.blNumber || rma?.erpOrderId || (rma ? `#${rma.id.slice(0, 8)}` : '');
  const returnValue = rma
    ? rma.items.reduce((s, it) => s + (it.quantity ?? 0) * (Number((it as any).unitPrice) || 0), 0)
    : 0;
  const steps = rma ? lifecycleSteps(rma, t) : [];
  const byLabel = t.returnsPage?.byLabel ?? 'par';

  const title = rma ? (
    <div className="flex items-center gap-2">
      <span className="truncate">{rma.clientName ?? '—'}</span>
      <StatusBadge status={rma.status} label={statusLabel(rma.status)} size="sm" />
    </div>
  ) : '';

  const footer = rma && NEXT[rma.status].length > 0 ? (
    <>
      <Button variant="ghost" size="sm" onClick={onClose}>{t.actions?.close ?? 'Fermer'}</Button>
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
      subtitle={rma ? `${t.returnsPage?.drawerSubtitle ?? 'Retour'} · ${ref}` : undefined}
      width={560}
      footer={footer}
    >
      {rma && (
        <div className="flex flex-col">
          {/* Summary grid */}
          <div className="grid grid-cols-2 gap-x-4 gap-y-3 px-5 py-4 border-b" style={{ borderColor: 'var(--border)' }}>
            <Metric label={t.returnsPage?.colBl ?? 'BL / Réf ERP'} value={rma.blNumber || rma.erpOrderId || '—'} mono />
            <Metric label={t.returnsPage?.drawerUnits ?? 'Unités · lignes'} value={`${rma.totalUnits} · ${rma.items.length}`} mono />
            <Metric
              label={t.returnsPage?.drawerValue ?? 'Valeur retournée'}
              value={returnValue > 0 ? formatMoney(returnValue, 'TND') : '—'}
              mono
            />
            <Metric
              label={t.returnsPage?.drawerCreated ?? 'Créé'}
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
                  {t.returnsPage?.drawerViewDelivery ?? 'Voir la livraison'}
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
                {t.returnsPage?.drawerItemsTitle ?? 'Articles retournés'}
              </span>
            </div>
            <table className="w-full border-collapse">
              <thead>
                <tr className="text-2xs font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                  <th className="text-left pb-1.5 font-[600]">{t.returnsPage?.colItemSku ?? 'SKU / Article'}</th>
                  <th className="text-right pb-1.5 font-[600]">{t.returnsPage?.colItemQty ?? 'Qté'}</th>
                  <th className="text-left pb-1.5 pl-3 font-[600]">{t.returnsPage?.colItemCondition ?? 'État'}</th>
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
                          {(it as any).reason ? <span style={{ color: 'var(--text-muted)' }}> · {(it as any).reason}</span> : null}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 text-right text-xs font-mono font-[600] tabular-nums" style={{ color: 'var(--text-primary)' }}>
                      {it.quantity}
                    </td>
                    <td className="py-2 pl-3"><ConditionPill condition={(it as any).condition} t={t} /></td>
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
                  <span className="text-2xs font-[700] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                    {t.returnsPage?.colReason ?? 'Motif'}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{rma.reason}</span>
                </div>
              )}
              {rma.resolutionNote && (
                <div className="flex flex-col gap-1">
                  <span className="text-2xs font-[700] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                    {t.returnsPage?.drawerResolution ?? 'Note de résolution'}
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
                <span className="text-2xs font-[700] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                  {t.returnsPage?.colSync ?? 'Sync ERP'}
                </span>
                <div className="flex items-center gap-2">
                  <StatusBadge status={rma.erpSyncStatus} label={(t.returnsPage?.syncLabels as any)?.[rma.erpSyncStatus] ?? rma.erpSyncStatus} size="sm" />
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
                  <IconReload size={12} /> {t.returnsPage?.resync ?? 'Resynchroniser'}
                </Button>
              )}
            </div>
          )}

          {/* Lifecycle timeline */}
          <div className="px-5 py-4">
            <div className="flex items-center gap-1.5 mb-3">
              <IconClock size={14} stroke={2} style={{ color: 'var(--text-muted)' }} />
              <span className="text-xs font-[700]" style={{ color: 'var(--text-primary)' }}>
                {t.returnsPage?.drawerTimeline ?? 'Chronologie'}
              </span>
            </div>
            {steps.map((s, i) => (
              <div key={i} className="flex items-start gap-2.5">
                <div className="flex flex-col items-center self-stretch">
                  <span
                    className="rounded-full shrink-0"
                    style={{
                      width: 9, height: 9, marginTop: 3,
                      background: s.reached ? s.dotColor : 'var(--surface)',
                      border: s.reached ? 'none' : '1.5px solid var(--border-strong)',
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
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </AppDrawer>
  );
}
