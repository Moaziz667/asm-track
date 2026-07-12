import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Range } from './useDashboardData';
import { analyticsDateParams } from '@/lib/analytics/date-params';

type ZoneData = {
  zoneId: string;
  zoneName: string;
  zoneColor: string;
  orders: number;
  zipcodes: number;
  previousOrders: number;
  delta: number | null;
  rank: number;
};

function aggregateByZone(points: Array<{
  zoneId: string;
  zoneName: string;
  zoneColor: string;
  ordersCount: number;
}>): Map<string, { orders: number; zipcodes: number; zoneName: string; zoneColor: string }> {
  const byZone = new Map<string, { orders: number; zipcodes: number; zoneName: string; zoneColor: string }>();
  for (const p of points) {
    const existing = byZone.get(p.zoneId);
    if (existing) {
      existing.orders += p.ordersCount;
      existing.zipcodes += 1;
    } else {
      byZone.set(p.zoneId, { orders: p.ordersCount, zipcodes: 1, zoneName: p.zoneName, zoneColor: p.zoneColor });
    }
  }
  return byZone;
}

/**
 * Zone demand + period-over-period delta. Single request: the server resolves the preceding window of
 * equal length (Tunis-anchored, PeriodResolver) via compare=true and returns previousOrdersByZone — so
 * the delta is correct for every preset, unlike the old client-side range shifting.
 */
export function useZoneComparison(range: Range, from?: string, to?: string) {
  const params = { ...analyticsDateParams(range, from, to), compare: true };

  const { data, isLoading } = useQuery({
    queryKey: ['zone-heatmap', range, from ?? '', to ?? ''],
    queryFn: () =>
      api.get('/api/admin/reports/zone-heatmap', { params })
        .then(r => r.data as { points?: Array<{ zoneId: string; zoneName: string; zoneColor: string; ordersCount: number }>; previousOrdersByZone?: Record<string, number> })
        .catch(() => ({ points: [], previousOrdersByZone: {} })),
    staleTime: 30_000,
  });

  const zones: ZoneData[] = useMemo(() => {
    const current = aggregateByZone(data?.points ?? []);
    const prevByZone: Record<string, number> = data?.previousOrdersByZone ?? {};

    const result: ZoneData[] = [];
    for (const [zoneId, d] of current) {
      const prevOrders = prevByZone[zoneId] ?? 0;
      const delta = prevOrders > 0
        ? ((d.orders - prevOrders) / prevOrders) * 100
        : null;

      result.push({
        zoneId,
        zoneName: d.zoneName,
        zoneColor: d.zoneColor,
        orders: d.orders,
        zipcodes: d.zipcodes,
        previousOrders: prevOrders,
        delta,
        rank: 0,
      });
    }

    result.sort((a, b) => b.orders - a.orders);
    result.forEach((z, i) => { z.rank = i + 1; });

    return result;
  }, [data]);

  const totalOrders = useMemo(() => zones.reduce((s, z) => s + z.orders, 0), [zones]);

  return { zones, totalOrders, isLoading };
}
