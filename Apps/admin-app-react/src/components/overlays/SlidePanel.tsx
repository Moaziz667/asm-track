
import React, { useEffect } from 'react';
import { IconX } from '@tabler/icons-react';

interface SlidePanelProps {
  open: boolean;
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  onClose: () => void;
  width?: number;
  footer?: React.ReactNode;
}

export function SlidePanel({
  open,
  title,
  subtitle,
  children,
  onClose,
  width = 380,
  footer,
}: SlidePanelProps) {
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  return (
    <>
      {open && (
        <div
          onClick={onClose}
          className="fixed inset-0 bg-black/20 z-[998]"
        />
      )}

      <div
        className="fixed right-0 bottom-0 flex flex-col border-l border-[var(--border)] shadow-[-8px_0_32px_rgba(0,0,0,0.08)] bg-[var(--surface)] z-[999] transition-transform duration-[220ms] ease-[cubic-bezier(0.4,0,0.2,1)]"
        style={{
          top: 64,
          width: `min(${width}px, 100vw)`,
          transform: open ? 'translateX(0)' : 'translateX(100%)',
          visibility: open ? 'visible' : 'hidden',
        }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[var(--border)] shrink-0">
          <div>
            <p className="text-sm font-semibold text-[var(--text-primary)]">{title}</p>
            {subtitle && (
              <p className="text-xs text-[var(--text-muted)] mt-0.5">{subtitle}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="w-7 h-7 rounded border border-[var(--border)] bg-transparent flex items-center justify-center text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors shrink-0"
          >
            <IconX size={14} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          {children}
        </div>

        {/* Footer */}
        {footer && (
          <div className="px-5 py-3 border-t border-[var(--border)] flex gap-2 shrink-0">
            {footer}
          </div>
        )}
      </div>
    </>
  );
}

