
import { useEffect, useRef } from 'react';
import { IconAlertTriangle } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { AppModal } from './AppModal';

interface ConfirmModalProps {
  open: boolean;
  title: string;
  description?: string;
  reasonLabel?: string;
  reasonPlaceholder?: string;
  reason?: string;
  onReasonChange?: (v: string) => void;
  reasonRequired?: boolean;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'primary';
  loading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmModal({
  open,
  title,
  description,
  reasonLabel,
  reasonPlaceholder = 'Raison (optionnel)',
  reason = '',
  onReasonChange,
  reasonRequired = false,
  confirmLabel = 'Confirmer',
  cancelLabel = 'Annuler',
  variant = 'primary',
  loading = false,
  onConfirm,
  onCancel,
}: ConfirmModalProps) {
  const isReasonInvalid = !!(reasonRequired && reasonLabel && !reason.trim());
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    if (reasonLabel) setTimeout(() => inputRef.current?.focus(), 80);
  }, [open, reasonLabel]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && !loading && e.target !== inputRef.current) {
        e.preventDefault();
        onConfirm();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, loading, onConfirm]);

  return (
    <AppModal
      open={open}
      onClose={onCancel}
      title={title}
      size="sm"
      variant={variant === 'danger' ? 'danger' : 'default'}
      footer={
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" onClick={onCancel} disabled={loading} size="sm">
            {cancelLabel}
          </Button>
          <Button
            variant={variant === 'danger' ? 'destructive' : 'default'}
            onClick={onConfirm}
            disabled={loading || isReasonInvalid}
            size="sm"
          >
            {loading && (
              <svg className="animate-spin -ml-0.5 mr-1.5 h-3 w-3" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
              </svg>
            )}
            {confirmLabel}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-4">
        {variant === 'danger' && (
          <div className="flex items-start gap-3">
            <div className="flex items-center justify-center w-8 h-8 rounded-md bg-[var(--danger-bg)] shrink-0">
              <IconAlertTriangle size={16} className="text-[var(--danger)]" />
            </div>
            {description && (
              <p className="text-sm text-[var(--text-muted)] leading-relaxed flex-1">{description}</p>
            )}
          </div>
        )}
        {variant !== 'danger' && description && (
          <p className="text-sm text-[var(--text-muted)] leading-relaxed">{description}</p>
        )}
        {reasonLabel && (
          <FieldInput
            ref={inputRef}
            label={reasonLabel}
            placeholder={reasonPlaceholder}
            value={reason}
            onChange={(e) => onReasonChange?.(e.currentTarget.value)}
          />
        )}
      </div>
    </AppModal>
  );
}

