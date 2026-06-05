

import { ReactNode, useState, useEffect } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from '@/ui/feedback/Toast';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ModalRenderer } from '@/lib/modal-manager/ModalRenderer';
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
            <ModalRenderer />
            <Toaster />
          </LocaleProvider>
        </TooltipProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
}

