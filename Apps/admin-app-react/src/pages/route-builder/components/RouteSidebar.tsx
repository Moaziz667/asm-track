'use client';

import { useState } from 'react';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import {
  IconPlus,
  IconSearch,
  IconLayoutGrid,
  IconLock,
  IconLockOpen,
  IconBolt,
  IconX,
} from '@tabler/icons-react';
import { useDroppable } from '@dnd-kit/core';
import { RouteItem, VehicleItem } from '../types';
import { colorForRouteIndex, useRouteBuilderContext } from '../hooks/useRouteBuilder';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';

function RouteCard({
  route,
  colorIndex,
  isSelected,
  isBatchSelected,
  totalWeight,
  vehicle,
  driverName,
  isLocking,
  onSelect,
  onToggleBatch,
  onLock,
}: {
  route: RouteItem;
  colorIndex: number;
  isSelected: boolean;
  isBatchSelected: boolean;
  totalWeight: number;
  vehicle: VehicleItem | undefined;
  driverName: string | undefined;
  isLocking: boolean;
  onSelect: (id: string) => void;
  onToggleBatch: (id: string) => void;
  onLock: (id: string, locked: boolean) => Promise<void>;
}) {
  const t = useT();
  const { setNodeRef, isOver } = useDroppable({ id: `route-drop:${route.id}` });

  const color = colorForRouteIndex(colorIndex);
  const capacity = vehicle?.payloadKg ?? 0;
  const loadPercent = capacity > 0 ? (totalWeight / capacity) * 100 : 0;
  const overloaded = loadPercent > 100;
  const stopsCount = route.stops?.length ?? 0;
  const isLocked = !!route.locked;

  return (
    <div
      ref={setNodeRef}
      data-route-id={route.id}
      style={{
        outline: isOver ? '2px solid rgba(59,130,246,0.35)' : 'none',
        outlineOffset: -2,
      }}
      className={`border-b border-[var(--border)] hover:bg-[var(--surface-2)] transition-all cursor-pointer relative py-2 px-3.5 flex items-center gap-2 ${
        isSelected ? 'bg-[var(--surface-2)] border-l-3 border-l-[var(--brand-orange)]' : 'border-l-3 border-l-transparent'
      } ${isOver ? 'bg-[var(--surface-2)] border-l-[var(--brand)]' : ''}`}
    >
      <input
        type="checkbox"
        checked={isBatchSelected}
        onChange={() => onToggleBatch(route.id)}
        onClick={(e) => e.stopPropagation()}
        className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 cursor-pointer"
        aria-label={t.routeBuilderPage.selectForBatchOptimize}
      />

      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="ghost"
            size="icon-xs"
            disabled={isLocking}
            onClick={(e) => { e.stopPropagation(); void onLock(route.id, !isLocked); }}
            className={`text-xs shrink-0 ${isLocked ? 'text-[var(--brand-orange)]' : 'text-[var(--text-muted)]'}`}
            aria-label={isLocked ? t.routeBuilderPage.unlockRoute : t.routeBuilderPage.lockRoute}
          >
            {isLocked ? <IconLock size={13} /> : <IconLockOpen size={13} />}
          </Button>
        </TooltipTrigger>
        <TooltipContent>{isLocked ? t.routeBuilderPage.unlockRoute : t.routeBuilderPage.lockRoute}</TooltipContent>
      </Tooltip>

      <div
        role="button"
        tabIndex={0}
        onClick={() => onSelect(route.id)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(route.id); } }}
        className="flex-1 min-w-0 select-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] rounded"
      >
        <div className="flex flex-col gap-0.5">
          <div className="flex justify-between items-center gap-2">
            <div className="flex items-center gap-1.5 min-w-0">
              <span
                className="w-2 h-2 rounded-full shrink-0"
                style={{ backgroundColor: color }}
              />
              <span className="text-xs font-semibold text-[var(--text-strong)] truncate">
                {route.name}
              </span>
            </div>
            <span className="font-mono text-2xs text-[var(--text-muted)] shrink-0">
              {route.date}
            </span>
          </div>

          <div className="flex justify-between items-center gap-2 text-xs text-[var(--text-soft)]">
            <span className="truncate flex-1">
              {driverName || t.routeBuilderPage.noDriver}
              {vehicle?.name ? ` · ${vehicle.name}` : ''}
            </span>
            <span className="font-mono text-2xs text-[var(--text-muted)] shrink-0">
              {stopsCount === 1 ? t.routeBuilderPage.stopsCountLabelSingular.replace('{count}', '1') : t.routeBuilderPage.stopsCountLabelPlural.replace('{count}', String(stopsCount))} · {totalWeight.toFixed(1)} kg
            </span>
          </div>

          {capacity > 0 && (
            <div className="w-full h-[3px] bg-[var(--border)] rounded-full overflow-hidden mt-1 shrink-0">
              <div
                className={`h-full transition-all duration-200 ${
                  overloaded ? 'bg-red-500' : isSelected ? 'bg-[var(--brand-orange)]' : 'bg-[var(--text-muted)]'
                }`}
                style={{ width: `${Math.min(100, loadPercent)}%` }}
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export function RouteSidebar() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const rb = useRouteBuilderContext();
  const {
    routes,
    selectedRouteId,
    setSelectedRouteId,
    setCreateOpen,
    routeWeightById,
    vehicles,
    driverNameById,
    batchSelectedRouteIds,
    toggleBatchSelection,
    clearBatchSelection,
    batchOptimizeRoutes,
    batchOptimizing,
    lockRoute,
    lockingRouteId,
  } = rb;

  const [query, setQuery] = useState('');

  const filtered = routes.filter((r) =>
    r.name.toLowerCase().includes(query.trim().toLowerCase()),
  );

  const selectionCount = batchSelectedRouteIds.length;
  const eligibleSelectionCount = batchSelectedRouteIds.filter((id) => {
    const r = routes.find((x) => x.id === id);
    return r && !r.locked && (r.stops?.length ?? 0) >= 2;
  }).length;

  return (
    <div className="flex flex-col h-full bg-[var(--surface-1)]">
      {/* Header */}
      <div className="flex flex-col gap-2 p-3.5 border-b border-[var(--border)] shrink-0">
        <div className="flex justify-between items-center gap-4">
          <h4 className="text-sm font-bold text-[var(--text-strong)]">{t.routeBuilderPage.kpiRoutes}</h4>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="outline"
                size="icon-sm"
                onClick={() => setCreateOpen(true)}
                className="bg-[var(--brand-orange)] border-transparent text-white hover:opacity-90 w-7 h-7"
                aria-label={t.routeBuilderPage.newRoute}
              >
                <IconPlus size={14} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>{t.routeBuilderPage.newRoute}</TooltipContent>
          </Tooltip>
        </div>

        <div className="relative flex items-center">
          <span className="absolute left-2 text-[var(--text-muted)] pointer-events-none">
            <IconSearch size={13} />
          </span>
          <input
            type="text"
            placeholder={t.routeBuilderPage.searchPlaceholder}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
            className="w-full h-8 pl-7 pr-3 rounded bg-[var(--surface-2)] border border-[var(--border)] text-xs text-[var(--text-strong)] placeholder:text-[var(--text-soft)] focus:outline-none focus:border-[var(--brand)]"
          />
        </div>
      </div>

      {/* Batch selection bar (only visible when ≥1 selected) */}
      {selectionCount > 0 && (
        <div className="flex justify-between items-center gap-3 px-3 py-1.5 bg-[var(--surface-2)] border-b border-[var(--border)] shrink-0">
          <div className="flex items-center gap-1.5 min-w-0">
            <span className="text-xs font-semibold text-[var(--brand-orange)] truncate">
              {t.routeBuilderPage.selectedRoutes.replace('{count}', String(selectionCount)).replace('{plural}', selectionCount > 1 ? 's' : '')}
            </span>
            {eligibleSelectionCount < selectionCount && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <span className="text-2xs text-[var(--text-muted)] cursor-help truncate">
                    {t.routeBuilderPage.eligibleRoutes.replace('{count}', String(eligibleSelectionCount))}
                  </span>
                </TooltipTrigger>
                <TooltipContent className="max-w-xs">
                  {t.routeBuilderPage.lockOrLessStopsIgnored}
                </TooltipContent>
              </Tooltip>
            )}
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            <Button
              size="xs"
              loading={batchOptimizing}
              disabled={eligibleSelectionCount === 0}
              onClick={() => void batchOptimizeRoutes(batchSelectedRouteIds)}
              className="bg-[var(--brand-orange)] border-transparent text-white hover:opacity-90 h-6 px-2 text-2xs font-semibold"
            >
              <IconBolt size={11} className="mr-0.5" />
              {t.routeBuilderPage.batchOptimizeButton}
            </Button>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={clearBatchSelection}
                  className="text-[var(--text-muted)] hover:text-[var(--text-strong)] w-5 h-5"
                  aria-label={t.routeBuilderPage.clearSelection}
                >
                  <IconX size={13} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>{t.routeBuilderPage.clearSelectionTooltip}</TooltipContent>
            </Tooltip>
          </div>
        </div>
      )}

      {/* Route list */}
      <div className="flex-1 overflow-y-auto min-h-0">
        {filtered.length === 0 ? (
          <div className="h-44 flex flex-col items-center justify-center gap-1.5 p-6">
            <IconLayoutGrid size={28} className="text-[var(--text-muted)]" />
            <span className="text-xs text-[var(--text-muted)] text-center max-w-[200px]">
              {query ? t.routeBuilderPage.noResults : t.routeBuilderPage.noRoutesForDate}
            </span>
          </div>
        ) : (
          <div className="flex flex-col py-1">
            {filtered.map((route) => {
              const realIdx = routes.findIndex((r) => r.id === route.id);
              return (
                <RouteCard
                  key={route.id}
                  route={route}
                  colorIndex={realIdx}
                  isSelected={selectedRouteId === route.id}
                  isBatchSelected={batchSelectedRouteIds.includes(route.id)}
                  totalWeight={routeWeightById[route.id] ?? 0}
                  vehicle={vehicles.find((v) => v.id === route.vehicleId)}
                  driverName={driverNameById.get(route.driverId ?? '')}
                  isLocking={lockingRouteId === route.id}
                  onSelect={setSelectedRouteId}
                  onToggleBatch={toggleBatchSelection}
                  onLock={lockRoute}
                />
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
