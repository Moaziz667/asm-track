

import { ReactNode, useState, useEffect } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from '@/ui/feedback/Toast';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ModalRenderer } from '@/lib/modal-manager/ModalRenderer';
import { LocaleProvider } from '@/lib/LocaleContext';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { oidcConfig, syncSession } from '@/lib/oidcConfig';

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

