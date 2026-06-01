'use client';

import { IconRotateClockwise2 } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { resolveOrderRef, shortId } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';

type OpsException = {
  deliveryId: string;
  orderRef?: string;
  clientName?: string;
  routeName?: string;
  motif: string;
};

interface ReplanModalProps {
  pendingAction: { kind: 'reassign' | 'replan'; row: OpsException } | null;
  actionNote: string;
  onNoteChange: (v: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
  loading: boolean;
  formatMotif: (motif?: string) => string;
}

export function ReplanModal({
  pendingAction,
  actionNote,
  onNoteChange,
  onConfirm,
  onCancel,
  loading,
  formatMotif,
}: ReplanModalProps) {
  const t = useT();
  const isReplan = pendingAction?.kind === 'replan';
  const row = pendingAction?.row;

  return (
    <AppModal
      opened={!!pendingAction}
      onClose={onCancel}
      subtitle={row ? `${resolveOrderRef(row)} · #${shortId(row.deliveryId)}` : undefined}
      title={isReplan ? t.dispatchDeskPage.replanModalTitleReplan : t.dispatchDeskPage.replanModalTitleReassign}
      size="lg"
      zIndex={10000}
      footer={
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" onClick={onCancel}>
            {t.actions?.cancel || 'Annuler'}
          </Button>
          <Button
            onClick={onConfirm}
            disabled={loading || !actionNote.trim()}
          >
            {loading ? (
              <span className="flex items-center gap-2">
                <svg className="animate-spin h-3.5 w-3.5" viewBox="0 0 24 24" fill="none">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
                </svg>
                {t.actions?.confirm || 'Confirmer'}
              </span>
            ) : (t.actions?.confirm || 'Confirmer')}
          </Button>
        </div>
      }
    >
      {row && (
        <div className="flex flex-col gap-4">
          {/* Summary card */}
          <div className="p-3 rounded-sm" style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', borderRadius: 2 }}>
            <div className="flex gap-6">
              <div className="flex flex-col gap-0.5">
                <span className="text-[9px] font-extrabold uppercase tracking-widest" style={{ color: 'var(--text-soft)' }}>{t.dispatchDeskPage.replanModalLabelOrder}</span>
                <span className="text-[14px] font-extrabold font-mono">{row.orderRef || '—'}</span>
              </div>
              <div className="flex flex-col gap-0.5">
                <span className="text-[9px] font-extrabold uppercase tracking-widest" style={{ color: 'var(--text-soft)' }}>{t.dispatchDeskPage.replanModalLabelProblem}</span>
                <span className="text-[12px] font-bold" style={{ color: '#c2410c' }}>{formatMotif(row.motif)}</span>
              </div>
              <div className="flex flex-col gap-0.5">
                <span className="text-[9px] font-extrabold uppercase tracking-widest" style={{ color: 'var(--text-soft)' }}>{t.dispatchDeskPage.replanModalLabelClient}</span>
                <span className="text-[12px] font-semibold" style={{ color: 'var(--text-primary)' }}>{row.clientName ?? '—'}</span>
              </div>
            </div>
          </div>

          {/* Replan explanation */}
          {isReplan && (
            <div className="p-3 flex gap-2 items-start" style={{ background: 'var(--brand-soft)', border: '1px solid rgba(255,87,34,0.25)', borderRadius: 2 }}>
              <IconRotateClockwise2 size={16} className="mt-0.5 shrink-0" style={{ color: 'var(--brand)' }} />
              <div className="flex flex-col gap-1">
                <span className="text-[12px] font-bold" style={{ color: 'var(--brand)' }}>{t.dispatchDeskPage.replanModalWhatWillHappen}</span>
                <span className="text-[11px] leading-relaxed" style={{ color: 'var(--text-muted)' }}>
                  {t.dispatchDeskPage.replanModalDescription.replace('{routeName}', row.routeName ? ` (${row.routeName})` : '')}
                </span>
              </div>
            </div>
          )}

          {/* Note input */}
          <FieldInput
            label={t.dispatchDeskPage.replanModalNoteLabel}
            hint={t.dispatchDeskPage.replanModalNoteHint}
            placeholder={t.dispatchDeskPage.replanModalNotePlaceholder}
            value={actionNote}
            onChange={(e) => onNoteChange(e.currentTarget.value)}
            required
          />
        </div>
      )}
    </AppModal>
  );
}
