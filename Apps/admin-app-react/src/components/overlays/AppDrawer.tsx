
import { ReactNode } from 'react';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { cn } from '@/lib/utils';

interface AppDrawerProps {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  subtitle?: string;
  width?: number | string;
  footer?: ReactNode;
  children: ReactNode;
  side?: 'right' | 'left';
  className?: string;
}

export function AppDrawer({
  open,
  onClose,
  title,
  subtitle,
  width = 480,
  footer,
  children,
  side = 'right',
  className,
}: AppDrawerProps) {
  return (
    <Sheet open={open} onOpenChange={(v) => !v && onClose()}>
      <SheetContent
        side={side}
        style={{
          '--drawer-width': typeof width === 'number' ? `${width}px` : width,
        } as React.CSSProperties}
        className={cn(
          'flex flex-col gap-0 p-0 bg-[var(--surface)] border-[var(--border)]',
          'w-full max-w-full sm:w-[var(--drawer-width)] sm:max-w-[95vw]',
          className,
        )}
      >
        {/* Header */}
        <SheetHeader className="px-5 py-4 border-b border-[var(--border)] shrink-0">
          {subtitle && (
            <p className="text-2xs font-medium uppercase tracking-widest text-[var(--text-soft)] mb-0.5">
              {subtitle}
            </p>
          )}
          <SheetTitle className="text-sm font-semibold text-[var(--text-primary)] leading-tight text-left">
            {title}
          </SheetTitle>
        </SheetHeader>

        {/* Body */}
        <div className="flex-1 overflow-y-auto min-h-0">
          {children}
        </div>

        {/* Footer */}
        {footer && (
          <div className="px-5 py-3 border-t border-[var(--border)] shrink-0 flex items-center justify-end gap-2 bg-[var(--app-bg)]">
            {footer}
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}

