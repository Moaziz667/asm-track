import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import {
  IconPackageImport, IconRoute, IconTruckLoading, IconTruckDelivery,
  IconArrowsExchange, IconCircleCheck, IconCircleX, IconAlertTriangle,
  IconClockExclamation, IconCircle, IconBan, IconPackages,
} from '@tabler/icons-react';
import { api } from '@/lib/api';
import { useT } from '@/lib/LocaleContext';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';

/**
 * Unified SLA timeline — the single enterprise view of a delivery's journey.
 * Reads the backend source of truth (GET /deliveries/{id}/sla-timeline): current phase + health
 * drive the headline; the phase stepper + per-phase event log replace the old emoji OperationalTimeline.
 * No emojis — Tabler SVG icons throughout. Two zoom levels via `variant`.
 */

type Health = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET' | 'LATE' | 'NONE';
type Phase = 'PLANNING' | 'ASSIGNMENT' | 'DEPARTURE' | 'DELIVERY' | 'HANDOFF'
  | 'DELIVERED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

interface SlaTimelineData {
  current?: {
    phase: Phase; health: Health; dueAt?: string; lateMinutes?: number;
    attributableToDriver?: boolean; reasonKey?: string; reasonParams?: Record<string, string>;
    phaseHealth?: Record<string, string>;
  };
  timeline: { at?: string; status?: string; eventKey?: string; params?: string; actor?: string; actorRole?: string }[];
  context?: {
    failureCode?: string; failReason?: string;
    backorderDirection?: 'parent' | 'child'; backorderDeliveryId?: string; backorderBlNumber?: string;
    podComment?: string;
    itemOutcomes?: { name?: string; outcome?: string; reason?: string; comment?: string; quantity?: number; quantityDone?: number }[];
  };
}

const FLOW: Phase[] = ['PLANNING', 'ASSIGNMENT', 'DEPARTURE', 'DELIVERY'];
const TERMINALS: Phase[] = ['DELIVERED', 'PARTIAL', 'FAILED', 'CANCELLED'];

const PHASE_ICON: Record<string, typeof IconCircle> = {
  PLANNING: IconPackageImport, ASSIGNMENT: IconRoute, DEPARTURE: IconTruckLoading,
  DELIVERY: IconTruckDelivery, HANDOFF: IconArrowsExchange,
  DELIVERED: IconCircleCheck, PARTIAL: IconPackages, FAILED: IconCircleX, CANCELLED: IconBan,
};

// Linear-style muted tones, consistent with StatusBadge.
const TONE = {
  ok:      { fg: '#2D8A5E', bg: 'rgba(76,175,130,0.10)', dot: '#4CAF82' },
  risk:    { fg: '#B05A18', bg: 'rgba(212,119,44,0.10)',  dot: '#D4772C' },
  breach:  { fg: '#A52B24', bg: 'rgba(199,55,47,0.10)',   dot: '#C7372F' },
  done:    { fg: '#2D8A5E', bg: 'rgba(76,175,130,0.10)',  dot: '#4CAF82' },
  pending: { fg: '#6B7280', bg: 'rgba(138,143,152,0.08)', dot: '#8A8F98' },
  neutral: { fg: '#4C56B8', bg: 'rgba(94,106,210,0.09)',  dot: '#5E6AD2' },
  // Partial outcome — its own identity (matches the PARTIAL StatusBadge), NOT the amber severity ramp:
  // SLA health lives on the health badge/tooltip, so amber here only collided with delivered-late.
  partial: { fg: '#6055A8', bg: 'rgba(123,111,204,0.10)', dot: '#7B6FCC' },
};

function healthTone(h?: Health) {
  switch (h) {
    case 'AT_RISK': case 'LATE': return TONE.risk;
    case 'BREACHED': return TONE.breach;
    case 'MET': case 'ON_TRACK': return TONE.ok;
    default: return TONE.pending;
  }
}

function fmtTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export interface SlaTimelineProps {
  deliveryId: string;
  variant?: 'compact' | 'detailed';
  /** Suppress the driver's POD note here when the host page renders it once elsewhere
   *  (dedup — see DeliveryDetailsPage's "Driver note" callout). */
  hidePodComment?: boolean;
  /** Suppress the per-item outcome list when the host already shows it (the delivery-details Articles
   *  table owns per-line status). The dispatch desk keeps it — it has no items table. */
  hideItemOutcomes?: boolean;
  /** Suppress the delivery-level failure motif banner ("Refusée · …") when the host surfaces it
   *  elsewhere — the delivery-details Articles table shows the failure motif per line. The dispatch
   *  desk keeps it (no table). */
  hideFailureContext?: boolean;
}

export default function SlaTimeline({ deliveryId, variant = 'detailed', hidePodComment = false, hideItemOutcomes = false, hideFailureContext = false }: SlaTimelineProps) {
  const t = useT() as any;
  const c = t.slaTimeline ?? {};
  const [data, setData] = useState<SlaTimelineData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    if (!deliveryId) { setLoading(false); setData(null); return; }
    setLoading(true);
    api.get(`/api/admin/deliveries/${deliveryId}/sla-timeline`)
      .then((r) => { if (alive) setData(r.data); })
      .catch(() => { if (alive) setData(null); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [deliveryId]);

  const phaseLabel = (p?: string) => (p && c.phase?.[p]) || p || '';
  const healthLabel = (h?: string) => (h && c.health?.[h]) || h || '';
  const reasonText = (key?: string, params?: Record<string, string>) => {
    if (!key) return '';
    const raw = c.reason?.[key];
    if (!raw) return key.split('.').pop() || key;
    return raw.replace(/\{(\w+)\}/g, (_: string, k: string) => params?.[k] ?? '');
  };
  // Rich, human-readable audit line: localized template + interpolated params (driver, route, reason…).
  const eventLabel = (ev: { eventKey?: string; status?: string; params?: string }) => {
    const tpl = (ev.eventKey && c.event?.[ev.eventKey]) || (ev.status && c.event?.[ev.status])
      || ev.eventKey || ev.status || '';
    let params: Record<string, string> = {};
    try { params = ev.params ? JSON.parse(ev.params) : {}; } catch { /* keep template as-is */ }
    const filled = String(tpl).replace(/\{(\w+)\}/g, (_: string, k: string) => params[k] ?? '');
    // Drop a dangling "· " separator left when an optional param (e.g. reason) is empty.
    return filled.replace(/\s*·\s*$/, '').trim();
  };
  // "by {driver name}" for driver actions, otherwise the role label (Admin / System / Client).
  const actorLabel = (ev: { actor?: string; actorRole?: string }) =>
    ev.actor || (ev.actorRole ? (c.actor?.[ev.actorRole] ?? '') : '');

  // Furthest phase the journey has reached, to mark nodes done/current/pending.
  const reachedIndex = useMemo(() => {
    const cur = data?.current?.phase;
    if (cur && TERMINALS.includes(cur)) return FLOW.length; // all flow phases passed
    return cur ? FLOW.indexOf(cur) : 0;
  }, [data]);

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', color: 'var(--text-muted)', fontSize: 12 }}>
        <span style={{ width: 14, height: 14, borderRadius: '50%', border: '2px solid var(--border,#e5e7eb)', borderTopColor: 'var(--text-muted)', animation: 'asm-pulse 1s linear infinite' }} />
        {c.loading ?? 'Loading SLA…'}
      </div>
    );
  }
  // No current state yet (e.g. brand-new delivery) — still show the event log if we have one.
  if (!data || !data.current) {
    if (variant === 'detailed' && data?.timeline?.length) {
      return <DetailedSection data={data} c={c} eventLabel={eventLabel} actorLabel={actorLabel} hidePodComment={hidePodComment} />;
    }
    return <div style={{ padding: '8px 12px', color: 'var(--text-muted)', fontSize: 12 }}>{c.noTimeline ?? '—'}</div>;
  }

  const cur = data.current;
  const terminal = TERMINALS.includes(cur.phase);

  const attribution = cur.attributableToDriver && c.driverAttributed ? ` · ${c.driverAttributed}` : '';
  const join = (...parts: (string | false | undefined)[]) => parts.filter(Boolean).join(' · ');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* Phase stepper — the node COLOUR is the at-a-glance health; hover a node for the detail
          (reason + due / outcome), same tooltip pattern as the item table. No redundant headline card:
          the phase + health already read from the panel badges, and the "reason" for a terminal state
          just restates them — so it lives in the popup where it stays useful for live phases. */}
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 0, paddingBottom: 4, width: '100%' }}>
        <TooltipProvider>
        {FLOW.map((p, i) => {
          const state = cur.phase === p ? 'current' : i < reachedIndex ? 'done' : 'pending';
          // A passed ('done') phase keeps the worst health it actually reached, so a late
          // departure/delivery stays amber/red instead of a flat green. Falls back to green when
          // no per-phase history was recorded (older deliveries).
          const pastHealth = cur.phaseHealth?.[p] as Health | undefined;
          const doneTone = pastHealth && (pastHealth === 'BREACHED' || pastHealth === 'LATE' || pastHealth === 'AT_RISK')
            ? healthTone(pastHealth) : TONE.done;
          const tone = state === 'current' ? healthTone(cur.health) : state === 'done' ? doneTone : TONE.pending;
          const tip = state === 'current'
            ? join(reasonText(cur.reasonKey, cur.reasonParams), cur.dueAt && `${c.due ?? 'Due'} ${fmtTime(cur.dueAt)}`) + attribution
            : state === 'done' && pastHealth ? healthLabel(pastHealth)
            : '';
          return (
            <StepNode
              key={p}
              icon={PHASE_ICON[p]}
              label={phaseLabel(p)}
              tooltip={tip.trim() || undefined}
              tone={tone}
              pulse={state === 'current' && (cur.health === 'AT_RISK' || cur.health === 'BREACHED')}
              connector={i < FLOW.length - 1}
              connectorDone={i < reachedIndex}
            />
          );
        })}
        {terminal && (
          <StepNode
            icon={PHASE_ICON[cur.phase]}
            label={phaseLabel(cur.phase)}
            tooltip={(join(
              reasonText(cur.reasonKey, cur.reasonParams),
              cur.health === 'LATE' && cur.lateMinutes ? (c.lateBy ?? 'Late {n}m').replace('{n}', String(cur.lateMinutes))
                : cur.health === 'MET' ? (c.onTime ?? 'On time') : '',
            ) + attribution).trim() || undefined}
            tone={terminalTone(cur.phase, cur.health)}
            pulse={false}
            connector={false}
            connectorDone={false}
          />
        )}
        </TooltipProvider>
      </div>

      {variant === 'detailed' && (
        <DetailedSection data={data} c={c} eventLabel={eventLabel} actorLabel={actorLabel} hidePodComment={hidePodComment} hideItemOutcomes={hideItemOutcomes} hideFailureContext={hideFailureContext} />
      )}
    </div>
  );
}

function terminalTone(phase: Phase, health?: Health) {
  if (phase === 'DELIVERED') return health === 'LATE' ? TONE.risk : TONE.done;
  if (phase === 'PARTIAL') return TONE.partial;
  if (phase === 'FAILED') return TONE.breach;
  if (phase === 'CANCELLED') return TONE.pending;
  return healthTone(health);
}

function StepNode({ icon: Icon, label, tooltip, tone, pulse, connector, connectorDone }: {
  icon: typeof IconCircle; label: string; tooltip?: string; tone: typeof TONE.ok;
  pulse: boolean; connector: boolean; connectorDone: boolean;
}) {
  const colStyle: CSSProperties = { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, flex: 1, minWidth: 0 };
  const circle = (
    <div style={{
      width: 30, height: 30, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: tone.bg, border: `1.5px solid ${tone.dot}`, flexShrink: 0,
      animation: pulse ? 'asm-pulse 2s infinite' : undefined,
    }}>
      <Icon size={16} color={tone.fg} stroke={1.9} />
    </div>
  );
  const label_ = <span style={{ fontSize: 11, fontWeight: 600, color: tone.fg, textAlign: 'center', lineHeight: 1.2 }}>{label}</span>;
  return (
    // flex:1 so nodes share the row width evenly (no fixed 96px → no overflow/scroll).
    <div style={{ display: 'flex', alignItems: 'flex-start', flex: 1, minWidth: 0 }}>
      {tooltip ? (
        <Tooltip>
          <TooltipTrigger render={<div style={{ ...colStyle, cursor: 'help' }} aria-label={tooltip} />}>
            {circle}{label_}
          </TooltipTrigger>
          <TooltipContent>{tooltip}</TooltipContent>
        </Tooltip>
      ) : (
        <div style={colStyle}>{circle}{label_}</div>
      )}
      {connector && (
        <div style={{
          flex: '0 0 12px', height: 2, marginTop: 14,
          background: connectorDone ? TONE.done.dot : 'var(--border, #e5e7eb)',
        }} />
      )}
    </div>
  );
}

function DetailedSection({ data, c, eventLabel, actorLabel, hidePodComment = false, hideItemOutcomes = false, hideFailureContext = false }: {
  data: SlaTimelineData; c: any;
  eventLabel: (e: { eventKey?: string; status?: string; params?: string }) => string;
  actorLabel: (e: { actor?: string; actorRole?: string }) => string;
  hidePodComment?: boolean;
  hideItemOutcomes?: boolean;
  hideFailureContext?: boolean;
}) {
  const by = c.by ?? 'by';
  const ctx = data.context;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {/* Driver context: failure motif + backorder link */}
      {((!hideFailureContext && ctx?.failureCode) || ctx?.backorderDirection) && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {!hideFailureContext && ctx?.failureCode && (
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: TONE.breach.fg,
              background: TONE.breach.bg, border: `1px solid ${TONE.breach.dot}33`, borderRadius: 8, padding: '4px 9px',
            }}>
              <IconAlertTriangle size={13} stroke={1.8} />
              {(c.motif?.[ctx.failureCode]) || ctx.failureCode}{ctx.failReason ? ` · ${ctx.failReason}` : ''}
            </span>
          )}
          {ctx?.backorderDirection && (
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: TONE.neutral.fg,
              background: TONE.neutral.bg, border: `1px solid ${TONE.neutral.dot}33`, borderRadius: 8, padding: '4px 9px',
            }}>
              <IconPackages size={13} stroke={1.8} />
              {ctx.backorderDirection === 'parent' ? (c.backorderParent ?? 'Backorder') : (c.backorderChild ?? 'Backorder of original')}
              {ctx.backorderBlNumber ? ` · ${ctx.backorderBlNumber}` : ''}
            </span>
          )}
        </div>
      )}

      {/* Driver's proof-of-delivery note */}
      {!hidePodComment && ctx?.podComment && (
        <div style={{
          fontSize: 11.5, color: 'var(--text-strong, #1f2937)', background: 'var(--app-bg)',
          border: '1px solid var(--border)', borderRadius: 8, padding: '6px 10px',
        }}>
          <span style={{ color: 'var(--text-muted)' }}>{c.podComment ?? 'Driver note'}: </span>
          {ctx.podComment}
        </div>
      )}

      {/* Per-item outcomes: only items not delivered cleanly (refused / damaged) */}
      {!hideItemOutcomes && ctx?.itemOutcomes && ctx.itemOutcomes.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)' }}>
            {c.itemsNotDelivered ?? 'Items not delivered'}
          </span>
          {ctx.itemOutcomes.map((it, i) => (
            <span key={i} style={{
              display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: TONE.breach.fg,
              background: TONE.breach.bg, border: `1px solid ${TONE.breach.dot}33`, borderRadius: 8, padding: '4px 9px', width: 'fit-content',
            }}>
              <IconPackages size={13} stroke={1.8} />
              {it.name ? `${it.name} · ` : ''}
              {it.outcome ? ((c.motif?.[it.outcome]) || it.outcome) : ''}
              {it.reason ? ` · ${(c.motif?.[it.reason]) || it.reason}` : ''}
              {it.quantityDone != null && it.quantity != null ? ` · ${it.quantityDone}/${it.quantity}` : ''}
              {it.comment ? ` · ${it.comment}` : ''}
            </span>
          ))}
        </div>
      )}

      {/* Raw event log */}
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {(!data.timeline || data.timeline.length === 0) && (
          <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{c.noTimeline ?? '—'}</span>
        )}
        {data.timeline?.map((ev, i) => {
          const who = actorLabel(ev);
          return (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0' }}>
              {ev.status && <span style={{ flexShrink: 0 }}><StatusBadge status={ev.status} size="sm" /></span>}
              <span style={{ flex: 1, lineHeight: 1.3 }}>
                <span style={{ fontSize: 12, color: 'var(--text-strong, #1f2937)' }}>{eventLabel(ev)}</span>
                {who && <span style={{ fontSize: 11, color: 'var(--text-muted)' }}> · {by} {who}</span>}
              </span>
              <span style={{ fontSize: 11, color: 'var(--text-muted)', flexShrink: 0 }}>{fmtTime(ev.at)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
