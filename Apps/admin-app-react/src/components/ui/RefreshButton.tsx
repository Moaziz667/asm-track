
import React from 'react';
import { IconRefresh } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';

export interface RefreshButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  refreshing: boolean;
  showText?: boolean;
}

export const RefreshButton = React.forwardRef<HTMLButtonElement, RefreshButtonProps>(
  ({ refreshing, showText = false, className, onClick, ...props }, ref) => {
    const t = useT();
    return (
      <button
        ref={ref}
        type="button"
        onClick={onClick}
        disabled={refreshing}
        className={cn(
          "flex items-center justify-center border border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] transition-all cursor-pointer disabled:opacity-50 focus:outline-none rounded-md shrink-0",
          showText 
            ? "h-8 px-3 gap-1.5 text-xs font-[500]" 
            : "w-8 h-8",
          className
        )}
        aria-label={t.actions.refresh}
        {...props}
      >
        <IconRefresh 
          size={13} 
          className={cn(
            "shrink-0",
            refreshing && "animate-spin"
          )} 
        />
        {showText && <span>{t.actions.refresh}</span>}
      </button>
    );
  }
);

RefreshButton.displayName = 'RefreshButton';

