import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listRemittances, getCirculation,
  type CashRemittance, type CashRemittanceStatus,
} from '@/lib/api/cash';

export const CASH_QUERY_KEY = ['cash'] as const;

interface RemittanceQuery {
  status?: CashRemittanceStatus;
  page: number;
  size: number;
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
    queryKey: [...CASH_QUERY_KEY, 'remittances', q.status ?? 'PENDING', q.page, q.size],
    queryFn: () => listRemittances({ status: q.status, page: q.page, size: q.size }),
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
