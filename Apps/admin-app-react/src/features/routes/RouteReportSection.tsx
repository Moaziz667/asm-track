
import { useState } from 'react';
import {
  IconDownload, IconAlertCircle, IconClockHour4, IconRoute,
  IconTruckDelivery, IconUser, IconBuildingWarehouse, IconCalendar,
  IconCheck, IconX, IconArrowRight, IconCamera,
} from '@tabler/icons-react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RcTooltip, BarChart, Bar, XAxis, YAxis, CartesianGrid } from 'recharts';

import { useRouteReport, downloadRouteReportPdf } from './hooks/useRouteReport';
import { REPORT_LABELS, CLASSIFICATION_COLORS, BREAKDOWN_COLORS } from './report-labels';
import type { RouteReport, StopClassification } from './report-types';
import { toast } from '@/lib/toast';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { messages } from '@/lib/toast-messages';
import { formatMinutes } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table';
import { AppModal } from '@/components/overlays/AppModal';
import { useT } from '@/lib/LocaleContext';

// ── Helpers ───────────────────────────────────────────────────────────────────
const fmtDate = (iso?: string | null) => iso ? new Date(iso).toLocaleDateString('fr-FR') : '—';
const fmtTime = (iso?: string | null) => iso ? new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : '—';
const fmtDateTime = (iso?: string | null) => iso ? new Date(iso).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' }) : '—';
const fmtClock = (t?: string | null) => (t ?? '').slice(0, 5) || '—';

// ── Main component ────────────────────────────────────────────────────────────
export default function RouteReportSection({ routeId }: { routeId: string }) {
  const { data, isLoading, isError, error } = useRouteReport(routeId);
  const [downloading, setDownloading] = useState(false);

  if (isLoading) {
    return (
      <div className="mt-6" style={{ borderTop: '2px solid var(--border)' }}>
        <div className="px-6 py-4 flex items-center gap-3" style={{ borderBottom: '1px solid var(--border)', background: 'var(--app-bg)' }}>
          <Skeleton className="h-5 w-[220px]" />
        </div>
        <div className="p-6 flex flex-col gap-4">
          <Skeleton className="h-[100px] w-full" />
          <Skeleton className="h-[220px] w-full" />
          <Skeleton className="h-[280px] w-full" />
        </div>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="mt-6" style={{ borderTop: '2px solid var(--border)' }}>
        <div className="m-6 flex items-start gap-3 rounded p-3" style={{ background: '#FEF2F2', border: '1px solid #FECACA' }}>
          <IconAlertCircle size={16} style={{ color: '#DC2626', marginTop: 1, flexShrink: 0 }} />
          <div>
            <p className="text-xs font-semibold" style={{ color: '#DC2626' }}>{REPORT_LABELS.errorTitle}</p>
            <p className="text-xs mt-0.5" style={{ color: '#B91C1C' }}>{(error as Error)?.message ?? 'Erreur réseau'}</p>
          </div>
        </div>
      </div>
    );
  }

  if (!data) return null;

  const handleDownload = async () => {
    setDownloading(true);
    try {
      await downloadRouteReportPdf(routeId, `rapport-tournee-${data.header.routeName ?? routeId}.pdf`);
      const msg = messages.routes.reportDownloaded;
      toast.success(msg.title);
    } catch {
      const msg = messages.routes.reportFailed;
      toast.error(msg.title, { description: msg.description });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div style={{ borderTop: '3px solid var(--border)', background: 'var(--surface)' }}>
      {/* ── Report document header ── */}
      <div className="flex items-center justify-between px-6 py-3.5" style={{ borderBottom: '1px solid var(--border)', background: 'var(--app-bg)' }}>
        <div className="flex items-center gap-3">
          <div style={{ width: 3, height: 28, background: 'var(--brand)', borderRadius: 2, flexShrink: 0 }} />
          <div>
            <p className="text-base font-bold uppercase tracking-[0.06em]" style={{ color: 'var(--text-primary)' }}>
              {REPORT_LABELS.title}
            </p>
            <p className="text-xs mt-0.5" style={{ color: 'var(--text-muted)' }}>
              {REPORT_LABELS.generatedAt} {new Date(data.generatedAt ?? '').toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' })}
            </p>
          </div>
        </div>
        <Button
          size="sm"
          disabled={downloading}
          onClick={handleDownload}
          style={{ background: 'var(--text-primary)', color: '#fff', fontSize: 11, fontWeight: 700 }}
        >
          <IconDownload size={14} className="mr-1.5" />
          {downloading ? '…' : REPORT_LABELS.downloadPdf}
        </Button>
      </div>

      {/* ── Report content ── */}
      <div className="flex flex-col gap-6 p-6">
        <HeaderCard report={data} />
        <KpiStrip report={data} />
        <div className="flex gap-4 flex-wrap items-start">
          <div style={{ flex: '1 1 360px', minWidth: 320 }}>
            <SectionTitle>{REPORT_LABELS.sections.breakdown}</SectionTitle>
            <BreakdownDonut report={data} />
          </div>
          <div style={{ flex: '2 1 480px', minWidth: 360 }}>
            <SectionTitle>{REPORT_LABELS.sections.timeline}</SectionTitle>
            <TimelineChart report={data} />
          </div>
        </div>
        <div>
          <SectionTitle>{REPORT_LABELS.sections.stops}</SectionTitle>
          <StopsTable report={data} />
        </div>
        {data.movements.length > 0 && (
          <div>
            <SectionTitle>{REPORT_LABELS.sections.movements}</SectionTitle>
            <MovementsList report={data} />
          </div>
        )}
        {data.podGallery.length > 0 && (
          <div>
            <SectionTitle>{REPORT_LABELS.sections.pods}</SectionTitle>
            <PodGallery report={data} />
          </div>
        )}
        {data.auditTrail.length > 0 && (
          <div>
            <SectionTitle>{REPORT_LABELS.sections.audit}</SectionTitle>
            <AuditTrailList report={data} />
          </div>
        )}
      </div>
    </div>
  );
}

// ── Header card ───────────────────────────────────────────────────────────────
function HeaderCard({ report }: { report: RouteReport }) {
  const h = report.header;
  const item = (icon: React.ReactNode, label: string, value: React.ReactNode) => (
    <div className="flex items-center gap-2">
      <div style={{ color: 'var(--text-muted)', flexShrink: 0 }}>{icon}</div>
      <div>
        <p className="text-xs uppercase font-medium" style={{ letterSpacing: '0.06em', color: 'var(--text-muted)' }}>{label}</p>
        <p className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{value ?? '—'}</p>
      </div>
    </div>
  );
  return (
    <div className="flex flex-wrap gap-6 p-4 rounded" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
      {item(<IconRoute size={18} />,             REPORT_LABELS.header.route,     h.routeName)}
      {item(<IconCalendar size={18} />,          REPORT_LABELS.header.date,      fmtDate(h.date))}
      {item(<IconUser size={18} />,              REPORT_LABELS.header.driver,    h.driverName)}
      {item(<IconTruckDelivery size={18} />,     REPORT_LABELS.header.vehicle,   `${h.vehicleType ?? ''}${h.vehiclePlate ? ' · ' + h.vehiclePlate : ''}`)}
      {item(<IconBuildingWarehouse size={18} />, REPORT_LABELS.header.depot,     h.depotName)}
      {item(<IconClockHour4 size={18} />,        REPORT_LABELS.header.startedAt, fmtTime(h.startedAt))}
      {item(<IconClockHour4 size={18} />,        REPORT_LABELS.header.closedAt,  fmtTime(h.closedAt))}
      {item(<IconClockHour4 size={18} />,        REPORT_LABELS.header.duration,  h.durationMinutes != null ? formatMinutes(h.durationMinutes) : '—')}
    </div>
  );
}

// ── KPI strip ─────────────────────────────────────────────────────────────────
function KpiStrip({ report }: { report: RouteReport }) {
  const k = report.kpis;
  const tile = (label: string, value: React.ReactNode, foot?: string, tone?: 'good' | 'warn' | 'bad') => (
    <div className="flex flex-col p-4 rounded" style={{ flex: '1 1 150px', minWidth: 150, border: '1px solid var(--border)', background: 'var(--surface)' }}>
      <p className="text-xs uppercase font-medium mb-1" style={{ letterSpacing: '0.06em', color: 'var(--text-muted)' }}>{label}</p>
      <p className={`text-xl font-bold ${
        tone === 'good' ? 'text-[var(--success)]' :
        tone === 'warn' ? 'text-[var(--warning)]' :
        tone === 'bad'  ? 'text-[var(--danger)]' :
        ''
      }`} style={!tone ? { color: 'var(--text-primary)' } : {}}>
        {value}
      </p>
      {foot && <p className="text-sm mt-0.5" style={{ color: 'var(--text-muted)' }}>{foot}</p>}
    </div>
  );
  return (
    <div className="flex flex-wrap gap-2">
      {tile(REPORT_LABELS.kpis.completionRate, `${k.completionRate.toFixed(1)} %`,
        `${k.completedStops + k.partialStops} ${REPORT_LABELS.kpis.completionFootnote.split(' ')[0]} ${k.attemptedStops}`,
        k.completionRate >= 90 ? 'good' : k.completionRate >= 70 ? 'warn' : 'bad')}
      {tile(REPORT_LABELS.kpis.onTimeRate, `${k.onTimeRate.toFixed(1)} %`,
        REPORT_LABELS.kpis.onTimeFootnote,
        k.onTimeRate >= 85 ? 'good' : k.onTimeRate >= 60 ? 'warn' : 'bad')}
      {tile(REPORT_LABELS.kpis.totalDistance, k.totalDistanceKm != null ? `${k.totalDistanceKm} km` : '—')}
      {tile(REPORT_LABELS.kpis.activeDuration, k.activeDurationMinutes != null ? formatMinutes(k.activeDurationMinutes) : '—')}
      {tile(REPORT_LABELS.kpis.cumulativeDelay, k.cumulativeDelayMinutes != null ? formatMinutes(k.cumulativeDelayMinutes) : '0m',
        undefined, k.cumulativeDelayMinutes && k.cumulativeDelayMinutes > 30 ? 'bad' : undefined)}
      {tile(REPORT_LABELS.kpis.failedStops, k.failedStops + k.failedAttemptStops,
        undefined, (k.failedStops + k.failedAttemptStops) > 0 ? 'bad' : 'good')}
      {tile(REPORT_LABELS.kpis.removedStops, k.replannedStops + k.cancelledStopsCount)}
      {tile(REPORT_LABELS.kpis.startDelay, k.routeStartDelayMinutes != null ? formatMinutes(k.routeStartDelayMinutes) : '—',
        undefined, k.routeStartDelayMinutes && k.routeStartDelayMinutes > 10 ? 'warn' : undefined)}
      {tile(REPORT_LABELS.kpis.attempted, `${k.attemptedStops} / ${k.totalStopsPlanned}`, REPORT_LABELS.kpis.planned)}
    </div>
  );
}

// ── Breakdown donut ───────────────────────────────────────────────────────────
function BreakdownDonut({ report }: { report: RouteReport }) {
  const data = report.statusBreakdown.filter(b => b.count > 0);
  if (data.length === 0) {
    return <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Aucun arrêt.</p>;
  }
  const total = data.reduce((s, b) => s + b.count, 0);
  return (
    <div className="p-4 rounded" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
      <div style={{ height: 240, position: 'relative' }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="count"
              nameKey="label"
              innerRadius={60}
              outerRadius={92}
              paddingAngle={2}
              cx="50%"
              cy="50%"
            >
              {data.map((entry) => (
                <Cell key={entry.key} fill={BREAKDOWN_COLORS[entry.key]} />
              ))}
            </Pie>
            <RcTooltip
              formatter={(v: any, _name: any, p: any) =>
                [`${v} arrêts (${p?.payload?.percentage ?? 0}%)`, p?.payload?.label ?? '']
              }
            />
          </PieChart>
        </ResponsiveContainer>
        <div style={{
          position: 'absolute', top: '50%', left: '50%',
          transform: 'translate(-50%, -50%)', textAlign: 'center',
          pointerEvents: 'none',
        }}>
          <p className="text-xl font-bold" style={{ color: 'var(--text-primary)' }}>{total}</p>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>arrêts</p>
        </div>
      </div>
      <div className="flex flex-wrap justify-center gap-3 mt-3">
        {data.map(b => (
          <div key={b.key} className="flex items-center gap-1.5">
            <div style={{ width: 10, height: 10, borderRadius: 2, background: BREAKDOWN_COLORS[b.key], flexShrink: 0 }} />
            <span className="text-sm" style={{ color: 'var(--text-primary)' }}>
              {b.label} <span style={{ color: 'var(--text-muted)' }}>({b.count})</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Timeline chart ────────────────────────────────────────────────────────────
function TimelineChart({ report }: { report: RouteReport }) {
  const data = report.timeline
    .filter(p => p.delayMinutes != null)
    .map(p => ({
      name: `#${p.stopOrder}`,
      delay: p.delayMinutes,
      classification: p.classification,
      client: p.clientName ?? '—',
    }));
  if (data.length === 0) {
    return (
      <div className="p-4 rounded" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Aucune donnée de retard à afficher.</p>
      </div>
    );
  }
  return (
    <div className="p-4 rounded" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
      <div style={{ height: 240 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#E5E7EB" />
            <XAxis dataKey="name" tick={{ fontSize: 10, fill: '#6B7280' }} />
            <YAxis tick={{ fontSize: 10, fill: '#6B7280' }} unit=" min" />
            <RcTooltip
              contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', fontSize: 12 }}
              formatter={(v: any, _n: any, p: any) => [`${v} min`, p?.payload?.client ?? '']}
            />
            <Bar dataKey="delay" radius={[3, 3, 0, 0]}>
              {data.map((d, i) => (
                <Cell key={i} fill={CLASSIFICATION_COLORS[d.classification as StopClassification]?.dot ?? '#9CA3AF'} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <p className="text-sm text-center mt-1" style={{ color: 'var(--text-muted)' }}>
        Retard par arrêt (min) — négatif = en avance, positif = en retard
      </p>
    </div>
  );
}

// ── Status helpers ────────────────────────────────────────────────────────────
function outcomePill(finalStatus: string) {
  const map: Record<string, { label: string; bg: string; fg: string }> = {
    COMPLETED:         { label: 'Livré',       bg: '#F0FDF4', fg: '#15803D' },
    PARTIAL:           { label: 'Partielle',   bg: '#FFFBEB', fg: '#B45309' },
    FAILED:            { label: 'Échec',       bg: '#FEF2F2', fg: '#991B1B' },
    FAILED_ATTEMPT:    { label: 'Tentative',   bg: '#FFF7ED', fg: '#C2410C' },
    REMOVED_REPLANNED: { label: 'Replanifié',  bg: '#EEF2FF', fg: '#4338CA' },
    REMOVED_CANCELLED: { label: 'Annulé',      bg: '#F3F4F6', fg: '#4B5563' },
  };
  return map[finalStatus] ?? { label: finalStatus, bg: '#F9FAFB', fg: '#6B7280' };
}

function timingPill(finalStatus: string, delayMinutes: number | null) {
  if (!['COMPLETED', 'PARTIAL'].includes(finalStatus) || delayMinutes == null) return null;
  if (delayMinutes > 0)
    return { label: `En retard · ${formatMinutes(delayMinutes)}`, bg: '#FEF2F2', fg: '#B91C1C' };
  if (delayMinutes > -5)
    return { label: 'À l\'heure', bg: '#F0FDF4', fg: '#15803D' };
  return { label: 'En avance', bg: '#EFF6FF', fg: '#1D4ED8' };
}

// ── Stops table ───────────────────────────────────────────────────────────────
function StopsTable({ report }: { report: RouteReport }) {
  const t = useT();
  return (
    <div className="overflow-hidden rounded" style={{ border: '1px solid var(--border)' }}>
      <div className="overflow-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{REPORT_LABELS.table.stopOrder}</TableHead>
              <TableHead>{REPORT_LABELS.table.client}</TableHead>
              <TableHead>{REPORT_LABELS.table.address}</TableHead>
              <TableHead>{REPORT_LABELS.table.window}</TableHead>
              <TableHead>{REPORT_LABELS.table.completed}</TableHead>
              <TableHead className="text-right">{REPORT_LABELS.table.delay}</TableHead>
              <TableHead>{REPORT_LABELS.table.status}</TableHead>
              <TableHead>{REPORT_LABELS.table.movement}</TableHead>
              <TableHead>{REPORT_LABELS.table.pod}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {report.stops.map(s => {
              const removed = s.movement === 'REPLANNED' || s.movement === 'CANCELLED';
              const outcome = outcomePill(s.finalStatus);
              const timing  = timingPill(s.finalStatus, s.delayMinutes);
              return (
                <TableRow key={s.stopId} style={{ opacity: removed ? 0.6 : 1 }}>
                  <TableCell>{s.stopOrder}</TableCell>
                  <TableCell>{s.clientName ?? '—'}</TableCell>
                  <TableCell className="text-xs" style={{ color: 'var(--text-muted)' }}>
                    {s.address ?? '—'}{s.city ? ', ' + s.city : ''}
                  </TableCell>
                  <TableCell>
                    {s.startTimeWindow && s.endTimeWindow
                      ? `${fmtClock(s.startTimeWindow)}–${fmtClock(s.endTimeWindow)}`
                      : '—'}
                  </TableCell>
                  <TableCell>{fmtTime(s.completedAt)}</TableCell>
                  <TableCell style={{
                    textAlign: 'right',
                    color: s.delayMinutes == null ? undefined : s.delayMinutes > 10 ? '#B91C1C' : s.delayMinutes > 0 ? '#B45309' : '#15803D',
                    fontWeight: 600,
                  }}>
                    {s.delayMinutes != null ? formatMinutes(s.delayMinutes) : '—'}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-col gap-1">
                      <Badge variant="outline" style={{ background: outcome.bg, color: outcome.fg, borderColor: outcome.bg, fontSize: 11 }}>
                        {outcome.label}
                      </Badge>
                      {timing && (
                        <Badge variant="outline" style={{ background: timing.bg, color: timing.fg, borderColor: timing.bg, fontSize: 11 }}>
                          {timing.label}
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-xs" style={{ color: 'var(--text-muted)' }}>
                    {s.movement
                      ? `${REPORT_LABELS.movement[s.movement]}${s.movementTarget ? ' → ' + s.movementTarget : ''}`
                      : '—'}
                  </TableCell>
                  <TableCell>
                    {s.hasPod
                      ? <span title={t.deliveryPage.sectionProof}><IconCheck size={16} color="#16A34A" /></span>
                      : <IconX size={16} color="#9CA3AF" />}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}

// ── Movements list ────────────────────────────────────────────────────────────
function MovementsList({ report }: { report: RouteReport }) {
  return (
    <div className="flex flex-col gap-2 p-4 rounded" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
      {report.movements.map((m, i) => (
        <div key={i} className="flex items-start gap-3 flex-nowrap">
          <span className="font-mono text-sm shrink-0" style={{ minWidth: 110, color: 'var(--text-muted)' }}>
            {fmtDateTime(m.at)}
          </span>
          <Badge variant="secondary" style={{ fontSize: 11, flexShrink: 0 }}>
            {REPORT_LABELS.movement[m.type] ?? m.type}
          </Badge>
          <span className="text-sm flex-1" style={{ color: 'var(--text-primary)' }}>{m.detail ?? '—'}</span>
          {m.actor && <span className="text-sm shrink-0" style={{ color: 'var(--text-muted)' }}>par {m.actor}</span>}
        </div>
      ))}
    </div>
  );
}

// ── POD gallery + lightbox ────────────────────────────────────────────────────
function PodGallery({ report }: { report: RouteReport }) {
  const [opened, setOpened] = useState<{ url: string; title: string } | null>(null);
  const cells = report.podGallery.flatMap(p => {
    const items: Array<{ url: string; title: string; stopOrder: number; client: string }> = [];
    const client = p.clientName ?? '—';
    if (p.photoUrl)         items.push({ url: p.photoUrl,         title: `Photo · ${client}`,         stopOrder: p.stopOrder, client });
    if (p.signatureUrl)     items.push({ url: p.signatureUrl,     title: `Signature · ${client}`,     stopOrder: p.stopOrder, client });
    if (p.bonLivraisonUrl)  items.push({ url: p.bonLivraisonUrl,  title: `BL · ${client}`,            stopOrder: p.stopOrder, client });
    return items;
  });
  if (cells.length === 0) return null;

  return (
    <>
      <div className="p-4 rounded" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}>
          {cells.map((c, i) => (
            <button
              key={i}
              type="button"
              onClick={() => setOpened({ url: c.url, title: c.title })}
              className="group relative aspect-square overflow-hidden rounded-sm transition-colors"
              style={{ padding: 0, background: 'transparent', border: '1px solid var(--border)' }}
            >
              <img src={c.url} alt={c.title} className="w-full h-full object-cover" />
              <div className="absolute bottom-0 left-0 right-0 px-2 py-1 flex items-center gap-1" style={{ background: 'rgba(0,0,0,0.6)' }}>
                <IconCamera size={10} color="#fff" />
                <span className="text-2xs text-white truncate">#{c.stopOrder} · {c.client}</span>
              </div>
            </button>
          ))}
        </div>
      </div>
      <AppModal
        opened={!!opened}
        onClose={() => setOpened(null)}
        title={opened?.title}
        size="lg"
      >
        {opened && (
          <img src={opened.url} alt={opened.title} className="w-full rounded" />
        )}
      </AppModal>
    </>
  );
}

// ── Audit trail ───────────────────────────────────────────────────────────────
function AuditTrailList({ report }: { report: RouteReport }) {
  const maxH = Math.min(report.auditTrail.length * 28 + 16, 280);
  return (
    <div className="p-4 rounded overflow-auto" style={{ border: '1px solid var(--border)', background: 'var(--surface)', maxHeight: maxH }}>
      <div className="flex flex-col gap-1">
        {report.auditTrail.map((a, i) => (
          <div key={i} className="flex items-center gap-3 flex-nowrap text-xs">
            <span className="font-mono shrink-0" style={{ minWidth: 110, color: 'var(--text-muted)' }}>{fmtDateTime(a.at)}</span>
            <span className="font-medium" style={{ color: 'var(--text-primary)' }}>{a.action}</span>
            {a.detail && <span className="truncate flex-1" style={{ color: 'var(--text-muted)' }}>{a.detail}</span>}
            {a.actor && (
              <span className="flex items-center gap-0.5 shrink-0" style={{ color: 'var(--text-muted)' }}>
                <IconArrowRight size={10} />{a.actor}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Section title ─────────────────────────────────────────────────────────────
function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-xs font-bold uppercase mb-2" style={{ color: 'var(--text-muted)', letterSpacing: '0.08em' }}>
      {children}
    </p>
  );
}

