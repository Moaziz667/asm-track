'use client';

import { IconRoute, IconLock, IconLockOpen } from '@tabler/icons-react';
import { colorForRouteIndex, useRouteBuilderContext } from '../hooks/useRouteBuilder';
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { useT } from '@/lib/i18n/LocaleContext';

const formatKm = (m?: number) => (!m || m <= 0 ? '—' : `${(m / 1000).toFixed(1)} km`);
const formatMin = (s?: number) => {
  if (!s || s <= 0) return '—';
  const mins = Math.round(s / 60);
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m === 0 ? `${h}h` : `${h}h${String(m).padStart(2, '0')}`;
};

export function RoutesTable() {
  const t = useT();
  const rb = useRouteBuilderContext();
  const {
    routes,
    selectedRouteId,
    setSelectedRouteId,
    driverNameById,
    vehicles,
    routeWeightById,
  } = rb;

  if (routes.length === 0) {
    return (
      <div className="h-44 flex flex-col items-center justify-center gap-2 p-6 bg-[var(--surface-1)]">
        <IconRoute size={32} className="text-[var(--text-muted)]" />
        <span className="text-xs text-[var(--text-muted)]">{t.routeBuilderPage.noRoutesForDate}</span>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto bg-[var(--surface-1)]">
      <Table>
        <TableHeader className="bg-[var(--surface-2)] sticky top-0 z-10">
          <TableRow className="border-b border-[var(--border)]">
            <TableHead className="w-10 p-0" />
            <TableHead className="text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerRoute}</TableHead>
            <TableHead className="text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerDriver}</TableHead>
            <TableHead className="text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerVehicle}</TableHead>
            <TableHead className="w-20 text-right text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerStops}</TableHead>
            <TableHead className="w-24 text-right text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerWeight}</TableHead>
            <TableHead className="w-24 text-right text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerDistance}</TableHead>
            <TableHead className="w-24 text-right text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerDuration}</TableHead>
            <TableHead className="w-24 text-center text-2xs font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerStatus}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {routes.map((route, idx) => {
            const isSelected = selectedRouteId === route.id;
            const color = colorForRouteIndex(idx);
            const driverName = driverNameById.get(route.driverId ?? '') ?? '—';
            const vehicle = vehicles.find((v) => v.id === route.vehicleId);
            const weight = routeWeightById[route.id] ?? 0;
            const stopsCount = route.stops?.length ?? 0;
            const isLocked = !!route.locked;

            return (
              <TableRow
                key={route.id}
                onClick={() => setSelectedRouteId(route.id)}
                className={`border-b border-[var(--border)] hover:bg-[var(--surface-2)] transition-colors cursor-pointer ${
                  isSelected ? 'bg-[var(--surface-2)] font-medium' : ''
                }`}
              >
                <TableCell className="pl-3 py-2 shrink-0">
                  <div className="flex items-center gap-1.5">
                    <span
                      className="w-2 h-2 rounded-full shrink-0"
                      style={{ backgroundColor: color }}
                    />
                    {isLocked ? (
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <span className="text-[var(--brand-orange)] shrink-0">
                            <IconLock size={11} />
                          </span>
                        </TooltipTrigger>
                        <TooltipContent>{t.routeBuilderPage.routeLockedBadge}</TooltipContent>
                      </Tooltip>
                    ) : (
                      <span className="text-[var(--text-muted)] shrink-0">
                        <IconLockOpen size={11} />
                      </span>
                    )}
                  </div>
                </TableCell>
                <TableCell className="py-2">
                  <div className="flex flex-col">
                    <span className="text-xs font-semibold text-[var(--text-strong)]">
                      {route.name}
                    </span>
                    <span className="font-mono text-2xs text-[var(--text-soft)]">
                      {route.date}
                    </span>
                  </div>
                </TableCell>
                <TableCell className="text-xs text-[var(--text-strong)] py-2">
                  {driverName}
                </TableCell>
                <TableCell className="text-xs text-[var(--text-soft)] py-2">
                  {vehicle?.name ?? '—'}
                </TableCell>
                <TableCell className="text-right font-mono font-bold text-xs text-[var(--text-strong)] py-2">
                  {stopsCount}
                </TableCell>
                <TableCell className="text-right font-mono font-semibold text-xs text-[var(--text-strong)] py-2">
                  {weight.toFixed(1)} kg
                </TableCell>
                <TableCell className="text-right font-mono text-xs text-[var(--text-soft)] py-2">
                  {formatKm(route.totalDistanceMeters ?? route.totalDistance)}
                </TableCell>
                <TableCell className="text-right font-mono text-xs text-[var(--text-soft)] py-2">
                  {formatMin(route.totalDurationSeconds ?? route.totalDuration)}
                </TableCell>
                <TableCell className="text-center py-2 shrink-0">
                  <div className="flex items-center justify-center">
                    {route.status === 'VALIDATED' ? (
                      <span className="inline-flex items-center px-1.5 py-0.5 rounded text-2xs font-semibold bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20">
                        {t.statusLabels.VALIDATED}
                      </span>
                    ) : (
                      <span className="inline-flex items-center px-1.5 py-0.5 rounded text-2xs font-semibold bg-blue-500/10 text-blue-600 dark:text-blue-400 border border-blue-500/20">
                        {t.statusLabels.DRAFT}
                      </span>
                    )}
                  </div>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
