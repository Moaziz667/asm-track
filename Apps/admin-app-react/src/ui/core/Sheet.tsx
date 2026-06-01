
import * as React from 'react';
import { cn } from '@/lib/utils';

type SheetProps = {
  opened: boolean;
  onClose: () => void;
  children: React.ReactNode;
  size?: string; // e.g., '380px', '500px'
  side?: 'left' | 'right';
  className?: string;
};

export function Sheet({
  opened,
  onClose,
  children,
  size = '380px',
  side = 'right',
  className,
}: SheetProps) {
  // Listen for Escape key
  React.useEffect(() => {
    if (!opened) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [opened, onClose]);

  if (!opened) return null;

  return (
    <div className="fixed inset-0 z-[1000] flex select-none">
      {/* Backdrop overlay */}
      <div
        className="fixed inset-0 bg-black/40 backdrop-blur-[1px] transition-opacity"
        onClick={onClose}
      />

      {/* Sheet Surface */}
      <div
        style={{ width: size }}
        className={cn(
          'fixed top-0 bottom-0 h-full bg-[var(--bg-panel)] border-[var(--border-grid)] shadow-xl flex flex-col z-[1010] transition-transform duration-200 ease-out',
          side === 'right' ? 'right-0 border-l' : 'left-0 border-r',
          className
        )}
      >
        {children}
      </div>
    </div>
  );
}

export function SheetHeader({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        'px-dense-4 h-12 flex items-center justify-between border-b border-[var(--border-grid)] bg-[var(--bg-canvas)]/50 shrink-0',
        className
      )}
    >
      {children}
    </div>
  );
}

export function SheetBody({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn('flex-1 min-h-0 overflow-y-auto p-dense-4 flex flex-col gap-dense-3', className)}>{children}</div>;
}

