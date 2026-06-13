import { useMemo } from 'react';
import { getDayBucket } from '@/lib/sla';
import type { DeliveryRow, QuickView } from './types';

interface ListOpts {
  query: string;
  quickView: QuickView;
  sortAsc: boolean;
  groupByClient: boolean;
  groupByZone: boolean;
  groupByStatus: boolean;
}

/**
 * Pure data shaping for the deliveries list: quick-view + text filtering, then the SLA-risk-first
 * sort (with optional status/zone/client grouping), plus the quick-filter counts. Extracted from
 * DeliveriesPage verbatim so the page is an orchestrator.
 */
export function useDeliveryListData(rows: DeliveryRow[], opts: ListOpts) {
  const { query, quickView, sortAsc, groupByClient, groupByZone, groupByStatus } = opts;

  const filteredRows = useMemo(() => {
    const q = query.trim().toLowerCase();

    const result = rows.filter((item: DeliveryRow) => {
      const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
      const bucket = getDayBucket(item.scheduledAt);

      if (quickView === 'needsPinning') return !item.dropoffPinned;
      if (quickView === 'unassigned' && Boolean(item.driverId)) return false;
      if (quickView === 'inTransit' && item.status !== 'IN_TRANSIT') return false;
      if (quickView === 'completed' && item.status !== 'DELIVERED') return false;
      if (quickView === 'failed' && !['FAILED', 'CANCELLED'].includes(item.status)) return false;
      if (quickView === 'overdue') return isPending && bucket === 'overdue';
      if (quickView === 'today') return isPending && bucket === 'today';
      if (quickView === 'future') return isPending && bucket === 'future';

      if (!q) return true;
      return [item.rowId, item.orderId, item.erpOrderId, item.orderRef, item.erpId, item.clientName, item.dropoffCity, item.driverName, item.routeName, item.status]
        .some(v => String(v ?? '').toLowerCase().includes(q));
    });

    // SLA risk rank — the dispatcher's first question. Pending overdue/breached first, then
    // at-risk/today, then the rest. Skipped while an explicit grouping is active. Failed-but-late
    // uses worst past health so it still ranks urgent.
    const riskRank = (d: DeliveryRow): number => {
      const pending = !['DELIVERED', 'CANCELLED', 'FAILED'].includes(d.status ?? '');
      const bucket = getDayBucket((d as any).scheduledAt);
      const h = ((d as any).slaHealth && (d as any).slaHealth !== 'NONE')
        ? (d as any).slaHealth : (d as any).slaWorstHealth;
      if (h === 'BREACHED' || h === 'LATE' || (pending && bucket === 'overdue')) return 0;
      if (h === 'AT_RISK' || (pending && bucket === 'today')) return 1;
      return 2;
    };
    const grouped = groupByStatus || groupByZone || groupByClient;

    result.sort((a: DeliveryRow, b: DeliveryRow) => {
      if (groupByStatus) {
        const cmp = (a.status ?? '').localeCompare(b.status ?? '');
        if (cmp !== 0) return cmp;
      }
      if (groupByZone) {
        const cmp = (a.zoneName ?? '').localeCompare(b.zoneName ?? '');
        if (cmp !== 0) return cmp;
      }
      if (groupByClient) {
        const cmp = (a.clientName ?? '').localeCompare(b.clientName ?? '');
        if (cmp !== 0) return cmp;
      }
      if (!grouped) {
        const r = riskRank(a) - riskRank(b);
        if (r !== 0) return r;
      }
      const da = a.createdAt ?? '';
      const db = b.createdAt ?? '';
      const cmp = da.localeCompare(db);
      return sortAsc ? cmp : -cmp;
    });

    return result;
  }, [query, quickView, rows, sortAsc, groupByClient, groupByZone, groupByStatus]);

  const quickCounts = useMemo(() => {
    let needsPinning = 0, unassigned = 0, inTransit = 0, completed = 0, failed = 0;
    let overdue = 0, today = 0, future = 0;

    rows.forEach((item: DeliveryRow) => {
      if (!item.dropoffPinned) needsPinning++;
      if (!item.driverId) unassigned++;
      if (item.status === 'IN_TRANSIT') inTransit++;
      if (item.status === 'DELIVERED') completed++;
      if (['FAILED', 'CANCELLED'].includes(item.status)) failed++;

      const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
      if (isPending && item.scheduledAt) {
        const bucket = getDayBucket(item.scheduledAt);
        if (bucket === 'overdue') overdue++;
        else if (bucket === 'today') today++;
        else if (bucket === 'future') future++;
      }
    });
    return { all: rows.length, needsPinning, unassigned, inTransit, completed, failed, overdue, today, future };
  }, [rows]);

  return { filteredRows, quickCounts };
}
