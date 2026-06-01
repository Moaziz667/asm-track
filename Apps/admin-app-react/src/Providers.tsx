

import { ReactNode, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from '@/ui/feedback/Toast';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ModalRenderer } from '@/lib/modal-manager/ModalRenderer';
import { LocaleProvider } from '@/lib/LocaleContext';

type ProvidersProps = {
  children: ReactNode;
};

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
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <LocaleProvider>
          {children}
          <ModalRenderer />
          <Toaster />
        </LocaleProvider>
      </TooltipProvider>
    </QueryClientProvider>
  );
}

