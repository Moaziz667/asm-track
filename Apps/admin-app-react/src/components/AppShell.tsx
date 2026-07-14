
import { useEffect, useState, ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { cn } from '@/lib/utils';
import AlertsProvider from './AlertsProvider';
import { RealtimeProvider } from './RealtimeProvider';
import SessionRevocationWatcher from './SessionRevocationWatcher';
import { AppSidebar } from './Sidebar';
import { BreadcrumbProvider } from '@/lib/ui/breadcrumb';
import { SidebarProvider, SidebarInset } from './ui/sidebar';
import GlobalFloatingMap from './GlobalFloatingMap';
import ErrorBoundary from './ErrorBoundary';
import ContentBreadcrumb from './ContentBreadcrumb';
import AlertBell from './AlertBell';
import GlobalSearch from './GlobalSearch';

// ── Shell ─────────────────────────────────────────────────────────────────────

import { useBreakpoint } from '@/hooks/use-mobile';

export default function AppShell({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const [mounted, setMounted] = useState(false);
  const { isMobile, isTablet } = useBreakpoint();

  const isAuthPage = pathname === '/login' || pathname === '/' || pathname?.startsWith('/track');
  // Full-screen DnD tools: the page-reveal animation leaves a retained `transform` on this wrapper
  // (animation-fill-mode: both), which becomes the containing block for the dnd-kit DragOverlay
  // (position: fixed) and breaks dragging (the dragged card won't follow the cursor). Skip it here.
  const isDndPage = Boolean(pathname?.startsWith('/route-builder') || pathname?.startsWith('/dispatch'));
  const lockViewport = isDndPage && !(isMobile || isTablet);

  useEffect(() => {
    setMounted(true);
  }, []);

  if (isAuthPage) return <>{children}</>;

  return (
    <SidebarProvider>
      <BreadcrumbProvider>
        <RealtimeProvider>
        <SessionRevocationWatcher />
        <ErrorBoundary>
        <AlertsProvider>
          {/* Fixed sidebar */}
          <AppSidebar />

          {/* Main — unified app shell with outer border radius */}
          <SidebarInset className="flex flex-col flex-1 min-w-0 min-h-dvh" style={{ borderRadius: '24px', overflow: 'hidden' }}>
            <div className="flex flex-col flex-1 min-w-0 min-h-dvh bg-[var(--app-bg)]">
              {/* Integrated breadcrumb — with background */}
              <div className="relative px-8 h-14 flex items-center justify-between bg-[var(--surface)] border-b border-[var(--border)]">
                <ContentBreadcrumb />

                {/* Global search — absolutely centered in the header */}
                <div className="absolute left-1/2 -translate-x-1/2 hidden md:block">
                  <GlobalSearch />
                </div>

                <AlertBell />
              </div>
              
              <div
                className={cn(
                  'flex-1 overflow-x-hidden',
                  lockViewport
                    ? 'overflow-y-hidden h-dvh'
                    : 'overflow-y-auto',
                )}
              >
                {/* key={pathname} replays the control-tower reveal on each route mount. DnD pages skip
                    it: the retained transform would break the drag overlay's fixed positioning. */}
                <div key={pathname} className={cn('w-full h-full', !isDndPage && 'page-reveal')}>
                  {children}
                </div>
              </div>
            </div>
          </SidebarInset>

          {/* Global floating live map */}
          <ErrorBoundary>
            <GlobalFloatingMap />
          </ErrorBoundary>
        </AlertsProvider>
        </ErrorBoundary>
        </RealtimeProvider>
      </BreadcrumbProvider>
    </SidebarProvider>
  );
}
