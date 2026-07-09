import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Range } from './useDashboardData';

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

function shiftRange(range: Range, from?: string, to?: string): { from?: string; to?: string } | { range: string } {
  if (range === 'custom' && from && to) {
    const start = new Date(from);
    const end = new Date(to);
    const duration = end.getTime() - start.getTime();
    const prevEnd = new Date(start.getTime() - 1);
    const prevStart = new Date(prevEnd.getTime() - duration);
    return {
      from: prevStart.toISOString().slice(0, 10) + 'T00:00:00',
      to: prevEnd.toISOString().slice(0, 10) + 'T23:59:59',
    };
  }

  const shiftMap: Record<string, string> = {
    today: 'yesterday',
    yesterday: 'yesterday',
    last7d: 'last30d',
    last30d: 'last30d',
  };

  return { range: shiftMap[range] || 'yesterday' };
}

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

export function useZoneComparison(range: Range, from?: string, to?: string) {
  const currentParams = range === 'custom' && from && to
    ? { from: `${from}T00:00:00`, to: `${to}T23:59:59` }
    : { range };

  const prevParams = shiftRange(range, from, to);

  const { data: currentData, isLoading } = useQuery({
    queryKey: ['zone-heatmap-current', range, from ?? '', to ?? ''],
    queryFn: () =>
      api.get('/api/admin/reports/zone-heatmap', { params: currentParams })
        .then(r => r.data?.points ?? [])
        .catch(() => []),
    staleTime: 30_000,
  });

  const { data: previousData } = useQuery({
    queryKey: ['zone-heatmap-prev', range, from ?? '', to ?? ''],
    queryFn: () =>
      api.get('/api/admin/reports/zone-heatmap', { params: prevParams })
        .then(r => r.data?.points ?? [])
        .catch(() => []),
    staleTime: 30_000,
  });

  const zones: ZoneData[] = useMemo(() => {
    const current = aggregateByZone(currentData ?? []);
    const previous = aggregateByZone(previousData ?? []);

    const result: ZoneData[] = [];
    for (const [zoneId, data] of current) {
      const prev = previous.get(zoneId);
      const prevOrders = prev?.orders ?? 0;
      const delta = prevOrders > 0
        ? ((data.orders - prevOrders) / prevOrders) * 100
        : null;

      result.push({
        zoneId,
        zoneName: data.zoneName,
        zoneColor: data.zoneColor,
        orders: data.orders,
        zipcodes: data.zipcodes,
        previousOrders: prevOrders,
        delta,
        rank: 0,
      });
    }

    result.sort((a, b) => b.orders - a.orders);
    result.forEach((z, i) => { z.rank = i + 1; });

    return result;
  }, [currentData, previousData]);

  const totalOrders = useMemo(() => zones.reduce((s, z) => s + z.orders, 0), [zones]);

  return { zones, totalOrders, isLoading };
}
