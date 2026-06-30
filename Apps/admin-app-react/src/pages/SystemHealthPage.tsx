import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import {
  IconActivityHeartbeat, IconReload, IconAlertTriangle, IconCircleCheck,
  IconChevronDown, IconPlugConnected, IconInbox, IconDatabase, IconWifiOff,
  IconInfoCircle, IconTimeline,
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { AppModal } from '@/components/overlays/AppModal';
import { deriveHealthSummary, computeStale, ageParts, describeKey, type AgeParts, type DescriptionKey } from '@/lib/system-health';

interface CircuitBreaker {
  name: string; state: string; reachable?: boolean; shallow?: boolean;
  failureRate: number; bufferedCalls: number; failedCalls: number; notPermittedCalls: number;
}
interface ErpSyncFailure {
  orderId: string; blNumber: string | null; erpRef: string | null;
  lastSyncOp: string | null; lastSyncError: string | null;
  retryCount: number; stuckSince: string | null;
}
interface ErpSyncInfo {
  failed: number; inProgress: number;
  oldestFailedAgeMinutes: number | null; failures: ErpSyncFailure[];
}
interface HealthPayload {
  generatedAt?: string;
  dlq: Record<string, number>;
  circuitBreakers: CircuitBreaker[];
  db?: { reachable: boolean };
  erpSync?: ErpSyncInfo;
  erp: { reachable: boolean; pendingSyncFailures: number };
}
type ComponentKey = 'drivers' | 'erp' | 'db' | 'queues';
interface HistoryPoint {
  t: number;
  erpFailed: number;
  maxFailureRate: number;
  dlqTotal: number;
  breakersOpen: number;
  components: Record<ComponentKey, Tone>;
}

// ── Tone → design-system tokens (no hardcoded hex; matches the rest of the app) ──
type Tone = 'ok' | 'warn' | 'down' | 'idle';
const TONE_VAR: Record<Tone, string> = {
  ok: 'var(--success)', warn: 'var(--warning)', down: 'var(--danger)', idle: 'var(--text-soft)',
};
const TONE_BG: Record<Tone, string> = {
  ok: 'var(--success-bg)', warn: 'var(--warning-bg)', down: 'var(--danger-bg)', idle: 'var(--hover-bg)',
};
const STATE_TONE: Record<string, Tone> = {
  CLOSED: 'ok', OPEN: 'down', HALF_OPEN: 'warn', DISABLED: 'idle', FORCED_OPEN: 'down',
};
const toneRank = (t: Tone) => (t === 'down' ? 3 : t === 'warn' ? 2 : t === 'ok' ? 1 : 0);
const worstTone = (tones: Tone[]): Tone =>
  tones.reduce<Tone>((acc, t) => (toneRank(t) > toneRank(acc) ? t : acc), 'ok');

const RANGES: { min: number; label: string }[] = [
  { min: 10, label: '10 min' }, { min: 30, label: '30 min' }, { min: 60, label: '1 h' },
];

export default function SystemHealthPage() {
  const t = useT();
  const [data, setData] = useState<HealthPayload | null>(null);
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [rangeMin, setRangeMin] = useState(60);
  const [refreshing, setRefreshing] = useState(false);
  const [replaying, setReplaying] = useState<string | null>(null);
  const [resyncing, setResyncing] = useState<string | null>(null);
  const [showTech, setShowTech] = useState(false);
  const [connected, setConnected] = useState(true);
  const [infoKey, setInfoKey] = useState<DescriptionKey | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [, setNowTick] = useState(0);

  const statusFor = (state: string) => {
    const s = t.systemHealthPage.states[state as keyof typeof t.systemHealthPage.states];
    return { label: s?.label ?? state, hint: s?.hint ?? '', tone: STATE_TONE[state] ?? 'idle' };
  };

  const getFriendlyService = (name: string): string => {
    const n = name.toLowerCase();
    const svc = t.systemHealthPage.services;
    if (n.includes('driver')) return svc.drivers;
    if (n.includes('erp') || n.includes('adapter')) return svc.erp;
    if (n.includes('auth') || n.includes('keycloak')) return svc.auth;
    if (n.includes('route') || n.includes('osrm') || n.includes('geocode')) return svc.routes;
    if (n.includes('notif') || n.includes('fcm')) return svc.notifications;
    if (n.includes('app') || n.includes('backend')) return svc.main;
    return name.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/client|feign/gi, '').trim() || name;
  };

  const getFriendlyQueue = (q: string): string => {
    const n = q.toLowerCase();
    const qs = t.systemHealthPage.queues;
    if (n.includes('erp') || n.includes('sync')) return qs.erp;
    if (n.includes('audit')) return qs.audit;
    if (n.includes('location')) return qs.location;
    if (n.includes('stat')) return qs.stat;
    if (n.includes('driver')) return qs.drivers;
    return q;
  };

  const opLabel = (op: string | null): string => {
    if (!op) return t.systemHealthPage.resync.opLabels.SYNC;
    const labels = t.systemHealthPage.resync.opLabels;
    return labels[op as keyof typeof labels] ?? op;
  };

  const fetchHealth = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    try {
      const [snapRes, histRes] = await Promise.all([
        api.get<HealthPayload>('/api/admin/system/health'),
        api.get<HistoryPoint[]>('/api/admin/system/health/history').catch(() => ({ data: [] as HistoryPoint[] })),
      ]);
      setData(snapRes.data);
      setHistory(Array.isArray(histRes.data) ? histRes.data : []);
      setConnected(true);
      setLastUpdated(Date.now());
    } catch {
      setConnected(false);
      if (!silent) showErrorToast(null, t.systemHealthPage.toastLoadError);
    } finally {
      setRefreshing(false);
    }
  }, [t]);

  useEffect(() => {
    void fetchHealth();
    const id = setInterval(() => void fetchHealth(true), 10_000);
    const tick = setInterval(() => setNowTick(n => n + 1), 5_000);
    return () => { clearInterval(id); clearInterval(tick); };
  }, [fetchHealth]);

  const replay = async (queue: string) => {
    setReplaying(queue);
    try {
      const res = await api.post(`/api/admin/dlq/${encodeURIComponent(queue)}/replay`, null, { params: { max: 100 } });
      showSuccessToast(t.systemHealthPage.toastReplaySuccess.replace('{count}', String(res.data?.replayed ?? 0)));
      await fetchHealth(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.systemHealthPage.toastReplayError);
    } finally {
      setReplaying(null);
    }
  };

  const resync = async (orderId: string, blNumber: string | null) => {
    setResyncing(orderId);
    try {
      const res = await api.post(`/api/admin/system/erp-sync/${orderId}/resync`);
      if (res.data?.queued) {
        showSuccessToast(t.systemHealthPage.resync.toastQueued.replace('{bl}', blNumber ?? res.data?.blNumber ?? ''));
      } else {
        showErrorToast(res.data?.reason, t.systemHealthPage.resync.toastNotQueued);
      }
      await fetchHealth(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.systemHealthPage.resync.toastError);
    } finally {
      setResyncing(null);
    }
  };

  const resyncAll = async () => {
    setResyncing('__all__');
    try {
      const res = await api.post('/api/admin/system/erp-sync/resync-all');
      showSuccessToast(t.systemHealthPage.resync.toastAllQueued.replace('{count}', String(res.data?.queued ?? 0)));
      await fetchHealth(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.systemHealthPage.resync.toastError);
    } finally {
      setResyncing(null);
    }
  };

  // ── Derived ────────────────────────────────────────────────────────────────
  const breakers = data?.circuitBreakers ?? [];
  const dlqEntries = Object.entries(data?.dlq ?? {});
  const stuckQueues = dlqEntries.filter(([, d]) => Number(d) > 0);
  const dbReachable = data?.db?.reachable ?? true;
  const erpSync = data?.erpSync;
  const failures = erpSync?.failures ?? [];
  const { serviceCount, okServices, totalStuck, erpFailed, problems, allGood, recoveringCount } =
    deriveHealthSummary(data, getFriendlyService);

  const openBreakers = breakers.filter(b => b.state === 'OPEN' || b.state === 'FORCED_OPEN' || b.reachable === false).length;
  const maxFailureRate = breakers.reduce((m, b) => (b.bufferedCalls > 0 && b.failureRate >= 0 ? Math.max(m, b.failureRate) : m), 0);
  const actionCount = failures.length + stuckQueues.length;

  const isStale = computeStale({ connected, generatedAt: data?.generatedAt, lastUpdated, now: Date.now() });
  const agoSeconds = lastUpdated ? Math.max(0, Math.round((Date.now() - lastUpdated) / 1000)) : null;

  // History window for sparklines + swimlanes.
  const now = Date.now();
  const windowPoints = history.filter(p => now - p.t <= rangeMin * 60_000);
  const series = (key: 'erpFailed' | 'maxFailureRate' | 'dlqTotal') => windowPoints.map(p => Number(p[key]) || 0);
  const latest = history[history.length - 1];

  const overall: Tone = allGood ? 'ok' : problems > 0 ? 'down' : recoveringCount > 0 ? 'warn' : 'ok';
  const overallLabel = overall === 'ok'
    ? (t.systemHealthPage.statusOperational ?? 'Opérationnel')
    : overall === 'warn'
      ? (t.systemHealthPage.recoveringTitle ?? 'Rétablissement')
      : t.systemHealthPage.pointsAttentionTitle.replace('{count}', String(problems)).replace('{plural}', problems > 1 ? 's' : '');

  const LANES: { key: ComponentKey; label: string }[] = [
    { key: 'drivers', label: t.systemHealthPage.services.drivers },
    { key: 'erp', label: t.systemHealthPage.services.erp },
    { key: 'db', label: t.systemHealthPage.services.database },
    { key: 'queues', label: t.systemHealthPage.queuesGroupLabel ?? 'Files de messages' },
  ];

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Command bar */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-3.5 flex items-center gap-3 flex-wrap max-w-[1400px] mx-auto">
          <IconActivityHeartbeat size={18} className="text-[var(--brand)]" />
          <h1 className="text-base font-bold text-[var(--text-primary)]">{t.systemHealthPage.title}</h1>
          <StatusChip tone={overall} label={overallLabel} />
          <div className="ms-auto flex items-center gap-3">
            <RangeSelector value={rangeMin} onChange={setRangeMin} />
            {agoSeconds != null && !isStale && (
              <span className="hidden sm:inline-flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
                <span className="is-live inline-block w-1.5 h-1.5 rounded-full" style={{ background: 'var(--success)' }} />
                {t.systemHealthPage.updatedAgo.replace('{n}', String(agoSeconds))}
              </span>
            )}
            <RefreshButton refreshing={refreshing} onClick={() => fetchHealth()} />
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1400px] mx-auto p-6 flex flex-col gap-5">

          {isStale && (
            <Banner tone="warn" icon={IconWifiOff} title={t.systemHealthPage.disconnectedTitle} sub={t.systemHealthPage.disconnectedSub} />
          )}

          {/* ── À traiter (action stream) ── */}
          {actionCount > 0 ? (
            <section className="rounded-[var(--radius-xl)] overflow-hidden border" style={{ borderColor: 'var(--danger)' }}>
              <header className="flex items-center justify-between gap-2 px-4 py-2.5" style={{ background: 'var(--danger-bg)' }}>
                <span className="inline-flex items-center gap-2 text-sm font-bold" style={{ color: 'var(--danger)' }}>
                  <IconAlertTriangle size={15} />
                  {(t.systemHealthPage.actionRequiredTitle ?? 'À traiter')} · {actionCount}
                </span>
                {failures.length > 1 && (
                  <ActionButton onClick={resyncAll} busy={resyncing === '__all__'} disabled={resyncing != null}
                    label={t.systemHealthPage.resync.resyncAllButton} />
                )}
              </header>
              <div className="flex flex-col">
                {failures.map(f => (
                  <ActionRow
                    key={f.orderId}
                    icon={IconPlugConnected}
                    title={`${t.systemHealthPage.erpSyncTitle} — ${f.blNumber || f.erpRef || f.orderId.slice(0, 8)}`}
                    meta={`${opLabel(f.lastSyncOp)} · ${t.systemHealthPage.stuckFor.replace('{duration}', formatAge(f.stuckSince, t))}${f.retryCount > 0 ? ` · ${t.systemHealthPage.attemptsLabel.replace('{count}', String(f.retryCount))}` : ''}`}
                    detail={f.lastSyncError ?? undefined}
                    action={<ActionButton onClick={() => resync(f.orderId, f.blNumber)} busy={resyncing === f.orderId} disabled={resyncing != null} label={resyncing === f.orderId ? t.systemHealthPage.resync.resyncingButton : t.systemHealthPage.resync.resyncButton} />}
                  />
                ))}
                {stuckQueues.map(([queue, depth]) => (
                  <ActionRow
                    key={queue}
                    icon={IconInbox}
                    title={getFriendlyQueue(queue)}
                    meta={t.systemHealthPage.replaysAwaiting.replace('{count}', String(depth))}
                    action={<ActionButton onClick={() => replay(queue)} busy={replaying === queue} disabled={replaying === queue} label={replaying === queue ? t.systemHealthPage.replayingButton : t.systemHealthPage.replayButton} />}
                  />
                ))}
              </div>
            </section>
          ) : (
            <div className="rounded-[var(--radius-xl)] px-4 py-3 flex items-center gap-2.5 border"
              style={{ background: 'var(--success-bg)', borderColor: 'color-mix(in srgb, var(--success) 30%, transparent)' }}>
              <IconCircleCheck size={18} style={{ color: 'var(--success)' }} />
              <span className="text-sm font-[600]" style={{ color: 'var(--text-primary)' }}>{t.systemHealthPage.allGoodTitle}</span>
              <span className="text-xs text-[var(--text-muted)]">{t.systemHealthPage.allGoodSub}</span>
            </div>
          )}

          {/* ── Signals strip (golden signals + sparklines) ── */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <StatTile label={t.systemHealthPage.erpSyncTitle} value={String(erpFailed)} tone={erpFailed > 0 ? 'down' : 'ok'} spark={series('erpFailed')} />
            <StatTile label={t.systemHealthPage.thFailureRate} value={`${Math.round(maxFailureRate)}%`} tone={maxFailureRate > 50 ? 'down' : maxFailureRate > 0 ? 'warn' : 'ok'} spark={series('maxFailureRate')} />
            <StatTile label={t.systemHealthPage.cardReplayTitle} value={String(totalStuck)} tone={totalStuck > 0 ? 'down' : 'ok'} spark={series('dlqTotal')} />
            <StatTile label={t.systemHealthPage.cardServicesTitle}
              value={serviceCount === 0 ? '—' : `${okServices}/${serviceCount}`}
              tone={openBreakers > 0 ? 'down' : recoveringCount > 0 ? 'warn' : 'ok'}
              suffix={openBreakers > 0 ? t.systemHealthPage.cardServicesFootFailures.replace('{count}', String(openBreakers)) : undefined} />
          </div>

          {/* ── Component status timelines (swimlanes) ── */}
          <section>
            <h2 className="text-sm font-bold text-[var(--text-primary)] mb-2.5 inline-flex items-center gap-1.5">
              <IconTimeline size={15} className="text-[var(--text-muted)]" />
              {t.systemHealthPage.timelineTitle ?? 'Chronologie des composants'}
              <span className="text-xs font-[500] text-[var(--text-soft)]">· {RANGES.find(r => r.min === rangeMin)?.label}</span>
            </h2>
            <div className="rounded-[var(--radius-xl)] border border-[var(--border)] bg-[var(--surface)] divide-y divide-[var(--border)]">
              {LANES.map(lane => (
                <Swimlane
                  key={lane.key}
                  label={lane.label}
                  points={windowPoints.map(p => p.components[lane.key] ?? 'ok')}
                  current={(latest?.components?.[lane.key] as Tone) ?? 'ok'}
                  okLabel={statusFor('CLOSED').label}
                  koLabel={statusFor('OPEN').label}
                />
              ))}
            </div>
          </section>

          {/* ── Technical details ── */}
          <div>
            <button
              onClick={() => setShowTech(v => !v)}
              className="text-sm font-semibold text-[var(--text-muted)] hover:text-[var(--text-primary)] inline-flex items-center gap-1.5 transition-colors"
            >
              <IconChevronDown size={14} className={cn('transition-transform', showTech && 'rotate-180')} />
              {t.systemHealthPage.techDetailsToggle}
            </button>
            {showTech && (
              <div className="mt-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--surface)] overflow-x-auto">
                <table className="w-full text-xs min-w-[600px]">
                  <thead>
                    <tr className="text-2xs uppercase tracking-wider text-[var(--text-muted)]" style={{ background: 'var(--app-bg)' }}>
                      <th className="text-start font-bold px-4 py-2">{t.systemHealthPage.thCircuitBreaker}</th>
                      <th className="text-start font-bold px-4 py-2">{t.systemHealthPage.thState}</th>
                      <th className="text-start font-bold px-4 py-2">{t.systemHealthPage.thFailureRate}</th>
                      <th className="text-start font-bold px-4 py-2">{t.systemHealthPage.thFailedBuffered}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {breakers.map(cb => (
                      <tr key={cb.name} className="border-t border-[var(--border)]">
                        <td className="px-4 py-2 font-mono text-[var(--text-secondary)]">
                          <span className="inline-flex items-center gap-1.5">
                            <InfoButton onClick={() => setInfoKey(describeKey(cb.name, false))} label={t.systemHealthPage.descriptionsModalSubtitle} />
                            <span className="truncate">{cb.name}{cb.reachable === false ? ' ⚠' : ''}</span>
                          </span>
                        </td>
                        <td className="px-4 py-2 font-mono" style={{ color: TONE_VAR[statusFor(cb.state).tone] }}>{cb.state}</td>
                        <td className="px-4 py-2 tabular-nums">{cb.failureRate < 0 ? '—' : `${cb.failureRate.toFixed(0)}%`}</td>
                        <td className="px-4 py-2 tabular-nums">{cb.failedCalls} / {cb.bufferedCalls}</td>
                      </tr>
                    ))}
                    {dlqEntries.map(([q, d]) => (
                      <tr key={q} className="border-t border-[var(--border)]">
                        <td className="px-4 py-2 font-mono text-[var(--text-secondary)]" colSpan={3}>
                          <span className="inline-flex items-center gap-1.5">
                            <InfoButton onClick={() => setInfoKey(describeKey(q, true))} label={t.systemHealthPage.descriptionsModalSubtitle} />
                            <span>DLQ · {q}</span>
                          </span>
                        </td>
                        <td className="px-4 py-2 tabular-nums">{d}</td>
                      </tr>
                    ))}
                    {!dbReachable && (
                      <tr className="border-t border-[var(--border)]">
                        <td className="px-4 py-2 font-mono" colSpan={3} style={{ color: 'var(--danger)' }}>
                          <span className="inline-flex items-center gap-1.5"><IconDatabase size={13} /> {t.systemHealthPage.services.database}</span>
                        </td>
                        <td className="px-4 py-2" style={{ color: 'var(--danger)' }}>{statusFor('OPEN').label}</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>

      {infoKey && (
        <AppModal
          open
          onClose={() => setInfoKey(null)}
          subtitle={t.systemHealthPage.descriptionsModalSubtitle}
          title={t.systemHealthPage.descriptions[infoKey].title}
          size="sm"
        >
          <p className="text-base text-[var(--text-secondary)] leading-relaxed">
            {t.systemHealthPage.descriptions[infoKey].body}
          </p>
          <div className="mt-3 rounded-lg border border-[var(--border)] bg-[var(--app-bg)] px-3 py-2.5">
            <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-1">
              {t.systemHealthPage.descriptionsTipLabel}
            </p>
            <p className="text-sm text-[var(--text-secondary)] leading-relaxed">
              {t.systemHealthPage.descriptions[infoKey].tip}
            </p>
          </div>
        </AppModal>
      )}
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function StatusChip({ tone, label }: { tone: Tone; label: string }) {
  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs font-[600] px-2.5 py-1 rounded-full"
      style={{ background: TONE_BG[tone], color: TONE_VAR[tone] }}
    >
      <span className="w-1.5 h-1.5 rounded-full" style={{ background: TONE_VAR[tone] }} />
      {label}
    </span>
  );
}

function RangeSelector({ value, onChange }: { value: number; onChange: (m: number) => void }) {
  return (
    <SegmentedControl<number>
      value={value}
      onChange={onChange}
      options={RANGES.map(r => ({ value: r.min, label: r.label }))}
    />
  );
}

function StatTile({ label, value, tone, spark, suffix }: { label: string; value: string; tone: Tone; spark?: number[]; suffix?: string }) {
  return (
    <div className="rounded-[var(--radius-xl)] border border-[var(--border)] bg-[var(--surface)] px-3.5 py-3 flex flex-col gap-2">
      <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] truncate">{label}</p>
      <div className="flex items-end justify-between gap-2">
        <span className="text-2xl font-bold font-mono tabular-nums leading-none" style={{ color: tone === 'down' ? 'var(--danger)' : 'var(--text-primary)' }}>{value}</span>
        {spark && spark.length > 1 ? <Sparkline data={spark} tone={tone} /> : null}
      </div>
      {suffix && <p className="text-2xs" style={{ color: TONE_VAR[tone] }}>{suffix}</p>}
    </div>
  );
}

function Sparkline({ data, tone }: { data: number[]; tone: Tone }) {
  const w = 60, h = 22;
  const max = Math.max(1, ...data);
  const min = Math.min(0, ...data);
  const span = max - min || 1;
  const pts = data.map((v, i) => `${(i / (data.length - 1)) * w},${h - 2 - ((v - min) / span) * (h - 4)}`).join(' ');
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} fill="none" aria-hidden="true" className="shrink-0">
      <polyline points={pts} stroke={TONE_VAR[tone]} strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

const MAX_SEGMENTS = 40;

function Swimlane({ label, points, current, okLabel, koLabel }: {
  label: string; points: Tone[]; current: Tone; okLabel: string; koLabel: string;
}) {
  // Downsample to <= MAX_SEGMENTS, worst tone per bucket.
  const segs: Tone[] = [];
  if (points.length > 0) {
    const size = Math.max(1, Math.ceil(points.length / MAX_SEGMENTS));
    for (let i = 0; i < points.length; i += size) segs.push(worstTone(points.slice(i, i + size)));
  }
  const okPct = points.length ? Math.round((100 * points.filter(p => p === 'ok').length) / points.length) : 100;

  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <span className="w-36 shrink-0 text-sm font-[600] text-[var(--text-primary)] truncate">{label}</span>
      <div className="flex-1 flex gap-[2px] min-w-0">
        {segs.length === 0
          ? <div className="h-4 flex-1 rounded-[2px]" style={{ background: 'var(--hover-bg)' }} title="—" />
          : segs.map((s, i) => (
            <div key={i} className="h-4 flex-1 rounded-[2px]" style={{ background: TONE_VAR[s], opacity: s === 'ok' ? 0.55 : 1 }} />
          ))}
      </div>
      <span className="w-24 shrink-0 text-end font-mono text-2xs" style={{ color: current === 'ok' ? 'var(--text-secondary)' : TONE_VAR[current] }}>
        {okPct}% · {current === 'ok' ? okLabel : koLabel}
      </span>
    </div>
  );
}

function ActionRow({ icon: Icon, title, meta, detail, action }: {
  icon: any; title: string; meta: string; detail?: string; action: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-3 px-4 py-3 border-t border-[var(--border)] first:border-t-0 bg-[var(--surface)]">
      <span className="w-7 h-7 rounded-[var(--radius)] flex items-center justify-center shrink-0" style={{ background: 'var(--danger-bg)', color: 'var(--danger)' }}>
        <Icon size={15} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-[600] text-[var(--text-primary)] truncate">{title}</p>
        <p className="text-xs text-[var(--text-muted)]">{meta}</p>
        {detail && <p className="text-2xs mt-0.5 line-clamp-2 break-words" style={{ color: 'var(--danger)' }}>{detail}</p>}
      </div>
      <div className="shrink-0">{action}</div>
    </div>
  );
}

function ActionButton({ onClick, busy, disabled, label }: { onClick: () => void; busy: boolean; disabled?: boolean; label: string }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="text-xs font-[600] px-3 h-7 rounded-[var(--radius)] border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--brand)] hover:text-white transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
    >
      <IconReload size={13} className={busy ? 'animate-spin' : ''} />
      {label}
    </button>
  );
}

function Banner({ tone, icon: Icon, title, sub }: { tone: Tone; icon: any; title: string; sub: string }) {
  return (
    <div className="rounded-[var(--radius-xl)] px-4 py-3 flex items-center gap-3 border"
      style={{ background: TONE_BG[tone], borderColor: `color-mix(in srgb, ${TONE_VAR[tone]} 35%, transparent)` }}>
      <Icon size={18} style={{ color: TONE_VAR[tone] }} />
      <div>
        <p className="text-sm font-bold text-[var(--text-primary)]">{title}</p>
        <p className="text-xs text-[var(--text-secondary)]">{sub}</p>
      </div>
    </div>
  );
}

function InfoButton({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="shrink-0 w-5 h-5 inline-flex items-center justify-center rounded-full text-[var(--text-muted)] hover:text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors"
    >
      <IconInfoCircle size={14} />
    </button>
  );
}

function formatAge(iso: string | null, t: ReturnType<typeof useT>): string {
  const parts: AgeParts = ageParts(iso, Date.now());
  switch (parts.kind) {
    case 'none': return '—';
    case 'minutes': return t.systemHealthPage.durationMinutes.replace('{n}', String(parts.n));
    case 'hours': return t.systemHealthPage.durationHours.replace('{h}', String(parts.h)).replace('{m}', String(parts.m));
    case 'days': return t.systemHealthPage.durationDays.replace('{d}', String(parts.d)).replace('{h}', String(parts.h));
  }
}
