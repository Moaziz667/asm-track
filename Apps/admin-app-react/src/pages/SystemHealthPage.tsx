import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { RefreshButton } from '@/components/ui/RefreshButton';
import {
  IconHeartbeat, IconReload, IconAlertTriangle, IconCircleCheck,
  IconRefreshDot, IconPlugConnected, IconServer2, IconInbox, IconChevronDown,
  IconDatabase, IconWifiOff, IconInfoCircle,
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { AppModal } from '@/components/overlays/AppModal';
import { deriveHealthSummary, computeStale, ageParts, groupServices, describeKey, type AgeParts, type DescriptionKey } from '@/lib/system-health';

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

// ── Humanization ────────────────────────────────────────────────────────────
type Tone = 'ok' | 'warn' | 'down' | 'idle';
const TONE_COLOR: Record<Tone, string> = { ok: '#4CAF82', warn: '#D4772C', down: '#C7372F', idle: '#8A8F98' };

const STATE_STATUS: Record<string, { tone: Tone }> = {
  CLOSED:      { tone: 'ok' },
  OPEN:        { tone: 'down' },
  HALF_OPEN:   { tone: 'warn' },
  DISABLED:    { tone: 'idle' },
  FORCED_OPEN: { tone: 'down' },
};

export default function SystemHealthPage() {
  const t = useT();
  const [data, setData] = useState<HealthPayload | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [replaying, setReplaying] = useState<string | null>(null);
  const [resyncing, setResyncing] = useState<string | null>(null);
  const [showTech, setShowTech] = useState(false);
  const [connected, setConnected] = useState(true);
  const [infoKey, setInfoKey] = useState<DescriptionKey | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [, setNowTick] = useState(0);
  const lastUpdatedRef = useRef<number | null>(null);

  const statusFor = (state: string) => {
    const s = t.systemHealthPage.states[state as keyof typeof t.systemHealthPage.states];
    const staticInfo = STATE_STATUS[state] ?? { tone: 'idle' as Tone };
    return { label: s?.label ?? state, hint: s?.hint ?? '', tone: staticInfo.tone };
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
      const res = await api.get<HealthPayload>('/api/admin/system/health');
      setData(res.data);
      setConnected(true);
      const ts = Date.now();
      setLastUpdated(ts);
      lastUpdatedRef.current = ts;
    } catch {
      setConnected(false);
      if (!silent) showErrorToast(null, t.systemHealthPage.toastLoadError);
    } finally {
      setRefreshing(false);
    }
  }, [t]);

  useEffect(() => {
    void fetchHealth();
    const id = setInterval(() => void fetchHealth(true), 20_000);
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

  // ── Derived, non-technical view ───────────────────────────────────────────
  const breakers = data?.circuitBreakers ?? [];
  const dlqEntries = Object.entries(data?.dlq ?? {});
  const stuckQueues = dlqEntries.filter(([, d]) => Number(d) > 0);
  const dbReachable = data?.db?.reachable ?? true;
  const erpSync = data?.erpSync;
  const { serviceCount, downCount, recoveringCount, okServices, totalStuck, erpFailed, erpInProgress, problems, allGood } =
    deriveHealthSummary(data, getFriendlyService);

  // ── Staleness ──────────────────────────────────────────────────────────────
  const isStale = computeStale({ connected, generatedAt: data?.generatedAt, lastUpdated, now: Date.now() });
  const agoSeconds = lastUpdated ? Math.max(0, Math.round((Date.now() - lastUpdated) / 1000)) : null;

  const banner = allGood
    ? { tone: 'ok' as Tone, icon: IconCircleCheck, title: t.systemHealthPage.allGoodTitle, sub: t.systemHealthPage.allGoodSub }
    : problems > 0
      ? {
          tone: 'down' as Tone, icon: IconAlertTriangle,
          title: t.systemHealthPage.pointsAttentionTitle.replace('{count}', String(problems)).replace('{plural}', problems > 1 ? 's' : ''),
          sub: t.systemHealthPage.pointsAttentionSub,
        }
      : { tone: 'warn' as Tone, icon: IconRefreshDot, title: t.systemHealthPage.recoveringTitle, sub: t.systemHealthPage.recoveringSub };
  const BannerIcon = banner.icon;

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Header */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-4 flex items-center justify-between max-w-[1400px] mx-auto">
          <div className="flex items-center gap-3">
            <IconHeartbeat size={18} className="text-[var(--brand)]" />
            <h1 className="text-lg font-bold text-[var(--text-primary)]">{t.systemHealthPage.title}</h1>
            {agoSeconds != null && !isStale && (
              <span className="text-xs text-[var(--text-muted)]">
                {t.systemHealthPage.updatedAgo.replace('{n}', String(agoSeconds))}
              </span>
            )}
          </div>
          <RefreshButton refreshing={refreshing} onClick={() => fetchHealth()} />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1400px] mx-auto p-6 flex flex-col gap-5">

          {/* ── Disconnected / stale strip ── */}
          {isStale && (
            <div
              className="rounded-xl px-4 py-3 flex items-center gap-3 border"
              style={{ background: `${TONE_COLOR.warn}12`, borderColor: `${TONE_COLOR.warn}40` }}
            >
              <IconWifiOff size={18} style={{ color: TONE_COLOR.warn }} />
              <div>
                <p className="text-sm font-bold text-[var(--text-primary)]">{t.systemHealthPage.disconnectedTitle}</p>
                <p className="text-xs text-[var(--text-secondary)]">{t.systemHealthPage.disconnectedSub}</p>
              </div>
            </div>
          )}

          {/* ── Headline status banner ── */}
          <div
            className="rounded-xl p-5 flex items-center gap-4 border"
            style={{ background: `${TONE_COLOR[banner.tone]}12`, borderColor: `${TONE_COLOR[banner.tone]}40` }}
          >
            <BannerIcon size={34} style={{ color: TONE_COLOR[banner.tone] }} className={banner.tone === 'warn' ? 'animate-pulse' : ''} />
            <div>
              <p className="text-xl font-black text-[var(--text-primary)] leading-tight">{banner.title}</p>
              <p className="text-sm text-[var(--text-secondary)] mt-0.5">{banner.sub}</p>
            </div>
            <HeartbeatLine color={TONE_COLOR[banner.tone]} paused={banner.tone === 'down'} />
          </div>

          {/* ── Summary cards ── */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <SummaryCard
              icon={IconPlugConnected}
              label={t.systemHealthPage.erpSyncTitle}
              value={erpFailed > 0
                ? t.systemHealthPage.erpSyncFailed.replace('{count}', String(erpFailed))
                : erpInProgress > 0
                  ? t.systemHealthPage.erpSyncInProgress.replace('{count}', String(erpInProgress))
                  : t.systemHealthPage.erpSyncAllOk}
              tone={erpFailed > 0 ? 'down' : erpInProgress > 0 ? 'warn' : 'ok'}
              foot={erpFailed > 0
                ? t.systemHealthPage.erpSyncFootFailed.replace('{count}', String(erpFailed))
                : t.systemHealthPage.erpSyncFootOk}
            />
            <SummaryCard
              icon={IconServer2}
              label={t.systemHealthPage.cardServicesTitle}
              value={serviceCount === 0 ? '—' : t.systemHealthPage.cardServicesValue.replace('{ok}', String(okServices)).replace('{total}', String(serviceCount))}
              tone={downCount > 0 ? 'down' : recoveringCount > 0 ? 'warn' : 'ok'}
              foot={downCount > 0
                ? t.systemHealthPage.cardServicesFootFailures.replace('{count}', String(downCount))
                : recoveringCount > 0 ? t.systemHealthPage.cardServicesFootRecovering.replace('{count}', String(recoveringCount)) : t.systemHealthPage.cardServicesFootOk}
            />
            <SummaryCard
              icon={IconInbox}
              label={t.systemHealthPage.cardReplayTitle}
              value={String(totalStuck)}
              tone={totalStuck > 0 ? 'down' : 'ok'}
              foot={totalStuck > 0 ? t.systemHealthPage.cardReplayFootFailures.replace('{count}', String(stuckQueues.length)) : t.systemHealthPage.cardReplayFootOk}
            />
          </div>

          {/* ── ERP sync failures drill-down (action needed) ── */}
          {erpSync && erpSync.failures.length > 0 && (
            <div>
              <div className="flex items-center justify-between mb-2.5">
                <div>
                  <h2 className="text-base font-bold text-[var(--text-primary)]">{t.systemHealthPage.failuresTitle}</h2>
                  <p className="text-xs text-[var(--text-muted)]">{t.systemHealthPage.failuresSubNeedsAttention}</p>
                </div>
                {erpSync.failures.length > 1 && (
                  <button
                    onClick={resyncAll}
                    disabled={resyncing != null}
                    className="shrink-0 text-sm font-bold px-3 py-1.5 rounded-md border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--brand)] hover:text-white transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
                  >
                    <IconReload size={13} className={resyncing === '__all__' ? 'animate-spin' : ''} />
                    {t.systemHealthPage.resync.resyncAllButton}
                  </button>
                )}
              </div>
              <div className="flex flex-col gap-2">
                {erpSync.failures.map(f => (
                  <div key={f.orderId} className="card p-4 rounded-xl border border-[#C7372F]/30 bg-[#C7372F]/5 flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-base font-bold text-[var(--text-primary)] truncate">{f.blNumber || f.erpRef || f.orderId.slice(0, 8)}</span>
                        <span className="text-2xs font-bold px-2 py-0.5 rounded-full" style={{ background: `${TONE_COLOR.idle}1a`, color: TONE_COLOR.idle }}>
                          {opLabel(f.lastSyncOp)}
                        </span>
                        <span className="text-[10.5px] text-[var(--text-muted)]">
                          {t.systemHealthPage.stuckFor.replace('{duration}', formatAge(f.stuckSince, t))}
                          {f.retryCount > 0 && ` · ${t.systemHealthPage.attemptsLabel.replace('{count}', String(f.retryCount))}`}
                        </span>
                      </div>
                      {f.lastSyncError && (
                        <p className="text-xs text-[var(--text-muted)] mt-1 line-clamp-2 break-words">{f.lastSyncError}</p>
                      )}
                    </div>
                    <button
                      onClick={() => resync(f.orderId, f.blNumber)}
                      disabled={resyncing != null}
                      className="shrink-0 text-sm font-bold px-3 py-1.5 rounded-md border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--brand)] hover:text-white transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
                    >
                      <IconReload size={13} className={resyncing === f.orderId ? 'animate-spin' : ''} />
                      {resyncing === f.orderId ? t.systemHealthPage.resync.resyncingButton : t.systemHealthPage.resync.resyncButton}
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* ── Services grid ── */}
          <div>
            <h2 className="text-base font-bold text-[var(--text-primary)] mb-2.5">{t.systemHealthPage.statusTitle}</h2>
            {breakers.length === 0 && !data?.db ? (
              <EmptyHint text={t.systemHealthPage.noServices} />
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {groupServices(breakers, getFriendlyService).map(g => {
                  const s = g.reachableDown ? statusFor('OPEN') : statusFor(g.state);
                  return (
                    <div key={g.label} className="card p-4 rounded-xl border border-[var(--border)] bg-[var(--surface)] flex flex-col gap-2">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-base font-bold text-[var(--text-primary)] truncate">{g.label}</span>
                        <StatusPill tone={s.tone} label={s.label} />
                      </div>
                      <p className="text-xs text-[var(--text-muted)] leading-snug">{s.hint}</p>
                      {g.failureRate >= 0 && g.bufferedCalls > 0 && (
                        <p className="text-[10.5px] text-[var(--text-soft)] mt-auto pt-1">
                          {t.systemHealthPage.recentErrorRate} <span className="font-bold tabular-nums" style={{ color: g.failureRate > 50 ? TONE_COLOR.down : 'var(--text-secondary)' }}>{g.failureRate.toFixed(0)}%</span>
                        </p>
                      )}
                    </div>
                  );
                })}
                {/* Database health card */}
                {data?.db && (
                  <div className="card p-4 rounded-xl border border-[var(--border)] bg-[var(--surface)] flex flex-col gap-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-base font-bold text-[var(--text-primary)] inline-flex items-center gap-1.5 truncate">
                        <IconDatabase size={14} className="text-[var(--text-muted)]" />
                        {t.systemHealthPage.services.database}
                      </span>
                      <StatusPill
                        tone={dbReachable ? 'ok' : 'down'}
                        label={dbReachable ? statusFor('CLOSED').label : statusFor('OPEN').label}
                      />
                    </div>
                    <p className="text-xs text-[var(--text-muted)] leading-snug">
                      {dbReachable ? t.systemHealthPage.dbOk : t.systemHealthPage.dbDown}
                    </p>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* ── Operations to replay (only when something is stuck) ── */}
          {stuckQueues.length > 0 && (
            <div>
              <h2 className="text-base font-bold text-[var(--text-primary)] mb-2.5">{t.systemHealthPage.replaysTitle}</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {stuckQueues.map(([queue, depth]) => (
                  <div key={queue} className="card p-4 rounded-xl border border-[#C7372F]/30 bg-[#C7372F]/5 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-base font-bold text-[var(--text-primary)] truncate">{getFriendlyQueue(queue)}</p>
                      <p className="text-xs text-[var(--text-muted)]">
                        {t.systemHealthPage.replaysAwaiting.replace('{count}', String(depth))}
                      </p>
                    </div>
                    <button
                      onClick={() => replay(queue)}
                      disabled={replaying === queue}
                      className="shrink-0 text-sm font-bold px-3 py-1.5 rounded-md border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--brand)] hover:text-white transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
                    >
                      <IconReload size={13} className={replaying === queue ? 'animate-spin' : ''} />
                      {replaying === queue ? t.systemHealthPage.replayingButton : t.systemHealthPage.replayButton}
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* ── Technical details (for engineers) ── */}
          <div className="mt-1">
            <button
              onClick={() => setShowTech(v => !v)}
              className="text-sm font-semibold text-[var(--text-muted)] hover:text-[var(--text-primary)] inline-flex items-center gap-1.5 transition-colors"
            >
              <IconChevronDown size={14} className={cn('transition-transform', showTech && 'rotate-180')} />
              {t.systemHealthPage.techDetailsToggle}
            </button>
            {showTech && (
              <div className="mt-3 rounded-lg border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-[9px] uppercase tracking-wider text-[var(--text-muted)]" style={{ background: 'var(--app-bg)' }}>
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
                        <td className="px-4 py-2 font-mono" style={{ color: TONE_COLOR[statusFor(cb.state).tone] }}>{cb.state}</td>
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
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Plain-language description modal */}
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

/** Small round info trigger used in the technical details table. */
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

// ── Small presentational helpers ─────────────────────────────────────────────
function formatAge(iso: string | null, t: ReturnType<typeof useT>): string {
  const parts: AgeParts = ageParts(iso, Date.now());
  switch (parts.kind) {
    case 'none': return '—';
    case 'minutes': return t.systemHealthPage.durationMinutes.replace('{n}', String(parts.n));
    case 'hours': return t.systemHealthPage.durationHours.replace('{h}', String(parts.h)).replace('{m}', String(parts.m));
    case 'days': return t.systemHealthPage.durationDays.replace('{d}', String(parts.d)).replace('{h}', String(parts.h));
  }
}

/** Decorative EKG trace — animates a heartbeat sweep when healthy, flatlines when something is down. */
function HeartbeatLine({ color, paused }: { color: string; paused: boolean }) {
  const ekg = 'M0 24 H40 L48 24 L54 10 L62 38 L70 24 L78 21 L86 24 H120 L128 24 L134 13 L142 35 L150 24 H200';
  const flat = 'M0 24 H200';
  return (
    <svg width="200" height="48" viewBox="0 0 200 48" fill="none"
         className="hidden md:block ml-auto shrink-0" aria-hidden="true">
      <path d={paused ? flat : ekg} stroke={color} strokeWidth="2" strokeOpacity="0.2"
            strokeLinejoin="round" strokeLinecap="round" />
      {!paused && (
        <path d={ekg} stroke={color} strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round"
              strokeDasharray="48 260">
          <animate attributeName="stroke-dashoffset" from="308" to="0" dur="2.4s" repeatCount="indefinite" />
        </path>
      )}
    </svg>
  );
}

function SummaryCard({ icon: Icon, label, value, tone, foot }: {
  icon: any; label: string; value: string; tone: Tone; foot: string;
}) {
  return (
    <div className="card p-4 rounded-xl border border-[var(--border)] bg-[var(--surface)] flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <Icon size={16} style={{ color: TONE_COLOR[tone] }} />
        <p className="text-[10.5px] font-bold uppercase tracking-wider text-[var(--text-muted)]">{label}</p>
      </div>
      <p className="text-[20px] font-black text-[var(--text-primary)] leading-tight">{value}</p>
      <p className="text-xs text-[var(--text-muted)]">{foot}</p>
    </div>
  );
}

function StatusPill({ tone, label }: { tone: Tone; label: string }) {
  return (
    <span
      className="shrink-0 text-2xs font-bold px-2 py-0.5 rounded-full inline-flex items-center gap-1 whitespace-nowrap"
      style={{ background: `${TONE_COLOR[tone]}1a`, color: TONE_COLOR[tone] }}
    >
      <span className="w-1.5 h-1.5 rounded-full" style={{ background: TONE_COLOR[tone] }} />
      {label}
    </span>
  );
}

function EmptyHint({ text }: { text: string }) {
  return (
    <div className="rounded-lg border border-[var(--border)] bg-[var(--surface)] px-4 py-8 text-center">
      <span className="text-sm text-[var(--text-muted)]">{text}</span>
    </div>
  );
}
