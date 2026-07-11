
import { useEffect, useState, ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { cn } from '@/lib/utils';
import AlertsProvider from './AlertsProvider';
import { RealtimeProvider } from './RealtimeProvider';
import SessionRevocationWatcher from './SessionRevocationWatcher';
import TopNav from './TopNav';
import { AppSidebar } from './Sidebar';
import { BreadcrumbProvider } from '@/lib/ui/breadcrumb';
import { SidebarProvider, SidebarInset } from './ui/sidebar';
import GlobalFloatingMap from './GlobalFloatingMap';
import ErrorBoundary from './ErrorBoundary';

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

          {/* Main */}
          <SidebarInset className="ct-canvas flex flex-col flex-1 min-w-0 min-h-dvh bg-[var(--app-bg)]">
            <TopNav />
            <div
              className={cn(
                'flex-1 overflow-x-hidden',
                lockViewport
                  ? 'overflow-y-hidden h-[calc(100dvh-56px)]'
                  : 'overflow-y-auto',
              )}
            >
              {/* key={pathname} replays the control-tower reveal on each route mount. DnD pages skip
                  it: the retained transform would break the drag overlay's fixed positioning. */}
              <div key={pathname} className={cn('w-full h-full', !isDndPage && 'page-reveal')}>
                {children}
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

