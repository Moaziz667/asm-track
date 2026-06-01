'use client';

import {
  IconBolt,
  IconCheck,
  IconClock,
  IconRoute,
  IconArrowRight,
  IconMapPin,
  IconGauge,
} from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';

interface OptimizePreviewRow {
  key: string;
  sequenceOrder: number;
  clientName: string;
  dropoffAddress?: string;
  dropoffCity?: string;
  etaAt: string;
  suggestedStart: string;
  suggestedEnd: string;
  driveDurationSeconds?: number;
  driveDistanceMeters?: number;
}

interface OptimizePreviewProps {
  rows: OptimizePreviewRow[];
  optimizationStartTime: string;
  setOptimizationStartTime: (val: string) => void;
  totalDurationSeconds?: number;
  totalDistanceMeters?: number;
  distanceSavedMeters?: number;
  durationSavedSeconds?: number;
  onApply: () => Promise<void> | void;
  onCancel: () => void;
  applying: boolean;
}

const formatDuration = (seconds?: number) => {
  if (!seconds || seconds <= 0) return '—';
  const mins = Math.round(seconds / 60);
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m === 0 ? `${h}h` : `${h}h${String(m).padStart(2, '0')}`;
};

const formatDistance = (meters?: number) => {
  if (!meters || meters <= 0) return '—';
  return `${(meters / 1000).toFixed(1)} km`;
};

export function OptimizePreview({
  rows,
  optimizationStartTime,
  setOptimizationStartTime,
  totalDurationSeconds,
  totalDistanceMeters,
  distanceSavedMeters,
  durationSavedSeconds,
  onApply,
  onCancel,
  applying,
}: OptimizePreviewProps) {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const hasGain = (distanceSavedMeters ?? 0) > 0 || (durationSavedSeconds ?? 0) > 0;

  return (
    <div className="flex flex-col h-full bg-[var(--surface-1)]">
      {/* Brand color top accent */}
      <div className="h-[3px] bg-[var(--brand-orange)] shrink-0" />

      {/* Header */}
      <div className="flex flex-col gap-4 p-4 border-b border-[var(--border)] shrink-0">
        <div className="flex justify-between items-start gap-4">
          <div className="flex gap-3 items-start min-w-0">
            <div className="w-8 h-8 rounded bg-[var(--brand-orange)] flex items-center justify-center shrink-0">
              <IconBolt size={16} className="text-white" />
            </div>
            <div className="flex flex-col gap-1 min-w-0">
              <h4 className="text-sm font-semibold text-[var(--text-strong)] leading-none">{t.routeBuilderPage.optimizePreviewTitle}</h4>
              {hasGain ? (
                <div className="flex items-center gap-1.5 flex-wrap">
                  <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-[var(--brand-soft)] text-[var(--brand-orange)] border border-[var(--brand-soft)]">
                    {t.routeBuilderPage.gainLabel} {formatDistance(distanceSavedMeters)}
                  </span>
                  {(durationSavedSeconds ?? 0) > 0 && (
                    <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-[var(--brand-soft)] text-[var(--brand-orange)] border border-[var(--brand-soft)]">
                      − {formatDuration(durationSavedSeconds)}
                    </span>
                  )}
                </div>
              ) : (
                <span className="text-xs text-[var(--text-muted)]">
                  {t.routeBuilderPage.suggestedRouteLabel}
                </span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            <Button
              variant="outline"
              size="sm"
              onClick={onCancel}
              disabled={applying}
              className="h-8 text-xs bg-[var(--surface-2)] border-[var(--border)] text-[var(--text-strong)] hover:bg-[var(--hover-bg)]"
            >
              {t.actions.cancel}
            </Button>
            <Button
              size="sm"
              loading={applying}
              disabled={rows.length === 0}
              onClick={() => void onApply()}
              className="h-8 text-xs bg-[var(--brand-orange)] hover:opacity-90 font-semibold text-white border-transparent"
            >
              <IconCheck size={13} className="mr-1" />
              {t.routeBuilderPage.applyButton}
            </Button>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-[11px] font-medium text-[var(--text-soft)]">{t.routeBuilderPage.departureTimeLabel}</label>
            <input
              type="time"
              value={optimizationStartTime}
              onChange={(e) => setOptimizationStartTime(e.currentTarget.value)}
              disabled={applying}
              className="w-full h-8 px-2 rounded bg-[var(--surface-2)] border border-[var(--border)] text-xs text-[var(--text-strong)] font-mono focus:outline-none focus:border-[var(--brand)]"
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-[11px] font-medium text-[var(--text-soft)]">{t.routeBuilderPage.headerDistance}</span>
            <div className="h-8 px-2 flex items-center gap-1.5 rounded bg-[var(--surface-2)] border border-[var(--border)] font-mono text-xs font-semibold text-[var(--text-strong)]">
              <IconRoute size={13} className="text-[var(--text-muted)]" />
              <span>{formatDistance(totalDistanceMeters)}</span>
            </div>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-[11px] font-medium text-[var(--text-soft)]">{t.routeBuilderPage.headerDuration}</span>
            <div className="h-8 px-2 flex items-center gap-1.5 rounded bg-[var(--surface-2)] border border-[var(--border)] font-mono text-xs font-semibold text-[var(--text-strong)]">
              <IconClock size={13} className="text-[var(--text-muted)]" />
              <span>
                {(() => {
                  const mins = rows.reduce((s, r) => s + Math.round((r.driveDurationSeconds ?? 0) / 60), 0);
                  return mins > 0 ? (mins < 60 ? `${mins} min` : `${Math.floor(mins/60)}h${String(mins%60).padStart(2,'0')}`) : formatDuration(totalDurationSeconds);
                })()}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Stops timeline */}
      <div className="flex-1 overflow-y-auto p-4 bg-[var(--surface-1)] min-h-0">
        {rows.length === 0 ? (
          <div className="h-44 flex items-center justify-center">
            <span className="text-xs text-[var(--text-muted)]">{t.routeBuilderPage.noSuggestionLabel}</span>
          </div>
        ) : (
          <div className="relative pl-8 border-l border-[var(--border)] flex flex-col gap-6 ml-3 my-2">
            {rows.map((row) => (
              <div key={row.key} className="relative flex flex-col gap-1.5">
                {/* Node dot with order number */}
                <div className="absolute -left-[45px] top-0 w-6 h-6 rounded-full bg-[var(--surface-1)] border-2 border-[var(--brand-orange)] text-[var(--brand-orange)] text-[10px] font-bold flex items-center justify-center font-mono z-10">
                  {String(row.sequenceOrder).padStart(2, '0')}
                </div>

                <div className="flex justify-between items-start gap-4">
                  <div className="flex flex-col gap-0.5 min-w-0">
                    <span className="text-xs font-semibold text-[var(--text-strong)] truncate">
                      {row.clientName}
                    </span>
                    {(row.dropoffAddress || row.dropoffCity) && (
                      <div className="flex items-center gap-1 text-[11px] text-[var(--text-soft)] truncate">
                        <IconMapPin size={11} className="text-[var(--text-muted)]" />
                        <span className="truncate">
                          {[row.dropoffAddress, row.dropoffCity].filter(Boolean).join(', ')}
                        </span>
                      </div>
                    )}
                  </div>

                  <div className="flex flex-col items-end gap-1 shrink-0">
                    <span className="font-mono text-xs font-bold text-[var(--text-strong)] leading-none">
                      {row.suggestedStart}
                    </span>
                    <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-[var(--brand-soft)] text-[var(--brand-orange)] border border-[var(--brand-soft)] text-[10px] font-mono font-semibold">
                      <span>{row.suggestedStart}</span>
                      <IconArrowRight size={8} />
                      <span>{row.suggestedEnd}</span>
                    </span>
                  </div>
                </div>

                {(row.driveDurationSeconds ?? 0) > 0 && (
                  <div className="flex items-center gap-2 text-[10px] text-[var(--text-muted)] bg-[var(--surface-2)] border border-[var(--border)] rounded px-2 py-1 w-fit mt-0.5">
                    <div className="flex items-center gap-1 font-mono">
                      <IconClock size={11} />
                      <span>{formatDuration(row.driveDurationSeconds)}</span>
                    </div>
                    <div className="flex items-center gap-1 font-mono">
                      <IconGauge size={11} />
                      <span>{formatDistance(row.driveDistanceMeters)}</span>
                    </div>
                    <span className="text-[var(--text-soft)]">{t.routeBuilderPage.sincePreviousStopLabel}</span>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
