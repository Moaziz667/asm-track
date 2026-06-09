import { useEffect, useMemo, useState } from 'react';
import {
  IconPackageImport, IconRoute, IconTruckLoading, IconTruckDelivery,
  IconArrowsExchange, IconCircleCheck, IconCircleX, IconAlertTriangle,
  IconClockExclamation, IconCircle, IconBan, IconPackages, IconPointFilled,
} from '@tabler/icons-react';
import { api } from '@/lib/api';
import { useT } from '@/lib/LocaleContext';

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
  };
  timeline: { at?: string; status?: string; eventKey?: string; params?: string }[];
  context?: {
    failureCode?: string; failReason?: string;
    backorderDirection?: 'parent' | 'child'; backorderDeliveryId?: string; backorderBlNumber?: string;
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
}

export default function SlaTimeline({ deliveryId, variant = 'detailed' }: SlaTimelineProps) {
  const t = useT() as any;
  const c = t.slaTimeline ?? {};
  const [data, setData] = useState<SlaTimelineData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
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
  const eventLabel = (ev: { eventKey?: string; status?: string }) =>
    (ev.eventKey && c.event?.[ev.eventKey]) || (ev.status && c.event?.[ev.status]) ||
    ev.eventKey || ev.status || '';

  // Furthest phase the journey has reached, to mark nodes done/current/pending.
  const reachedIndex = useMemo(() => {
    const cur = data?.current?.phase;
    if (cur && TERMINALS.includes(cur)) return FLOW.length; // all flow phases passed
    return cur ? FLOW.indexOf(cur) : 0;
  }, [data]);

  if (loading) return <div style={{ padding: 12, color: 'var(--text-muted)', fontSize: 12 }}>…</div>;
  if (!data || !data.current) return null;

  const cur = data.current;
  const terminal = TERMINALS.includes(cur.phase);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* Headline: current phase + health + reason */}
      <Headline
        phaseLabel={phaseLabel(cur.phase)}
        healthLabel={healthLabel(cur.health)}
        tone={terminal ? terminalTone(cur.phase, cur.health) : healthTone(cur.health)}
        phase={cur.phase}
        reason={reasonText(cur.reasonKey, cur.reasonParams)}
        attributable={cur.attributableToDriver}
        attributionCopy={c.driverAttributed}
      />

      {/* Phase stepper */}
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 0, overflowX: 'auto', paddingBottom: 4 }}>
        {FLOW.map((p, i) => {
          const state = cur.phase === p ? 'current' : i < reachedIndex ? 'done' : 'pending';
          const tone = state === 'current' ? healthTone(cur.health) : state === 'done' ? TONE.done : TONE.pending;
          return (
            <StepNode
              key={p}
              icon={PHASE_ICON[p]}
              label={phaseLabel(p)}
              sub={state === 'current' && cur.dueAt ? `${c.due ?? 'Due'} ${fmtTime(cur.dueAt)}` : ''}
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
            sub={cur.health === 'LATE' && cur.lateMinutes
              ? (c.lateBy ?? 'Late {n}m').replace('{n}', String(cur.lateMinutes))
              : cur.health === 'MET' ? (c.onTime ?? 'On time') : ''}
            tone={terminalTone(cur.phase, cur.health)}
            pulse={false}
            connector={false}
            connectorDone={false}
          />
        )}
      </div>

      {variant === 'detailed' && (
        <DetailedSection data={data} c={c} eventLabel={eventLabel} />
      )}
    </div>
  );
}

function terminalTone(phase: Phase, health?: Health) {
  if (phase === 'DELIVERED') return health === 'LATE' ? TONE.risk : TONE.done;
  if (phase === 'PARTIAL') return TONE.risk;
  if (phase === 'FAILED') return TONE.breach;
  if (phase === 'CANCELLED') return TONE.pending;
  return healthTone(health);
}

function Headline({ phaseLabel, healthLabel, tone, phase, reason, attributable, attributionCopy }: {
  phaseLabel: string; healthLabel: string; tone: typeof TONE.ok; phase: Phase;
  reason: string; attributable?: boolean; attributionCopy?: string;
}) {
  const Icon = PHASE_ICON[phase] ?? IconCircle;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px',
      background: tone.bg, border: `1px solid ${tone.dot}33`, borderRadius: 10,
    }}>
      <Icon size={18} color={tone.fg} stroke={1.8} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: tone.fg }}>{phaseLabel}</span>
          <span style={{
            fontSize: 10.5, fontWeight: 600, color: tone.fg, background: '#fff',
            border: `1px solid ${tone.dot}55`, borderRadius: 99, padding: '1px 7px', textTransform: 'uppercase', letterSpacing: 0.3,
          }}>{healthLabel}</span>
        </div>
        {reason && <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
          {reason}{attributable && attributionCopy ? ` · ${attributionCopy}` : ''}
        </span>}
      </div>
    </div>
  );
}

function StepNode({ icon: Icon, label, sub, tone, pulse, connector, connectorDone }: {
  icon: typeof IconCircle; label: string; sub: string; tone: typeof TONE.ok;
  pulse: boolean; connector: boolean; connectorDone: boolean;
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', minWidth: 96 }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, width: 96 }}>
        <div style={{
          width: 30, height: 30, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
          background: tone.bg, border: `1.5px solid ${tone.dot}`,
          animation: pulse ? 'asm-pulse 2s infinite' : undefined,
        }}>
          <Icon size={16} color={tone.fg} stroke={1.9} />
        </div>
        <span style={{ fontSize: 11, fontWeight: 600, color: tone.fg, textAlign: 'center', lineHeight: 1.2 }}>{label}</span>
        {sub && <span style={{ fontSize: 10, color: 'var(--text-muted)', textAlign: 'center', lineHeight: 1.2 }}>{sub}</span>}
      </div>
      {connector && (
        <div style={{
          flex: '0 0 18px', height: 2, marginTop: 14,
          background: connectorDone ? TONE.done.dot : 'var(--border, #e5e7eb)',
        }} />
      )}
    </div>
  );
}

function DetailedSection({ data, c, eventLabel }: {
  data: SlaTimelineData; c: any; eventLabel: (e: { eventKey?: string; status?: string }) => string;
}) {
  const ctx = data.context;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {/* Driver context: failure motif + backorder link */}
      {(ctx?.failureCode || ctx?.backorderDirection) && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {ctx?.failureCode && (
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

      {/* Raw event log */}
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {(!data.timeline || data.timeline.length === 0) && (
          <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{c.noTimeline ?? '—'}</span>
        )}
        {data.timeline?.map((ev, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '5px 0' }}>
            <IconPointFilled size={14} color={TONE.pending.dot} />
            <span style={{ fontSize: 12, color: 'var(--text-strong, #1f2937)', flex: 1 }}>{eventLabel(ev)}</span>
            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{fmtTime(ev.at)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
