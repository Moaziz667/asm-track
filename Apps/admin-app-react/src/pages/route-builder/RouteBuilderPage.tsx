
import { useT } from '@/lib/i18n/LocaleContext';
import { tcount } from '@/lib/i18n/i18n-dict';
import { lazy as dynamic } from 'react';
import { useBreakpoint } from '@/hooks/use-mobile';
import { cn } from '@/lib/utils';
import {
  IconMapPin as MapPinnedIcon,
  IconRoute as RouteIcon,
  IconClock as ClockIcon,
  IconChevronUp as ChevronUp,
  IconChevronDown as ChevronDown,
  IconArrowsMove as Move,
  IconList,
  IconCalendarStats,
  IconMapPlus } from '@tabler/icons-react';
import { AppLoader } from '@/components/AppLoader';
import { RouteBuilderProvider, useRouteBuilderContext } from './hooks/useRouteBuilder';
import { RouteSidebar } from './components/RouteSidebar';
import { StopsPanel } from './components/StopsPanel';
import { OrdersTable } from './components/OrdersTable';
import { RoutesTable } from './components/RoutesTable';
import { TimelineGantt } from './components/TimelineGantt';
import { ActionBar } from './components/ActionBar';
import {
  CreateRouteModal,
  SettingsModal,
  DeleteModal,
  ValidationModal,
} from './components/RouteModals';
import { useState, useCallback, useRef, useEffect, Suspense } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  closestCenter,
} from '@dnd-kit/core';
import { sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import { StopRow } from './components/StopRow';
import { resolveOrderRef, shortId } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';

// Import our core custom stylesheet
import './route-builder.scss';

const formatKm = (m?: number) => (!m || m <= 0 ? '—' : `${(m / 1000).toFixed(1)} km`);
const formatMin = (s?: number) => {
  if (!s || s <= 0) return '—';
  const mins = Math.round(s / 60);
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m === 0 ? `${h}h` : `${h}h${String(m).padStart(2, '0')}`;
};

const RouteBuilderMap = dynamic(() => import('@/components/RouteBuilderMap'));

function RouteBuilderPageInner() {
  const t = useT();
  const rb = useRouteBuilderContext();
  const unscheduledCount = rb.waitingDeliveries.length;
  const routesCount = rb.routes.length;
  const scheduledCount = rb.routes.reduce((sum, r) => sum + (r.stops?.filter(s => s.stopType !== 'PICKUP').length ?? 0), 0);
  const totalCount = scheduledCount + unscheduledCount;
  const [searchParams] = useSearchParams();
  const { isMobile, isTablet } = useBreakpoint();
  const isMobileOrTablet = isMobile || isTablet;
  const [builderTab, setBuilderTab] = useState<'routes' | 'stops' | 'map'>('routes');

  // Auto-select route from URL param (e.g. navigated from dispatch-desk after batch assign)
  useEffect(() => {
    const routeId = searchParams.get('routeId');
    if (routeId && rb.routes.length > 0 && !rb.selectedRouteId) {
      rb.setSelectedRouteId(routeId);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, rb.routes]);

  // ── Drag ghost preview index (wheel to cycle through selected orders) ───
  const [previewIndex, setPreviewIndex] = useState(0);
  useEffect(() => {
    // Reset to first card whenever a new drag starts
    setPreviewIndex(0);
  }, [rb.activeDragId]);
  useEffect(() => {
    if (!rb.activeDragId?.startsWith('order:')) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const total = rb.selectedOrderIds.includes(rb.activeDragId!.slice('order:'.length)) && rb.selectedOrderIds.length > 1
        ? rb.selectedOrderIds.length
        : 1;
      if (total <= 1) return;
      setPreviewIndex((i) => e.deltaY > 0 ? (i + 1) % total : (i - 1 + total) % total);
    };
    window.addEventListener('wheel', onWheel, { passive: false });
    return () => window.removeEventListener('wheel', onWheel);
  }, [rb.activeDragId, rb.selectedOrderIds]);

  // Custom Resize States
  const [sidebarWidth, setSidebarWidth] = useState(240);
  const [stopsWidth, setStopsWidth] = useState(380);
  const [sheetHeight, setSheetHeight] = useState(320);
  const [sheetCollapsed, setSheetCollapsed] = useState(false);
  const [mapLayer, setMapLayer] = useState<'street' | 'satellite' | 'hot'>('street');
  const [showDepot, setShowDepot] = useState(true);
  const [bottomTab, setBottomTab] = useState<'orders' | 'routes' | 'timeline'>('orders');
  const [targetRouteId, setTargetRouteId] = useState<string | null>(null);

  // ── DnD sensors (pointer + keyboard) ────────────────────────────────────
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  // ── Map pin drag state ───────────────────────────────────────────────────
  type PinDrag = { orderIds: string[]; clientName: string; x: number; y: number } | null;
  const [pinDrag, setPinDrag] = useState<PinDrag>(null);
  const pinDragDataRef = useRef<{ orderIds: string[] } | null>(null);
  const isPinDraggingRef = useRef(false);
  const pinGhostRef = useRef<HTMLDivElement | null>(null);
  const assignRef = useRef(rb.assignDeliveriesToRoute);
  useEffect(() => { assignRef.current = rb.assignDeliveriesToRoute; }, [rb.assignDeliveriesToRoute]);

  const handlePinDragStart = useCallback((
    _orderId: string, orderIds: string[], clientName: string, e: MouseEvent,
  ) => {
    isPinDraggingRef.current = true;
    pinDragDataRef.current = { orderIds };
    setPinDrag({ orderIds, clientName, x: e.clientX + 14, y: e.clientY - 10 });
    document.body.classList.add('pin-drag-active');
    document.body.style.cursor = 'grabbing';
  }, []);

  // Update ghost position on mousemove — direct DOM write, no re-render
  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!isPinDraggingRef.current || !pinGhostRef.current) return;
      pinGhostRef.current.style.left = `${e.clientX + 14}px`;
      pinGhostRef.current.style.top = `${e.clientY - 10}px`;
    };
    window.addEventListener('mousemove', onMove);
    return () => window.removeEventListener('mousemove', onMove);
  }, []);

  // Drop detection on mouseup
  useEffect(() => {
    const cancelDrag = () => {
      isPinDraggingRef.current = false;
      pinDragDataRef.current = null;
      setPinDrag(null);
      document.body.classList.remove('pin-drag-active');
      document.body.style.cursor = 'default';
    };
    const onUp = (e: MouseEvent) => {
      if (!isPinDraggingRef.current) return;
      const el = document.elementFromPoint(e.clientX, e.clientY);
      const routeEl = (el as HTMLElement)?.closest('[data-route-id]') as HTMLElement | null;
      const routeId = routeEl?.dataset?.routeId;
      if (routeId && pinDragDataRef.current) {
        void assignRef.current(pinDragDataRef.current.orderIds, routeId);
      }
      cancelDrag();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') cancelDrag();
    };
    window.addEventListener('mouseup', onUp);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('keydown', onKey);
    };
  }, []);

  // Keep targetRouteId in sync with the selected route so single-click behaviour still works
  useEffect(() => {
    setTargetRouteId(rb.selectedRouteId ?? null);
  }, [rb.selectedRouteId]);

  const COLLAPSED_HEIGHT = 44;

  const isResizingSidebar = useRef(false);
  const isResizingStops = useRef(false);
  const isResizingBottom = useRef(false);

  const startResizingSidebar = useCallback(() => {
    isResizingSidebar.current = true;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }, []);

  const startResizingStops = useCallback(() => {
    isResizingStops.current = true;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }, []);

  const startResizingBottom = useCallback(() => {
    if (sheetCollapsed) return;
    isResizingBottom.current = true;
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
  }, [sheetCollapsed]);

  const stopResizing = useCallback(() => {
    isResizingSidebar.current = false;
    isResizingStops.current = false;
    isResizingBottom.current = false;
    document.body.style.cursor = 'default';
    document.body.style.userSelect = 'auto';
  }, []);

  const resize = useCallback((e: MouseEvent) => {
    if (isResizingSidebar.current) {
      setSidebarWidth(Math.max(200, Math.min(e.clientX, 500)));
    } else if (isResizingStops.current) {
      const mouseXInWorkspace = e.clientX - sidebarWidth;
      setStopsWidth(Math.max(300, Math.min(mouseXInWorkspace, window.innerWidth - sidebarWidth - 400)));
    } else if (isResizingBottom.current) {
      const windowHeight = window.innerHeight;
      const newHeight = windowHeight - e.clientY;
      setSheetHeight(Math.max(140, Math.min(newHeight, windowHeight - 200)));
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    window.addEventListener('mousemove', resize);
    window.addEventListener('mouseup', stopResizing);
    return () => {
      window.removeEventListener('mousemove', resize);
      window.removeEventListener('mouseup', stopResizing);
    };
  }, [resize, stopResizing]);

  if (rb.loading) {
    return <AppLoader centered height="100vh" size="xl" label={t.loading.generic} />;
  }

  const renderMapPanel = () => (
    <div className="flex-1 relative bg-[var(--surface-2)] min-w-0 h-full">
      <RouteBuilderMap
        onPinDragStart={handlePinDragStart}
        getDragging={() => isPinDraggingRef.current}
        mapLayer={mapLayer}
        showDepot={showDepot}
      />

      {/* FLOATING LAYER PANEL (top-right) */}
      <div
        style={{ zIndex: 100 }}
        className="absolute top-4 right-4 p-2.5 rounded bg-[var(--surface-1)] border border-[var(--border)] shadow-md flex flex-col gap-2"
      >
        <div className="flex bg-[var(--surface-2)] border border-[var(--border)] rounded p-0.5 shrink-0">
          {([
            { value: 'street', label: t.routeBuilderPage.mapLayerStreet },
            { value: 'hot', label: t.routeBuilderPage.mapLayerHot },
            { value: 'satellite', label: t.routeBuilderPage.mapLayerSatellite },
          ] as const).map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => setMapLayer(opt.value)}
              className={`px-2.5 py-1 text-2xs font-semibold rounded-sm transition-all cursor-pointer ${
                mapLayer === opt.value
                  ? 'bg-[var(--surface-1)] text-[var(--text-strong)] shadow-sm'
                  : 'text-[var(--text-soft)] hover:text-[var(--text-strong)]'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <label className="flex items-center gap-2 cursor-pointer select-none">
          <input
            type="checkbox"
            checked={showDepot}
            onChange={(e) => setShowDepot(e.target.checked)}
            className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 cursor-pointer"
          />
          <span className="text-xs font-medium text-[var(--text-strong)]">{t.routeBuilderPage.layerDepots}</span>
        </label>

        <label className="flex items-center gap-2 cursor-pointer select-none">
          <input
            type="checkbox"
            checked={rb.showRouteTrajet}
            onChange={(e) => rb.setShowRouteTrajet(e.target.checked)}
            className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 cursor-pointer"
          />
          <span className="text-xs font-medium text-[var(--text-strong)]">{t.routeBuilderPage.layerTraces}</span>
        </label>
      </div>

      {/* FLOATING OVERLAY: Zone + Totals (top-left) */}
      <div
        style={{ zIndex: 100 }}
        className="absolute top-4 left-4 p-2.5 rounded bg-[var(--surface-1)] border border-[var(--border)] shadow-md flex items-center gap-3.5 select-none"
      >
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded bg-[var(--surface-2)] border border-[var(--border)] flex items-center justify-center text-[var(--brand-orange)] shrink-0">
            <MapPinnedIcon size={14} />
          </div>
          <div className="flex flex-col leading-tight">
            <span className="text-2xs text-[var(--text-muted)] font-medium">{t.routeBuilderPage.activeZoneLabel}</span>
            <span className="text-xs font-semibold text-[var(--text-strong)]">
              {rb.selectedRouteZoneLabel || t.routeBuilderPage.activeZoneNone}
            </span>
          </div>
        </div>

        {rb.selectedRoute && (
          <>
            <div className="h-6 w-[1px] bg-[var(--border)]" />
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1 font-mono text-xs font-semibold text-[var(--text-strong)]">
                <RouteIcon size={13} className="text-[var(--text-muted)]" />
                <span>{formatKm(rb.selectedRoute.totalDistanceMeters ?? rb.selectedRoute.totalDistance)}</span>
              </div>
              <div className="flex items-center gap-1 font-mono text-xs font-semibold text-[var(--text-strong)]">
                <ClockIcon size={13} className="text-[var(--text-muted)]" />
                <span>{formatMin(rb.selectedRoute.totalDurationSeconds ?? rb.selectedRoute.totalDuration)}</span>
              </div>
            </div>
          </>
        )}
      </div>

      {/* FLOATING BOTTOM SHEET: Orders */}
      <div
        style={{
          position: 'absolute',
          left: 12,
          right: 12,
          bottom: 12,
          height: sheetCollapsed ? COLLAPSED_HEIGHT : sheetHeight,
          zIndex: 100,
          transition: 'height 160ms ease',
        }}
        className="bg-[var(--surface-1)] border border-[var(--border)] rounded flex flex-col overflow-hidden shadow-lg"
      >
        {/* Resize handle (top edge) */}
        {!sheetCollapsed && (
          <div
            onMouseDown={startResizingBottom}
            className="h-1.5 cursor-row-resize hover:bg-[var(--surface-2)] transition-colors flex items-center justify-center shrink-0 border-b border-[var(--border)]"
            title={t.routeBuilderPage.actionBarSettingsTooltip}
          >
            <div className="w-10 h-[2px] bg-[var(--border)] rounded-full" />
          </div>
        )}

        {/* Sheet header: tabs (always visible, even when collapsed) */}
        <div className="flex items-center gap-1 bg-[var(--surface-2)] p-1 rounded-t border-b border-[var(--border)] shrink-0 h-11 justify-between px-3">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                setBottomTab('orders');
                if (sheetCollapsed) setSheetCollapsed(false);
              }}
              className={`px-3 py-1.5 text-xs font-semibold rounded transition-all cursor-pointer flex items-center gap-1.5 ${
                bottomTab === 'orders'
                  ? 'bg-[var(--surface-1)] text-[var(--text-strong)] border border-[var(--border)] shadow-sm'
                  : 'text-[var(--text-soft)] hover:text-[var(--text-strong)]'
              }`}
            >
              <IconList size={13} />
              <span>{t.routeBuilderPage.tabOrders}</span>
              <span className={`inline-flex items-center px-1.5 py-0.5 rounded-xs text-2xs font-bold ${
                bottomTab === 'orders'
                  ? 'bg-[var(--brand-soft)] text-[var(--brand-orange)]'
                  : 'bg-[var(--surface-2)] text-[var(--text-soft)]'
              }`}>
                {rb.filteredDeliveries.length}
              </span>
            </button>

            <button
              type="button"
              onClick={() => {
                setBottomTab('routes');
                if (sheetCollapsed) setSheetCollapsed(false);
              }}
              className={`px-3 py-1.5 text-xs font-semibold rounded transition-all cursor-pointer flex items-center gap-1.5 ${
                bottomTab === 'routes'
                  ? 'bg-[var(--surface-1)] text-[var(--text-strong)] border border-[var(--border)] shadow-sm'
                  : 'text-[var(--text-soft)] hover:text-[var(--text-strong)]'
              }`}
            >
              <IconMapPlus size={13} className="shrink-0" />
              <span>{t.routeBuilderPage.tabRoutes}</span>
              <span className={`inline-flex items-center px-1.5 py-0.5 rounded-xs text-2xs font-bold ${
                bottomTab === 'routes'
                  ? 'bg-[var(--surface-2)] text-[var(--text-soft)]'
                  : 'bg-[var(--surface-2)] text-[var(--text-soft)]'
              }`}>
                {rb.routes.length}
              </span>
            </button>

            <button
              type="button"
              onClick={() => {
                setBottomTab('timeline');
                if (sheetCollapsed) setSheetCollapsed(false);
              }}
              className={`px-3 py-1.5 text-xs font-semibold rounded transition-all cursor-pointer flex items-center gap-1.5 ${
                bottomTab === 'timeline'
                  ? 'bg-[var(--surface-1)] text-[var(--text-strong)] border border-[var(--border)] shadow-sm'
                  : 'text-[var(--text-soft)] hover:text-[var(--text-strong)]'
              }`}
            >
              <IconCalendarStats size={13} />
              <span>{t.routeBuilderPage.tabTimeline}</span>
            </button>
          </div>

          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={(e) => {
                  e.stopPropagation();
                  setSheetCollapsed((c) => !c);
                }}
                className="text-[var(--text-soft)] hover:text-[var(--text-strong)] w-7 h-7"
                aria-label={sheetCollapsed ? t.routeBuilderPage.buttonExpand : t.routeBuilderPage.buttonCollapse}
              >
                {sheetCollapsed ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              </Button>
            </TooltipTrigger>
            <TooltipContent>{sheetCollapsed ? t.routeBuilderPage.buttonExpand : t.routeBuilderPage.buttonCollapse}</TooltipContent>
          </Tooltip>
        </div>

        {/* Sheet body */}
        {!sheetCollapsed && (
          <div className="flex-1 min-h-0 overflow-hidden">
            {bottomTab === 'orders' && (
              <OrdersTable
                targetRouteId={targetRouteId}
                setTargetRouteId={setTargetRouteId}
              />
            )}
            {bottomTab === 'routes' && (
              <RoutesTable />
            )}
            {bottomTab === 'timeline' && (
              <TimelineGantt />
            )}
          </div>
        )}
      </div>
    </div>
  );

  return (
    <>
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={rb.handleDragStart}
      onDragEnd={rb.handleDragEnd}
    >
      <div className="h-screen flex flex-col bg-[var(--app-bg)] overflow-hidden font-sans select-none text-[var(--text-strong)]">

        {/* HEADER BAR */}
        <div
          style={{ zIndex: 10 }}
          className="h-12 px-4 bg-[var(--surface-1)] border-b border-[var(--border)] shrink-0 flex items-center justify-between relative"
        >
          <div className="flex items-center gap-3">
            <span className="text-xs font-bold text-[var(--text-strong)] tracking-wide">
              {t.routeBuilderPage.pageTitle}
            </span>
          </div>

          <ActionBar />
        </div>

        {/* KPI STRIP + DATE PICKER */}
        <div
          className="h-14 bg-[var(--surface-1)] border-b border-[var(--border)] shrink-0 flex items-center justify-between"
        >
          <div className="flex items-center h-full">
          {[
            { label: t.routeBuilderPage.kpiScheduled, value: scheduledCount, accent: false },
            { label: t.routeBuilderPage.kpiUnscheduled, value: unscheduledCount, accent: unscheduledCount > 0 },
            { label: t.routeBuilderPage.kpiTotal, value: totalCount, accent: false },
            { label: t.routeBuilderPage.kpiRoutes, value: routesCount, accent: false },
          ].map((kpi) => (
            <div
              key={kpi.label}
              className="flex items-center gap-3 px-4 h-full border-r border-[var(--border)] min-w-[160px]"
            >
              <span
                className={`font-mono text-lg font-bold leading-none ${
                  kpi.accent ? "text-[var(--brand-orange)]" : "text-[var(--text-strong)]"
                }`}
              >
                {kpi.value}
              </span>
              <span className="text-xs text-[var(--text-muted)] font-medium">
                {kpi.label}
              </span>
            </div>
          ))}
          </div>

          {/* DATE FILTER */}
          <div className="flex items-center gap-2 px-4 h-full">
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  size="sm"
                  variant={
                    rb.routesDate === new Date().toISOString().slice(0, 10)
                      ? 'default'
                      : 'outline'
                  }
                  onClick={() => rb.setRoutesDate(new Date().toISOString().slice(0, 10))}
                  className={
                    rb.routesDate === new Date().toISOString().slice(0, 10)
                      ? 'bg-[var(--brand-orange)] border-transparent text-white hover:opacity-90 h-8 text-xs font-semibold'
                      : 'h-8 text-xs bg-[var(--surface-2)] border-[var(--border)] text-[var(--text-strong)] hover:bg-[var(--hover-bg)]'
                  }
                >
                  {t.routeBuilderPage.buttonToday}
                </Button>
              </TooltipTrigger>
              <TooltipContent>{t.routeBuilderPage.tooltipTodayRoutes}</TooltipContent>
            </Tooltip>

            <DatePickerPopover
              value={rb.routesDate}
              onChange={rb.setRoutesDate}
            />
          </div>
        </div>

        <div className="flex-1 flex overflow-hidden min-h-0">
          {isMobileOrTablet ? (
            <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
              {/* Tab Bar */}
              <div className="flex h-10 shrink-0 border-b bg-[var(--surface-1)]" style={{ borderColor: 'var(--border)' }}>
                {([
                  { id: 'routes', label: t.routeBuilderPage.tabRoutes ?? 'Tournées' },
                  { id: 'stops', label: t.routeBuilderPage.tabStops ?? 'Arrêts' },
                  { id: 'map', label: t.routeBuilderPage.tabMap ?? 'Carte' },
                ] as const).map(tab => {
                  const active = builderTab === tab.id;
                  return (
                    <button
                      key={tab.id}
                      type="button"
                      onClick={() => setBuilderTab(tab.id)}
                      className={cn(
                        "flex-1 text-center text-xs font-[600] uppercase tracking-wide border-b-2 transition-colors",
                        active ? 'border-[var(--brand)] text-[var(--text-primary)]' : 'border-transparent text-[var(--text-muted)]'
                      )}
                    >
                      {tab.label}
                    </button>
                  );
                })}
              </div>

              {/* Tab Panels */}
              <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
                {builderTab === 'routes' && (
                  <div className="flex-1 flex flex-col min-h-0 bg-[var(--app-bg)]">
                    <RouteSidebar />
                  </div>
                )}
                {builderTab === 'stops' && (
                  <div className="flex-1 flex flex-col min-h-0 bg-[var(--app-bg)]">
                    <StopsPanel />
                  </div>
                )}
                {builderTab === 'map' && renderMapPanel()}
              </div>
            </div>
          ) : (
            <>
              {/* ZONE 1: Sidebar (Routes) */}
              <div style={{ width: sidebarWidth }} className="flex flex-col h-full shrink-0 border-r border-[var(--border)]">
                <RouteSidebar />
              </div>

              {/* HANDLE 1 */}
              <div onMouseDown={startResizingSidebar} className="w-1 hover:bg-[var(--surface-2)] cursor-col-resize z-30 relative flex items-center justify-center transition-colors">
                <div className="w-[1px] h-full bg-[var(--border)]" />
              </div>

              {/* RIGHT SIDE (Rest) — Stops + Map (full bleed) */}
              <div className="flex-1 flex overflow-hidden min-w-0 bg-[var(--app-bg)]">

                {/* ZONE 2: Stops Panel */}
                <div style={{ width: stopsWidth }} className="flex flex-col h-full shrink-0">
                  <StopsPanel />
                </div>

                {/* HANDLE 2 */}
                <div onMouseDown={startResizingStops} className="w-1 hover:bg-[var(--surface-2)] cursor-col-resize z-30 relative flex items-center justify-center transition-colors">
                  <div className="w-[1px] h-full bg-[var(--border)]" />
                </div>

                {/* ZONE 3: Map (full bleed hero) + floating overlays */}
                {renderMapPanel()}
              </div>
            </>
          )}
        </div>

        {/* MODALS */}
        <CreateRouteModal />
        <SettingsModal />
        <DeleteModal />
        <ValidationModal />
      </div>

      {/* ── Global drag overlay ─────────────────────────────────────────── */}
      <DragOverlay dropAnimation={{ duration: 180, easing: 'cubic-bezier(0.18,0.67,0.6,1.22)' }}>
        {(() => {
          const id = rb.activeDragId;
          if (!id) return null;
          if (id.startsWith('stop:')) {
            const parts = id.split(':');
            const stopId = parts[1];
            const routeId = parts[2];
            const stop = rb.selectedRouteStops.find((s) => s.id === stopId);
            if (!stop) return null;
            const idx = rb.selectedRouteStops.indexOf(stop);
            return (
              <StopRow
                stop={stop}
                index={idx}
                routeId={routeId}
                delivery={rb.waitingMap.get(stop.deliveryId)}
                window={rb.stopWindows[stop.id]}
                violation={null}
                onRemove={() => {}}
                onUpdateWindow={() => {}}
                isRemoving={false}
                isOverlay
              />
            );
          }
          if (id.startsWith('order:')) {
            const draggedId = id.slice('order:'.length);
            const isBatch = rb.selectedOrderIds.includes(draggedId) && rb.selectedOrderIds.length > 1;
            const draggingCount = isBatch ? rb.selectedOrderIds.length : 1;
            // Wheel-scrollable preview: show selectedOrderIds[previewIndex] if batch, else the dragged one
            const previewId = isBatch ? rb.selectedOrderIds[previewIndex] : draggedId;
            const delivery = rb.waitingDeliveries.find((d) => d.id === previewId);
            if (!delivery) return null;
            return (
              <div
                style={{
                  background: 'var(--surface-1)',
                  border: '2px solid var(--brand-orange)',
                  borderRadius: 8,
                  boxShadow: '0 12px 40px rgba(0,0,0,0.28)',
                  width: 260,
                  overflow: 'hidden',
                  userSelect: 'none',
                  backdropFilter: 'blur(6px)',
                }}
              >
                {/* Header strip */}
                <div
                  style={{
                    background: 'var(--brand-orange)',
                    padding: '5px 10px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 6,
                  }}
                >
                  <span style={{ fontSize: 10, fontWeight: 800, color: 'white', letterSpacing: '0.06em', textTransform: 'uppercase' }}>
                    {draggingCount > 1 ? t.routeBuilderPage.dragCardOrders.replace('{count}', String(draggingCount)) : t.routeBuilderPage.dragCardOrder}
                  </span>
                  {/* Page indicator + ERP ID */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    {draggingCount > 1 && (
                      <span style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.7)', fontFamily: 'monospace' }}>
                        {previewIndex + 1}/{draggingCount}
                      </span>
                    )}
                    <span style={{ fontSize: 10, fontWeight: 700, color: 'rgba(255,255,255,0.85)', fontFamily: 'monospace' }}>
                      {resolveOrderRef(delivery)}
                    </span>
                  </div>
                </div>
                {/* Client + address */}
                <div style={{ padding: '8px 10px 6px', display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-strong)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {delivery.clientName || shortId(delivery.id)}
                  </span>
                  {(delivery.dropoffAddress || delivery.dropoffCity) && (
                    <span style={{ fontSize: 11, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {delivery.dropoffAddress || delivery.dropoffCity}
                    </span>
                  )}
                </div>
                {/* Items list */}
                {delivery.items && delivery.items.length > 0 ? (
                  <div style={{ borderTop: '1px solid var(--border-color)', padding: '6px 10px', display: 'flex', flexDirection: 'column', gap: 3 }}>
                    <span style={{ fontSize: 9, fontWeight: 800, color: 'var(--text-muted)', letterSpacing: '0.07em', textTransform: 'uppercase', marginBottom: 1 }}>
                      {t.routeBuilderPage.dragCardArticles}
                    </span>
                    {delivery.items.slice(0, 4).map((item, idx) => (
                      <div key={item.id || `item-${idx}`} style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                        <span
                          style={{
                            fontSize: 10, fontWeight: 800, color: 'white',
                            background: 'var(--brand-orange)', borderRadius: 3,
                            padding: '1px 5px', flexShrink: 0, fontFamily: 'monospace',
                          }}
                        >
                          ×{item.quantity}
                        </span>
                        <span style={{ fontSize: 11, color: 'var(--text-strong)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                          {item.name || item.sku || item.id.slice(0, 8)}
                        </span>
                        {item.unitWeightKg && (
                          <span style={{ fontSize: 10, color: 'var(--text-muted)', fontFamily: 'monospace', flexShrink: 0 }}>
                            {(item.unitWeightKg * item.quantity).toFixed(1)} kg
                          </span>
                        )}
                      </div>
                    ))}
                    {delivery.items.length > 4 && (
                      <span style={{ fontSize: 10, color: 'var(--text-muted)', fontStyle: 'italic' }}>
                        {t.routeBuilderPage.dragCardOtherArticles.replace('{count}', String(delivery.items.length - 4))}
                      </span>
                    )}
                  </div>
                ) : delivery.itemsSummary ? (
                  <div style={{ borderTop: '1px solid var(--border-color)', padding: '5px 10px' }}>
                    <span style={{ fontSize: 11, color: 'var(--text-soft)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'block' }}>
                      {delivery.itemsSummary}
                    </span>
                  </div>
                ) : null}
                {/* Weight / quantity footer row */}
                {((delivery.totalWeightKg ?? 0) > 0 || (delivery.totalQuantity ?? 0) > 0) && (
                  <div style={{ padding: '4px 10px 6px', display: 'flex', gap: 10 }}>
                    {(delivery.totalWeightKg ?? 0) > 0 && (
                      <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-soft)', fontFamily: 'monospace' }}>
                        {delivery.totalWeightKg!.toFixed(1)} kg
                      </span>
                    )}
                    {(delivery.totalQuantity ?? 0) > 0 && (
                      <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                        {tcount(t.routeBuilderPage.dragCardArticleCount, delivery.totalQuantity!, t.pluralMark)}
                      </span>
                    )}
                  </div>
                )}
                {/* Footer */}
                <div
                  style={{
                    borderTop: '1px solid var(--border-color)',
                    padding: '4px 10px',
                    fontSize: 10,
                    color: 'var(--text-muted)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <Move size={10} />
                    {t.routeBuilderPage.dragCardDropPrompt}
                  </div>
                  {draggingCount > 1 && (
                    <span style={{ fontSize: 9, color: 'var(--text-muted)', fontStyle: 'italic' }}>
                      {t.routeBuilderPage.dragCardScrollPrompt}
                    </span>
                  )}
                </div>
              </div>
            );
          }
          return null;
        })()}
      </DragOverlay>
    </DndContext>

    {/* ── Pin drag ghost ──────────────────────────────────────────────── */}
    {pinDrag && (
      <div
        ref={pinGhostRef}
        style={{
          position: 'fixed',
          left: pinDrag.x,
          top: pinDrag.y,
          zIndex: 9999,
          pointerEvents: 'none',
          background: 'var(--surface-1)',
          border: '2px solid var(--brand-orange)',
          borderRadius: 8,
          padding: '6px 12px 6px 8px',
          boxShadow: '0 8px 32px rgba(0,0,0,0.28)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          maxWidth: 240,
          fontSize: 12,
          fontWeight: 700,
          color: 'var(--text-strong)',
          userSelect: 'none',
          backdropFilter: 'blur(6px)',
          willChange: 'transform',
        }}
      >
        <Move size={13} className="text-[var(--brand-orange)]" style={{ flexShrink: 0 }} />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {pinDrag.clientName}
        </span>
        {pinDrag.orderIds.length > 1 && (
          <span
            style={{
              background: 'var(--brand-orange)', color: 'white',
              borderRadius: 4, padding: '1px 6px',
              fontSize: 10, fontWeight: 800, flexShrink: 0,
            }}
          >
            ×{pinDrag.orderIds.length}
          </span>
        )}
      </div>
    )}

    {/* Drop zone highlight + cursor during pin drag */}
    <style>{`
      body.pin-drag-active { cursor: grabbing !important; }
      body.pin-drag-active [data-route-id] {
        outline: 2px dashed rgba(59,130,246,0.45) !important;
        outline-offset: -2px;
        transition: background-color 80ms, outline 80ms;
      }
      body.pin-drag-active [data-route-id]:hover {
        background-color: rgba(59,130,246,0.1) !important;
        outline: 2px solid #3b82f6 !important;
      }
    `}</style>
    </>
  );
}

export default function RouteBuilderPage() {
  return (
    <Suspense>
      <RouteBuilderProvider>
        <RouteBuilderPageInner />
      </RouteBuilderProvider>
    </Suspense>
  );
}

