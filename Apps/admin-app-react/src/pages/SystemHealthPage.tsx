import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { IconHeartbeat, IconReload, IconAlertTriangle, IconCircleCheck } from '@tabler/icons-react';
import { cn } from '@/lib/utils';

interface CircuitBreaker { name: string; state: string; failureRate: number; bufferedCalls: number; failedCalls: number; notPermittedCalls: number; }
interface HealthPayload {
  dlq: Record<string, number>;
  circuitBreakers: CircuitBreaker[];
  erp: { reachable: boolean; pendingSyncFailures: number };
}

const CB_STATE_COLOR: Record<string, string> = {
  CLOSED: '#4CAF82', OPEN: '#C7372F', HALF_OPEN: '#D4772C', DISABLED: '#8A8F98', FORCED_OPEN: '#C7372F',
};

export default function SystemHealthPage() {
  const [data, setData] = useState<HealthPayload | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [replaying, setReplaying] = useState<string | null>(null);

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
      showSuccessToast(`${res.data?.replayed ?? 0} message(s) rejoué(s)`);
      await fetchHealth(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, 'Échec du rejeu');
    } finally {
      setReplaying(null);
    }
  };

  const dlqEntries = Object.entries(data?.dlq ?? {});
  const erpOk = data?.erp?.reachable ?? true;

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-4 flex items-center justify-between max-w-[1800px] mx-auto">
          <div className="flex items-center gap-3">
            <IconHeartbeat size={18} className="text-[var(--brand)]" />
            <h1 className="text-[15px] font-bold text-[var(--text-primary)]">Santé du système</h1>
          </div>
          <RefreshButton refreshing={refreshing} onClick={() => fetchHealth()} />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1800px] mx-auto p-6 flex flex-col gap-6">
          {/* ERP connectivity */}
          <div className="grid grid-cols-2 gap-4">
            <div className="card p-4 border border-[var(--border)] rounded-[12px] bg-[var(--surface)] flex items-center gap-3">
              {erpOk ? <IconCircleCheck size={26} className="text-[#4CAF82]" /> : <IconAlertTriangle size={26} className="text-[#C7372F]" />}
              <div>
                <p className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">Connectivité ERP</p>
                <p className="text-[18px] font-black text-[var(--text-primary)]">{erpOk ? 'Opérationnel' : 'Dégradé'}</p>
              </div>
            </div>
            <div className="card p-4 border border-[var(--border)] rounded-[12px] bg-[var(--surface)]">
              <p className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">Échecs de synchro ERP en attente</p>
              <p className={cn('text-[28px] font-black tabular-nums', (data?.erp?.pendingSyncFailures ?? 0) > 0 ? 'text-[#C7372F]' : 'text-[var(--text-primary)]')}>
                {data?.erp?.pendingSyncFailures ?? 0}
              </p>
            </div>
          </div>

          {/* Circuit breakers */}
          <div>
            <h2 className="text-[13px] font-bold text-[var(--text-primary)] mb-2">Disjoncteurs (Circuit Breakers)</h2>
            <div className="rounded-lg overflow-hidden border border-[var(--border)] bg-[var(--surface)]">
              <table className="w-full text-[12px]">
                <thead>
                  <tr className="text-[10px] uppercase tracking-wider text-[var(--text-muted)]" style={{ background: 'var(--app-bg)' }}>
                    <th className="text-start font-bold px-4 py-2.5">Service</th>
                    <th className="text-start font-bold px-4 py-2.5">État</th>
                    <th className="text-start font-bold px-4 py-2.5">Taux d'échec</th>
                    <th className="text-start font-bold px-4 py-2.5">Appels (échoués / total)</th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.circuitBreakers ?? []).length === 0 ? (
                    <tr><td colSpan={4} className="px-4 py-8 text-center text-[var(--text-muted)]">Aucun disjoncteur actif</td></tr>
                  ) : data!.circuitBreakers.map(cb => (
                    <tr key={cb.name} className="border-t border-[var(--border)]">
                      <td className="px-4 py-2.5 font-mono text-[11px] text-[var(--text-secondary)]">{cb.name}</td>
                      <td className="px-4 py-2.5">
                        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full" style={{ background: `${CB_STATE_COLOR[cb.state] ?? '#888'}1a`, color: CB_STATE_COLOR[cb.state] ?? '#888' }}>
                          {cb.state}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-[var(--text-secondary)]">{cb.failureRate < 0 ? '—' : `${cb.failureRate.toFixed(0)}%`}</td>
                      <td className="px-4 py-2.5 text-[var(--text-muted)]">{cb.failedCalls} / {cb.bufferedCalls}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* DLQ */}
          <div>
            <h2 className="text-[13px] font-bold text-[var(--text-primary)] mb-2">Files de messages mortes (DLQ)</h2>
            <div className="rounded-lg overflow-hidden border border-[var(--border)] bg-[var(--surface)]">
              <table className="w-full text-[12px]">
                <thead>
                  <tr className="text-[10px] uppercase tracking-wider text-[var(--text-muted)]" style={{ background: 'var(--app-bg)' }}>
                    <th className="text-start font-bold px-4 py-2.5">File</th>
                    <th className="text-start font-bold px-4 py-2.5">Messages bloqués</th>
                    <th className="px-4 py-2.5" />
                  </tr>
                </thead>
                <tbody>
                  {dlqEntries.length === 0 ? (
                    <tr><td colSpan={3} className="px-4 py-8 text-center text-[var(--text-muted)]">Aucune file</td></tr>
                  ) : dlqEntries.map(([queue, depth]) => (
                    <tr key={queue} className="border-t border-[var(--border)]">
                      <td className="px-4 py-2.5 font-mono text-[11px] text-[var(--text-secondary)]">{queue}</td>
                      <td className="px-4 py-2.5">
                        <span className={cn('font-bold tabular-nums', Number(depth) > 0 ? 'text-[#C7372F]' : 'text-[var(--text-muted)]')}>{depth}</span>
                      </td>
                      <td className="px-4 py-2.5 text-end">
                        {Number(depth) > 0 && (
                          <button onClick={() => replay(queue)} disabled={replaying === queue}
                                  className="text-[11px] font-bold px-2.5 py-1 rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] inline-flex items-center gap-1 text-[var(--brand)]">
                            <IconReload size={12} /> {replaying === queue ? 'Rejeu…' : 'Rejouer'}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
