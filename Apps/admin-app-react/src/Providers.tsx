

import { ReactNode, useState, useEffect, useRef } from 'react';
import { QueryClient, QueryClientProvider, QueryCache } from '@tanstack/react-query';
import { showErrorToast } from '@/lib/ui/toast-service';
import { Toaster } from '@/components/feedback/Toast';
import { TooltipProvider } from '@/components/ui/tooltip';
import { LocaleProvider } from '@/lib/i18n/LocaleContext';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { oidcConfig, syncSession } from '@/lib/api/oidcConfig';
import { registerTokenRefresher, registerLogoutHandler } from '@/lib/api';

type ProvidersProps = {
  children: ReactNode;
};

/**
 * Keeps localStorage (read by the axios layer) in sync with the live OIDC
 * session, including tokens issued by automaticSilentRenew.
 */
function AuthSync() {
  const auth = useAuth();
  useEffect(() => {
    if (auth.isAuthenticated) {
      syncSession(auth.user);
    }
  }, [auth.isAuthenticated, auth.user]);

  /*
    Register the refresher ONCE, and let it read the live auth through a ref.

    Keyed on [auth], this effect re-ran on every auth state change — and its cleanup sets the
    refresher to null before the next one registers. A 401 landing in that window found no
    refresher, `runRefresh()` returned false, and the axios layer concluded the token was dead and
    hard-logged the user out. The window opens precisely when auth state is churning, which is
    exactly when tokens are being renewed: the moments the retry exists for are the moments it was
    unavailable.

    `useAuth()` returns a fresh object whenever anything in the session changes, so [auth] was never
    a meaningful dependency — only a guarantee of churn.
  */
  const authRef = useRef(auth);
  authRef.current = auth;

  useEffect(() => {
    registerTokenRefresher(async () => {
      const user = await authRef.current.signinSilent();
      syncSession(user);
      return user;
    });
    registerLogoutHandler(() => { void authRef.current.removeUser(); });
    return () => {
      registerTokenRefresher(null);
      registerLogoutHandler(null);
    };
  }, []);

  return null;
}

export default function Providers({ children }: ProvidersProps) {
  const [queryClient] = useState(
    () => new QueryClient({
      // Query failures (list/detail loads) were previously silent: on error the
      // hooks fall back to [] and most pages render an empty state, so a failed
      // fetch looked identical to "no data". Surface it once, centrally, so the
      // user sees a real error instead of a misleading empty table. Mutations
      // keep their own onError toasts; QueryCache.onError never fires for them.
      // A query opts out with meta.silent (e.g. background polls) or overrides
      // the copy with meta.errorKey.
      queryCache: new QueryCache({
        onError: (_err, query) => {
          const meta = query.meta as { silent?: boolean; errorKey?: string } | undefined;
          if (meta?.silent) return;
          // 401 is handled by the axios layer (silent renew / logout redirect).
          const status = (_err as { response?: { status?: number } })?.response?.status;
          if (status === 401) return;
          showErrorToast(null, meta?.errorKey ?? 'errorDataLoadFailed');
        },
      }),
      defaultOptions: {
        queries: {
          retry: 1,
          refetchOnWindowFocus: false,
        },
      },
    })
  );

  return (
    <AuthProvider {...oidcConfig}>
      <AuthSync />
      <QueryClientProvider client={queryClient}>
        <TooltipProvider>
          <LocaleProvider>
            {children}
            <Toaster />
          </LocaleProvider>
        </TooltipProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
}

