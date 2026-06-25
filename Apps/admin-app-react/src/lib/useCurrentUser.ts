import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { getCurrentUser } from '@/lib/auth';
import { AdminUser } from '@/types';

/**
 * The signed-in user's own profile. `/api/admin/me` reads the display name LIVE from Keycloak (the
 * master — users self-edit it in the account console), so a self-edit shows without a re-login. We
 * keep it `staleTime: 0` so React Query's refetch-on-window-focus picks up a change the moment the
 * user returns to the app tab. The token snapshot is the placeholder for an instant first paint.
 */
export function useCurrentUser() {
  return useQuery<AdminUser>({
    queryKey: ['me'],
    queryFn: async () => (await api.get('/api/admin/me')).data as AdminUser,
    placeholderData: (getCurrentUser() ?? undefined) as AdminUser | undefined,
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
}
