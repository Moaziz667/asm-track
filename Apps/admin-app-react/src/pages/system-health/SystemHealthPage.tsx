import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import {
  IconActivityHeartbeat, IconChevronDown, IconChevronRight, IconInfoCircle,
  IconMessages, IconServer, IconDatabase, IconPlugConnected,
} from '@tabler/icons-react';
import { useT, useLocaleContext } from '@/lib/i18n/LocaleContext';
import { AppModal } from '@/components/overlays/AppModal';
import {
  deriveHealthSummary, computeStale, groupServices, describeKey,
  type DescriptionKey,
} from '@/lib/health/system-health';
import {
  type Tone, type CircuitBreaker,
  TONE_VAR, groupTone, formatAge,
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
/** One recorded exchange with the ERP — see {@code erp_sync_event} on the backend. */
interface SyncEvent {
  id: string; occurredAt: string; provider: string | null; op: string | null;
  success: boolean; errorReason: string | null;
  orderId: string | null; rmaId: string | null; reference: string | null;
}
interface JournalPayload {
  events: SyncEvent[]; total24h: number; failed24h: number; provider: string | null;
}

const PROVIDER_LABELS: Record<string, string> = { odoo: 'Odoo', erpnext: 'ERPNext' };

// ── Quiet building blocks ─────────────────────────────────────────────────────
/** Status is a small coloured dot plus a word — never a badge shouting for attention. */
function Dot({ tone }: { tone: Tone }) {
  return (
    <span
      aria-hidden
      className="inline-block w-1.5 h-1.5 rounded-full shrink-0"
      style={{ background: TONE_VAR[tone] }}
    />
  );
}

function Status({ tone, label }: { tone: Tone; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-sm shrink-0" style={{ color: 'var(--text-secondary)' }}>
      <Dot tone={tone} />{label}
    </span>
  );
}

function Section({ title, sub, aside, children }: {
  title: string; sub?: string; aside?: React.ReactNode; children: React.ReactNode;
}) {
  return (
    <section>
      <div className="flex items-end justify-between gap-3 mb-2">
        <div className="min-w-0">
          <h2 className="text-sm font-[600]" style={{ color: 'var(--text-primary)' }}>{title}</h2>
          {sub && <p className="text-xs mt-0.5" style={{ color: 'var(--text-muted)' }}>{sub}</p>}
        </div>
        {aside}
      </div>
      <div className="rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
        {children}
      </div>
    </section>
  );
}

/** A label/value line. The whole overview is three of these — nothing to decode. */
function Line({ label, value, status }: { label: string; value?: string; status?: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3 px-4 py-2.5 border-t border-[var(--border)] first:border-t-0">
      <span className="text-sm min-w-0 flex-1" style={{ color: 'var(--text-secondary)' }}>{label}</span>
      {value && <span className="text-sm tabular-nums" style={{ color: 'var(--text-primary)' }}>{value}</span>}
      {status}
    </div>
  );
}

/** A text button. Deliberately not a filled call-to-action: these are rare, corrective actions. */
function QuietButton({ onClick, disabled, label }: { onClick: () => void; disabled?: boolean; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="text-xs px-2.5 h-7 rounded-[var(--radius)] border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors disabled:opacity-40 shrink-0"
      style={{ color: 'var(--text-secondary)' }}
    >
      {label}
    </button>
  );
}

function InfoDot({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <button
      type="button" onClick={onClick} aria-label={label} title={label}
      className="shrink-0 w-4 h-4 inline-flex items-center justify-center rounded-full text-[var(--text-soft)] hover:text-[var(--brand)] transition-colors"
    >
      <IconInfoCircle size={13} />
    </button>
  );
}

export default function SystemHealthPage() {
  const t = useT();
  const { locale } = useLocaleContext();
  const dd = t.systemHealthPage;
  const [data, setData] = useState<HealthPayload | null>(null);
  const [journal, setJournal] = useState<JournalPayload | null>(null);
  const [onlyFailed, setOnlyFailed] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [replaying, setReplaying] = useState<string | null>(null);
  const [resyncing, setResyncing] = useState<string | null>(null);
  const [showTechnical, setShowTechnical] = useState(false);
  const [connected, setConnected] = useState(true);
  const [infoKey, setInfoKey] = useState<DescriptionKey | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  // The clock is state, not a Date.now() call in render: "updated 12s ago" has to keep ticking on
  // its own, and reading the wall clock while rendering makes the output depend on when React runs.
  const [now, setNow] = useState(() => Date.now());

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
  const toneLabel = (tone: Tone): string =>
    tone === 'ok' ? dd.statusOperational
    : tone === 'warn' ? dd.states.HALF_OPEN.label
    : tone === 'down' ? dd.states.OPEN.label
    : dd.states.DISABLED.label;

  const formatTime = useCallback((iso: string): string => {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '—';
    const tag = locale === 'ar' ? 'ar-TN' : locale === 'en' ? 'en-GB' : 'fr-FR';
    return d.toLocaleString(tag, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }, [locale]);

  // ── Data ────────────────────────────────────────────────────────────────────
  const fetchHealth = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    try {
      const [snapRes, journalRes] = await Promise.all([
        api.get<HealthPayload>('/admin/system/health'),
        api.get<JournalPayload>('/admin/system/erp-sync/history', { params: { limit: 50, failedOnly: onlyFailed } })
          .catch(() => ({ data: null as JournalPayload | null })),
      ]);
      setData(snapRes.data);
      setJournal(journalRes.data);
      setConnected(true);
      setLastUpdated(Date.now());
    } catch {
      setConnected(false);
      if (!silent) showErrorToast(null, dd.toastLoadError);
    } finally {
      setRefreshing(false);
    }
  }, [dd, onlyFailed]);

  useEffect(() => {
    // Silent on mount: the visible spinner belongs to an explicit refresh, and a non-silent call
    // would set state synchronously inside the effect body.
    void fetchHealth(true);
    const id = setInterval(() => void fetchHealth(true), 15_000);
    const tick = setInterval(() => setNow(Date.now()), 5_000);
    return () => { clearInterval(id); clearInterval(tick); };
  }, [fetchHealth]);

  const replay = async (queue: string) => {
    setReplaying(queue);
    try {
      const res = await api.post(`/admin/dlq/${encodeURIComponent(queue)}/replay`, null, { params: { max: 100 } });
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
      const res = await api.post(`/admin/system/erp-sync/${orderId}/resync`);
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
      const res = await api.post('/admin/system/erp-sync/resync-all');
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

  const isStale = computeStale({ connected, generatedAt: data?.generatedAt, lastUpdated, now });
  const agoSeconds = lastUpdated ? Math.max(0, Math.round((now - lastUpdated) / 1000)) : null;

  const erpReachable = data?.erp?.reachable ?? true;
  const providerLabel = journal?.provider ? (PROVIDER_LABELS[journal.provider] ?? journal.provider) : null;

  const overall: Tone = summary.downCount > 0 || !dbReachable ? 'down'
    : actionCount > 0 || summary.recoveringCount > 0 ? 'warn'
    : 'ok';
  const headline = overall === 'ok' ? dd.allGoodTitle
    : actionCount > 0
      ? dd.pointsAttentionTitle.replace('{count}', String(actionCount)).replace('{plural}', actionCount > 1 ? 's' : '')
      : dd.recoveringTitle;
  const headlineSub = overall === 'ok' ? dd.allGoodSub
    : actionCount > 0 ? dd.pointsAttentionSub
    : dd.recoveringSub;

  const groups = groupServices(breakers, getFriendlyService);
  const events = journal?.events ?? [];
  const loading = data === null && connected;

  // ── Render ──────────────────────────────────────────────────────────────────
  return (
    <div className="min-h-full" style={{ background: 'var(--app-bg)' }}>
      <header className="border-b border-[var(--border)] bg-[var(--surface)] sticky top-0 z-[5]">
        <div className="px-6 py-3 flex items-center gap-3 max-w-[900px] mx-auto">
          <IconActivityHeartbeat size={17} style={{ color: 'var(--text-soft)' }} />
          <h1 className="text-sm font-[600]" style={{ color: 'var(--text-primary)' }}>{dd.title}</h1>
          <div className="ms-auto flex items-center gap-3">
            {agoSeconds != null && !isStale && (
              <span className="hidden sm:inline text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                {dd.updatedAgo.replace('{n}', String(agoSeconds))}
              </span>
            )}
            <RefreshButton refreshing={refreshing} onClick={() => fetchHealth()} />
          </div>
        </div>
      </header>

      <div className="max-w-[900px] mx-auto px-6 py-6 flex flex-col gap-7">

        {/* ── One sentence: is anything wrong, and does it need me? ── */}
        <div className="flex items-start gap-2.5">
          <span className="mt-[7px]"><Dot tone={loading || isStale ? 'idle' : overall} /></span>
          <div className="min-w-0">
            <p className="text-base font-[600] leading-snug" style={{ color: 'var(--text-primary)' }}>
              {loading ? dd.loading : isStale ? dd.disconnectedTitle : headline}
            </p>
            <p className="text-sm mt-0.5" style={{ color: 'var(--text-muted)' }}>
              {loading ? dd.subtitle : isStale ? dd.disconnectedSub : headlineSub}
            </p>
          </div>
        </div>

        {/* ── The integration, in three plain lines ── */}
        <Section title={dd.overviewTitle} sub={providerLabel ?? dd.noProvider}>
          <Line
            label={dd.overviewConnection}
            status={<Status tone={erpReachable ? 'ok' : 'down'} label={erpReachable ? dd.connectionActive : dd.connectionInterrupted} />}
          />
          <Line
            label={dd.overviewSyncs}
            value={journal
              ? (journal.failed24h > 0
                  ? dd.syncsValue.replace('{total}', String(journal.total24h)).replace('{failed}', String(journal.failed24h))
                  : dd.syncsValueOk.replace('{total}', String(journal.total24h)))
              : '—'}
          />
          <Line label={dd.overviewPending} value={String(erpSync?.inProgress ?? 0)} />
        </Section>

        {/* ── Only shown when someone has to do something ── */}
        {actionCount > 0 && (
          <Section
            title={dd.actionRequiredTitle}
            sub={dd.failuresSubNeedsAttention}
            aside={failures.length > 1
              ? <QuietButton onClick={resyncAll} disabled={resyncing != null} label={dd.resync.resyncAllButton} />
              : undefined}
          >
            {failures.map(f => (
              <div key={f.orderId} className="flex items-start gap-3 px-4 py-3 border-t border-[var(--border)] first:border-t-0">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-[500] truncate" style={{ color: 'var(--text-primary)' }}>
                    {f.blNumber || f.erpRef || f.orderId.slice(0, 8)}
                  </p>
                  <p className="text-xs mt-0.5" style={{ color: 'var(--text-muted)' }}>
                    {opLabel(f.lastSyncOp)} · {dd.stuckFor.replace('{duration}', formatAge(f.stuckSince, t))}
                    {f.retryCount > 0 ? ` · ${dd.attemptsLabel.replace('{count}', String(f.retryCount))}` : ''}
                  </p>
                  {f.lastSyncError && (
                    <p className="text-xs mt-1 break-words line-clamp-2" style={{ color: 'var(--text-soft)' }}>{f.lastSyncError}</p>
                  )}
                </div>
                <QuietButton
                  onClick={() => resync(f.orderId, f.blNumber)}
                  disabled={resyncing != null}
                  label={resyncing === f.orderId ? dd.resync.resyncingButton : dd.resync.resyncButton}
                />
              </div>
            ))}
            {stuckQueues.map(([queue, depth]) => (
              <div key={queue} className="flex items-center gap-3 px-4 py-3 border-t border-[var(--border)] first:border-t-0">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-[500] truncate" style={{ color: 'var(--text-primary)' }}>{getFriendlyQueue(queue)}</p>
                  <p className="text-xs mt-0.5" style={{ color: 'var(--text-muted)' }}>{dd.replaysAwaiting.replace('{count}', String(depth))}</p>
                </div>
                <QuietButton
                  onClick={() => replay(queue)}
                  disabled={replaying === queue}
                  label={replaying === queue ? dd.replayingButton : dd.replayButton}
                />
              </div>
            ))}
          </Section>
        )}

        {/* ── The history: what actually happened, whichever ERP it was ── */}
        <Section
          title={dd.journalTitle}
          sub={dd.journalSubtitle}
          aside={
            <SegmentedControl<boolean>
              value={onlyFailed}
              onChange={setOnlyFailed}
              options={[{ value: false, label: dd.journalAll }, { value: true, label: dd.journalOnlyFailed }]}
            />
          }
        >
          {events.length === 0 ? (
            <p className="px-4 py-8 text-sm text-center" style={{ color: 'var(--text-muted)' }}>
              {onlyFailed ? dd.journalEmptyFailed : dd.journalEmpty}
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[520px]">
                <thead>
                  <tr style={{ color: 'var(--text-muted)' }}>
                    <th className="text-start font-[500] text-xs px-4 py-2">{dd.colTime}</th>
                    <th className="text-start font-[500] text-xs px-4 py-2">{dd.colReference}</th>
                    <th className="text-start font-[500] text-xs px-4 py-2">{dd.colOperation}</th>
                    <th className="text-start font-[500] text-xs px-4 py-2">{dd.colStatus}</th>
                  </tr>
                </thead>
                <tbody>
                  {events.map(e => (
                    <tr key={e.id} className="border-t border-[var(--border)] align-top">
                      <td className="px-4 py-2.5 whitespace-nowrap tabular-nums" style={{ color: 'var(--text-muted)' }}>
                        {formatTime(e.occurredAt)}
                      </td>
                      <td className="px-4 py-2.5" style={{ color: 'var(--text-primary)' }}>
                        <span className="block truncate max-w-[180px]">{e.reference ?? '—'}</span>
                        {e.errorReason && (
                          <span className="block text-xs mt-0.5 break-words line-clamp-2" style={{ color: 'var(--text-soft)' }}>
                            {e.errorReason}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-2.5 whitespace-nowrap" style={{ color: 'var(--text-secondary)' }}>{opLabel(e.op)}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap">
                        <Status tone={e.success ? 'ok' : 'down'} label={e.success ? dd.journalOk : dd.journalFailedLabel} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Section>

        {/* ── Everything an operator never needs, folded away ── */}
        <section>
          <button
            type="button"
            onClick={() => setShowTechnical(v => !v)}
            className="inline-flex items-center gap-1.5 text-sm hover:text-[var(--text-primary)] transition-colors"
            style={{ color: 'var(--text-muted)' }}
            aria-expanded={showTechnical}
          >
            {showTechnical ? <IconChevronDown size={15} /> : <IconChevronRight size={15} />}
            {dd.techDetailsToggle}
          </button>
          {showTechnical && (
            <div className="mt-3 flex flex-col gap-4">
              <p className="text-xs" style={{ color: 'var(--text-soft)' }}>{dd.technicalSub}</p>

              <div className="rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
                {groups.map(g => {
                  const tone = groupTone(g);
                  return (
                    <div key={g.label} className="flex items-center gap-3 px-4 py-2.5 border-t border-[var(--border)] first:border-t-0">
                      <IconServer size={15} style={{ color: 'var(--text-soft)' }} />
                      <span className="text-sm min-w-0 flex-1 truncate" style={{ color: 'var(--text-secondary)' }}>{g.label}</span>
                      <InfoDot onClick={() => setInfoKey(describeKey(g.label, false))} label={dd.descriptionsModalSubtitle} />
                      <Status tone={tone} label={toneLabel(tone)} />
                    </div>
                  );
                })}
                <div className="flex items-center gap-3 px-4 py-2.5 border-t border-[var(--border)]">
                  <IconDatabase size={15} style={{ color: 'var(--text-soft)' }} />
                  <span className="text-sm min-w-0 flex-1" style={{ color: 'var(--text-secondary)' }}>{dd.services.database}</span>
                  <Status tone={dbReachable ? 'ok' : 'down'} label={dbReachable ? dd.connectionActive : dd.connectionInterrupted} />
                </div>
                <div className="flex items-center gap-3 px-4 py-2.5 border-t border-[var(--border)]">
                  <IconMessages size={15} style={{ color: 'var(--text-soft)' }} />
                  <span className="text-sm min-w-0 flex-1" style={{ color: 'var(--text-secondary)' }}>{dd.queuesGroupLabel}</span>
                  <InfoDot onClick={() => setInfoKey('dlqGeneric')} label={dd.descriptionsModalSubtitle} />
                  <span className="text-sm tabular-nums" style={{ color: totalStuck > 0 ? 'var(--danger)' : 'var(--text-muted)' }}>{totalStuck}</span>
                </div>
                {data?.erp && (
                  <div className="flex items-center gap-3 px-4 py-2.5 border-t border-[var(--border)]">
                    <IconPlugConnected size={15} style={{ color: 'var(--text-soft)' }} />
                    <span className="text-sm min-w-0 flex-1" style={{ color: 'var(--text-secondary)' }}>{dd.cardErpTitle}</span>
                    <InfoDot onClick={() => setInfoKey('erp')} label={dd.descriptionsModalSubtitle} />
                    <Status tone={erpReachable ? 'ok' : 'down'} label={erpReachable ? dd.connectionActive : dd.connectionInterrupted} />
                  </div>
                )}
              </div>

              {breakers.length > 0 && (
                <div className="overflow-x-auto rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)]">
                  <table className="w-full text-xs min-w-[460px]">
                    <thead>
                      <tr style={{ color: 'var(--text-muted)' }}>
                        <th className="text-start font-[500] px-4 py-2">{dd.thCircuitBreaker}</th>
                        <th className="text-start font-[500] px-4 py-2">{dd.thState}</th>
                        <th className="text-end font-[500] px-4 py-2">{dd.thFailureRate}</th>
                        <th className="text-end font-[500] px-4 py-2">{dd.thFailedBuffered}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {breakers.map(cb => (
                        <tr key={cb.name} className="border-t border-[var(--border)]">
                          <td className="px-4 py-1.5 font-mono truncate" style={{ color: 'var(--text-secondary)' }}>
                            {cb.name}{cb.reachable === false ? ' ·' : ''}
                          </td>
                          <td className="px-4 py-1.5 font-mono" style={{ color: 'var(--text-muted)' }}>{cb.state}</td>
                          <td className="px-4 py-1.5 text-end tabular-nums" style={{ color: 'var(--text-muted)' }}>
                            {cb.failureRate < 0 ? '—' : `${cb.failureRate.toFixed(0)}%`}
                          </td>
                          <td className="px-4 py-1.5 text-end tabular-nums" style={{ color: 'var(--text-muted)' }}>
                            {cb.failedCalls} / {cb.bufferedCalls}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {dlqEntries.length > 0 && (
                <div className="rounded-[var(--radius)] border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
                  {dlqEntries.map(([q, d]) => (
                    <div key={q} className="flex items-center gap-3 px-4 py-2 border-t border-[var(--border)] first:border-t-0">
                      <span className="font-mono text-xs min-w-0 flex-1 truncate" style={{ color: 'var(--text-secondary)' }}>{getFriendlyQueue(q)}</span>
                      <InfoDot onClick={() => setInfoKey(describeKey(q, true))} label={dd.descriptionsModalSubtitle} />
                      <span className="text-xs tabular-nums" style={{ color: Number(d) > 0 ? 'var(--danger)' : 'var(--text-muted)' }}>{d}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </section>
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
