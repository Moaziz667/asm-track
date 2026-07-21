import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

/**
 * Shared `driverId → avatar URL` map for surfaces that only have a driver id (handoff cards,
 * dashboard fleet, stats, reports). One cached fetch, deduped across every `<DriverAvatarById>`.
 */
export function useDriverAvatars(): Record<string, string> {
  const { data } = useQuery({
    queryKey: ['driver-avatars'],
    queryFn: async () => {
      const res = await api.get<Record<string, string>>('/admin/drivers/avatars');
      return res.data ?? {};
    },
    staleTime: 5 * 60_000,
    gcTime: 10 * 60_000,
  });
  return data ?? {};
}
