import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listRemittances, getCirculation, getRemittanceCounts, getRemittanceCollections,
  getOutstandingByDriver,
  type CashRemittance, type CashRemittanceStatus,
} from '@/lib/api/cash';

export const CASH_QUERY_KEY = ['cash'] as const;

interface RemittanceQuery {
  status?: CashRemittanceStatus;
  page: number;
  size: number;
  /** Spring's `property,direction`. Sorting on the server so it reaches past the current page. */
  sort: string;
}

/**
 * Handovers waiting on a human.
 *
 * <p>No `staleTime`: unlike a driver list or a reason catalogue, this changes because somebody is
 * standing at the depot right now, and a cashier looking at a cached worklist would count money
 * against figures that have already moved.
 */
export function useRemittances(q: RemittanceQuery) {
  return useQuery({
    queryKey: [...CASH_QUERY_KEY, 'remittances', q.status ?? 'PENDING', q.page, q.size, q.sort],
    queryFn: () => listRemittances({ status: q.status, page: q.page, size: q.size, sort: q.sort }),
    retry: 1,
  });
}

/** The headline figure — collected and not yet handed over. */
export function useCashCirculation() {
  return useQuery({
    queryKey: [...CASH_QUERY_KEY, 'circulation'],
    queryFn: getCirculation,
    retry: 1,
    staleTime: 30_000,
  });
}

/**
 * Who is holding how much, right now.
 *
 * <p>The endpoint has existed since the module shipped and no screen called it, so a collection was
 * invisible between the customer's door and the driver's declaration: a cashier looking for the
 * 108 DT a driver had just taken found an empty desk, because the desk only ever listed handovers.
 * Same freshness as the headline figure it breaks down.
 */
export function useOutstandingByDriver() {
  return useQuery({
    queryKey: [...CASH_QUERY_KEY, 'outstanding-by-driver'],
    queryFn: getOutstandingByDriver,
    retry: 1,
    staleTime: 30_000,
  });
}

/** Figures for the filter tabs. Same freshness as the list they label. */
export function useRemittanceCounts() {
  return useQuery({
    queryKey: [...CASH_QUERY_KEY, 'counts'],
    queryFn: getRemittanceCounts,
    retry: 1,
  });
}

/**
 * What one handover is made of. Fetched only when a row is opened.
 *
 * <p>A desk with twenty-five rows would otherwise issue twenty-five requests to render a table
 * nobody has asked to look inside yet.
 */
export function useRemittanceCollections(remittanceId: string | null) {
  return useQuery({
    queryKey: [...CASH_QUERY_KEY, 'collections', remittanceId],
    queryFn: () => getRemittanceCollections(remittanceId!),
    enabled: remittanceId !== null,
    retry: 1,
  });
}

/**
 * Invalidate everything cash-related after an action.
 *
 * <p>Counting one handover changes the fleet total as well as the row, so refreshing only the list
 * would leave the figure at the top of the page contradicting the table under it.
 */
export function useRefreshCash() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: CASH_QUERY_KEY });
}

export type { CashRemittance };
