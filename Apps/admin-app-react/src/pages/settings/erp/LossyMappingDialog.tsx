import { IconAlertTriangle } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';

/**
 * Confirms a mapping that converts but loses something.
 *
 * <p>A dedicated dialog rather than the browser's own confirm box: this is the one moment where an
 * integrator decides that a quantity may lose its decimals or that a delivery time will be invented,
 * and the decision deserves to state what is being given up. A native alert cannot show the field, the
 * types, or the consequence — and it looks like an error, which this is not. The mapping is allowed;
 * it simply has a cost.
 *
 * <p>The reason text comes from the server, which is where compatibility is decided. Restating it here
 * would mean two wordings drifting apart, and the screen eventually explaining a rule the adapter no
 * longer applies.
 */
export function LossyMappingDialog({
  open, field, sourcePath, reason, busy, copy, onCancel, onConfirm,
}: {
  open: boolean;
  /** The ASM field being filled, e.g. `ITEM_QUANTITY`. */
  field: string;
  /** The ERP path chosen, e.g. `product_uom_qty`. */
  sourcePath: string;
  /** The server's explanation of what is lost. */
  reason: string;
  busy: boolean;
  copy: Record<string, string>;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <AppModal
      opened={open}
      onClose={onCancel}
      title={copy.lossyTitle}
      subtitle={copy.lossySubtitle}
      size="md"
      footer={
        <div className="flex w-full items-center justify-end gap-2">
          <Button variant="outline" size="sm" onClick={onCancel} disabled={busy}>
            {copy.lossyCancel}
          </Button>
          <Button size="sm" loading={busy} onClick={onConfirm}>
            {copy.lossyConfirmAction}
          </Button>
        </div>
      }
    >
      <div className="space-y-3.5">
        <div className="flex items-start gap-2.5 rounded-lg border border-[var(--warning)]/25 bg-[var(--warning)]/10 px-3 py-2.5">
          <IconAlertTriangle size={16} className="mt-px shrink-0 text-[var(--warning)]" stroke={1.8} />
          <p className="text-base leading-relaxed text-[var(--text-primary)]">{reason}</p>
        </div>

        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
          <dt className="text-xs font-semibold uppercase tracking-[0.04em] text-[var(--text-soft)]">
            {copy.lossyFieldLabel}
          </dt>
          <dd className="font-mono text-sm text-[var(--text-primary)]">{field}</dd>

          <dt className="text-xs font-semibold uppercase tracking-[0.04em] text-[var(--text-soft)]">
            {copy.lossySourceLabel}
          </dt>
          <dd className="font-mono text-sm text-[var(--text-primary)]">{sourcePath}</dd>
        </dl>

        <p className="text-sm text-[var(--text-muted)]">{copy.lossyFootnote}</p>
      </div>
    </AppModal>
  );
}
