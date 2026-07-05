import { useState } from 'react';
import {
  IconFileTypePdf, IconPrinter, IconAlertCircle, IconCamera, IconChevronDown, IconChevronRight,
  IconArrowBackUp, IconArrowsExchange, IconCircleX, IconBan, IconFlag,
} from '@tabler/icons-react';

import { useRouteReport, downloadRouteReportPdf } from './hooks/useRouteReport';
import { REPORT_LABELS as L } from './report-labels';
import type { RouteReport } from './report-types';
import { toast } from '@/lib/toast';
import { messages } from '@/lib/toast-messages';
import { formatMinutes } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { AppModal } from '@/components/overlays/AppModal';

// ── formatters ─────────────────────────────────────────────────────────────────
const fmtDate = (iso?: string | null) => (iso ? new Date(iso).toLocaleDateString('fr-FR') : '—');
const fmtTime = (iso?: string | null) => (iso ? new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '—');
const fmtDT = (iso?: string | null) => (iso ? new Date(iso).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' }) : '—');
const clock = (t?: string | null) => (t ?? '').slice(0, 5) || '—';
const absMin = (m: number) => formatMinutes(Math.abs(m)).replace(/\s/g, ' ');
const signed = (m?: number | null) => (m == null ? '—' : m === 0 ? absMin(0) : `${m > 0 ? '+' : '−'}${absMin(m)}`);

// Threshold tones — consumed only by inline `style` for values the class engine can't precompute.
const T = { danger: 'var(--danger)', warning: 'var(--warning)', success: 'var(--success)', muted: 'var(--text-soft)' };
const delayTone = (m: number | null) => (m == null ? T.muted : m > 10 ? T.danger : m > 0 ? T.warning : T.success);

export default function RouteClosureReport({ routeId }: { routeId: string }) {
  const { data, isLoading, isError, error } = useRouteReport(routeId);
  const [downloading, setDownloading] = useState(false);

  if (isLoading) {
    return (
      <div className="p-4">
        <Skeleton className="h-[220px] w-full" />
      </div>
    );
  }
  if (isError) {
    return (
      <div className="m-4 flex items-start gap-2.5 rounded-[var(--radius-lg)] p-3 bg-[var(--danger-bg)] border border-[var(--danger)]">
        <IconAlertCircle size={16} className="mt-px shrink-0 text-[var(--danger)]" />
        <div>
          <p className="text-xs font-bold text-[var(--danger)]">{L.errorTitle}</p>
          <p className="text-xs mt-0.5 text-[var(--text-muted)]">{(error as Error)?.message ?? 'Erreur réseau'}</p>
        </div>
      </div>
    );
  }
  if (!data) return null;

  const download = async () => {
    setDownloading(true);
    try {
      await downloadRouteReportPdf(routeId, `rapport-tournee-${data.header.routeName ?? routeId}.pdf`);
      toast.success(messages.routes.reportDownloaded.title);
    } catch {
      const m = messages.routes.reportFailed;
      toast.error(m.title, { description: m.description });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="p-4">
      <div className="border border-[var(--border-strong)] bg-[var(--surface)] text-[var(--text-primary)]">
        <HeaderBar report={data} downloading={downloading} onDownload={download} onPrint={() => window.print()} />
        <IdentityGrid report={data} />
        <VerdictLine report={data} />
        <KpiRow report={data} />
        <StopsTable report={data} />
        <Synthese report={data} />
        <Highlights report={data} />
        <PodStrip report={data} />
        <AuditFold report={data} />
      </div>
    </div>
  );
}

// ── section bar ─────────────────────────────────────────────────────────────────
function Bar({ title, meta }: { title: string; meta?: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between px-2.5 py-1.5 bg-[var(--surface-sunken)] border-y border-[var(--border-strong)]">
      <span className="text-2xs text-[var(--text-secondary)]">{title}</span>
      {meta != null && <span className="text-2xs text-[var(--text-muted)]">{meta}</span>}
    </div>
  );
}

// ── header ─────────────────────────────────────────────────────────────────────
function HeaderBar({ report, downloading, onDownload, onPrint }: { report: RouteReport; downloading: boolean; onDownload: () => void; onPrint: () => void }) {
  const h = report.header;
  return (
    <div className="flex items-center justify-between gap-3 px-2.5 py-1.5 bg-[var(--surface-sunken)] border-b border-[var(--border-strong)]">
      <div className="flex items-center gap-2 min-w-0">
        <span className="font-mono text-sm font-semibold text-[var(--text-primary)]">{h.routeName ?? 'Tournée'}</span>
        <span className="text-2xs text-[var(--text-muted)] truncate">— Rapport de clôture</span>
        <StatusBadge status="CLOSED" label="Clôturée" size="sm" />
      </div>
      <div className="flex gap-1.5 shrink-0">
        <Button size="sm" variant="outline" onClick={onPrint} className="h-7 gap-1 rounded-none text-2xs">
          <IconPrinter size={14} /> Imprimer
        </Button>
        <Button size="sm" disabled={downloading} onClick={onDownload} className="h-7 gap-1 rounded-none text-2xs">
          <IconFileTypePdf size={14} /> {downloading ? '…' : 'PDF'}
        </Button>
      </div>
    </div>
  );
}

// ── identity grid ─────────────────────────────────────────────────────────────
function IdentityGrid({ report }: { report: RouteReport }) {
  const h = report.header;
  const K = ({ children }: { children: React.ReactNode }) => (
    <td className="px-2 py-1 w-[86px] bg-[var(--surface-sunken)] text-[var(--text-muted)] border-b border-[var(--border)]">{children}</td>
  );
  const V = ({ children, mono, border }: { children: React.ReactNode; mono?: boolean; border?: boolean }) => (
    <td className={`px-2 py-1 border-b border-[var(--border)] text-[var(--text-secondary)] ${border ? 'border-r border-[var(--border)]' : ''} ${mono ? 'font-mono' : ''}`}>{children}</td>
  );
  return (
    <table className="w-full border-collapse text-xs">
      <tbody>
        <tr>
          <K>Chauffeur</K><V border>{h.driverName ?? '—'}</V>
          <K>Véhicule</K><V mono>{h.vehicleType ?? ''}{h.vehiclePlate ? ` · ${h.vehiclePlate}` : ''}</V>
        </tr>
        <tr>
          <K>Dépôt</K><V border>{h.depotName ?? '—'}</V>
          <K>Date</K><V mono>{fmtDate(h.date)}</V>
        </tr>
        <tr>
          <td className="px-2 py-1 bg-[var(--surface-sunken)] text-[var(--text-muted)]">Exécution</td>
          <td className="px-2 py-1 font-mono text-[var(--text-secondary)]" colSpan={3}>
            démarrée {fmtTime(h.startedAt)}  →  clôturée {fmtTime(h.closedAt)}  ·  durée {h.durationMinutes != null ? formatMinutes(h.durationMinutes) : '—'}  ·  plan {clock(h.plannedStartTime)}–{clock(h.plannedEndTime)}
          </td>
        </tr>
      </tbody>
    </table>
  );
}

// ── verdict line ─────────────────────────────────────────────────────────────
function VerdictLine({ report }: { report: RouteReport }) {
  const k = report.kpis;
  const dot = <span className="text-[var(--border-strong)]">·</span>;
  return (
    <div className="flex items-baseline flex-wrap gap-x-2 gap-y-0.5 px-2.5 py-1.5 border-t border-[var(--border-strong)] text-xs">
      <span className="font-semibold text-[var(--text-primary)]">{k.completedStops + k.partialStops} / {k.attemptedStops} livrés</span>
      {dot}
      <span style={{ color: k.onTimeRate >= 85 ? undefined : T.danger }}>{k.onTimeRate.toFixed(0)} % à l’heure</span>
      {k.failedStops + k.failedAttemptStops > 0 && <>{dot}<span style={{ color: T.danger }}>{k.failedStops + k.failedAttemptStops} échec{k.failedStops + k.failedAttemptStops > 1 ? 's' : ''}</span></>}
      {k.replannedStops > 0 && <>{dot}<span className="text-[var(--text-secondary)]">{k.replannedStops} réassigné{k.replannedStops > 1 ? 's' : ''}</span></>}
    </div>
  );
}

// ── KPI row ──────────────────────────────────────────────────────────────────
function KpiRow({ report }: { report: RouteReport }) {
  const k = report.kpis;
  const cell = (label: string, value: React.ReactNode, tone?: string, last?: boolean) => (
    <td className={`px-2.5 py-1.5 ${last ? '' : 'border-r border-[var(--border)]'}`}>
      <div className="text-2xs text-[var(--text-muted)]">{label}</div>
      <div className="text-sm font-mono font-semibold tabular-nums" style={tone ? { color: tone } : undefined}>{value}</div>
    </td>
  );
  const compTone = k.completionRate >= 90 ? undefined : k.completionRate >= 70 ? T.warning : T.danger;
  const otTone = k.onTimeRate >= 85 ? undefined : k.onTimeRate >= 60 ? T.warning : T.danger;
  return (
    <table className="w-full border-collapse border-t border-[var(--border-strong)]">
      <tbody><tr>
        {cell('Complétion', `${k.completionRate.toFixed(1)} %`, compTone)}
        {cell('Ponctualité', `${k.onTimeRate.toFixed(1)} %`, otTone)}
        {cell('Retard cumulé', signed(k.cumulativeDelayMinutes), (k.cumulativeDelayMinutes ?? 0) > 30 ? T.danger : (k.cumulativeDelayMinutes ?? 0) > 0 ? T.warning : undefined)}
        {cell('Distance', k.totalDistanceKm != null ? `${k.totalDistanceKm} km` : '—')}
        {cell('Durée active', k.activeDurationMinutes != null ? formatMinutes(k.activeDurationMinutes) : '—')}
        {cell('Retard départ', signed(k.routeStartDelayMinutes), (k.routeStartDelayMinutes ?? 0) > 10 ? T.warning : undefined, true)}
      </tr></tbody>
    </table>
  );
}

// ── stops table ─────────────────────────────────────────────────────────────
function delayCell(s: RouteReport['stops'][number]) {
  if (!['COMPLETED', 'PARTIAL'].includes(s.finalStatus) || s.delayMinutes == null) return <span className="text-[var(--text-soft)]">—</span>;
  if (s.delayMinutes <= 0 && s.delayMinutes > -5) return <span style={{ color: T.success }}>à l’heure</span>;
  return <span style={{ color: delayTone(s.delayMinutes) }}>{signed(s.delayMinutes)}</span>;
}
function StopsTable({ report }: { report: RouteReport }) {
  const k = report.kpis;
  const th = 'text-left font-normal text-[var(--text-muted)] px-1.5 py-1 border-b border-[var(--border-strong)] border-r border-[var(--border)]';
  const td = 'px-1.5 py-1.5 border-b border-[var(--border)] border-r border-[var(--border)] align-top';
  return (
    <>
      <Bar title="Détail des arrêts" meta={`${report.stops.length} livraisons · ${k.completedStops} livrés · ${k.failedStops + k.failedAttemptStops} échec`} />
      <table className="w-full border-collapse text-xs table-fixed">
        <colgroup>
          <col className="w-[26px]" /><col /><col className="w-[76px]" /><col className="w-[52px]" />
          <col className="w-[58px]" /><col className="w-[58px]" /><col className="w-[70px]" /><col className="w-[110px]" />
        </colgroup>
        <thead>
          <tr className="bg-[var(--surface-sunken)]">
            <th className={th}>#</th>
            <th className={th}>Client</th>
            <th className={th}>Créneau</th>
            <th className={th}>Arrivée</th>
            <th className={th}>Livré</th>
            <th className={`${th} text-right`}>Retard</th>
            <th className={th}>Statut</th>
            <th className={`${th} border-r-0`}>Motif / preuve</th>
          </tr>
        </thead>
        <tbody className="font-mono tabular-nums">
          {report.stops.map(s => {
            const removed = s.movement === 'REPLANNED' || s.movement === 'CANCELLED';
            return (
              <tr key={s.stopId} className={removed ? 'opacity-55' : ''}>
                <td className={`${td} text-[var(--text-muted)]`}>{String(s.stopOrder).padStart(2, '0')}</td>
                <td className={`${td} font-sans`}>
                  <div className={`font-semibold text-[var(--text-primary)] ${removed ? 'line-through' : ''}`}>{s.clientName ?? '—'}</div>
                  <div className="text-2xs text-[var(--text-muted)] truncate">
                    {s.orderRef && <span className="font-mono">{s.orderRef}</span>}{s.orderRef && (s.city || s.address) ? ' · ' : ''}{s.city ?? s.address ?? ''}
                  </div>
                </td>
                <td className={`${td} text-[var(--text-secondary)]`}>{s.startTimeWindow && s.endTimeWindow ? `${clock(s.startTimeWindow)}–${clock(s.endTimeWindow)}` : '—'}</td>
                <td className={`${td} text-[var(--text-secondary)]`}>{fmtTime(s.arrivedAt)}</td>
                <td className={td}>
                  {fmtTime(s.completedAt)}
                  {s.dwellMinutes != null && s.dwellMinutes > 0 && <div className="text-2xs font-sans text-[var(--text-muted)]">{s.dwellMinutes} min sur place</div>}
                </td>
                <td className={`${td} text-right`}>{delayCell(s)}</td>
                <td className={`${td} font-sans`}><StatusBadge status={s.finalStatus} size="sm" /></td>
                <td className={`${td} font-sans border-r-0 text-[var(--text-secondary)]`}>
                  {removed ? (
                    // A removed stop with a target route was *reassigned* (moved to another driver),
                    // not "replanned" (sent back to the pool) — label it accordingly.
                    <span className="text-[var(--info)]">
                      {s.movement === 'CANCELLED' ? L.movement.CANCELLED : s.removedReason === 'REASSIGNED' ? 'Réassigné' : 'Replanifié'}
                      {s.movementTarget ? ` → ${s.movementTarget}` : ''}
                    </span>
                  ) : s.failReason ? (
                    s.failReason
                  ) : s.failureCode ? (
                    <StatusBadge status={s.failureCode} size="sm" />
                  ) : s.hasPod ? (
                    <span className="inline-flex items-center gap-1 text-[var(--text-muted)]"><IconCamera size={13} /> Preuve</span>
                  ) : <span className="text-[var(--text-soft)]">—</span>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </>
  );
}

// ── synthèse (auto narrative) ─────────────────────────────────────────────────
function Ref({ order, ref }: { order: number; ref: string | null }) {
  return <><b className="font-semibold">#{order}</b>{ref ? <> <span className="font-mono text-2xs text-[var(--text-secondary)]">({ref})</span></> : null}</>;
}
function Synthese({ report }: { report: RouteReport }) {
  const h = report.header, k = report.kpis;
  const failed = report.stops.filter(s => s.finalStatus === 'FAILED' || s.finalStatus === 'FAILED_ATTEMPT');
  const reassigned = report.stops.filter(s => s.removedReason === 'REASSIGNED');
  const startLate = (k.routeStartDelayMinutes ?? 0) > 0;
  return (
    <>
      <Bar title="Synthèse" />
      <p className="px-2.5 py-2 text-xs leading-relaxed text-[var(--text-primary)]">
        Départ à <b className="font-semibold">{fmtTime(h.startedAt)}</b>
        {startLate && <span style={{ color: T.danger }}> (+{absMin(k.routeStartDelayMinutes!)} sur le plan {clock(h.plannedStartTime)})</span>}.{' '}
        Sur {k.attemptedStops} arrêt{k.attemptedStops > 1 ? 's' : ''} tenté{k.attemptedStops > 1 ? 's' : ''} : <b className="font-semibold">{k.completedStops} livré{k.completedStops > 1 ? 's' : ''}</b>
        {k.partialStops > 0 && <>, {k.partialStops} partiel{k.partialStops > 1 ? 's' : ''}</>}
        {failed.length > 0 && <>, <span style={{ color: T.danger }}>{failed.length} en échec</span></>}.
        {failed.length > 0 && (
          <> Échec{failed.length > 1 ? 's' : ''} : {failed.map((s, i) => (
            <span key={s.stopId}>{i > 0 ? ' ; ' : ' '}<Ref order={s.stopOrder} ref={s.orderRef} />{s.failReason ? ` — ${s.failReason}` : ''}</span>
          ))}.</>
        )}
        {reassigned.length > 0 && (
          <> Réassigné{reassigned.length > 1 ? 's' : ''} : {reassigned.map((s, i) => (
            <span key={s.stopId}>{i > 0 ? ' ; ' : ' '}<Ref order={s.stopOrder} ref={s.orderRef} />{s.movementTarget ? ` → ${s.movementTarget}` : ''}</span>
          ))}.</>
        )}
        {' '}Clôturée à <b className="font-semibold">{fmtTime(h.closedAt)}</b>{h.durationMinutes != null ? ` (durée ${formatMinutes(h.durationMinutes)})` : ''}.
      </p>
    </>
  );
}

// ── highlights (movements = the real, human exceptions) ───────────────────────
function hlIcon(type: string): { icon: React.ReactNode; tone: string } {
  if (type === 'STOP_REMOVED_REPLANNED') return { icon: <IconArrowBackUp size={14} />, tone: 'var(--info)' };
  if (type === 'HANDOFF_CONFIRMED') return { icon: <IconArrowsExchange size={14} />, tone: 'var(--brand)' };
  if (type === 'STOP_FAILED') return { icon: <IconCircleX size={14} />, tone: T.danger };
  if (type === 'STOP_REMOVED_CANCELLED' || type === 'DELIVERY_CANCELLED') return { icon: <IconBan size={14} />, tone: T.muted };
  return { icon: <IconFlag size={14} />, tone: T.muted };
}
function Highlights({ report }: { report: RouteReport }) {
  if (report.movements.length === 0) return null;
  return (
    <>
      <Bar title="Faits marquants" />
      <div>
        {report.movements.map((m, i) => {
          const { icon, tone } = hlIcon(m.type);
          return (
            <div key={i} className="flex gap-2 px-2.5 py-1.5 border-b border-[var(--border)] text-xs">
              <span className="font-mono text-2xs text-[var(--text-muted)] w-[38px] shrink-0 pt-px">{fmtTime(m.at)}</span>
              <span className="shrink-0 pt-px" style={{ color: tone }}>{icon}</span>
              <div className="text-[var(--text-secondary)]">
                {m.detail ?? m.type}
                {m.actor && <span className="text-[var(--text-muted)]"> · {m.actor}</span>}
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}

// ── POD strip ─────────────────────────────────────────────────────────────────
function PodStrip({ report }: { report: RouteReport }) {
  const [open, setOpen] = useState<{ url: string; title: string; comment?: string | null } | null>(null);
  const cells = report.podGallery.flatMap(p => {
    const c = p.clientName ?? '—';
    const out: { url: string; title: string; comment?: string | null }[] = [];
    if (p.photoUrl) out.push({ url: p.photoUrl, title: `Photo · ${c}`, comment: p.comment });
    if (p.signatureUrl) out.push({ url: p.signatureUrl, title: `Signature · ${c}` });
    if (p.bonLivraisonUrl) out.push({ url: p.bonLivraisonUrl, title: `Bon de livraison · ${c}` });
    return out;
  });
  if (cells.length === 0) return null;
  return (
    <>
      <Bar title="Preuves de livraison" meta={String(cells.length)} />
      <div className="flex flex-wrap gap-1.5 p-2">
        {cells.slice(0, 12).map((c, i) => (
          <button key={i} type="button" onClick={() => setOpen(c)}
            className="relative w-16 h-16 overflow-hidden p-0 border border-[var(--border-strong)] bg-[var(--surface-sunken)]">
            <img src={c.url} alt={c.title} className="w-full h-full object-cover" />
          </button>
        ))}
        {cells.length > 12 && <div className="self-center text-2xs font-semibold text-[var(--text-muted)]">+ {cells.length - 12}</div>}
      </div>
      <AppModal opened={!!open} onClose={() => setOpen(null)} title={open?.title} size="lg">
        {open && (
          <div className="flex flex-col gap-2">
            <img src={open.url} alt={open.title} className="w-full rounded-[var(--radius-lg)]" />
            {open.comment && <p className="text-xs text-[var(--text-secondary)] italic">« {open.comment} »</p>}
          </div>
        )}
      </AppModal>
    </>
  );
}

// ── full audit (collapsed) ────────────────────────────────────────────────────
function AuditFold({ report }: { report: RouteReport }) {
  const [open, setOpen] = useState(false);
  const rows = report.auditTrail;
  if (rows.length === 0) return null;
  return (
    <>
      <button type="button" onClick={() => setOpen(o => !o)}
        className="flex items-center justify-between w-full px-2.5 py-1.5 bg-[var(--surface-sunken)] border-t border-[var(--border-strong)] text-2xs text-[var(--text-secondary)] hover:bg-[var(--hover-bg)]">
        <span>Audit complet · {rows.length} événements</span>
        {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
      </button>
      {open && (
        <div className="divide-y divide-[var(--border)]">
          {rows.map((a, i) => (
            <div key={i} className="flex gap-2 px-2.5 py-1 text-2xs">
              <span className="font-mono text-[var(--text-muted)] w-[86px] shrink-0">{fmtDT(a.at)}</span>
              <div className="min-w-0">
                <span className="text-[var(--text-secondary)]">{a.action ?? '—'}</span>
                {a.detail && <span className="text-[var(--text-muted)]"> — {a.detail}</span>}
                {a.actor && <span className="text-[var(--text-soft)]"> · {a.actor}</span>}
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
