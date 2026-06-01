
import { useCallback, useEffect, useState } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { api } from '@/lib/api';

type SlaSummary = {
  onTime: number;
  late: number;
  total: number;
};

export default function TopStatusBar() {
  const router = useRouter();
  const [sla, setSla] = useState<SlaSummary | null>(null);
  const [unassigned, setUnassigned] = useState(0);

  const refresh = useCallback(async () => {
    try {
      const [slaRes, deliveryRes] = await Promise.all([
        api.get('/api/admin/routes/sla-summary').catch(() => ({ data: null })),
        api.get('/api/admin/deliveries', { params: { status: 'UNSCHEDULED', size: 1 } }).catch(() => ({ data: null })),
      ]);

      const slaData = slaRes.data as SlaSummary | null;
      setSla(slaData && typeof slaData === 'object' ? slaData : null);

      const totalWaiting = Number(deliveryRes.data?.totalElements ?? 0);
      setUnassigned(Number.isFinite(totalWaiting) ? totalWaiting : 0);
    } catch {
      // Ignore status bar refresh failures
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 30000);
    return () => clearInterval(id);
  }, [refresh]);

  const late = sla?.late ?? 0;
  const total = sla?.total ?? 0;
  const allClear = late === 0 && unassigned === 0;

  if (allClear) {
    return (
      <div style={{ height: 38, minHeight: 38, borderBottom: '1px solid rgba(169,180,185,0.15)', background: 'rgba(247,249,251,0.82)', display: 'flex', alignItems: 'center', padding: '0 16px' }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: '#15803d' }}>All systems stable. {total} active route stops on track.</span>
      </div>
    );
  }

  return (
    <div style={{ height: 38, minHeight: 38, borderBottom: '1px solid rgba(169,180,185,0.15)', background: 'rgba(247,249,251,0.82)', display: 'flex', alignItems: 'center', padding: '0 12px', gap: 8, overflowX: 'auto' }}>
      {late > 0 && (
        <button
          onClick={() => router('/command-center?severity=critical')}
          style={{ height: 26, borderRadius: 999, border: '1px solid rgba(185,28,28,0.18)', background: 'rgba(185,28,28,0.1)', color: '#b91c1c', fontSize: 11, fontWeight: 700, padding: '0 10px', cursor: 'pointer', whiteSpace: 'nowrap' }}
        >
          {late} late
        </button>
      )}

      {unassigned > 0 && (
        <button
          onClick={() => router('/deliveries?status=UNSCHEDULED')}
          style={{ height: 26, borderRadius: 999, border: '1px solid rgba(81,95,116,0.2)', background: 'rgba(81,95,116,0.1)', color: '#515f74', fontSize: 11, fontWeight: 700, padding: '0 10px', cursor: 'pointer', whiteSpace: 'nowrap' }}
        >
          {unassigned} unplanned orders
        </button>
      )}
    </div>
  );
}

