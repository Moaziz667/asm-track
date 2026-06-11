
import React, { useState } from 'react';
import { cn } from '@/lib/utils';

type SplitLayoutProps = {
  sidebar: React.ReactNode;
  children: React.ReactNode;
  sidebarLabel?: string;
  contentLabel?: string;
  sidebarClassName?: string;
  height?: string;
};

export function SplitLayout({
  sidebar,
  children,
  sidebarLabel = 'Filtres',
  contentLabel = 'Liste',
  sidebarClassName = 'lg:w-[280px]',
  height = 'h-[calc(100vh-56px)]',
}: SplitLayoutProps) {
  const [mobileTab, setMobileTab] = useState<'sidebar' | 'content'>('content');

  return (
    <div className={cn('overflow-hidden bg-[var(--app-bg)] flex flex-col', height)}>
      {/* Mobile Tab Bar */}
      <div className="lg:hidden flex shrink-0 border-b border-[var(--border-color)] bg-[var(--surface-1)]">
        {(['sidebar', 'content'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setMobileTab(tab)}
            className={cn(
              'flex-1 h-10 text-sm font-bold tracking-wide transition-colors',
              mobileTab === tab
                ? 'text-[var(--brand-orange)] border-b-2 border-[var(--brand-orange)]'
                : 'text-[var(--text-muted)] hover:text-[var(--text-strong)]',
            )}
          >
            {tab === 'sidebar' ? sidebarLabel : contentLabel}
          </button>
        ))}
      </div>

      {/* Content area */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* Sidebar */}
        <div
          className={cn(
            'shrink-0 border-r border-[var(--border-color)] bg-[var(--surface-1)] overflow-y-auto',
            sidebarClassName,
            mobileTab === 'sidebar'
              ? 'flex flex-col w-full'
              : 'hidden lg:flex lg:flex-col',
          )}
        >
          {sidebar}
        </div>

        {/* Main */}
        <div
          className={cn(
            'flex-1 min-w-0 overflow-hidden bg-[var(--surface-1)]',
            mobileTab === 'content'
              ? 'flex flex-col'
              : 'hidden lg:flex lg:flex-col',
          )}
        >
          {children}
        </div>
      </div>
    </div>
  );
}

export default SplitLayout;

