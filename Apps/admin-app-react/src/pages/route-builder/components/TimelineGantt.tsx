'use client';

import { useMemo } from 'react';
import { IconCalendarStats } from '@tabler/icons-react';
import { colorForRouteIndex, useRouteBuilderContext } from '../hooks/useRouteBuilder';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';

const HOUR_WIDTH = 64; // px per hour
const LANE_HEIGHT = 36;
const HEADER_HEIGHT = 28;
const FALLBACK_START_HOUR = 6;
const FALLBACK_END_HOUR = 20;

const parseHHMM = (hhmm?: string): number | null => {
  if (!hhmm) return null;
  const [h, m] = hhmm.split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return null;
  return h * 60 + m;
};

const minutesToLabel = (mins: number) => {
  const h = Math.floor(mins / 60) % 24;
  const m = mins % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
};

export function TimelineGantt() {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const rb = useRouteBuilderContext();
  const {
    routes,
    selectedRouteId,
    setSelectedRouteId,
    driverNameById,
    vehicles,
  } = rb;

  // Compute time range from data, with sensible fallbacks
  const { startMin, endMin, totalMins } = useMemo(() => {
    let min: number | null = null;
    let max: number | null = null;
    routes.forEach((route) => {
      const ps = parseHHMM(route.plannedStartTime);
      const pe = parseHHMM(route.plannedEndTime);
      if (ps != null) min = min == null ? ps : Math.min(min, ps);
      if (pe != null) max = max == null ? pe : Math.max(max, pe);
      route.stops?.forEach((stop) => {
        const ss = parseHHMM(stop.startTimeWindow);
        const se = parseHHMM(stop.endTimeWindow);
        if (ss != null) min = min == null ? ss : Math.min(min, ss);
        if (se != null) max = max == null ? se : Math.max(max, se);
      });
    });
    const startHourBase = min != null ? Math.floor(min / 60) : FALLBACK_START_HOUR;
    const endHourBase = max != null ? Math.ceil(max / 60) : FALLBACK_END_HOUR;
    const startHour = Math.max(0, Math.min(startHourBase, FALLBACK_START_HOUR));
    const endHour = Math.max(endHourBase, FALLBACK_END_HOUR);
    const sM = startHour * 60;
    const eM = endHour * 60;
    return { startMin: sM, endMin: eM, totalMins: eM - sM };
  }, [routes]);

  const minToX = (m: number) => ((m - startMin) / 60) * HOUR_WIDTH;

  const hourTicks = useMemo(() => {
    const ticks: number[] = [];
    for (let h = startMin; h <= endMin; h += 60) ticks.push(h);
    return ticks;
  }, [startMin, endMin]);

  const totalWidth = (totalMins / 60) * HOUR_WIDTH;

  if (routes.length === 0) {
    return (
      <div className="h-full bg-[var(--surface-1)] flex flex-col items-center justify-center gap-2 p-6">
        <IconCalendarStats size={32} className="text-[var(--text-muted)]" />
        <span className="text-sm text-[var(--text-muted)]">
          {t.routeBuilderPage.emptyTimelineGantt}
        </span>
      </div>
    );
  }

  return (
    <div className="h-full bg-[var(--surface-1)] flex overflow-hidden select-none">
      {/* Sticky left column: driver lanes */}
      <div className="w-[220px] shrink-0 border-r border-[var(--border)] bg-[var(--surface-2)] flex flex-col overflow-hidden">
        {/* Header spacer */}
        <div className="h-7 px-3 border-b border-[var(--border)] shrink-0 flex items-center bg-[var(--surface-2)]">
          <span className="text-xs font-bold text-[var(--text-soft)] uppercase tracking-wider">
            {t.routeBuilderPage.headerDriver}
          </span>
        </div>
        <div className="flex-1 overflow-y-auto min-h-0">
          {routes.map((route, idx) => {
            const isSelected = selectedRouteId === route.id;
            const driverName = driverNameById.get(route.driverId ?? '') ?? t.routeBuilderPage.noDriver;
            const vehicle = vehicles.find((v) => v.id === route.vehicleId);
            const color = colorForRouteIndex(idx);
            return (
              <button
                key={route.id}
                type="button"
                onClick={() => setSelectedRouteId(route.id)}
                className={`w-full h-9 px-3 border-b border-[var(--border)] text-left flex items-center gap-2 transition-colors cursor-pointer ${
                  isSelected
                    ? 'bg-[var(--surface-1)] border-l-3 border-l-[var(--brand-orange)] font-medium'
                    : 'border-l-3 border-l-transparent hover:bg-[var(--surface-1)]'
                }`}
              >
                <span
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{ backgroundColor: color }}
                />
                <div className="flex-1 min-w-0 flex flex-col leading-tight">
                  <span className="text-xs font-semibold text-[var(--text-strong)] truncate">
                    {driverName}
                  </span>
                  <span className="text-2xs text-[var(--text-muted)] truncate">
                    {route.name}
                    {vehicle?.name ? ` · ${vehicle.name}` : ''}
                  </span>
                </div>
                <span className="font-mono text-2xs text-[var(--text-muted)] font-medium shrink-0">
                  {route.stops?.length ?? 0}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Right scrollable area: time axis + lanes */}
      <div className="flex-1 overflow-auto bg-[var(--surface-1)]">
        <div style={{ width: totalWidth, position: 'relative' }} className="h-full flex flex-col">
          {/* Time axis header */}
          <div className="h-7 sticky top-0 z-20 bg-[var(--surface-2)] border-b border-[var(--border)] shrink-0 relative">
            {hourTicks.map((m) => (
              <div
                key={`tick-${m}`}
                style={{
                  position: 'absolute',
                  left: minToX(m),
                  top: 0,
                  bottom: 0,
                }}
                className="pl-1 border-l border-[var(--border)] flex items-center"
              >
                <span className="font-mono text-[9px] text-[var(--text-soft)] font-medium">
                  {minutesToLabel(m)}
                </span>
              </div>
            ))}
          </div>

          {/* Lanes with stop blocks */}
          <div className="flex-1 min-h-0">
            {routes.map((route, idx) => {
              const isSelected = selectedRouteId === route.id;
              const color = colorForRouteIndex(idx);
              const stops = [...(route.stops ?? [])].sort(
                (a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0),
              );
              return (
                <div
                  key={route.id}
                  style={{
                    height: LANE_HEIGHT,
                    position: 'relative',
                  }}
                  className={`border-b border-[var(--border)] transition-colors cursor-pointer ${
                    isSelected ? 'bg-[var(--surface-2)]' : 'bg-[var(--surface-1)] hover:bg-[var(--surface-2)]'
                  }`}
                  onClick={() => setSelectedRouteId(route.id)}
                >
                  {/* Hour gridlines */}
                  {hourTicks.map((m) => (
                    <div
                      key={`grid-${route.id}-${m}`}
                      style={{
                        position: 'absolute',
                        left: minToX(m),
                        top: 0,
                        bottom: 0,
                        width: 1,
                      }}
                      className="bg-[var(--border)] pointer-events-none"
                    />
                  ))}

                  {/* Planned start/end bracket */}
                  {(() => {
                    const ps = parseHHMM(route.plannedStartTime);
                    const pe = parseHHMM(route.plannedEndTime);
                    if (ps == null || pe == null || pe <= ps) return null;
                    return (
                      <div
                        style={{
                          position: 'absolute',
                          left: minToX(ps),
                          width: minToX(pe) - minToX(ps),
                          top: LANE_HEIGHT - 3,
                          height: 2,
                        }}
                        className="bg-[var(--text-muted)] pointer-events-none"
                      />
                    );
                  })()}

                  {/* Stop blocks */}
                  {stops.map((stop) => {
                    const ss = parseHHMM(stop.startTimeWindow);
                    const se = parseHHMM(stop.endTimeWindow);
                    if (ss == null || se == null || se <= ss) return null;
                    const x = minToX(ss);
                    const w = Math.max(8, minToX(se) - minToX(ss));
                    return (
                      <Tooltip key={stop.id}>
                        <TooltipTrigger asChild>
                          <div
                            style={{
                              position: 'absolute',
                              left: x,
                              top: 6,
                              height: LANE_HEIGHT - 14,
                              width: w,
                              backgroundColor: color,
                              boxShadow: isSelected
                                ? '0 0 0 1.5px var(--brand-orange)'
                                : 'none',
                            }}
                            className="rounded-[2px] flex items-center justify-center text-white text-[9px] font-bold font-mono cursor-pointer overflow-hidden whitespace-nowrap text-ellipsis px-0.5 select-none"
                          >
                            {w >= 22 ? String(stop.stopOrder ?? '').padStart(2, '0') : ''}
                          </div>
                        </TooltipTrigger>
                        <TooltipContent>
                          <div className="flex flex-col gap-0.5 leading-none">
                            <span className="font-semibold text-xs text-[var(--text-strong)]">
                              #{stop.stopOrder ?? ''} · {route.name}
                            </span>
                            <span className="font-mono text-2xs text-[var(--text-soft)] font-medium mt-0.5">
                              {stop.startTimeWindow} → {stop.endTimeWindow}
                            </span>
                          </div>
                        </TooltipContent>
                      </Tooltip>
                    );
                  })}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
