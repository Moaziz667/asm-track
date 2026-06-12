import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { formatMoney } from '@/lib/utils';
import { IconChevronDown } from '@tabler/icons-react';

interface RouteStopTimelineItemProps {
  stopOrder: number;
  status: string;
  clientName: string;
  address: string;
  amount: number;
  currency: string;
  isExpanded: boolean;
  onToggle: () => void;
  canEdit?: boolean;
  canRemove?: boolean;
  canCancel?: boolean;
  onEditWindow?: () => void;
  onRemove?: () => void;
  onCancel?: () => void;
  children?: React.ReactNode;
}

export function RouteStopTimelineItem({
  stopOrder,
  status,
  clientName,
  address,
  amount,
  currency,
  isExpanded,
  onToggle,
  canEdit,
  canRemove,
  canCancel,
  onEditWindow,
  onRemove,
  onCancel,
  children,
}: RouteStopTimelineItemProps) {
  return (
    <div
      role="button"
      tabIndex={0}
      aria-expanded={isExpanded}
      onClick={onToggle}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle?.(); } }}
      className="mb-2 p-3 border border-[var(--border-color)] rounded bg-[var(--surface)] cursor-pointer hover:bg-[var(--surface-hover)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)]"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-xs font-mono font-semibold text-[var(--brand)] bg-[var(--brand)]/10 px-2 py-0.5 rounded">
              #{stopOrder}
            </span>
            <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{clientName}</p>
          </div>
          <p className="text-xs text-[var(--text-muted)] truncate">{address}</p>
          <p className="text-xs font-mono font-semibold text-[var(--text-primary)] mt-1">{formatMoney(amount, currency)}</p>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <StatusBadge status={status} size="sm" />
          <IconChevronDown size={16} className={`text-[var(--text-muted)] transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
        </div>
      </div>

      {isExpanded && (
        <div className="mt-3 pt-3 border-t border-[var(--border-color)] space-y-3">
          {children}
          <div className="flex gap-2 flex-wrap">
            {canEdit && onEditWindow && (
              <Button
                size="xs"
                variant="outline"
                onClick={(e) => { e.stopPropagation(); onEditWindow(); }}
              >
                Edit Window
              </Button>
            )}
            {canRemove && onRemove && (
              <Button
                size="xs"
                variant="destructive"
                onClick={(e) => { e.stopPropagation(); onRemove(); }}
              >
                Remove
              </Button>
            )}
            {canCancel && onCancel && (
              <Button
                size="xs"
                variant="destructive"
                onClick={(e) => { e.stopPropagation(); onCancel(); }}
              >
                Cancel
              </Button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

