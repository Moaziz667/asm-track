import { lazy as dynamic, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useT } from '@/lib/i18n/LocaleContext';
import { usePageBreadcrumb } from '@/lib/ui/breadcrumb';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { IconAlertCircle, IconFileText, IconMap2 } from '@tabler/icons-react';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { SkeletonMap } from '@/components/feedback/SkeletonMap';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { DRIVER_STATUS_COLOR } from '@/lib/ui/design-tokens';
import RouteClosureReport from '@/features/routes/RouteClosureReport';
import { RouteHeader } from '@/components/route/RouteHeader';
import { RouteStats } from '@/components/route/RouteStats';
import { useRouteData } from './useRouteData';
import { StopCard } from './StopCard';
import { fmtLong, fmtDuration, fmtDist } from './helpers';

// ─── Dynamic Map ──────────────────────────────────────────────────────────────
const RouteTrackingMap = dynamic(() => import('@/components/RouteTrackingMap'));

export default function RouteDetailsPage() {
  const t = useT();
  const params = useParams<{ id: string }>();
  const routeId = Array.isArray(params?.id) ? params.id[0] : params?.id;
  const navigate = useNavigate();

  const d = useRouteData(routeId);
  const { route, loading } = d;
  // Closed-route view switcher (report ↔ normal map+stops). Declared before any early return.
  const [closedView, setClosedView] = useState<'report' | 'stops'>('report');

  usePageBreadcrumb(
    route
      ? [{ label: t.pages.routes?.title || 'Tournées', href: '/routes-table' }, { label: route.name }]
      : [{ label: t.pages.routes?.title || 'Tournées', href: '/routes-table' }]
  );

  if (loading) {
    return (
      <div className="flex flex-col h-full bg-[var(--app-bg)]">
        <div className="h-14 flex items-center gap-3 px-6 border-b border-[var(--border-color)] bg-[var(--surface)] shrink-0">
          <div className="w-8 h-8 bg-[var(--surface-hover)] rounded animate-pulse" />
          <div className="flex-1 max-w-xs h-4 bg-[var(--surface-hover)] rounded animate-pulse" />
        </div>
        <div className="border-b border-[var(--border-color)] bg-[var(--surface)] p-3 shrink-0">
          <div className="grid grid-cols-4 gap-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-12 bg-[var(--surface-hover)] rounded animate-pulse" />
            ))}
          </div>
        </div>
        <div className="flex-1 p-6">
          <SkeletonMap height={400} />
        </div>
      </div>
    );
  }

  if (!route) {
    return (
      <div className="h-full flex items-center justify-center bg-[var(--app-bg)]">
        <div className="flex flex-col items-center gap-4 text-center">
          <IconAlertCircle size={32} className="text-[var(--text-muted)]" />
          <p className="text-sm font-semibold text-[var(--text-muted)]">{t.routeBuilderPage.routeNotFound}</p>
          <Button onClick={() => navigate('/routes-table')} variant="outline" size="sm">
            {t.routeBuilderPage.backToRoutes}
          </Button>
        </div>
      </div>
    );
  }

  const completed = route.completedStops ?? 0;
  const failed = route.failedStops ?? 0;
  const partial = (route as { partialStops?: number }).partialStops ?? 0;
  const total = route.totalStops ?? d.orderedStops.length;
  // "Done" = every terminal stop: delivered, partial AND failed (échec). A failed stop is finished,
  // so a 1-stop route that failed reads 1/1, not 0/1.
  const pct = route.progressPercent ?? (total > 0 ? ((completed + failed + partial) / total * 100) : 0);
  const currency = d.orderedStops.find((s) => s.order?.currency)?.order?.currency ?? 'TND';
  const isActiveRoute = route.status === 'VALIDATED' || route.status === 'IN_PROGRESS';
  const isClosed = route?.status === 'CLOSED';
  // Closed routes default to the report; the switcher lets the user flip to the normal map + stops view.
  const showReport = isClosed && closedView === 'report';
  const showNormal = !isClosed || closedView === 'stops';

  return (
    <div className="flex flex-col h-full bg-[var(--app-bg)]" style={{ height: showReport ? 'auto' : 'calc(100dvh - 56px)', minHeight: showReport ? 'calc(100dvh - 56px)' : undefined, overflow: showReport ? 'visible' : 'hidden' }}>
      <RouteHeader
        routeName={route.name}
        routeStatus={route.status}
        isOptimized={route.isOptimized}
        date={route.date}
        city={route.city}
        onBack={() => navigate('/routes-table')}
        onRefresh={() => void d.fetchData()}
        isRefreshing={loading}
      />

      {/* Closed route → switch between the closure report and the normal map + stops view. */}
      {isClosed && (
        <TooltipProvider>
          <div className="print-hide flex items-center px-4 py-2 border-b border-[var(--border-color)] bg-[var(--surface)] shrink-0">
            <div className="inline-flex items-center gap-0.5 p-0.5 rounded-md border border-[var(--border)] bg-[var(--surface-sunken)]">
              {([['report', t.routeReport.subtitle, IconFileText], ['stops', t.routeReport.viewNormal, IconMap2]] as const).map(([v, label, Icon]) => {
                const active = closedView === v;
                return (
                  <Tooltip key={v}>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        aria-label={label}
                        aria-pressed={active}
                        onClick={() => setClosedView(v)}
                        className={`h-7 w-8 flex items-center justify-center rounded transition-all ${active ? 'bg-[var(--surface)] text-[var(--text-primary)] shadow-sm' : 'text-[var(--text-muted)] hover:text-[var(--text-secondary)]'}`}
                      >
                        <Icon size={15} stroke={1.8} />
                      </button>
                    </TooltipTrigger>
                    <TooltipContent>{label}</TooltipContent>
                  </Tooltip>
                );
              })}
            </div>
          </div>
        </TooltipProvider>
      )}

      {/* Active route → live detail (KPI strip + map + editable stops). Closed route → report or,
          via the switcher, the same read-only map + stops view. */}
      {showNormal && (<>
      <RouteStats
        completed={completed}
        failed={failed}
        partial={partial}
        total={total}
        progressPercent={pct}
        driverId={route.driver?.id}
        driverName={route.driver?.name}
        driverStatus={d.driverOnlineStatus ?? undefined}
        driverStatusColor={d.driverOnlineStatus ? (DRIVER_STATUS_COLOR[d.driverOnlineStatus as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE) : undefined}
        cumulativeDelayMinutes={route.cumulativeDelayMinutes}
        routeStartDelayMinutes={route.routeStartDelayMinutes}
        onTimeCompletionRate={route.onTimeCompletionRate}
        distance={fmtDist(route.totalDistanceMeters)}
        duration={fmtDuration(route.totalDurationSeconds)}
        vehiclePlate={route.vehicle?.plate}
        vehicleType={route.vehicle?.type || route.vehicle?.name}
        totalWeightKg={d.totalWeightKg}
        vehicleCapacityKg={route.vehicle?.payloadKg}
      />

      <div className="lg:hidden flex shrink-0 border-b border-[var(--border-color)] bg-[var(--surface)]">
        {([['map', t.routeBuilderPage.tabMap], ['stops', t.routeBuilderPage.tabStops]] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => d.setMobilePanel(tab)}
            className={`flex-1 h-10 text-xs font-semibold transition-colors ${
              d.mobilePanel === tab ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]' : 'text-[var(--text-muted)]'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 overflow-hidden">
        <div className={`lg:w-[45%] lg:flex lg:flex-col lg:shrink-0 lg:border-r lg:border-[var(--border-color)] bg-[var(--surface)] ${d.mobilePanel === 'map' ? 'flex flex-col w-full' : 'hidden lg:flex'}`}>
          <div className="flex-1 min-h-[300px]">
            <RouteTrackingMap
              stops={d.mapStops}
              driver={route.driver ? {
                id: route.driver.id,
                name: route.driver.name ?? '',
                lat: route.driver.currentLat,
                lng: route.driver.currentLng,
                lastLocationAt: d.driverLastSeen,
              } : null}
              depot={route.depot?.latitude != null && route.depot?.longitude != null ? {
                lat: route.depot.latitude,
                lng: route.depot.longitude,
                name: route.depot.name,
              } : null}
              height="100%"
              onStopClick={d.scrollToStop}
            />
          </div>

          <div className="shrink-0 border-t border-[var(--border-color)] overflow-y-auto max-h-[180px] bg-[var(--surface)] space-y-4 p-3">
            <div className="border-b border-[var(--border-color)] pb-3">
              <p className="text-xs font-semibold text-[var(--text-muted)] mb-2 uppercase">{t.routeBuilderPage.depotLabel}</p>
              {route.depot ? (
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate('/depots')}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate('/depots'); } }}
                  className="space-y-1 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] rounded"
                >
                  <p className="text-xs font-semibold text-[var(--text-primary)] hover:text-[var(--brand)] transition-colors">{route.depot.name ?? '—'}</p>
                  <p className="text-xs text-[var(--text-muted)]">{route.depot.address ?? route.depot.city ?? '—'}</p>
                  {route.departureTime && <p className="text-xs text-[var(--text-muted)] font-mono">{t.routeBuilderPage.departureLabel} {fmtLong(route.departureTime)}</p>}
                </div>
              ) : (
                <p className="text-xs text-[var(--text-muted)]">{t.routeBuilderPage.noDepot}</p>
              )}
            </div>

            <div>
              <p className="text-xs font-semibold text-[var(--text-muted)] mb-2 uppercase">{t.routeBuilderPage.lifecycleTitle}</p>
              <div className="grid grid-cols-2 gap-2 text-xs">
                {[
                  [t.routeBuilderPage.statusCreated, fmtLong(route.createdAt)],
                  [t.routeBuilderPage.statusValidated, fmtLong(route.validatedAt)],
                  [t.routeBuilderPage.statusStarted, fmtLong(route.startedAt)],
                  [t.routeBuilderPage.statusClosed, fmtLong(route.closedAt)],
                ].map(([l, v]) => (
                  <div key={l}>
                    <p className="text-[var(--text-muted)]">{l}:</p>
                    <p className="font-mono text-[var(--text-primary)] font-semibold">{v}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        <div className={`flex-1 flex flex-col overflow-hidden bg-[var(--surface)] min-w-0 ${d.mobilePanel === 'stops' ? 'flex' : 'hidden lg:flex'}`}>
          <div className="px-4 h-11 flex items-center justify-between border-b border-[var(--border-color)] shrink-0">
            <div className="flex items-center gap-2">
              <p className="text-xs font-semibold text-[var(--text-primary)]">{t.routeBuilderPage.stopSequence}</p>
              <Badge variant="secondary" className="text-2xs font-mono">{d.orderedStops.length}</Badge>
            </div>
            <div className="flex items-center gap-2">
              <p className="text-xs font-mono font-semibold text-[var(--text-muted)]">{completed + failed + partial}/{total}</p>
              {failed > 0 && (
                <Badge variant="destructive" className="text-2xs">
                  {failed}
                </Badge>
              )}
            </div>
          </div>

          <div className="flex-1 overflow-y-auto bg-[var(--surface-sunken)] p-3">
            {d.orderedStops.map((stop) => (
              <StopCard
                key={stop.id}
                stop={stop}
                delivery={d.deliveryMap[stop.deliveryId]}
                pod={d.podMap[stop.deliveryId]}
                route={route}
                currency={currency}
                isActiveRoute={isActiveRoute}
                isExpanded={d.expandedStops.has(stop.id)}
                stopRefs={d.stopRefs}
                toggleStop={d.toggleStop}
                downloadBL={d.downloadBL}
                openEditWindow={d.openEditWindow}
                setRemoveStopTarget={d.setRemoveStopTarget}
                setCancelStopTarget={d.setCancelStopTarget}
                setCancelStopReason={d.setCancelStopReason}
                setViewerTitle={d.setViewerTitle}
                setViewerImage={d.setViewerImage}
              />
            ))}
          </div>
        </div>
      </div>
      </>)}

      {showReport && typeof routeId === 'string' && (
        <RouteClosureReport routeId={routeId} />
      )}

      <ConfirmModal
        open={d.cancelStopTarget !== null}
        title={t.routeBuilderPage.removeStopTitle}
        description={t.routeBuilderPage.removeStopTitle + '\n\nClient: ' + d.cancelStopTarget?.client}
        variant="danger"
        reasonLabel={t.routeBuilderPage.reasonRequired}
        reasonPlaceholder={t.routeBuilderPage.reasonPlaceholder}
        reason={d.cancelStopReason}
        onReasonChange={d.setCancelStopReason}
        reasonRequired={true}
        confirmLabel={t.routeBuilderPage.removeStop}
        cancelLabel={t.routeBuilderPage.closeLabel}
        loading={d.cancellingStop}
        onConfirm={() => void d.handleCancelStop()}
        onCancel={() => { d.setCancelStopTarget(null); d.setCancelStopReason(''); }}
      />

      <ConfirmModal
        open={d.removeStopTarget !== null}
        title={t.routeBuilderPage.removeStopTitle}
        description={t.routeBuilderPage.removeStop + ' "' + (d.removeStopTarget?.client ?? '') + '"'}
        variant="danger"
        reasonLabel={t.routeBuilderPage.reasonRequired}
        reasonPlaceholder={t.routeBuilderPage.reasonPlaceholder}
        reason={d.removeStopReason}
        onReasonChange={d.setRemoveStopReason}
        reasonRequired={true}
        confirmLabel={t.routeBuilderPage.removeStop}
        cancelLabel={t.routeBuilderPage.cancelLabel}
        loading={d.removingStop}
        onConfirm={() => void d.handleRemoveStop()}
        onCancel={() => { d.setRemoveStopTarget(null); d.setRemoveStopReason(''); }}
      />

      <Dialog open={d.editWindowTarget !== null} onOpenChange={(open) => !open && d.setEditWindowTarget(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t.routeBuilderPage.labelWindow} — {d.editWindowTarget?.client}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-xs font-semibold">{t.routeBuilderPage.startLabel}</label>
                <Input
                  type="time"
                  value={d.editWindowStart}
                  onChange={(e) => d.setEditWindowStart(e.target.value)}
                  className="mt-1"
                />
              </div>
              <div>
                <label className="text-xs font-semibold">{t.routeBuilderPage.endLabel}</label>
                <Input
                  type="time"
                  value={d.editWindowEnd}
                  onChange={(e) => d.setEditWindowEnd(e.target.value)}
                  className="mt-1"
                />
              </div>
            </div>

            {d.editErrStartGtEnd && (
              <div className="text-xs text-rose-600 bg-rose-50 dark:bg-rose-950/30 dark:text-rose-400 p-2.5 rounded border border-rose-200 dark:border-rose-900/50">
                {t.routeBuilderPage.invalidWindow}
              </div>
            )}

            {d.editOverlaps.length > 0 && (
              <div className="text-xs text-amber-600 bg-amber-50 dark:bg-amber-950/30 dark:text-amber-400 p-2.5 rounded border border-amber-200 dark:border-amber-900/50">
                {t.routeBuilderPage.windowOverlap}
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => d.setEditWindowTarget(null)}>{t.routeBuilderPage.cancelLabel}</Button>
            <Button onClick={() => void d.handleSaveWindow()} disabled={d.editErrStartGtEnd || d.savingWindow}>{t.routeBuilderPage.saveLabel}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!d.viewerImage} onOpenChange={(open) => !open && d.setViewerImage(null)}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{d.viewerTitle}</DialogTitle>
          </DialogHeader>
          {d.viewerImage && (
            <img src={d.viewerImage} alt={d.viewerTitle} className="w-full h-auto max-h-96 object-contain" />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
