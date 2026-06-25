import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { getCurrentUser } from '@/lib/auth';
import { AdminUser } from '@/types';

/**
 * The signed-in user's own profile, read live from the DB (`/api/admin/me`) instead of the JWT.
 * A name/email change is persisted to the DB synchronously, so sourcing the displayed identity from
 * here makes it show immediately — no need to wait for a new token (re-login). The token snapshot is
 * used as placeholder for an instant first paint. Invalidate `['me']` after editing the own profile.
 */
export function useCurrentUser() {
  return useQuery<AdminUser>({
    queryKey: ['me'],
    queryFn: async () => (await api.get('/api/admin/me')).data as AdminUser,
    placeholderData: (getCurrentUser() ?? undefined) as AdminUser | undefined,
    staleTime: 30_000,
  });
}
