
import * as React from 'react';
import { Toaster as SonnerToaster } from 'sonner';
import { useColorScheme } from '@/hooks/useColorScheme';

export function Toaster() {
  const { colorScheme } = useColorScheme();

  return (
    <SonnerToaster
      theme={colorScheme}
      position="bottom-left"
      closeButton
      className="toaster group !z-[9999]"
      style={{ zIndex: 99999 }}
      toastOptions={{
        classNames: {
          toast:
            'group toast flex items-start gap-3 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] text-[var(--text-primary)] font-medium text-base pl-4 pr-10 py-3.5 shadow-md select-none transition-colors duration-150 relative',
          description: 'text-[var(--text-muted)] font-normal text-sm mt-0.5',
          closeButton:
            '!absolute !left-auto !right-2 !top-3 !transform-none !bg-transparent hover:!bg-[var(--hover-bg)] !border-none !text-[var(--text-muted)] hover:!text-[var(--text-primary)] !transition-colors !rounded-full',
          actionButton:
            'bg-transparent hover:bg-black/5 dark:hover:bg-white/5 text-[var(--brand)] font-semibold rounded-full h-7 px-3 text-sm transition-colors border border-[var(--border)]',
          cancelButton:
            'bg-transparent hover:bg-black/5 dark:hover:bg-white/5 text-[var(--text-muted)] font-medium rounded-full h-7 px-3 text-sm transition-colors',
          error:
            '!bg-[var(--surface)] !text-[var(--text-primary)] !border-[var(--border)] !border-l-[4px] !border-l-[var(--danger)] !rounded-xl',
          success:
            '!bg-[var(--surface)] !text-[var(--text-primary)] !border-[var(--border)] !border-l-[4px] !border-l-[var(--success)] !rounded-xl',
          warning:
            '!bg-[var(--surface)] !text-[var(--text-primary)] !border-[var(--border)] !border-l-[4px] !border-l-[var(--warning)] !rounded-xl',
          info:
            '!bg-[var(--surface)] !text-[var(--text-primary)] !border-[var(--border)] !border-l-[4px] !border-l-[var(--info)] !rounded-xl',
        },
      }}
    />
  );
}
