'use client';

import { useMemo } from 'react';
import { IconMapPin, IconTrash, IconX } from '@tabler/icons-react';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { StopRow } from './StopRow';
import { RouteStop, DeliveryOption, StopWindowDraft } from '../types';
import { resolveOrderRef, shortId } from '@/lib/utils';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';

interface StopsListProps {
  stops: RouteStop[];
  routeId: string;
  waitingMap: Map<string, DeliveryOption>;
  stopWindows: Record<string, StopWindowDraft>;
  chronoViolations: Record<string, string | null>;
  onRemove: (id: string) => void;
  onUpdateWindow: (id: string, updates: Partial<StopWindowDraft>) => void;
  onReorder?: (fromIndex: number, toIndex: number) => Promise<void>;
  removingStopId: string | null;
  selectedStopIds: string[];
  onToggleStopSelect: (id: string) => void;
  onClearStopSelection: () => void;
  onBatchRemove: (ids: string[]) => Promise<void>;
  batchRemoving: boolean;
}

export function StopsList({
  stops,
  routeId,
  waitingMap,
  stopWindows,
  chronoViolations,
  onRemove,
  onUpdateWindow,
  removingStopId,
  selectedStopIds,
  onToggleStopSelect,
  onClearStopSelection,
  onBatchRemove,
  batchRemoving,
}: StopsListProps) {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const selectableStops = stops.filter((s) => s.stopType !== 'PICKUP');
  const allSelected = selectableStops.length > 0 && selectedStopIds.length === selectableStops.length;
  const someSelected = selectedStopIds.length > 0;

  // Multi-depot derivations: a delivery is "non-home" iff its source depot has a PICKUP stop.
  const { pickupDepotIds, deliveriesByDepot } = useMemo(() => {
    const depots = new Set<string>();
    const byDepot = new Map<string, RouteStop[]>();
    for (const s of stops) {
      if (s.stopType === 'PICKUP' && s.sourceDepotId) depots.add(s.sourceDepotId);
    }
    for (const s of stops) {
      if (s.stopType !== 'PICKUP' && s.sourceDepotId && depots.has(s.sourceDepotId)) {
        const list = byDepot.get(s.sourceDepotId) ?? [];
        list.push(s);
        byDepot.set(s.sourceDepotId, list);
      }
    }
    return { pickupDepotIds: depots, deliveriesByDepot: byDepot };
  }, [stops]);

  const pickListFor = (depotId: string | null | undefined) =>
    (depotId ? deliveriesByDepot.get(depotId) ?? [] : []).map((s) => {
      const d = waitingMap.get(s.deliveryId);
      return { id: s.id, label: d ? `${resolveOrderRef(d)} · ${d.clientName ?? ''}`.trim() : shortId(s.deliveryId) };
    });

  const toggleAll = () => {
    if (allSelected) {
      onClearStopSelection();
    } else {
      stops.forEach((s) => {
        if (!selectedStopIds.includes(s.id)) onToggleStopSelect(s.id);
      });
    }
  };

  if (stops.length === 0) {
    return (
      <div className="flex-1 overflow-y-auto bg-[var(--surface-1)] flex items-center justify-center p-6">
        <div className="flex flex-col items-center gap-2 max-w-[220px]">
          <IconMapPin size={28} className="text-[var(--text-muted)]" />
          <p className="text-xs text-[var(--text-muted)] text-center leading-relaxed">
            {t.routeBuilderPage.dragDropPrompt}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
      {/* Select-all header — always visible when list has items */}
      <div className="flex items-center gap-2 px-3.5 py-1.5 bg-[var(--surface-2)] border-b border-[var(--border)] shrink-0">
        <input
          type="checkbox"
          checked={allSelected}
          onChange={toggleAll}
          ref={(el) => {
            if (el) {
              el.indeterminate = someSelected && !allSelected;
            }
          }}
          className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 cursor-pointer"
          aria-label={t.routeBuilderPage.selectAll}
        />
        <span className="text-xs text-[var(--text-muted)] flex-1 select-none">
          {someSelected
            ? t.routeBuilderPage.selectedStops.replace('{count}', String(selectedStopIds.length)).replace(/{plural}/g, selectedStopIds.length > 1 ? 's' : '')
            : (stops.length === 1
                ? t.routeBuilderPage.stopsCountLabelSingular.replace('{count}', '1')
                : t.routeBuilderPage.stopsCountLabelPlural.replace('{count}', String(stops.length)))}
        </span>
        {someSelected && (
          <div className="flex items-center gap-1.5 shrink-0">
            <Button
              size="xs"
              variant="destructive"
              loading={batchRemoving}
              onClick={() => void onBatchRemove(selectedStopIds)}
              className="h-6 px-2 text-[10px] font-semibold border-transparent"
            >
              <IconTrash size={11} className="mr-0.5" />
              {t.routeBuilderPage.removeButton.replace('{count}', String(selectedStopIds.length))}
            </Button>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={onClearStopSelection}
                  className="text-[var(--text-muted)] hover:text-[var(--text-strong)] w-5 h-5"
                  aria-label={t.routeBuilderPage.deselectButton}
                >
                  <IconX size={11} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>{t.routeBuilderPage.deselectButton}</TooltipContent>
            </Tooltip>
          </div>
        )}
      </div>

      <SortableContext
        items={stops.map((s) => `stop:${s.id}:${routeId}`)}
        strategy={verticalListSortingStrategy}
      >
        <div className="flex-1 overflow-y-auto bg-[var(--surface-1)]">
          <div className="flex flex-col min-h-full">
            {stops.map((stop, index) => {
              const isPickup = stop.stopType === 'PICKUP';
              const depotChip = !isPickup && stop.sourceDepotId && pickupDepotIds.has(stop.sourceDepotId)
                ? (stop.sourceDepotName ?? null)
                : null;
              return (
                <StopRow
                  key={stop.id}
                  stop={stop}
                  index={index}
                  routeId={routeId}
                  delivery={waitingMap.get(stop.deliveryId)}
                  window={stopWindows[stop.id]}
                  violation={chronoViolations[stop.id]}
                  onRemove={onRemove}
                  onUpdateWindow={onUpdateWindow}
                  isRemoving={removingStopId === stop.id}
                  isSelected={selectedStopIds.includes(stop.id)}
                  onToggleSelect={isPickup ? undefined : onToggleStopSelect}
                  pickupCount={isPickup ? pickListFor(stop.sourceDepotId).length : undefined}
                  pickList={isPickup ? pickListFor(stop.sourceDepotId) : undefined}
                  depotChipLabel={depotChip}
                />
              );
            })}
          </div>
        </div>
      </SortableContext>
    </div>
  );
}
