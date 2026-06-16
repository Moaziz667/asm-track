import React, { createContext, useContext, useState, useEffect } from 'react';
import { safeStorage } from '@/lib/storage';
import { useIsMobile } from '@/hooks/use-mobile';

type SidebarContextProps = {
  state: 'expanded' | 'collapsed';
  open: boolean;
  setOpen: (open: boolean) => void;
  toggleSidebar: () => void;
  isMobile: boolean;
};

const SidebarContext = createContext<SidebarContextProps | null>(null);

export function useSidebar() {
  const context = useContext(SidebarContext);
  if (!context) throw new Error('useSidebar must be used within a SidebarProvider.');
  return context;
}

export function SidebarProvider({ children }: { children: React.ReactNode }) {
  const isMobile = useIsMobile();
  const [open, setOpen] = useState(() => {
    if (typeof window === 'undefined') return true;
    return safeStorage.getItem('sidebar_state') !== 'collapsed';
  });

  // Sync open state when viewport becomes mobile
  useEffect(() => {
    if (isMobile) {
      setOpen(false);
    } else {
      const stored = safeStorage.getItem('sidebar_state');
      setOpen(stored !== 'collapsed');
    }
  }, [isMobile]);

  const toggleSidebar = () => {
    setOpen((prev) => {
      const next = !prev;
      if (!isMobile) {
        safeStorage.setItem('sidebar_state', next ? 'expanded' : 'collapsed');
      }
      return next;
    });
  };

  const state = open ? 'expanded' : 'collapsed';

  return (
    <SidebarContext.Provider value={{ state, open, setOpen, toggleSidebar, isMobile }}>
      <div className="flex min-h-screen w-full">
        {children}
      </div>
    </SidebarContext.Provider>
  );
}

export function SidebarInset({ children, className, style, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  const { open, isMobile } = useSidebar();
  return (
    <main
      className={`relative flex w-full flex-1 flex-col bg-background transition-[padding] duration-200 ease-linear ${className}`}
      style={{
        paddingInlineStart: isMobile ? '0px' : (open ? '212px' : '48px'),
        ...style,
      }}
      {...props}
    >
      {children}
    </main>
  );
}

export function SidebarTrigger({ className, 'aria-label': ariaLabel, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const { toggleSidebar } = useSidebar();
  return (
    <button
      onClick={toggleSidebar}
      className={`p-1.5 rounded-md hover:bg-[var(--hover-bg)] cursor-pointer flex items-center justify-center shrink-0 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors ${className}`}
      type="button"
      aria-label={ariaLabel ?? 'Toggle sidebar'}
      {...props}
    >
      <svg width="16" height="16" viewBox="0 0 15 15" fill="none" xmlns="http://www.w3.org/2000/svg" className="text-current shrink-0" aria-hidden="true">
        <path d="M1.5 3C1.5 2.17157 2.17157 1.5 3 1.5H12C12.8284 1.5 13.5 2.17157 13.5 3V12C13.5 12.8284 12.8284 13.5 12 13.5H3C2.17157 13.5 1.5 12.8284 1.5 12V3ZM3 2.5C2.72386 2.5 2.5 2.72386 2.5 3V12C2.5 12.2761 2.72386 12.5 3 12.5H4.5V2.5H3ZM5.5 12.5H12C12.2761 12.5 12.5 12.2761 12.5 12V3C12.5 2.72386 12.2761 2.5 12 2.5H5.5V12.5Z" fill="currentColor" fillRule="evenodd" clipRule="evenodd" />
      </svg>
    </button>
  );
}
