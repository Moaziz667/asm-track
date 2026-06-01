
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { RouteReport } from '../report-types';

/**
 * Fetches the immutable closure report for a CLOSED route.
 * The result is `staleTime: Infinity` — the snapshot never changes.
 *
 * Pass `enabled=false` (or `routeStatus !== 'CLOSED'`) to skip the fetch.
 */
export function useRouteReport(routeId: string | undefined, enabled = true) {
  return useQuery<RouteReport>({
    queryKey: ['route-report', routeId],
    queryFn: async () => {
      const res = await api.get<RouteReport>(`/api/admin/routes/${routeId}/report`);
      return res.data;
    },
    enabled: Boolean(routeId) && enabled,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: 1,
  });
}

export async function downloadRouteReportPdf(routeId: string, fileName?: string) {
  const res = await api.get(`/api/admin/routes/${routeId}/report/pdf`, {
    responseType: 'blob',
  });
  const url = URL.createObjectURL(res.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName ?? `rapport-tournee-${routeId}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
