'use client';

import { useT } from '@/lib/i18n/LocaleContext';
import { IconCheck, IconAlertTriangle, IconCircleCheck } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { useRouteBuilderContext } from '../../hooks/useRouteBuilder';

export function ValidationModal() {
  const t = useT();
  const rb = useRouteBuilderContext();
  const {
    confirmValidateRouteId,
    setConfirmValidateRouteId,
    selectedRouteStops,
    waitingMap,
    chronoViolations,
    canValidate,
    validatingRouteId,
    savingWindows,
    selectedRouteIsDraft,
    saveStopWindows,
    validateRoute,
    hasChronoViolation,
    missingWindowCount,
    stopWindows,
  } = rb;

  const rows = selectedRouteStops
    .filter((stop) => stop.stopType !== 'PICKUP')
    .map((stop) => {
      const delivery = waitingMap.get(stop.deliveryId);
      return {
        id: stop.id,
        deliveryId: stop.deliveryId,
        clientName: delivery?.clientName,
        start: stopWindows[stop.id]?.startTime ?? '',
        end: stopWindows[stop.id]?.endTime ?? '',
      };
    });

  const isProcessing = validatingRouteId === confirmValidateRouteId || savingWindows;

  return (
    <AppModal
      opened={!!confirmValidateRouteId}
      onClose={() => setConfirmValidateRouteId(null)}
      subtitle={t.routeBuilderPage.finalReviewSubtitle}
      title={t.routeBuilderPage.validateTitle}
      size="xl"
      footer={
        <div className="w-full flex items-center justify-between">
          <div>
            {!canValidate ? (
              <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-500/10 text-red-500 border border-red-500/20">
                <IconAlertTriangle size={12} />
                {t.routeBuilderPage.chronoErrors}
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-650 dark:text-emerald-400 border border-emerald-500/20">
                <IconCircleCheck size={12} />
                {t.routeBuilderPage.readyForValidation}
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => setConfirmValidateRouteId(null)}>
              {t.actions.cancel}
            </Button>
            <Button
              size="sm"
              loading={isProcessing}
              disabled={!selectedRouteIsDraft || !canValidate || isProcessing}
              onClick={async () => {
                await saveStopWindows();
                if (confirmValidateRouteId && !hasChronoViolation && missingWindowCount === 0) {
                   await validateRoute(confirmValidateRouteId);
                }
              }}
              className="bg-[var(--brand-orange)] hover:opacity-90 font-semibold"
            >
              <IconCheck size={14} className="mr-1.5" />
              {t.routeBuilderPage.finalizeValidateButton}
            </Button>
          </div>
        </div>
      }
    >
      <div className="max-h-[55vh] overflow-y-auto pr-1">
        {rows.length === 0 ? (
          <p className="text-sm text-[var(--text-muted)] text-center py-8 italic">
            {t.routeBuilderPage.noStopsDetected}
          </p>
        ) : (
          <Table>
            <TableHeader className="bg-[var(--surface-2)]">
              <TableRow>
                <TableHead className="w-12 font-semibold text-2xs text-[var(--text-soft)] uppercase tracking-wider">
                  {t.routeBuilderPage.headerIndex}
                </TableHead>
                <TableHead className="font-semibold text-2xs text-[var(--text-soft)] uppercase tracking-wider">
                  {t.routeBuilderPage.headerClientDestination}
                </TableHead>
                <TableHead className="w-24 font-semibold text-2xs text-[var(--text-soft)] uppercase tracking-wider">
                  {t.routeBuilderPage.headerStart}
                </TableHead>
                <TableHead className="w-24 font-semibold text-2xs text-[var(--text-soft)] uppercase tracking-wider">
                  {t.routeBuilderPage.headerEnd}
                </TableHead>
                <TableHead className="w-16 text-center font-semibold text-2xs text-[var(--text-soft)] uppercase tracking-wider">
                  {t.routeBuilderPage.headerStatus}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((stop, idx) => {
                const violation = chronoViolations[stop.id];
                const missing = !(stop.start && stop.end);
                const error = !!(violation || missing);
                return (
                  <TableRow
                    key={stop.id}
                    className={error ? 'bg-red-500/5 hover:bg-red-500/10' : undefined}
                  >
                    <TableCell className="font-mono text-xs text-[var(--text-muted)] font-medium">
                      {(idx + 1).toString().padStart(2, '0')}
                    </TableCell>
                    <TableCell className="py-2.5">
                      <div className="flex flex-col gap-0.5">
                        <span className="text-xs font-semibold text-[var(--text-strong)] uppercase">
                          {stop.clientName?.trim() || stop.deliveryId.slice(0, 8).toUpperCase()}
                        </span>
                        {violation && (
                          <span className="text-2xs text-red-500 dark:text-red-400 font-bold uppercase tracking-wide">
                            {violation}
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs font-semibold text-[var(--text-strong)]">
                      {stop.start || '--:--'}
                    </TableCell>
                    <TableCell className="font-mono text-xs font-semibold text-[var(--text-strong)]">
                      {stop.end || '--:--'}
                    </TableCell>
                    <TableCell className="text-center py-2.5">
                      <div className="flex items-center justify-center">
                        {error ? (
                          <IconAlertTriangle size={15} className="text-red-500" />
                        ) : (
                          <IconCircleCheck size={15} className="text-emerald-500" />
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>
    </AppModal>
  );
}
