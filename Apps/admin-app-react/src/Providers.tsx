

import { ReactNode, useState, useEffect } from 'react';
import { QueryClient, QueryClientProvider, QueryCache } from '@tanstack/react-query';
import { showErrorToast } from '@/lib/toast-service';
import { Toaster } from '@/ui/feedback/Toast';
import { TooltipProvider } from '@/components/ui/tooltip';
import { LocaleProvider } from '@/lib/LocaleContext';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { oidcConfig, syncSession } from '@/lib/oidcConfig';
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

  // Let the axios layer drive a silent renew on 401 (single-flight) and retry,
  // instead of hard-redirecting on the first expired token.
  useEffect(() => {
    registerTokenRefresher(async () => {
      const user = await auth.signinSilent();
      syncSession(user);
      return user;
    });
    registerLogoutHandler(() => { void auth.removeUser(); });
    return () => {
      registerTokenRefresher(null);
      registerLogoutHandler(null);
    };
  }, [auth]);

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

