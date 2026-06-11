
import * as React from 'react';
import { cn } from '@/lib/utils';

interface PanelProps extends React.ComponentProps<'div'> {
  title?: string;
  subtitle?: string;
  headerActions?: React.ReactNode;
}

export function Panel({ title, subtitle, headerActions, children, className, ...props }: PanelProps) {
  return (
    <div
      className={cn(
        'bg-[var(--bg-panel)] border border-[var(--border-grid)] rounded-dense-md flex flex-col min-w-0 overflow-hidden shadow-sm shrink-0',
        className
      )}
      {...props}
    >
      {(title || headerActions) && (
        <div className="px-dense-4 h-12 flex items-center justify-between border-b border-[var(--border-grid)] shrink-0 bg-[var(--bg-canvas)]/50">
          <div className="flex flex-col min-w-0">
            {title && (
              <span className="text-xs font-bold text-[var(--text-strong)] truncate">
                {title}
              </span>
            )}
            {subtitle && (
              <span className="text-2xs font-semibold text-[var(--text-muted)] truncate">
                {subtitle}
              </span>
            )}
          </div>
          {headerActions && <div className="flex items-center gap-dense-2">{headerActions}</div>}
        </div>
      )}
      <div className="flex-1 min-h-0 overflow-y-auto">
        {children}
      </div>
    </div>
  );
}

export function PanelBody({ className, ...props }: React.ComponentProps<'div'>) {
  return <div className={cn('p-dense-4 flex flex-col gap-dense-3', className)} {...props} />;
}

