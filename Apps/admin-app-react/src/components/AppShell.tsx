
import { useEffect, useState, ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { cn } from '@/lib/utils';
import AlertsProvider from './AlertsProvider';
import { RealtimeProvider } from './RealtimeProvider';
import SessionRevocationWatcher from './SessionRevocationWatcher';
import TopNav from './TopNav';
import { AppSidebar } from './Sidebar';
import { BreadcrumbProvider } from '@/lib/breadcrumb';
import { SidebarProvider, SidebarInset } from './ui/sidebar';

// ── Shell ─────────────────────────────────────────────────────────────────────

export default function AppShell({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const [mounted, setMounted] = useState(false);

  const isAuthPage = pathname === '/login' || pathname === '/' || pathname?.startsWith('/track');
  const lockViewport = pathname?.startsWith('/route-builder') || pathname?.startsWith('/dispatch');

  useEffect(() => {
    setMounted(true);
  }, []);

  if (isAuthPage) return <>{children}</>;

  return (
    <SidebarProvider>
      <BreadcrumbProvider>
        <RealtimeProvider>
        <SessionRevocationWatcher />
        <AlertsProvider>
          {/* Fixed sidebar */}
          <AppSidebar />

          {/* Main */}
          <SidebarInset className="flex flex-col flex-1 min-w-0 min-h-dvh bg-[var(--app-bg)]">
            <TopNav />
            <div
              className={cn(
                'flex-1 overflow-x-hidden',
                lockViewport
                  ? 'overflow-y-hidden h-[calc(100dvh-56px)]'
                  : 'overflow-y-auto',
              )}
            >
              <div className="w-full h-full">
                {children}
              </div>
            </div>
          </SidebarInset>
        </AlertsProvider>
        </RealtimeProvider>
      </BreadcrumbProvider>
    </SidebarProvider>
  );
}

