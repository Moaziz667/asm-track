import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  IconFileTypePdf, IconPrinter, IconAlertCircle, IconCamera, IconChevronDown, IconChevronRight,
  IconArrowBackUp, IconArrowsExchange, IconCircleX, IconBan, IconFlag,
} from '@tabler/icons-react';

import { useRouteReport, downloadRouteReportPdf } from './hooks/useRouteReport';
import { REPORT_LABELS as L } from './report-labels';
import type { RouteReport } from './report-types';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { formatMinutes } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { AppModal } from '@/components/overlays/AppModal';

// ── formatters ─────────────────────────────────────────────────────────────────
const fmtDate = (iso?: string | null) => (iso ? new Date(iso).toLocaleDateString('fr-FR') : '—');
const fmtTime = (iso?: string | null) => (iso ? new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '—');
// Compact audit timestamp: day/month + time, no year — keeps the Heure column narrow so it never
// bleeds into the Réf column (e.g. "05/07 02:12" instead of "05/07/2026 02:12").
const fmtAuditAt = (iso?: string | null) => (iso ? new Date(iso).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—');
const clock = (t?: string | null) => (t ?? '').slice(0, 5) || '—';
const absMin = (m: number) => formatMinutes(Math.abs(m));
const signed = (m?: number | null) => (m == null ? '—' : m === 0 ? absMin(0) : `${m > 0 ? '+' : '−'}${absMin(m)}`);

// Threshold tones — consumed only by inline `style` for values the class engine can't precompute.
const T = { danger: 'var(--danger)', warning: 'var(--warning)', success: 'var(--success)', muted: 'var(--text-soft)' };
const delayTone = (m: number | null) => (m == null ? T.muted : m > 10 ? T.danger : m > 0 ? T.warning : T.success);

type C = ReturnType<typeof useT>['routeReport'];

export default function RouteClosureReport({ routeId }: { routeId: string }) {
  const c = useT().routeReport;
  const { data, isLoading, isError, error } = useRouteReport(routeId);
  const [downloading, setDownloading] = useState(false);

  if (isLoading) {
    return <div className="p-4"><Skeleton className="h-[220px] w-full" /></div>;
  }
  if (isError) {
    return (
      <div className="m-4 flex items-start gap-2.5 rounded-[var(--radius-lg)] p-3 bg-[var(--danger-bg)] border border-[var(--danger)]">
        <IconAlertCircle size={16} className="mt-px shrink-0 text-[var(--danger)]" />
        <div>
          <p className="text-xs font-bold text-[var(--danger)]">{c.loadError}</p>
          <p className="text-xs mt-0.5 text-[var(--text-muted)]">{(error as Error)?.message ?? c.networkError}</p>
        </div>
      </div>
    );
  }
  if (!data) return null;

  const download = async () => {
    setDownloading(true);
    try {
      await downloadRouteReportPdf(routeId, `rapport-tournee-${data.header.routeName ?? routeId}.pdf`);
      showSuccessToast('successReportDownloaded');
    } catch {
      showErrorToast(null, 'errorReportDownloadFailed');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="p-4 print-report">
      <div className="border border-[var(--border-strong)] bg-[var(--surface)] text-[var(--text-primary)]">
        <HeaderBar report={data} c={c} downloading={downloading} onDownload={download} onPrint={() => window.print()} />
        <IdentityGrid report={data} c={c} />
        <VerdictLine report={data} c={c} />
        <KpiRow report={data} c={c} />
        <StopsTable report={data} c={c} />
        <Synthese report={data} c={c} />
        <Highlights report={data} c={c} />
        <PodStrip report={data} c={c} />
        <AuditFold report={data} c={c} />
      </div>
    </div>
  );
}

// ── section bar ─────────────────────────────────────────────────────────────────
function Bar({ title, meta }: { title: string; meta?: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between px-3 py-2 bg-[var(--surface-sunken)] border-t border-[var(--border-strong)] border-b border-[var(--border)]">
      <span className="text-xs font-semibold text-[var(--text-secondary)]">{title}</span>
      {meta != null && <span className="text-xs text-[var(--text-muted)]">{meta}</span>}
    </div>
  );
}

// ── header ─────────────────────────────────────────────────────────────────────
function HeaderBar({ report, c, downloading, onDownload, onPrint }: { report: RouteReport; c: C; downloading: boolean; onDownload: () => void; onPrint: () => void }) {
  const h = report.header;
  return (
    <div className="flex items-center justify-between gap-3 px-2.5 py-1.5 bg-[var(--surface-sunken)] border-b border-[var(--border-strong)]">
      <div className="flex items-center gap-2 min-w-0">
        <img src="/AppLogo.png" alt="ASM Track" className="h-5 w-5 rounded-[4px] shrink-0" />
        <span className="font-mono text-sm font-semibold text-[var(--text-primary)]">{h.routeName ?? 'Tournée'}</span>
        <span className="text-xs text-[var(--text-muted)] truncate">— {c.subtitle}</span>
        <StatusBadge status="CLOSED" label={c.closed} size="sm" />
      </div>
      <div className="flex gap-1.5 shrink-0 print-hide">
        <Button size="sm" variant="outline" onClick={onPrint} className="h-7 gap-1 text-xs">
          <IconPrinter size={14} /> {c.print}
        </Button>
        <Button size="sm" disabled={downloading} onClick={onDownload} className="h-7 gap-1 text-xs">
          <IconFileTypePdf size={14} /> {downloading ? '…' : 'PDF'}
        </Button>
      </div>
    </div>
  );
}

// ── identity grid ─────────────────────────────────────────────────────────────
function IdentityGrid({ report, c }: { report: RouteReport; c: C }) {
  const h = report.header;
  const K = ({ children }: { children: React.ReactNode }) => (
    <td className="px-2.5 py-1.5 w-[90px] bg-[var(--surface-sunken)] text-[var(--text-muted)] border-b border-[var(--border)] text-xs font-medium">{children}</td>
  );
  const V = ({ children, mono, border }: { children: React.ReactNode; mono?: boolean; border?: boolean }) => (
    <td className={`px-2.5 py-1.5 border-b border-[var(--border)] text-[var(--text-secondary)] text-xs ${border ? 'border-r border-[var(--border)]' : ''} ${mono ? 'font-mono' : ''}`}>{children}</td>
  );
  const driverLink = h.driverId
    ? <Link to={`/drivers?driverId=${h.driverId}`} className="hover:text-[var(--brand)] transition-colors">{h.driverName ?? '—'}</Link>
    : (h.driverName ?? '—');
  const vehicleLink = h.vehicleId
    ? <Link to={`/vehicles?vehicleId=${h.vehicleId}`} className="hover:text-[var(--brand)] transition-colors">{h.vehicleType ?? ''}{h.vehiclePlate ? ` · ${h.vehiclePlate}` : ''}</Link>
    : <>{h.vehicleType ?? ''}{h.vehiclePlate ? ` · ${h.vehiclePlate}` : ''}</>;
  return (
    <table className="w-full border-collapse text-xs">
      <tbody>
        <tr>
          <K>{c.driver}</K><V border>{driverLink}</V>
          <K>{c.vehicle}</K><V mono>{vehicleLink}</V>
        </tr>
        <tr>
          <K>{c.depot}</K><V border>{h.depotName ?? '—'}</V>
          <K>{c.date}</K><V mono>{fmtDate(h.date)}</V>
        </tr>
        <tr>
          <td className="px-2.5 py-1.5 bg-[var(--surface-sunken)] text-[var(--text-muted)] text-xs font-medium">{c.execution}</td>
          <td className="px-2.5 py-1.5 font-mono text-xs text-[var(--text-secondary)]" colSpan={3}>
            {c.started} <span className="font-semibold">{fmtTime(h.startedAt)}</span>  →  {c.endedAt} <span className="font-semibold">{fmtTime(h.closedAt)}</span>  ·  {c.duration} {h.durationMinutes != null ? formatMinutes(h.durationMinutes) : '—'}  ·  {c.plan} {clock(h.plannedStartTime)}–{clock(h.plannedEndTime)}
          </td>
        </tr>
      </tbody>
    </table>
  );
}

// ── verdict line ─────────────────────────────────────────────────────────────
function VerdictLine({ report, c }: { report: RouteReport; c: C }) {
  const k = report.kpis;
  const dot = <span className="text-[var(--border-strong)]">·</span>;
  return (
    <div className="flex items-baseline flex-wrap gap-x-2 gap-y-0.5 px-2.5 py-1.5 border-t border-[var(--border-strong)] text-xs">
      {/*
        "Livré" means one thing on this page.

        This line counted completed + partial and called the total "livrés", while the stops bar
        below counted completed alone and the summary listed the two apart. One route printed
        "1 / 1 livrés" at the top and "0 livrés · 1 partiels" at the bottom — the same word, two
        answers, on a document meant to close a day. A partial delivery is not a delivery; it gets
        its own mention, next to the count rather than folded into it.
      */}
      <span className="font-semibold text-[var(--text-primary)]">{k.completedStops} / {k.attemptedStops} {c.verdictDelivered}</span>
      {k.partialStops > 0 && <>{dot}<span style={{ color: T.warning }}>{k.partialStops} {c.synPartial}</span></>}
      {dot}
      <span style={{ color: k.onTimeRate >= 85 ? undefined : T.danger }}>{k.onTimeRate.toFixed(0)} % {c.verdictOnTime}</span>
      {k.failedStops + k.failedAttemptStops > 0 && <>{dot}<span style={{ color: T.danger }}>{k.failedStops + k.failedAttemptStops} {c.verdictFailures}</span></>}
      {k.replannedStops > 0 && <>{dot}<span className="text-[var(--text-secondary)]">{k.replannedStops} {c.verdictReassigned}</span></>}
    </div>
  );
}

// ── KPI row ──────────────────────────────────────────────────────────────────
function KpiRow({ report, c }: { report: RouteReport; c: C }) {
  const k = report.kpis;
  const cell = (label: string, value: React.ReactNode, tone?: string, last?: boolean) => (
    <td className={`px-3 py-2.5 align-top ${last ? '' : 'border-r border-[var(--border)]'}`}>
      <div className="text-xs text-[var(--text-muted)] font-medium mb-1">{label}</div>
      <div className="text-lg font-[650] tabular-nums leading-none" style={tone ? { color: tone } : undefined}>{value}</div>
    </td>
  );
  const compTone = k.completionRate >= 90 ? undefined : k.completionRate >= 70 ? T.warning : T.danger;
  const otTone = k.onTimeRate >= 85 ? undefined : k.onTimeRate >= 60 ? T.warning : T.danger;
  return (
    <table className="w-full border-collapse border-t border-[var(--border-strong)]">
      <tbody><tr>
        {cell(c.kpiCompletion, `${k.completionRate.toFixed(1)} %`, compTone)}
        {cell(c.kpiPunctuality, `${k.onTimeRate.toFixed(1)} %`, otTone)}
        {cell(c.kpiCumDelay, signed(k.cumulativeDelayMinutes), (k.cumulativeDelayMinutes ?? 0) > 30 ? T.danger : (k.cumulativeDelayMinutes ?? 0) > 0 ? T.warning : undefined)}
        {cell(c.kpiDistance, k.totalDistanceKm != null ? `${k.totalDistanceKm} km` : '—')}
        {cell(c.kpiActive, k.activeDurationMinutes != null ? formatMinutes(k.activeDurationMinutes) : '—')}
        {cell(c.kpiStartDelay, signed(k.routeStartDelayMinutes), (k.routeStartDelayMinutes ?? 0) > 10 ? T.warning : undefined, true)}
      </tr></tbody>
    </table>
  );
}

// ── stops table ─────────────────────────────────────────────────────────────
function delayCell(s: RouteReport['stops'][number], c: C) {
  if (!['COMPLETED', 'PARTIAL'].includes(s.finalStatus) || s.delayMinutes == null) return <span className="text-[var(--text-soft)]">—</span>;
  if (s.delayMinutes <= 0 && s.delayMinutes > -5) return <span className="whitespace-nowrap" style={{ color: T.success }}>{c.onTimeShort}</span>;
  /*
    A negative delay is an early arrival, and the column is headed "Retard".
    One report showed "−17h 1m" there, which reads as broken rather than as information: the reader
    has to work out that a minus sign in a delay column means the opposite of the column's name.
    Early arrivals say so in words and keep the magnitude beside them.
  */
  if (s.delayMinutes < 0) {
    return (
      <span className="whitespace-nowrap tabular-nums" style={{ color: T.success }}>
        {c.earlyShort} {absMin(s.delayMinutes)}
      </span>
    );
  }
  return <span className="whitespace-nowrap tabular-nums" style={{ color: delayTone(s.delayMinutes) }}>{signed(s.delayMinutes)}</span>;
}
function StopsTable({ report, c }: { report: RouteReport; c: C }) {
  const k = report.kpis;
  const th = 'text-left font-medium text-[var(--text-muted)] px-2 py-2 border-b border-[var(--border-strong)] border-r border-[var(--border)] text-xs';
  const td = 'px-2 py-2 border-b border-[var(--border)] border-r border-[var(--border)] align-top';
  return (
    <>
      <Bar title={c.stopsTitle} meta={`${report.stops.length} ${c.deliveries} · ${k.completedStops} ${c.deliveredShort} · ${k.failedStops + k.failedAttemptStops} ${c.failuresShort}`} />
      <table className="w-full border-collapse text-xs">
        <colgroup>
          <col className="w-[28px]" /><col /><col className="w-[72px]" /><col className="w-[56px]" />
          <col className="w-[60px]" /><col className="w-[56px]" /><col className="w-[110px]" /><col className="w-[110px]" />
        </colgroup>
        <thead>
          <tr className="bg-[var(--surface-sunken)]">
            <th className={th}>#</th>
            <th className={th}>{c.colClient}</th>
            <th className={th}>{c.colWindow}</th>
            <th className={th}>{c.colDelivered}</th>
            <th className={`${th} text-right`}>{c.colDelay}</th>
            <th className={th}>{c.colStatus}</th>
            <th className={`${th} border-r-0`}>{c.colMotif}</th>
          </tr>
        </thead>
        <tbody className="font-mono tabular-nums">
          {report.stops.map(s => {
            const removed = s.movement === 'REPLANNED' || s.movement === 'CANCELLED';
            return (
              <tr key={s.stopId} className={`transition-colors hover:bg-[var(--hover-bg)] ${removed ? 'opacity-55' : ''}`}>
                <td className={`${td} text-[var(--text-muted)]`}>{String(s.stopOrder).padStart(2, '0')}</td>
                <td className={`${td} font-sans`}>
                  <div className={`font-semibold text-[var(--text-primary)] ${removed ? 'line-through' : ''}`}>{s.clientName ?? '—'}</div>
                  <div className="text-xs text-[var(--text-muted)] truncate mt-0.5">
                    {s.orderRef && <Link to={`/deliveries/${s.deliveryId}`} className="font-mono hover:text-[var(--brand)] transition-colors">{s.orderRef}</Link>}
                    {s.orderRef && (s.city || s.address) ? ' · ' : ''}{s.city ?? s.address ?? ''}
                  </div>
                </td>
                <td className={`${td} text-[var(--text-secondary)] whitespace-nowrap`}>{s.startTimeWindow && s.endTimeWindow ? `${clock(s.startTimeWindow)}–${clock(s.endTimeWindow)}` : '—'}</td>
                <td className={td}>
                  <span className="whitespace-nowrap">{fmtTime(s.completedAt)}</span>
                  {s.dwellMinutes != null && s.dwellMinutes > 0 && <div className="text-2xs font-sans text-[var(--text-muted)] mt-0.5">{s.dwellMinutes} min {c.dwellSuffix}</div>}
                </td>
                <td className={`${td} text-right`}>{delayCell(s, c)}</td>
                <td className={`${td} font-sans`}><StatusBadge status={s.removedReason === 'REASSIGNED' ? 'REASSIGNED' : s.finalStatus} label={s.removedReason !== 'REASSIGNED' && s.finalStatus === 'COMPLETED' ? c.colDelivered : undefined} size="sm" /></td>
                <td className={`${td} font-sans border-r-0 text-[var(--text-secondary)]`}>
                  {removed ? (
                    <span className="text-[var(--info)]">
                      {s.movement === 'CANCELLED' ? L.movement.CANCELLED : s.removedReason === 'REASSIGNED' ? c.reassigned : c.replanned}
                      {s.movementTarget ? ` → ${s.movementTarget}` : ''}
                    </span>
                  ) : s.failReason ? (
                    s.failReason
                  ) : s.failureCode ? (
                    <StatusBadge status={s.failureCode} size="sm" />
                  ) : s.hasPod ? (
                    <span className="inline-flex items-center gap-1 text-[var(--text-muted)]"><IconCamera size={13} /> {c.proof}</span>
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

// ── synthèse (structured K/V — i18n-friendly) ─────────────────────────────────
function Ref({ order, ref }: { order: number; ref: string | null }) {
  return <><b className="font-semibold">#{order}</b>{ref ? <> <span className="font-mono text-2xs text-[var(--text-secondary)]">({ref})</span></> : null}</>;
}
function Synthese({ report, c }: { report: RouteReport; c: C }) {
  const h = report.header, k = report.kpis;
  const failed = report.stops.filter(s => s.finalStatus === 'FAILED' || s.finalStatus === 'FAILED_ATTEMPT');
  const reassigned = report.stops.filter(s => s.removedReason === 'REASSIGNED');
  const startLate = (k.routeStartDelayMinutes ?? 0) > 0;
  const Row = ({ label, children }: { label: string; children: React.ReactNode }) => (
    <tr>
      <td className="px-2.5 py-1.5 w-[96px] bg-[var(--surface-sunken)] text-[var(--text-muted)] border-b border-[var(--border)] align-top text-xs font-medium">{label}</td>
      <td className="px-2.5 py-1.5 border-b border-[var(--border)] text-[var(--text-secondary)] text-xs">{children}</td>
    </tr>
  );
  return (
    <>
      <Bar title={c.syntheseTitle} />
      <table className="w-full border-collapse text-xs">
        <tbody>
          <Row label={c.synDeparture}>
            <span className="font-mono">{fmtTime(h.startedAt)}</span>
            {startLate && <span style={{ color: T.danger }}> (+{absMin(k.routeStartDelayMinutes!)} {c.synOnPlan} <span className="font-mono">{clock(h.plannedStartTime)}</span>)</span>}
          </Row>
          <Row label={c.synResults}>
            <b className="font-semibold">{k.completedStops}</b> {c.synDelivered} · <b className="font-semibold">{k.partialStops}</b> {c.synPartial} · <span style={{ color: failed.length ? T.danger : undefined }}><b className="font-semibold">{failed.length}</b> {c.synFailed}</span> — {k.attemptedStops} {c.synStops}
          </Row>
          {reassigned.length > 0 && (
            <Row label={c.synReassigned}>
              {reassigned.map((s, i) => (
                <span key={s.stopId}>{i > 0 ? ' ; ' : ''}<Ref order={s.stopOrder} ref={s.orderRef} />{s.movementTarget ? <span className="font-mono"> → {s.movementTarget}</span> : ''}</span>
              ))}
            </Row>
          )}
          {failed.length > 0 && (
            <Row label={c.synFailures}>
              {failed.map((s, i) => (
                <span key={s.stopId}>{i > 0 ? ' ; ' : ''}<Ref order={s.stopOrder} ref={s.orderRef} />{s.failReason ? ` — ${s.failReason}` : ''}</span>
              ))}
            </Row>
          )}
          <Row label={c.synClosure}>
            <span className="font-mono">{fmtTime(h.closedAt)}</span>{h.durationMinutes != null ? <> · {c.duration} {formatMinutes(h.durationMinutes)}</> : ''}
          </Row>
        </tbody>
      </table>
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
function Highlights({ report, c }: { report: RouteReport; c: C }) {
  if (report.movements.length === 0) return null;
  return (
    <>
      <Bar title={c.highlightsTitle} />
      <div>
        {report.movements.map((m, i) => {
          const { icon, tone } = hlIcon(m.type);
          return (
            <div key={i} className="flex gap-2 px-2.5 py-1.5 border-b border-[var(--border)] text-xs">
              <span className="font-mono text-xs text-[var(--text-muted)] w-[38px] shrink-0 pt-px">{fmtTime(m.at)}</span>
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
function PodStrip({ report, c }: { report: RouteReport; c: C }) {
  const [open, setOpen] = useState<{ url: string; title: string; comment?: string | null } | null>(null);
  const cells = report.podGallery.flatMap(p => {
    const cl = p.clientName ?? '—';
    const out: { url: string; title: string; comment?: string | null }[] = [];
    if (p.photoUrl) out.push({ url: p.photoUrl, title: `${cl}`, comment: p.comment });
    if (p.signatureUrl) out.push({ url: p.signatureUrl, title: `${cl}` });
    if (p.bonLivraisonUrl) out.push({ url: p.bonLivraisonUrl, title: `${cl}` });
    return out;
  });
  if (cells.length === 0) return null;
  return (
    <>
      <Bar title={c.podTitle} meta={String(cells.length)} />
      <div className="flex flex-wrap gap-2 p-2.5">
        {cells.slice(0, 12).map((cell, i) => (
          <button key={i} type="button" onClick={() => setOpen(cell)}
            className="relative w-20 h-20 overflow-hidden p-0 border border-[var(--border-strong)] bg-[var(--surface-sunken)] hover:border-[var(--brand)] transition-colors">
            <img src={cell.url} alt={cell.title} className="w-full h-full object-cover" />
          </button>
        ))}
        {cells.length > 12 && <div className="self-center text-xs font-medium text-[var(--text-muted)]">+ {cells.length - 12}</div>}
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

// ── full audit (collapsed, columnar) ──────────────────────────────────────────
// Build the event detail from resolved params with locale-aware prefixes ("Motif: …" /
// "Reason: …" / "السبب: …"). Falls back to the backend detail string for old snapshots.
function auditDetail(a: RouteReport['auditTrail'][number], c: C): string | null {
  const p = a.detailParams;
  if (p) {
    const parts = (['route', 'driver', 'reason', 'note'] as const)
      .filter(k => p[k])
      .map(k => `${c.detailLabels[k]}: ${p[k]}`);
    if (parts.length) return parts.join(' | ');
  }
  return a.detail ?? null;
}
function AuditFold({ report, c }: { report: RouteReport; c: C }) {
  const [open, setOpen] = useState(false);
  const rows = report.auditTrail;
  if (rows.length === 0) return null;
  const th = 'text-left font-semibold text-[var(--text-muted)] px-2 py-1.5 border-b border-[var(--border-strong)] border-r border-[var(--border)] text-2xs';
  const td = 'px-2 py-1.5 border-b border-[var(--border)] border-r border-[var(--border)] align-top';
  return (
    <>
      <button type="button" onClick={() => setOpen(o => !o)}
        className="flex items-center justify-between w-full px-2.5 py-1.5 bg-[var(--surface-sunken)] border-t border-[var(--border-strong)] text-2xs text-[var(--text-secondary)] hover:bg-[var(--hover-bg)]">
        <span>{c.auditTitle} · {rows.length} {c.auditEvents}</span>
        {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
      </button>
      {open && (
        <table className="w-full border-collapse text-2xs table-fixed">
          <colgroup><col className="w-[112px]" /><col className="w-[128px]" /><col /><col className="w-[120px]" /></colgroup>
          <thead>
            <tr className="bg-[var(--surface-sunken)]">
              <th className={th}>{c.colTime}</th>
              <th className={th}>{c.colRef}</th>
              <th className={th}>{c.colEvent}</th>
              <th className={`${th} border-r-0`}>{c.colActor}</th>
            </tr>
          </thead>
          <tbody className="font-mono">
            {rows.map((a, i) => {
              const label = c.eventLabels[a.actionKey ?? ''] ?? a.action ?? '—';
              const detail = auditDetail(a, c);
              return (
                <tr key={i}>
                  <td className={`${td} text-[var(--text-muted)] whitespace-nowrap`}>{fmtAuditAt(a.at)}</td>
                  <td className={`${td} overflow-hidden`}>
                    {a.stopOrder != null && <span className="text-[var(--text-muted)]">#{a.stopOrder}</span>}
                    {a.orderRef && <div className="whitespace-nowrap text-[var(--text-secondary)]">{a.orderRef}</div>}
                  </td>
                  <td className={`${td} font-sans text-[var(--text-secondary)] break-words`}>{label}{detail && <span className="text-[var(--text-muted)]"> — {detail}</span>}</td>
                  <td className={`${td} font-sans border-r-0 text-[var(--text-muted)] break-words`}>{a.actor ?? '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </>
  );
}
