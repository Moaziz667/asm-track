import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export interface SlaSettings {
  assignLeadtimeMinutes: number;
  assignLimitMinutes: number;
  pickupLimitMinutes: number;
  waitingLimitMinutes: number;
}

const DEFAULTS: SlaSettings = {
  assignLeadtimeMinutes: 120,
  assignLimitMinutes: 20,
  pickupLimitMinutes: 15,
  waitingLimitMinutes: 15,
};

const SLA_SETTINGS_KEY = ['sla-settings'] as const;

async function fetchSlaSettings(): Promise<SlaSettings> {
  const res = await api.get<Record<string, string>>('/api/admin/reports/settings');
  const d = res.data ?? {};
  const parse = (key: string, fallback: number) => {
    const v = parseInt(d[key] ?? '', 10);
    return Number.isFinite(v) && v > 0 ? v : fallback;
  };
  return {
    assignLeadtimeMinutes: parse('ops.sla.assign-leadtime-minutes', DEFAULTS.assignLeadtimeMinutes),
    assignLimitMinutes: parse('ops.sla.assign-limit-minutes', DEFAULTS.assignLimitMinutes),
    pickupLimitMinutes: parse('ops.sla.pickup-limit-minutes', DEFAULTS.pickupLimitMinutes),
    waitingLimitMinutes: parse('ops.sla.waiting-limit-minutes', DEFAULTS.waitingLimitMinutes),
  };
}

export function useSlaSettings(): SlaSettings {
  const { data } = useQuery({
    queryKey: SLA_SETTINGS_KEY,
    queryFn: fetchSlaSettings,
    staleTime: 5 * 60 * 1000,
    placeholderData: DEFAULTS,
  });
  return data ?? DEFAULTS;
}
