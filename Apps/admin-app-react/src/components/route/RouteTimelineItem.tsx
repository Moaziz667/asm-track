
import { ReactNode } from 'react';
import {
  IconMapPin,
  IconClock,
  IconChevronDown,
  IconChevronUp,
  IconPencil,
  IconX,
  IconBan,
} from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import styles from '@/styles/route-timeline.module.scss';

interface RouteTimelineItemProps {
  stopOrder: number;
  status: string;
  clientName: string;
  address: string;
  city?: string;
  amount: number;
  currency: string;
  timeWindow?: { start?: string; end?: string };
  isExpanded: boolean;
  onToggle: () => void;
  onEditWindow?: () => void;
  onRemove?: () => void;
  onCancel?: () => void;
  canEdit?: boolean;
  canRemove?: boolean;
  canCancel?: boolean;
  isActive?: boolean;
  isDone?: boolean;
  delayBadge?: ReactNode;
  priorityBadge?: ReactNode;
  children?: ReactNode;
}

export function RouteTimelineItem({
  stop, currency, canEdit, canRemove, canCancel, isExpanded, onToggle, onEditWindow, onRemove, onCancel,
}: RouteTimelineItemProps) {
  const t = useT();
  // Status color mapping
  const statusConfig: Record<string, { bg: string; border: string; text: string; dot: string }> = {
    SCHEDULED: { bg: '#EFF6FF', border: '#BFDBFE', text: '#2563EB', dot: '#3B82F6' },
    IN_TRANSIT: { bg: '#FFF7ED', border: '#FED7AA', text: '#EA580C', dot: '#F97316' },
    DELIVERED: { bg: '#F0FDF4', border: '#A7F3D0', text: '#059669', dot: '#10B981' },
    FAILED: { bg: '#FEF2F2', border: '#FECACA', text: '#DC2626', dot: '#EF4444' },
    PARTIAL: { bg: '#F5F3FF', border: '#DDD6FE', text: '#7C3AED', dot: '#8B5CF6' },
    COMPLETED: { bg: '#F0FDF4', border: '#A7F3D0', text: '#059669', dot: '#10B981' },
    PICKED_UP: { bg: '#ECFEFF', border: '#A5F3FC', text: '#0891B2', dot: '#06B6D4' },
  };

  const config = statusConfig[status] || statusConfig.SCHEDULED;

  return (
    <div className={styles.timelineItem}>
      {/* Timeline node */}
      <div
        className={cn(styles.node, isActive && styles.active)}
        style={{
          background: config.bg,
          border: `2px solid ${config.dot}`,
          color: config.text,
        }}
      >
        {stopOrder}
      </div>

      {/* Stop card */}
      <div
        className={cn(
          styles.card,
          isActive && styles.active,
          isDone && styles.completed,
          !isActive && !isDone && styles.pending
        )}
        style={
          isActive
            ? { borderLeftColor: config.dot, backgroundColor: `${config.bg}` }
            : isDone
            ? { borderLeftColor: config.dot }
            : {}
        }
      >
        {/* Card header (clickable) */}
        <div className={styles.cardHeader} onClick={onToggle}>
          {/* Main info */}
          <div className={styles.mainInfo}>
            {/* Client + status badges */}
            <div className={styles.clientRow}>
              <div className={styles.clientName}>{clientName}</div>
              <Badge variant="outline" className="text-[9px]">
                {status}
              </Badge>
              {priorityBadge}
              {delayBadge}
            </div>

            {/* Address + time window */}
            <div className={styles.addressRow}>
              <div className={styles.address}>
                <IconMapPin size={10} className={styles.icon} />
                <div className={styles.text}>{address}</div>
              </div>
              {timeWindow?.start && (
                <div className={styles.timeWindow}>
                  <IconClock size={10} className={styles.icon} />
                  <div className={styles.time}>
                    {timeWindow.start}–{timeWindow.end || ''}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Right section: amount, COD, actions */}
          <div className={styles.rightSection}>
            <div className={styles.amountBlock}>
              <div className={styles.amount}>
                {amount.toLocaleString('fr-FR', {
                  minimumFractionDigits: 2,
                  maximumFractionDigits: 2,
                })}{' '}
                {currency}
              </div>
            </div>

            {canEdit && (
              <button
                className={styles.actionButton}
                onClick={(e) => {
                  e.stopPropagation();
                  onEditWindow?.();
                }}
                title={t.tooltips.editWindow}
              >
                <IconPencil size={13} />
              </button>
            )}
            {canRemove && (
              <button
                className={cn(styles.actionButton, styles.danger)}
                onClick={(e) => {
                  e.stopPropagation();
                  onRemove?.();
                }}
                title={t.tooltips.removeStop}
              >
                <IconX size={13} />
              </button>
            )}
            {canCancel && (
              <button
                className={cn(styles.actionButton, styles.danger)}
                onClick={(e) => {
                  e.stopPropagation();
                  onCancel?.();
                }}
                title={t.tooltips.cancelStop}
              >
                <IconBan size={13} />
              </button>
            )}

            {/* Chevron indicator */}
            <div className={cn(styles.chevron, isExpanded && styles.expanded)}>
              {isExpanded ? '▲' : '▼'}
            </div>
          </div>
        </div>

        {/* Card body (expanded) */}
        {isExpanded && children && <div className={styles.cardBody}>{children}</div>}
      </div>
    </div>
  );
}

