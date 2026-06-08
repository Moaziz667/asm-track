import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { RefreshButton } from '@/components/ui/RefreshButton';
import {
  IconHeartbeat, IconReload, IconAlertTriangle, IconCircleCheck,
  IconRefreshDot, IconPlugConnected, IconServer2, IconInbox, IconChevronDown,
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';

interface CircuitBreaker { name: string; state: string; failureRate: number; bufferedCalls: number; failedCalls: number; notPermittedCalls: number; }
interface HealthPayload {
  dlq: Record<string, number>;
  circuitBreakers: CircuitBreaker[];
  erp: { reachable: boolean; pendingSyncFailures: number };
}

// ── Humanization ────────────────────────────────────────────────────────────
type Tone = 'ok' | 'warn' | 'down' | 'idle';
const TONE_COLOR: Record<Tone, string> = { ok: '#4CAF82', warn: '#D4772C', down: '#C7372F', idle: '#8A8F98' };

const STATE_STATUS: Record<string, { label: string; tone: Tone; hint: string }> = {
  CLOSED:      { label: 'Normal',           tone: 'ok',   hint: 'Le service répond normalement.' },
  OPEN:        { label: 'Indisponible',     tone: 'down', hint: 'Service temporairement coupé — les appels sont suspendus le temps qu\'il se rétablisse.' },
  HALF_OPEN:   { label: 'Reprise en cours', tone: 'warn', hint: 'Le service se rétablit — quelques appels de test sont en cours.' },
  DISABLED:    { label: 'Désactivé',        tone: 'idle', hint: 'Surveillance désactivée pour ce service.' },
  FORCED_OPEN: { label: 'Hors-ligne forcé', tone: 'down', hint: 'Service mis hors-ligne manuellement.' },
};
const statusFor = (state: string) => STATE_STATUS[state] ?? { label: state, tone: 'idle' as Tone, hint: '' };

function friendlyService(name: string): string {
  const n = name.toLowerCase();
  if (n.includes('driver')) return 'Service chauffeurs';
  if (n.includes('erp') || n.includes('adapter')) return 'Synchronisation ERP';
  if (n.includes('auth') || n.includes('keycloak')) return 'Authentification';
  if (n.includes('route') || n.includes('osrm') || n.includes('geocode')) return 'Itinéraires & géocodage';
  if (n.includes('notif') || n.includes('fcm')) return 'Notifications';
  if (n.includes('app') || n.includes('backend')) return 'Service principal';
  return name.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/client|feign/gi, '').trim() || name;
}

function friendlyQueue(q: string): string {
  const n = q.toLowerCase();
  if (n.includes('erp') || n.includes('sync')) return 'Synchronisation ERP';
  if (n.includes('audit')) return "Journaux d'audit";
  if (n.includes('location')) return 'Positions chauffeurs';
  if (n.includes('stat')) return 'Statistiques chauffeurs';
  if (n.includes('driver')) return 'Service chauffeurs';
  return q;
}

export default function SystemHealthPage() {
  const [data, setData] = useState<HealthPayload | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [replaying, setReplaying] = useState<string | null>(null);
  const [showTech, setShowTech] = useState(false);

  const fetchHealth = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    try {
      const res = await api.get<HealthPayload>('/api/admin/system/health');
      setData(res.data);
    } catch {
      if (!silent) showErrorToast(null, 'Échec du chargement de la santé système');
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void fetchHealth();
    const id = setInterval(() => void fetchHealth(true), 20_000);
    return () => clearInterval(id);
  }, [fetchHealth]);

  const replay = async (queue: string) => {
    setReplaying(queue);
    try {
      const res = await api.post(`/api/admin/dlq/${encodeURIComponent(queue)}/replay`, null, { params: { max: 100 } });
      showSuccessToast(`${res.data?.replayed ?? 0} opération(s) relancée(s)`);
      await fetchHealth(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, 'Échec de la relance');
    } finally {
      setReplaying(null);
    }
  };

  // ── Derived, non-technical view ───────────────────────────────────────────
  const breakers = data?.circuitBreakers ?? [];
  const dlqEntries = Object.entries(data?.dlq ?? {});
  const stuckQueues = dlqEntries.filter(([, d]) => Number(d) > 0);
  const totalStuck = stuckQueues.reduce((s, [, d]) => s + Number(d), 0);
  const downServices = breakers.filter(b => b.state === 'OPEN' || b.state === 'FORCED_OPEN');
  const recovering = breakers.filter(b => b.state === 'HALF_OPEN');
  const erpOk = data?.erp?.reachable ?? true;
  const okServices = breakers.length - downServices.length - recovering.length;

  const problems = downServices.length + stuckQueues.length + (erpOk ? 0 : 1);
  const allGood = problems === 0 && recovering.length === 0;

  const banner = allGood
    ? { tone: 'ok' as Tone, icon: IconCircleCheck, title: 'Tout fonctionne normalement', sub: 'Aucun problème détecté sur les services et les synchronisations.' }
    : problems > 0
      ? { tone: 'down' as Tone, icon: IconAlertTriangle, title: `${problems} point${problems > 1 ? 's' : ''} d'attention`, sub: 'Certains services ou opérations nécessitent une vérification.' }
      : { tone: 'warn' as Tone, icon: IconRefreshDot, title: 'Reprise en cours', sub: 'Un ou plusieurs services se rétablissent.' };
  const BannerIcon = banner.icon;

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Header */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-4 flex items-center justify-between max-w-[1400px] mx-auto">
          <div className="flex items-center gap-3">
            <IconHeartbeat size={18} className="text-[var(--brand)]" />
            <h1 className="text-[15px] font-bold text-[var(--text-primary)]">Santé du système</h1>
            <span className="text-[11px] text-[var(--text-muted)]">· actualisé automatiquement</span>
          </div>
          <RefreshButton refreshing={refreshing} onClick={() => fetchHealth()} />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1400px] mx-auto p-6 flex flex-col gap-5">

          {/* ── Headline status banner ── */}
          <div
            className="rounded-[14px] p-5 flex items-center gap-4 border"
            style={{ background: `${TONE_COLOR[banner.tone]}12`, borderColor: `${TONE_COLOR[banner.tone]}40` }}
          >
            <BannerIcon size={34} style={{ color: TONE_COLOR[banner.tone] }} className={banner.tone === 'warn' ? 'animate-pulse' : ''} />
            <div>
              <p className="text-[18px] font-black text-[var(--text-primary)] leading-tight">{banner.title}</p>
              <p className="text-[12px] text-[var(--text-secondary)] mt-0.5">{banner.sub}</p>
            </div>
          </div>

          {/* ── Summary cards ── */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <SummaryCard
              icon={IconPlugConnected}
              label="Connexion ERP (Odoo)"
              value={erpOk ? 'Opérationnel' : 'Dégradé'}
              tone={erpOk ? 'ok' : 'down'}
              foot={(data?.erp?.pendingSyncFailures ?? 0) > 0
                ? `${data?.erp?.pendingSyncFailures} synchro(s) en échec`
                : 'Synchronisations à jour'}
            />
            <SummaryCard
              icon={IconServer2}
              label="Services internes"
              value={breakers.length === 0 ? '—' : `${okServices}/${breakers.length} normaux`}
              tone={downServices.length > 0 ? 'down' : recovering.length > 0 ? 'warn' : 'ok'}
              foot={downServices.length > 0
                ? `${downServices.length} indisponible(s)`
                : recovering.length > 0 ? `${recovering.length} en reprise` : 'Tous opérationnels'}
            />
            <SummaryCard
              icon={IconInbox}
              label="Opérations à relancer"
              value={String(totalStuck)}
              tone={totalStuck > 0 ? 'down' : 'ok'}
              foot={totalStuck > 0 ? `${stuckQueues.length} file(s) concernée(s)` : 'Aucune opération bloquée'}
            />
          </div>

          {/* ── Services grid ── */}
          <div>
            <h2 className="text-[13px] font-bold text-[var(--text-primary)] mb-2.5">État des services</h2>
            {breakers.length === 0 ? (
              <EmptyHint text="Aucun service surveillé pour le moment." />
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {breakers.map(cb => {
                  const s = statusFor(cb.state);
                  return (
                    <div key={cb.name} className="card p-4 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] flex flex-col gap-2">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-[13px] font-bold text-[var(--text-primary)] truncate">{friendlyService(cb.name)}</span>
                        <StatusPill tone={s.tone} label={s.label} />
                      </div>
                      <p className="text-[11px] text-[var(--text-muted)] leading-snug">{s.hint}</p>
                      {cb.failureRate >= 0 && cb.bufferedCalls > 0 && (
                        <p className="text-[10.5px] text-[var(--text-soft)] mt-auto pt-1">
                          Taux d'erreur récent : <span className="font-bold tabular-nums" style={{ color: cb.failureRate > 50 ? TONE_COLOR.down : 'var(--text-secondary)' }}>{cb.failureRate.toFixed(0)}%</span>
                        </p>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* ── Operations to replay (only when something is stuck) ── */}
          {stuckQueues.length > 0 && (
            <div>
              <h2 className="text-[13px] font-bold text-[var(--text-primary)] mb-2.5">Opérations en échec à relancer</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {stuckQueues.map(([queue, depth]) => (
                  <div key={queue} className="card p-4 rounded-[12px] border border-[#C7372F]/30 bg-[#C7372F]/5 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-[13px] font-bold text-[var(--text-primary)] truncate">{friendlyQueue(queue)}</p>
                      <p className="text-[11px] text-[var(--text-muted)]">
                        <span className="font-bold text-[#C7372F] tabular-nums">{depth}</span> opération(s) en attente de relance
                      </p>
                    </div>
                    <button
                      onClick={() => replay(queue)}
                      disabled={replaying === queue}
                      className="shrink-0 text-[12px] font-bold px-3 py-1.5 rounded-md border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--brand)] hover:text-white transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
                    >
                      <IconReload size={13} className={replaying === queue ? 'animate-spin' : ''} />
                      {replaying === queue ? 'Relance…' : 'Relancer'}
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
              className="text-[12px] font-semibold text-[var(--text-muted)] hover:text-[var(--text-primary)] inline-flex items-center gap-1.5 transition-colors"
            >
              <IconChevronDown size={14} className={cn('transition-transform', showTech && 'rotate-180')} />
              Détails techniques
            </button>
            {showTech && (
              <div className="mt-3 rounded-lg border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
                <table className="w-full text-[11px]">
                  <thead>
                    <tr className="text-[9px] uppercase tracking-wider text-[var(--text-muted)]" style={{ background: 'var(--app-bg)' }}>
                      <th className="text-start font-bold px-4 py-2">Circuit breaker</th>
                      <th className="text-start font-bold px-4 py-2">State</th>
                      <th className="text-start font-bold px-4 py-2">Failure rate</th>
                      <th className="text-start font-bold px-4 py-2">Failed / buffered</th>
                    </tr>
                  </thead>
                  <tbody>
                    {breakers.map(cb => (
                      <tr key={cb.name} className="border-t border-[var(--border)]">
                        <td className="px-4 py-2 font-mono text-[var(--text-secondary)]">{cb.name}</td>
                        <td className="px-4 py-2 font-mono" style={{ color: TONE_COLOR[statusFor(cb.state).tone] }}>{cb.state}</td>
                        <td className="px-4 py-2 tabular-nums">{cb.failureRate < 0 ? '—' : `${cb.failureRate.toFixed(0)}%`}</td>
                        <td className="px-4 py-2 tabular-nums">{cb.failedCalls} / {cb.bufferedCalls}</td>
                      </tr>
                    ))}
                    {dlqEntries.map(([q, d]) => (
                      <tr key={q} className="border-t border-[var(--border)]">
                        <td className="px-4 py-2 font-mono text-[var(--text-secondary)]" colSpan={3}>DLQ · {q}</td>
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
    </div>
  );
}

// ── Small presentational helpers ─────────────────────────────────────────────
function SummaryCard({ icon: Icon, label, value, tone, foot }: {
  icon: any; label: string; value: string; tone: Tone; foot: string;
}) {
  return (
    <div className="card p-4 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <Icon size={16} style={{ color: TONE_COLOR[tone] }} />
        <p className="text-[10.5px] font-bold uppercase tracking-wider text-[var(--text-muted)]">{label}</p>
      </div>
      <p className="text-[20px] font-black text-[var(--text-primary)] leading-tight">{value}</p>
      <p className="text-[11px] text-[var(--text-muted)]">{foot}</p>
    </div>
  );
}

function StatusPill({ tone, label }: { tone: Tone; label: string }) {
  return (
    <span
      className="shrink-0 text-[10px] font-bold px-2 py-0.5 rounded-full inline-flex items-center gap-1 whitespace-nowrap"
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
      <span className="text-[12px] text-[var(--text-muted)]">{text}</span>
    </div>
  );
}
