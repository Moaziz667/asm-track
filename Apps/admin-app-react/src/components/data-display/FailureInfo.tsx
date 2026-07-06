import { IconAlertTriangle } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { tlabel } from '@/lib/i18n-dict';
import { cn } from '@/lib/utils';

/**
 * Standard failure presentation shared across delivery / dispatch / route / timeline.
 * Shows a SINGLE red chip: the specific motif (failReason — the catalog motif, enriched with the
 * driver's comment) when available, otherwise the canonical category (failureCode → label). We no
 * longer render both, because the specific motif already implies its category (e.g. "Refus —
 * produit non conforme" alongside "Refus du client" was redundant).
 */
export function FailureInfo({
  code,
  reason,
  size = 'sm',
  className,
}: {
  code?: string | null;
  reason?: string | null;
  size?: 'sm' | 'xs';
  className?: string;
}) {
  const t = useT();
  if (!code && !reason) return null;

  const label = code ? (tlabel(t.failureCodes, code) ?? code) : null;
  // Prefer the specific motif; fall back to the category. One chip, no duplication.
  const text = reason || label;
  if (!text) return null;

  // Same pill as the SLA / Activité timeline: warning-triangle icon + danger (breach) tone.
  return (
    <span className={cn('inline-flex items-center flex-wrap', className)}>
      <span
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          fontSize: size === 'xs' ? 11 : 11.5, fontWeight: 500,
          color: 'var(--danger)',
          background: 'var(--danger-bg)',
          border: '1px solid color-mix(in srgb, var(--danger) 22%, transparent)',
          borderRadius: 8, padding: '4px 9px',
        }}
      >
        <IconAlertTriangle size={size === 'xs' ? 12 : 13} stroke={1.8} style={{ flexShrink: 0 }} />
        {text}
      </span>
    </span>
  );
}

export default FailureInfo;
