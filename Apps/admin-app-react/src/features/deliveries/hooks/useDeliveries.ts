
import { useState, useCallback, useEffect } from 'react';
import { api } from '@/lib/api';
import { showErrorToast } from '@/lib/toast-service';
import type { Delivery, DeliveryStatus, Driver, Zone } from '@/types';

export interface DeliveriesFilters {
  query?: string;
  status?: DeliveryStatus | '';
  date?: string;
  driverId?: string;
  zoneId?: string;
  quickView?: 'all' | 'needsPinning' | 'unassigned' | 'inTransit' | 'completed' | 'failed';
}

function isUuid(v: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);
}

/**
 * Data hook for the /deliveries page.
 * Handles paginated fetch, filter state, and meta (drivers, zones).
 */
export function useDeliveries(initialFilters: DeliveriesFilters = {}) {
  const [rows, setRows] = useState<Delivery[]>([]);
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [zones, setZones] = useState<Zone[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(25);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const [filters, setFilters] = useState<DeliveriesFilters>(initialFilters);

  // Resolve zone name → id
  const effectiveZoneId = ((): string => {
    const raw = (filters.zoneId ?? '').trim();
    if (!raw || isUuid(raw)) return raw;
    const byName = zones.find((z) => (z as any).name?.trim().toLowerCase() === raw.toLowerCase());
    return (byName as any)?.id ?? '';
  })();

  const fetchDeliveries = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true);
    else setLoading(true);

    try {
      const params: Record<string, any> = { page, size: pageSize };
      if (filters.status) params.status = filters.status;
      if (filters.date) params.date = filters.date;
      if (filters.driverId) params.driverId = filters.driverId;
      if (effectiveZoneId) params.zoneId = effectiveZoneId;
      if (filters.quickView === 'needsPinning') params.unpinned = 'true';
      if (filters.query?.trim()) params.search = filters.query.trim();

      const res = await api.get('/api/admin/deliveries', { params });
      const raw = res.data?.content ?? res.data;
      const list: Delivery[] = Array.isArray(raw) ? raw : [];
      const getId = (d: Delivery) => String((d as any).deliveryId ?? (d as any).id ?? '');
      setRows(list.map((d) => ({ ...d, rowId: getId(d) })));
      setTotalElements(res.data?.totalElements ?? list.length);
      setTotalPages(res.data?.totalPages ?? 1);
    } catch {
      showErrorToast(undefined, 'errorDataLoadFailed');
      setRows([]);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [page, pageSize, filters, effectiveZoneId]);

  const fetchMeta = useCallback(async () => {
    try {
      const [dR, zR] = await Promise.all([
        api.get('/api/admin/fleet/drivers'),
        api.get('/api/v1/zones/active').catch(() => ({ data: [] })),
      ]);
      setDrivers(Array.isArray(dR.data) ? dR.data : []);
      setZones(Array.isArray(zR.data) ? zR.data : []);
    } catch {}
  }, []);

  useEffect(() => { void fetchDeliveries(); }, [fetchDeliveries]);
  useEffect(() => { void fetchMeta(); }, [fetchMeta]);

  const refresh = useCallback(() => void fetchDeliveries(), [fetchDeliveries]);
  const silentRefresh = useCallback(() => void fetchDeliveries(true), [fetchDeliveries]);

  return {
    rows,
    drivers,
    zones,
    loading,
    refreshing,
    page,
    setPage,
    pageSize,
    totalElements,
    totalPages,
    filters,
    setFilters,
    refresh,
    silentRefresh,
  };
}
