'use client';

import { useState } from 'react';
import { IconX, IconGripVertical, IconAlertTriangle, IconBuildingWarehouse, IconChevronDown } from '@tabler/icons-react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { RouteStop, DeliveryOption, StopWindowDraft } from '../types';
import { resolveOrderRef, shortId } from '@/lib/utils';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';

/** Pickup-phase accent (cyan family, matches PICKED_UP status token). */
const PICKUP_COLOR = '#0891B2';

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
  /** PICKUP rows: number of deliveries loaded at this depot. */
  pickupCount?: number;
  /** PICKUP rows: the deliveries loaded here (orderRef/clientName) for the pick list. */
  pickList?: { id: string; label: string }[];
  /** DELIVERY rows: source depot name to chip when sourced from a non-home depot. */
  depotChipLabel?: string | null;
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
  pickupCount = 0,
  pickList = [],
  depotChipLabel = null,
}: StopRowProps) {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const [pickListOpen, setPickListOpen] = useState(false);
  const isPickup = stop.stopType === 'PICKUP';
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

  // ── PICKUP stop: depot loading row + collapsible pick list ──
  if (isPickup) {
    const depotName = stop.sourceDepotName || '—';
    const title = t.routeBuilderPage.pickupTitle
      .replace('{count}', String(pickupCount))
      .replace('{depot}', depotName);
    return (
      <div
        ref={isOverlay ? undefined : setNodeRef}
        style={{ ...(isOverlay ? {} : style) }}
        className="border-b border-[var(--border)] transition-colors w-full min-w-0 bg-[var(--surface-1)]"
        {...(isOverlay ? {} : attributes)}
        data-dragging={isDragging || undefined}
      >
        <div className="flex items-center w-full min-w-0">
          <div
            {...(isOverlay ? {} : listeners)}
            className={`w-11 py-2 flex items-center justify-center gap-1.5 border-r border-[var(--border)] shrink-0 self-stretch touch-action-none ${isOverlay ? 'cursor-grabbing' : 'cursor-grab'}`}
          >
            <IconGripVertical size={11} className="text-[var(--text-muted)] shrink-0" />
            <IconBuildingWarehouse size={13} style={{ color: PICKUP_COLOR }} className="shrink-0" />
          </div>

          <button
            type="button"
            onClick={() => pickList.length > 0 && setPickListOpen((v) => !v)}
            className="flex-1 min-w-0 flex items-center gap-2 px-3 py-2 text-left"
          >
            <span className="text-xs font-semibold truncate" style={{ color: PICKUP_COLOR }}>
              {title}
            </span>
            {pickList.length > 0 && (
              <IconChevronDown
                size={13}
                className="text-[var(--text-muted)] shrink-0 transition-transform"
                style={{ transform: pickListOpen ? 'rotate(180deg)' : undefined }}
              />
            )}
          </button>
        </div>

        {pickListOpen && pickList.length > 0 && (
          <div className="ps-12 pe-3 pb-2 flex flex-col gap-1">
            <span className="text-2xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
              {t.routeBuilderPage.pickListLabel}
            </span>
            {pickList.map((p) => (
              <span key={p.id} className="text-xs text-[var(--text-muted)] truncate">• {p.label}</span>
            ))}
          </div>
        )}
      </div>
    );
  }

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
            className="font-mono text-2xs font-bold text-[var(--brand-orange)] cursor-pointer tracking-wide truncate max-w-full hover:underline"
          >
            {resolveOrderRef(delivery)}
            <span className="opacity-50 ml-1">#{shortId(stop.deliveryId)}</span>
          </span>
        )}
        <span className="text-xs font-semibold text-[var(--text-strong)] truncate max-w-full leading-tight">
          {delivery?.clientName || shortId(stop.deliveryId)}
        </span>
        <span className="text-xs text-[var(--text-muted)] truncate max-w-full leading-none">
          {delivery?.dropoffAddress || delivery?.dropoffCity || '—'}
          {(delivery?.totalWeightKg ?? 0) > 0 ? ` · ${delivery!.totalWeightKg!.toFixed(1)} kg` : ''}
        </span>
        {depotChipLabel && (
          <span
            className="mt-0.5 inline-flex items-center gap-1 self-start rounded-xs px-1.5 py-0.5 text-2xs font-semibold"
            style={{ color: PICKUP_COLOR, background: 'color-mix(in srgb, ' + PICKUP_COLOR + ' 10%, transparent)' }}
          >
            <IconBuildingWarehouse size={10} />
            {depotChipLabel}
          </span>
        )}
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
