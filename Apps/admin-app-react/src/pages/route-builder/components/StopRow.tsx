'use client';

import { IconX, IconGripVertical, IconAlertTriangle } from '@tabler/icons-react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { RouteStop, DeliveryOption, StopWindowDraft } from '../types';
import { resolveOrderRef, shortId } from '@/lib/utils';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';

interface StopRowProps {
  stop: RouteStop;
  index: number;
  routeId: string;
  delivery: DeliveryOption | undefined;
  window: StopWindowDraft | undefined;
  violation: string | null;
  onRemove: (id: string) => void;
  onUpdateWindow: (id: string, updates: Partial<StopWindowDraft>) => void;
  isRemoving: boolean;
  isSelected?: boolean;
  onToggleSelect?: (id: string) => void;
  /** When true the row renders as a floating drag overlay — no dnd handles, full opacity */
  isOverlay?: boolean;
}

export function StopRow({
  stop,
  index,
  routeId,
  delivery,
  window,
  violation,
  onRemove,
  onUpdateWindow,
  isRemoving,
  isSelected = false,
  onToggleSelect,
  isOverlay = false,
}: StopRowProps) {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: `stop:${stop.id}:${routeId}`,
    disabled: isOverlay,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: transition ?? undefined,
    opacity: isDragging ? 0 : 1,
    zIndex: isDragging ? 999 : undefined,
  };

  const rowStyles = `border-b border-[var(--border)] transition-colors flex items-center w-full min-w-0 ${
    isOverlay
      ? 'bg-[var(--surface-2)] shadow-lg rounded'
      : isSelected
        ? 'bg-blue-500/5 dark:bg-blue-500/10 border-l-[3px] border-l-[var(--brand-orange)]'
        : violation
          ? 'bg-red-500/10 hover:bg-red-500/15 border-l-[3px] border-l-red-500'
          : 'bg-[var(--surface-1)] hover:bg-[var(--surface-2)] border-l-[3px] border-l-transparent'
  }`;

  return (
    <div
      ref={isOverlay ? undefined : setNodeRef}
      style={isOverlay ? undefined : style}
      className={rowStyles}
      {...(isOverlay ? {} : attributes)}
      data-dragging={isDragging || undefined}
    >
      {/* Selection checkbox */}
      {!isOverlay && onToggleSelect && (
        <div className="w-7 flex items-center justify-center shrink-0 pl-1.5">
          <input
            type="checkbox"
            checked={isSelected}
            onChange={(e) => {
              e.stopPropagation();
              onToggleSelect(stop.id);
            }}
            onClick={(e) => e.stopPropagation()}
            className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 cursor-pointer"
            aria-label={locale === 'ar' ? `اختر الموقف ${index + 1}` : locale === 'en' ? `Select stop ${index + 1}` : `Sélectionner l'arrêt ${index + 1}`}
          />
        </div>
      )}

      {/* Drag handle + sequence number */}
      <div
        {...(isOverlay ? {} : listeners)}
        className={`w-11 py-2 flex items-center justify-center gap-1.5 border-r border-[var(--border)] shrink-0 self-stretch touch-action-none ${
          isOverlay ? 'cursor-grabbing' : 'cursor-grab'
        }`}
        aria-label={isOverlay ? undefined : (locale === 'ar' ? `تحريك الموقف ${index + 1} — ${delivery?.clientName ?? stop.deliveryId.slice(0, 8).toUpperCase()}` : locale === 'en' ? `Move stop ${index + 1} — ${delivery?.clientName ?? stop.deliveryId.slice(0, 8).toUpperCase()}` : `Déplacer l'arrêt ${index + 1} — ${delivery?.clientName ?? stop.deliveryId.slice(0, 8).toUpperCase()}`)}
      >
        <IconGripVertical size={11} className="text-[var(--text-muted)] shrink-0" />
        <span className="font-mono text-xs font-bold text-[var(--text-soft)] shrink-0">
          {(index + 1).toString().padStart(2, '0')}
        </span>
      </div>

      {/* Client details */}
      <div className="flex-1 min-w-0 flex flex-col gap-0.5 px-3 py-1.5 justify-center">
        {(delivery?.erpOrderId || delivery?.orderRef) && (
          <span
            onClick={(e) => {
              e.stopPropagation();
              globalThis.open?.(`/deliveries/${stop.deliveryId}`, '_blank');
            }}
            className="font-mono text-[10px] font-bold text-[var(--brand-orange)] cursor-pointer tracking-wide truncate max-w-full hover:underline"
          >
            {resolveOrderRef(delivery)}
            <span className="opacity-50 ml-1">#{shortId(stop.deliveryId)}</span>
          </span>
        )}
        <span className="text-xs font-semibold text-[var(--text-strong)] truncate max-w-full leading-tight">
          {delivery?.clientName || shortId(stop.deliveryId)}
        </span>
        <span className="text-[11px] text-[var(--text-muted)] truncate max-w-full leading-none">
          {delivery?.dropoffAddress || delivery?.dropoffCity || '—'}
          {(delivery?.totalWeightKg ?? 0) > 0 ? ` · ${delivery!.totalWeightKg!.toFixed(1)} kg` : ''}
        </span>
      </div>

      {/* Time window inputs */}
      <div className="flex items-center gap-1.5 shrink-0 px-2 justify-end select-none">
        <input
          type="time"
          value={window?.startTime || '08:00'}
          onChange={(e) => onUpdateWindow(stop.id, { startTime: e.currentTarget.value })}
          className="rb-time-input text-right pr-0.5 font-semibold text-[var(--text-strong)]"
        />
        <span className="text-xs text-[var(--text-muted)] shrink-0 font-medium">–</span>
        <input
          type="time"
          value={window?.endTime || '18:00'}
          onChange={(e) => onUpdateWindow(stop.id, { endTime: e.currentTarget.value })}
          className="rb-time-input text-left pl-0.5 font-semibold text-[var(--text-strong)]"
        />
      </div>

      {/* Violation indicator */}
      <div className="w-6 flex items-center justify-center shrink-0">
        {violation && (
          <Tooltip>
            <TooltipTrigger asChild>
              <span className="text-red-500 shrink-0 cursor-help">
                <IconAlertTriangle size={13} />
              </span>
            </TooltipTrigger>
            <TooltipContent className="max-w-xs">{violation}</TooltipContent>
          </Tooltip>
        )}
      </div>

      {/* Remove button */}
      <div className="flex items-center justify-center shrink-0 pr-2">
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={() => onRemove(stop.id)}
              disabled={isRemoving}
              className="text-[var(--text-muted)] hover:text-[var(--text-strong)] w-5 h-5 shrink-0"
              aria-label={t.actions.removeStop}
            >
              <IconX size={13} />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t.actions.removeStop}</TooltipContent>
        </Tooltip>
      </div>
    </div>
  );
}
