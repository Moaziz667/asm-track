import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import type { TablerIcon } from '@tabler/icons-react';
import {
  IconActivityHeartbeat, IconAlertTriangle, IconPlugConnected, IconInbox,
  IconTruck, IconMessages, IconShieldCheck, IconRoute, IconBell, IconServer, IconDatabase,
  IconWifiOff, IconChevronDown, IconInfoCircle,
} from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { AppModal } from '@/components/overlays/AppModal';
import {
  deriveHealthSummary, computeStale, groupServices, describeKey,
  type DescriptionKey,
} from '@/lib/health/system-health';
import {
  type Tone, type ComponentRowData, type CircuitBreaker,
  TONE_ICON, TONE_VAR, TONE_BG, worstTone, groupTone, RANGES,
  HeroBanner, ActionRow, ActionButton, Banner, SkeletonRow, formatAge,
} from './SystemHealthParts';

// ── Backend payload contract ──────────────────────────────────────────────────
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
  t: number; erpFailed: number; maxFailureRate: number; dlqTotal: number;
  breakersOpen: number; components: Record<ComponentKey, Tone>;
}

// ── Small presentational helpers ──────────────────────────────────────────────
function StatusPill({ tone, label }: { tone: Tone; label: string }) {
  const Icon = TONE_ICON[tone];
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-[600]"
      style={{ background: TONE_BG[tone], color: TONE_VAR[tone], border: `1px solid color-mix(in srgb, ${TONE_VAR[tone]} 18%, transparent)` }}>
      <Icon size={13} /><span>{label}</span>
    </span>
  );
}

function InfoDot({ onClick, label }: { onClick: () => void; label: string }) {
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

function MetricCard({
  title, icon: Icon, value, sub, tone,
}: {
  title: string; icon: TablerIcon; value: string | number; sub: string; tone: Tone;
}) {
  return (
    <div className="rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)] p-3.5 flex items-center gap-3">
      <Icon size={18} style={{ color: 'var(--text-soft)' }} />
      <div className="min-w-0 flex-1">
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{title}</p>
        <p className="text-lg font-bold tabular-nums leading-tight" style={{ color: 'var(--text-primary)' }}>{value}</p>
      </div>
      <StatusPill tone={tone} label={sub} />
    </div>
  );
}

function ServiceRow({
  label, icon: Icon, tone, statusLabel, metric,
  hasDetails, isOpen, onToggle, onInfo, children,
}: {
  label: string; icon: TablerIcon; tone: Tone; statusLabel: string;
  metric?: { value: string; tone: Tone };
  hasDetails: boolean; isOpen: boolean; onToggle: () => void;
  onInfo?: () => void; children?: React.ReactNode;
}) {
  return (
    <div className="border-b border-[var(--border)] last:border-b-0">
      <button
        type="button"
        onClick={onToggle}
        disabled={!hasDetails}
        className="w-full text-start px-4 py-2.5 transition-colors hover:bg-[var(--hover-bg)] disabled:cursor-default"
      >
        <div className="flex items-center gap-3">
          <Icon size={17} style={{ color: 'var(--text-soft)' }} />
          <div className="min-w-0 flex-1 flex items-center gap-2">
            <span className="text-sm font-medium truncate" style={{ color: 'var(--text-primary)' }}>{label}</span>
            {onInfo && <InfoDot onClick={onInfo} label="What this means" />}
          </div>
          {metric && (
            <span className="font-mono text-xs tabular-nums font-[600]" style={{ color: TONE_VAR[metric.tone] }}>{metric.value}</span>
          )}
          <StatusPill tone={tone} label={statusLabel} />
          {hasDetails && <IconChevronDown size={15} className={`shrink-0 transition-transform ${isOpen ? 'rotate-180' : ''}`} style={{ color: 'var(--text-soft)' }} />}
        </div>
      </button>
      {isOpen && hasDetails && children && (
        <div className="px-4 pb-3 pt-1 bg-[var(--surface-sunken)]">
          {children}
        </div>
      )}
    </div>
  );
}

function BreakerTable({
  breakers, statusFor, onInfo, thBreaker, thState, thRate, thFb, infoLabel,
}: {
  breakers: CircuitBreaker[]; statusFor: (s: string) => { label: string; tone: Tone };
  onInfo: (k: DescriptionKey) => void;
  thBreaker: string; thState: string; thRate: string; thFb: string; infoLabel: string;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs min-w-[460px]">
        <thead>
          <tr className="text-2xs" style={{ color: 'var(--text-muted)' }}>
            <th className="text-start font-[600] py-1.5">{thBreaker}</th>
            <th className="text-start font-[600] py-1.5">{thState}</th>
            <th className="text-end font-[600] py-1.5">{thRate}</th>
            <th className="text-end font-[600] py-1.5">{thFb}</th>
          </tr>
        </thead>
        <tbody>
          {breakers.map(cb => {
            const s = statusFor(cb.state);
            return (
              <tr key={cb.name} className="border-t border-[var(--border)]">
                <td className="py-1.5 font-mono" style={{ color: 'var(--text-secondary)' }}>
                  <span className="inline-flex items-center gap-1.5">
                    <InfoDot onClick={() => onInfo(describeKey(cb.name, false))} label={infoLabel} />
                    <span className="truncate">{cb.name}{cb.reachable === false ? ' ⚠' : ''}</span>
                  </span>
                </td>
                <td className="py-1.5 font-mono" style={{ color: TONE_VAR[s.tone] }}>{cb.state}</td>
                <td className="py-1.5 text-end tabular-nums">{cb.failureRate < 0 ? '—' : `${cb.failureRate.toFixed(0)}%`}</td>
                <td className="py-1.5 text-end tabular-nums">{cb.failedCalls} / {cb.bufferedCalls}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function SystemHealthPage() {
  const t = useT();
  const dd = t.systemHealthPage;
  const [data, setData] = useState<HealthPayload | null>(null);
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [rangeMin, setRangeMin] = useState(60);
  const [refreshing, setRefreshing] = useState(false);
  const [replaying, setReplaying] = useState<string | null>(null);
  const [resyncing, setResyncing] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [connected, setConnected] = useState(true);
  const [infoKey, setInfoKey] = useState<DescriptionKey | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [, setNowTick] = useState(0);

  // ── Friendly-name resolvers (raw breaker / queue ids → human labels) ─────────
  const getFriendlyService = (name: string): string => {
    const n = name.toLowerCase();
    const svc = dd.services;
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
    const qs = dd.queues;
    if (n.includes('erp') || n.includes('sync')) return qs.erp;
    if (n.includes('audit')) return qs.audit;
    if (n.includes('location')) return qs.location;
    if (n.includes('stat')) return qs.stat;
    if (n.includes('driver')) return qs.drivers;
    return q;
  };
  const opLabel = (op: string | null): string => {
    if (!op) return dd.resync.opLabels.SYNC;
    const labels = dd.resync.opLabels;
    return labels[op as keyof typeof labels] ?? op;
  };
  const statusFor = (state: string): { label: string; tone: Tone } => {
    const s = dd.states[state as keyof typeof dd.states];
    const tone: Tone = state === 'CLOSED' ? 'ok'
      : state === 'OPEN' || state === 'FORCED_OPEN' ? 'down'
      : state === 'HALF_OPEN' ? 'warn' : 'idle';
    return { label: s?.label ?? state, tone };
  };
  const toneLabel = (tone: Tone): string =>
    tone === 'ok' ? (dd.statusOperational ?? 'Operational')
    : tone === 'warn' ? dd.states.HALF_OPEN.label
    : tone === 'down' ? dd.states.OPEN.label
    : dd.states.DISABLED.label;

  const iconForGroup = (label: string) => {
    const s = dd.services;
    if (label === s.drivers) return IconTruck;
    if (label === s.erp) return IconPlugConnected;
    if (label === s.auth) return IconShieldCheck;
    if (label === s.routes) return IconRoute;
    if (label === s.notifications) return IconBell;
    if (label === s.main) return IconServer;
    return IconServer;
  };

  // ── Data ────────────────────────────────────────────────────────────────────
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
      if (!silent) showErrorToast(null, dd.toastLoadError);
    } finally {
      setRefreshing(false);
    }
  }, [dd]);

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
      showSuccessToast(dd.toastReplaySuccess.replace('{count}', String(res.data?.replayed ?? 0)));
      await fetchHealth(true);
    } catch (err) {
      showErrorToast(err, dd.toastReplayError);
    } finally {
      setReplaying(null);
    }
  };
  const resync = async (orderId: string, blNumber: string | null) => {
    setResyncing(orderId);
    try {
      const res = await api.post(`/api/admin/system/erp-sync/${orderId}/resync`);
      if (res.data?.queued) {
        showSuccessToast(dd.resync.toastQueued.replace('{bl}', blNumber ?? res.data?.blNumber ?? ''));
      } else {
        showErrorToast(res.data?.reason, dd.resync.toastNotQueued);
      }
      await fetchHealth(true);
    } catch (err) {
      showErrorToast(err, dd.resync.toastError);
    } finally {
      setResyncing(null);
    }
  };
  const resyncAll = async () => {
    setResyncing('__all__');
    try {
      const res = await api.post('/api/admin/system/erp-sync/resync-all');
      showSuccessToast(dd.resync.toastAllQueued.replace('{count}', String(res.data?.queued ?? 0)));
      await fetchHealth(true);
    } catch (err) {
      showErrorToast(err, dd.resync.toastError);
    } finally {
      setResyncing(null);
    }
  };

  // ── Derived ─────────────────────────────────────────────────────────────────
  const breakers = data?.circuitBreakers ?? [];
  const dlqEntries = Object.entries(data?.dlq ?? {});
  const stuckQueues = dlqEntries.filter(([, d]) => Number(d) > 0);
  const totalStuck = stuckQueues.reduce((s, [, d]) => s + Number(d), 0);
  const dbReachable = data?.db?.reachable ?? true;
  const erpSync = data?.erpSync;
  const failures = erpSync?.failures ?? [];
  const summary = deriveHealthSummary(data, getFriendlyService);
  const actionCount = failures.length + stuckQueues.length;

  const isStale = computeStale({ connected, generatedAt: data?.generatedAt, lastUpdated, now: Date.now() });
  const agoSeconds = lastUpdated ? Math.max(0, Math.round((Date.now() - lastUpdated) / 1000)) : null;

  const now = Date.now();
  const windowPoints = history.filter(p => now - p.t <= rangeMin * 60_000);
  const hasDown = summary.downCount > 0 || !dbReachable;
  const hasWarn = summary.recoveringCount > 0 || summary.erpFailed > 0 || stuckQueues.length > 0;
  const overall: Tone = hasDown ? 'down' : hasWarn ? 'warn' : 'ok';
  const recoveringOnly = summary.recoveringCount > 0 && !hasDown && summary.erpFailed === 0 && stuckQueues.length === 0;
  const heroTitle = overall === 'ok' ? dd.allGoodTitle
    : overall === 'warn' && recoveringOnly ? dd.recoveringTitle
    : dd.pointsAttentionTitle.replace('{count}', String(summary.problems)).replace('{plural}', summary.problems > 1 ? 's' : '');
  const heroSub = overall === 'ok' ? dd.allGoodSub
    : overall === 'warn' && recoveringOnly ? dd.recoveringSub
    : dd.pointsAttentionSub;
  const HeroIcon = TONE_ICON[overall];

  // ── Build grouped service rows ──────────────────────────────────────────────
  const groups = useMemo(() => groupServices(breakers, getFriendlyService), [breakers, getFriendlyService]);
  const rows: ComponentRowData[] = useMemo(() => {
    const out: ComponentRowData[] = [];
    for (const g of groups) {
      const isDrivers = g.label === dd.services.drivers;
      const isErp = g.label === dd.services.erp;
      const lane: ComponentKey | null = isDrivers ? 'drivers' : isErp ? 'erp' : null;
      let tone = groupTone(g);
      let metric: ComponentRowData['metric'];
      if (isErp && erpSync && erpSync.failed > 0) {
        tone = worstTone([tone, 'warn']);
        metric = { value: String(erpSync.failed), tone: 'down' };
      } else if (g.bufferedCalls > 0 && g.failureRate >= 0) {
        const fr = Math.round(g.failureRate);
        if (fr > 0) metric = { value: `${fr}%`, tone: fr > 50 ? 'down' : 'warn' };
      }
      out.push({
        kind: 'group', label: g.label, icon: iconForGroup(g.label), lane, tone, metric,
        breakers: breakers.filter(b => getFriendlyService(b.name) === g.label),
      });
    }
    return out;
  }, [groups, breakers, erpSync, dd.services.drivers, dd.services.erp, getFriendlyService]);

  const rangeLabel = RANGES.find(r => r.min === rangeMin)?.label;

  // ── Render ──────────────────────────────────────────────────────────────────
  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Command bar */}
      <header className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0 sticky top-0 z-[5]">
        <div className="px-6 py-3 flex items-center gap-3 flex-wrap max-w-[1200px] mx-auto">
          <IconActivityHeartbeat size={18} style={{ color: 'var(--brand)' }} />
          <h1 className="text-base font-bold leading-tight" style={{ color: 'var(--text-primary)' }}>{dd.title}</h1>
          <span className="text-xs hidden sm:inline" style={{ color: 'var(--text-soft)' }}>{dd.subtitle}</span>
          <div className="ms-auto flex items-center gap-3">
            <SegmentedControl<number>
              value={rangeMin}
              onChange={setRangeMin}
              options={RANGES.map(r => ({ value: r.min, label: r.label }))}
            />
            {agoSeconds != null && !isStale && (
              <span className="hidden md:inline text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                {dd.updatedAgo.replace('{n}', String(agoSeconds))}
              </span>
            )}
            <RefreshButton refreshing={refreshing} onClick={() => fetchHealth()} />
          </div>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1200px] mx-auto p-6 flex flex-col gap-6">

          {/* ── Hero status banner ── */}
          {data ? (
            <HeroBanner
              tone={overall} icon={HeroIcon} title={heroTitle} sub={heroSub}
              range={rangeLabel}
            />
          ) : (
            <HeroBanner tone="idle" icon={IconActivityHeartbeat} title={dd.loading ?? 'Loading…'} sub={dd.subtitle} range={rangeLabel} />
          )}

          {/* ── Stale / disconnected ── */}
          {isStale && (
            <Banner tone="warn" icon={IconWifiOff} title={dd.disconnectedTitle} sub={dd.disconnectedSub} />
          )}

          {/* ── Loading skeleton ── */}
          {!data && (
            <section className="rounded-[var(--radius-xl)] border border-[var(--border)] bg-[var(--surface)] divide-y divide-[var(--border)] overflow-hidden">
              {[0, 1, 2, 3].map(i => <SkeletonRow key={i} />)}
            </section>
          )}

          {data && (
            <>
              {/* ── Summary cards ── */}
              <section className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <MetricCard
                  title={dd.cardServicesTitle}
                  icon={IconServer}
                  value={`${summary.okServices}/${summary.serviceCount}`}
                  tone={summary.downCount > 0 ? 'down' : summary.recoveringCount > 0 ? 'warn' : 'ok'}
                  sub={summary.downCount > 0 ? dd.cardServicesFootFailures.replace('{count}', String(summary.downCount))
                    : summary.recoveringCount > 0 ? dd.cardServicesFootRecovering.replace('{count}', String(summary.recoveringCount))
                    : dd.cardServicesFootOk}
                />
                <MetricCard
                  title={dd.cardReplayTitle}
                  icon={IconMessages}
                  value={totalStuck}
                  tone={stuckQueues.length > 0 ? 'warn' : 'ok'}
                  sub={stuckQueues.length > 0 ? dd.cardReplayFootFailures.replace('{count}', String(stuckQueues.length)) : dd.cardReplayFootOk}
                />
                <MetricCard
                  title={dd.cardErpTitle}
                  icon={IconPlugConnected}
                  value={summary.erpFailed}
                  tone={summary.erpFailed > 0 ? 'down' : 'ok'}
                  sub={summary.erpFailed > 0 ? dd.cardErpFootFailures.replace('{count}', String(summary.erpFailed)) : dd.cardErpFootOk}
                />
              </section>

              {/* ── Service list ── */}
              <section>
                <h2 className="text-sm font-bold mb-2.5 inline-flex items-center gap-1.5" style={{ color: 'var(--text-primary)' }}>
                  <IconActivityHeartbeat size={15} style={{ color: 'var(--text-muted)' }} />
                  {dd.statusTitle}
                </h2>
                {rows.length === 0 ? (
                  <p className="px-4 py-6 text-sm text-center rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)]" style={{ color: 'var(--text-muted)' }}>{dd.noServices}</p>
                ) : (
                  <div className="rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
                    {rows.map(row => {
                      const key = `group:${row.label}`;
                      const isOpen = expanded === key;
                      const hasDetails = (row.breakers?.length ?? 0) > 0;
                      return (
                        <ServiceRow
                          key={key}
                          label={row.label}
                          icon={row.icon}
                          tone={row.tone}
                          statusLabel={toneLabel(row.tone)}
                          metric={row.metric}
                          hasDetails={hasDetails}
                          isOpen={isOpen}
                          onToggle={() => hasDetails && setExpanded(isOpen ? null : key)}
                          onInfo={() => setInfoKey(describeKey(row.label, false))}
                        >
                          {hasDetails && (
                            <BreakerTable
                              breakers={row.breakers!}
                              statusFor={statusFor}
                              onInfo={setInfoKey}
                              thBreaker={dd.thCircuitBreaker}
                              thState={dd.thState}
                              thRate={dd.thFailureRate}
                              thFb={dd.thFailedBuffered}
                              infoLabel={dd.descriptionsModalSubtitle}
                            />
                          )}
                        </ServiceRow>
                      );
                    })}

                    {/* Database row */}
                    <ServiceRow
                      label={dd.services.database}
                      icon={IconDatabase}
                      tone={dbReachable ? 'ok' : 'down'}
                      statusLabel={dbReachable ? dd.dbOk : dd.dbDown}
                      hasDetails={false}
                      isOpen={false}
                      onToggle={() => {}}
                    />

                    {/* Queues row */}
                    <ServiceRow
                      label={dd.queuesGroupLabel}
                      icon={IconMessages}
                      tone={stuckQueues.length > 0 ? 'warn' : 'ok'}
                      statusLabel={stuckQueues.length > 0 ? dd.cardReplayFootFailures.replace('{count}', String(stuckQueues.length)) : dd.cardReplayFootOk}
                      metric={totalStuck > 0 ? { value: String(totalStuck), tone: 'warn' } : undefined}
                      hasDetails={dlqEntries.length > 0}
                      isOpen={expanded === 'queues'}
                      onToggle={() => setExpanded(expanded === 'queues' ? null : 'queues')}
                      onInfo={() => setInfoKey('dlqGeneric')}
                    >
                      <div className="flex flex-col">
                        {dlqEntries.map(([q, d]) => (
                          <div key={q} className="flex items-center justify-between gap-3 py-1.5 border-t border-[var(--border)] first:border-t-0">
                            <span className="inline-flex items-center gap-1.5 min-w-0 font-mono text-xs" style={{ color: 'var(--text-secondary)' }}>
                              <InfoDot onClick={() => setInfoKey(describeKey(q, true))} label={dd.descriptionsModalSubtitle} />
                              <span className="truncate">{getFriendlyQueue(q)}</span>
                            </span>
                            <span className="font-mono text-xs tabular-nums shrink-0" style={{ color: Number(d) > 0 ? 'var(--danger)' : 'var(--text-soft)' }}>{d}</span>
                          </div>
                        ))}
                      </div>
                    </ServiceRow>
                  </div>
                )}
              </section>

              {/* ── Action feed (ERP resync + DLQ replay) ── */}
              {actionCount > 0 && (
                <section className="rounded-[var(--radius-xl)] overflow-hidden border" style={{ borderColor: 'var(--danger)' }}>
                  <header className="flex items-center justify-between gap-2 px-4 py-2.5" style={{ background: 'var(--danger-bg)' }}>
                    <span className="inline-flex items-center gap-2 text-sm font-bold" style={{ color: 'var(--danger)' }}>
                      <IconAlertTriangle size={15} />
                      {(dd.actionRequiredTitle ?? 'Action required')} · {actionCount}
                    </span>
                    {failures.length > 1 && (
                      <ActionButton onClick={resyncAll} busy={resyncing === '__all__'} disabled={resyncing != null} label={dd.resync.resyncAllButton} />
                    )}
                  </header>
                  <div className="flex flex-col">
                    {failures.map(f => (
                      <ActionRow
                        key={f.orderId}
                        icon={IconPlugConnected}
                        title={`${dd.erpSyncTitle} — ${f.blNumber || f.erpRef || f.orderId.slice(0, 8)}`}
                        meta={`${opLabel(f.lastSyncOp)} · ${dd.stuckFor.replace('{duration}', formatAge(f.stuckSince, t))}${f.retryCount > 0 ? ` · ${dd.attemptsLabel.replace('{count}', String(f.retryCount))}` : ''}`}
                        detail={f.lastSyncError ?? undefined}
                        action={<ActionButton onClick={() => resync(f.orderId, f.blNumber)} busy={resyncing === f.orderId} disabled={resyncing != null} label={resyncing === f.orderId ? dd.resync.resyncingButton : dd.resync.resyncButton} />}
                      />
                    ))}
                    {stuckQueues.map(([queue, depth]) => (
                      <ActionRow
                        key={queue}
                        icon={IconInbox}
                        title={getFriendlyQueue(queue)}
                        meta={dd.replaysAwaiting.replace('{count}', String(depth))}
                        action={<ActionButton onClick={() => replay(queue)} busy={replaying === queue} disabled={replaying === queue} label={replaying === queue ? dd.replayingButton : dd.replayButton} />}
                      />
                    ))}
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      </div>

      {infoKey && (
        <AppModal open onClose={() => setInfoKey(null)} subtitle={dd.descriptionsModalSubtitle} title={dd.descriptions[infoKey].title} size="sm">
          <p className="text-base leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{dd.descriptions[infoKey].body}</p>
          <div className="mt-3 rounded-lg border border-[var(--border)] bg-[var(--app-bg)] px-3 py-2.5">
            <p className="text-2xs font-[600] mb-1" style={{ color: 'var(--text-muted)' }}>{dd.descriptionsTipLabel}</p>
            <p className="text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{dd.descriptions[infoKey].tip}</p>
          </div>
        </AppModal>
      )}
    </div>
  );
}
