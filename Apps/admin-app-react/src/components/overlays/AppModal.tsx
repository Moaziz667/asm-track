
import { ReactNode } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogClose,
} from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { IconX } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';

interface AppModalProps {
  open?: boolean;
  opened?: boolean; // Mantine compat alias
  onClose: () => void;
  title: ReactNode;
  subtitle?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  footer?: ReactNode;
  children: ReactNode;
  variant?: 'default' | 'danger';
  className?: string;
  zIndex?: number; // Mantine compat — ignored, shadcn Dialog handles z-index
}

const SIZE: Record<string, string> = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
};

export function AppModal({
  open,
  opened,
  onClose,
  title,
  subtitle,
  size = 'md',
  footer,
  children,
  variant = 'default',
  className,
}: AppModalProps) {
  const isOpen = open ?? opened ?? false;
  const t = useT();
  return (
    <Dialog open={isOpen} onOpenChange={(v) => !v && onClose()}>
      <DialogContent
        showCloseButton={false}
        style={{ boxShadow: 'var(--shadow-lg)' }}
        className={cn(
          'flex flex-col gap-0 p-0 bg-[var(--surface)] border-[var(--border)] rounded-2xl',
          'max-h-[90dvh]',
          SIZE[size],
          className,
        )}
      >
        {/* Header */}
        <DialogHeader className="px-5 py-4 border-b border-[var(--border)] shrink-0 flex-row items-center justify-between">
          <div className="flex flex-col gap-0.5 min-w-0">
            {subtitle && (
              <p className={cn(
                'text-2xs font-semibold tracking-wider text-[var(--text-muted)]',
                variant === 'danger' && 'text-[var(--danger)]',
              )}>
                {subtitle}
              </p>
            )}
            <DialogTitle className="text-sm font-semibold text-[var(--text-primary)] leading-tight">
              {title}
            </DialogTitle>
          </div>
          <DialogClose
            render={
              <button
                type="button"
                className="w-7 h-7 flex items-center justify-center rounded border border-transparent hover:border-[var(--border)] hover:bg-[var(--hover-bg)] text-[var(--text-muted)] transition-colors shrink-0 ml-3"
                aria-label={t.actions.close}
              />
            }
          >
            <IconX size={14} />
          </DialogClose>
        </DialogHeader>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 min-h-0">
          {children}
        </div>

        {/* Footer */}
        {footer && (
          <div className="px-5 py-3 border-t border-[var(--border)] shrink-0 flex items-center justify-end gap-2">
            {footer}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

