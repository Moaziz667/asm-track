'use client';

import {
  IconRoute,
  IconTrash,
  IconAlertTriangle,
  IconCheck,
  IconDeviceFloppy,
  IconBolt,
  IconSettings,
  IconRefresh,
} from '@tabler/icons-react';
import { StopsList } from './StopsList';
import { OptimizePreview } from './OptimizePreview';
import { RouteItem, RouteStop, DeliveryOption, StopWindowDraft } from '../types';
import { colorForRouteIndex, useRouteBuilderContext } from '../hooks/useRouteBuilder';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';

export function StopsPanel() {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const rb = useRouteBuilderContext();
  const {
    selectedRoute,
    selectedRouteStops,
    stopWindows,
    setStopWindows,
    waitingMap,
    chronoViolations,
    hasChronoViolation,
    canModifyRoute,
    optimizing,
    optimizeRouteOrder,
    suggestion,
    setSuggestion,
    applyBackendOptimization,
    setSettingsOpen,
    refreshAll,
    loading: refreshing,
    saveStopWindows,
    savingWindows,
    setConfirmValidateRouteId,
    deleteDraftRoute,
    deletingRouteId,
    removeStopFromRoute,
    removingStopId,
    reorderSelectedRouteStops,
    selectedStopIds,
    setSelectedStopIds,
    batchRemoveStops,
    batchRemovingStops,
    selectedWeight,
    vehicleCapacity,
    overloadKg,
    payloadPercent,
    suggestionEtaRows,
    routes,
    optimizationStartTime,
    setOptimizationStartTime,
  } = rb;

  if (!selectedRoute) {
    return (
      <div className="h-full bg-[var(--surface-2)] flex items-center justify-center p-6 text-center">
        <div className="flex flex-col items-center gap-3 max-w-[260px]">
          <IconRoute size={36} className="text-[var(--text-muted)]" />
          <div className="flex flex-col gap-1">
            <h4 className="text-sm font-bold text-[var(--text-strong)]">
              {t.routeBuilderPage.selectRoutePromptTitle}
            </h4>
            <p className="text-xs text-[var(--text-muted)] leading-relaxed">
              {t.routeBuilderPage.selectRoutePromptDesc}
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (suggestion) {
    return (
      <OptimizePreview
        rows={suggestionEtaRows}
        optimizationStartTime={optimizationStartTime}
        setOptimizationStartTime={setOptimizationStartTime}
        totalDurationSeconds={suggestion?.totalDurationSeconds}
        totalDistanceMeters={suggestion?.totalDistanceMeters}
        distanceSavedMeters={
          suggestion?.savings?.distanceSavedMeters ?? suggestion?.distanceGain
        }
        durationSavedSeconds={suggestion?.savings?.durationSavedSeconds}
        onApply={applyBackendOptimization}
        onCancel={() => setSuggestion(null)}
        applying={optimizing}
      />
    );
  }

  const handleUpdateWindow = (id: string, updates: Partial<StopWindowDraft>) => {
    setStopWindows({
      ...stopWindows,
      [id]: { ...stopWindows[id], ...updates },
    });
  };

  const overloaded = overloadKg > 0;
  const realIdx = routes.findIndex((r) => r.id === selectedRoute.id);
  const routeColor = colorForRouteIndex(Math.max(0, realIdx));

  return (
    <div className="flex flex-col h-full bg-[var(--surface-1)]">
      {/* Per-route color accent strip */}
      <div className="h-[3px] shrink-0" style={{ backgroundColor: routeColor }} />

      {/* Header */}
      <div className="flex flex-col gap-3 p-4 border-b border-[var(--border)] shrink-0">
        <div className="flex justify-between items-start gap-4">
          <div className="flex flex-col gap-1 min-w-0">
            <div className="flex items-center gap-1.5 flex-wrap min-w-0">
              <h3 className="text-base font-bold text-[var(--text-strong)] truncate leading-none">
                {selectedRoute.name}
              </h3>
              {selectedRoute.status === 'VALIDATED' && (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20">
                  {t.statusLabels.VALIDATED}
                </span>
              )}
              {selectedRoute.status === 'DRAFT' && (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-blue-500/10 text-blue-605 dark:text-blue-400 border border-blue-500/20">
                  {t.statusLabels.DRAFT}
                </span>
              )}
            </div>
            <div className="flex items-center gap-2 text-xs text-[var(--text-muted)] font-medium">
              <span>
                {selectedRouteStops.length === 1
                  ? (t.routeBuilderPage.stopsCountLabelSingular || '{count} arrêt').replace('{count}', '1')
                  : (t.routeBuilderPage.stopsCountLabelPlural || '{count} arrêts').replace('{count}', String(selectedRouteStops.length))}
              </span>
              <span className="text-[var(--text-soft)] font-mono">•</span>
              <span className="font-mono">{selectedWeight.toFixed(1)} kg</span>
            </div>
          </div>

          <div className="flex items-center gap-1 shrink-0">
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={() => void refreshAll()}
                  disabled={refreshing}
                  className="text-[var(--text-muted)] hover:text-[var(--text-strong)] w-7 h-7"
                  aria-label={t.actions.refresh}
                >
                  <IconRefresh size={15} className={refreshing ? 'animate-spin' : ''} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>{t.actions.refresh}</TooltipContent>
            </Tooltip>

            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={() => setSettingsOpen(true)}
                  className="text-[var(--text-muted)] hover:text-[var(--text-strong)] w-7 h-7"
                  aria-label={t.routeBuilderPage.settingsTitle}
                >
                  <IconSettings size={15} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>{t.routeBuilderPage.settingsTitle}</TooltipContent>
            </Tooltip>

            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={() => void deleteDraftRoute(selectedRoute.id)}
                  disabled={deletingRouteId === selectedRoute.id}
                  className="text-red-500 hover:bg-red-500/10 hover:text-red-650 w-7 h-7 shrink-0"
                  aria-label={t.routeBuilderPage.deleteRouteButton}
                >
                  <IconTrash size={15} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>{t.routeBuilderPage.deleteRouteButton}</TooltipContent>
            </Tooltip>
          </div>
        </div>

        {vehicleCapacity > 0 && (
          <div className="flex flex-col gap-1.5">
            <div className="flex justify-between items-center text-xs">
              <span className="text-[var(--text-muted)] font-medium">{t.routeBuilderPage.payloadLabel}</span>
              <span className={`font-mono ${overloaded ? 'text-red-500 font-bold' : 'text-[var(--text-soft)] font-semibold'}`}>
                {selectedWeight.toFixed(1)} / {vehicleCapacity.toFixed(0)} kg ({payloadPercent.toFixed(0)}%)
              </span>
            </div>
            <div className="w-full h-1 bg-[var(--surface-2)] rounded-full overflow-hidden shrink-0">
              <div
                className={`h-full transition-all duration-200 ${
                  overloaded ? 'bg-red-500' : payloadPercent > 85 ? 'bg-amber-500' : 'bg-[var(--brand-orange)]'
                }`}
                style={{ width: `${Math.min(100, payloadPercent)}%` }}
              />
            </div>
            {overloaded && (
              <div className="flex items-center gap-1 text-[11px] text-red-500 font-medium">
                <IconAlertTriangle size={12} className="shrink-0" />
                <span>{t.routeBuilderPage.overloadLabel.replace('{amount}', overloadKg.toFixed(1))}</span>
              </div>
            )}
          </div>
        )}

        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => void optimizeRouteOrder()}
            disabled={!canModifyRoute || selectedRouteStops.length < 2 || optimizing}
            className="h-8 text-xs bg-[var(--surface-2)] border-[var(--border)] text-[var(--text-strong)] hover:bg-[var(--hover-bg)]"
          >
            <IconBolt size={13} className="text-[var(--brand-orange)] mr-1" />
            {t.routeBuilderPage.optimize}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void saveStopWindows()}
            disabled={!canModifyRoute || savingWindows}
            className="h-8 text-xs bg-[var(--surface-2)] border-[var(--border)] text-[var(--text-strong)] hover:bg-[var(--hover-bg)]"
          >
            <IconDeviceFloppy size={13} className="text-[var(--brand-orange)] mr-1" />
            {savingWindows ? t.routeBuilderPage.savingProgress : t.routeBuilderPage.save}
          </Button>
        </div>
      </div>
 
      {hasChronoViolation && (
        <div className="flex items-center gap-2 px-4 py-2 bg-red-500/10 border-b border-[var(--border)] text-red-500 font-medium text-xs">
          <IconAlertTriangle size={14} className="shrink-0" />
          <span>{t.routeBuilderPage.chronoConflictWarning}</span>
        </div>
      )}

      <StopsList
        stops={selectedRouteStops}
        waitingMap={waitingMap}
        stopWindows={stopWindows}
        chronoViolations={chronoViolations}
        onRemove={removeStopFromRoute}
        onUpdateWindow={handleUpdateWindow}
        onReorder={reorderSelectedRouteStops}
        removingStopId={removingStopId}
        routeId={selectedRoute.id}
        selectedStopIds={selectedStopIds}
        onToggleStopSelect={(id) => setSelectedStopIds((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])}
        onClearStopSelection={() => setSelectedStopIds([])}
        onBatchRemove={batchRemoveStops}
        batchRemoving={batchRemovingStops}
      />

      {selectedRouteStops.length > 0 && selectedRoute.status === 'DRAFT' && (
        <div className="p-3 border-t border-[var(--border)] shrink-0 bg-[var(--surface-1)]">
          <Button
            onClick={() => setConfirmValidateRouteId(selectedRoute.id)}
            className="w-full h-9 bg-[var(--brand-orange)] hover:opacity-90 font-semibold text-white border-transparent text-sm"
          >
            <IconCheck size={15} className="mr-1" />
            {t.routeBuilderPage.finalizeValidateButton}
          </Button>
        </div>
      )}
    </div>
  );
}
