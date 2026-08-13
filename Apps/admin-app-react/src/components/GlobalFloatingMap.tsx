import React, { useMemo, useEffect, useState, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { getBusinessDayKey } from '@/lib/sla';
import { createRouteColorMap, routeColorFromMap } from '@/lib/utils';
import { useGlobalMapStore } from '@/lib/state/global-map-store';
import DispatchLiveMap, { type MapRoute, type LiveDriver } from '@/components/DispatchLiveMap';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { useDriverAvatars } from '@/hooks/useDriverAvatars';
import { useT } from '@/lib/i18n/LocaleContext';
import {
  IconMap2,
  IconX,
  IconSearch,
  IconGripVertical,
  IconMinus,
  IconLayoutSidebar,
  IconAlertTriangle } from '@tabler/icons-react';

const MAP_SIZES = {
  S: 'w-[380px] h-[280px]',
  M: 'w-[680px] h-[480px]',
  L: 'w-[1020px] h-[680px]',
};

function isGpsStale(lastLocationAt?: string | null): boolean {
  if (!lastLocationAt) return true;
  return Date.now() - new Date(lastLocationAt).getTime() > 10 * 60 * 1000;
}

export default function GlobalFloatingMap() {
  const t = useT();
  const { pathname } = useLocation();
  const queryClient = useQueryClient();

  const {
    focusedRouteId,
    setFocusedRouteId,
    focusedDriverId,
    setFocusedDriverId,
    mapMode,
    setMapMode,
    mapSize,
    setMapSize,
  } = useGlobalMapStore();

  const containerRef = useRef<HTMLDivElement>(null);
  const dragStartRef = useRef({ x: 0, y: 0 });
  const [position, setPosition] = useState<{ x: number; y: number } | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);

  const isHiddenPage = useMemo(() => {
    return (
      pathname.startsWith('/route-builder') ||
      pathname.includes('/routes/') ||
      pathname.startsWith('/login') ||
      pathname.startsWith('/callback') ||
      pathname.startsWith('/track/') ||
      pathname === '/'
    );
  }, [pathname]);

  const todayIso = useMemo(() => getBusinessDayKey(), []);

  const { data: todayRoutes = [] } = useQuery<MapRoute[]>({
    queryKey: ['global-map-routes', todayIso],
    queryFn: async () => {
      // Today's routes (validated/upcoming) UNION every IN_PROGRESS route — a running route is active
      // regardless of its planned date, so drivers on a route started on a prior day still map correctly.
      const [todayRes, runningRes] = await Promise.all([
        api.get('/admin/routes', { params: { from: todayIso, to: todayIso } }),
        api.get('/admin/routes', { params: { status: 'IN_PROGRESS' } }),
      ]);
      const a = Array.isArray(todayRes.data) ? todayRes.data : [];
      const b = Array.isArray(runningRes.data) ? runningRes.data : [];
      const byId = new Map<string, MapRoute>();
      [...a, ...b].forEach((r: MapRoute) => byId.set(r.id, r));
      return Array.from(byId.values());
    },
    enabled: !isHiddenPage && mapMode !== 'hidden',
    staleTime: 30_000,
  });

  const activeRoutes = useMemo(() => {
    return todayRoutes.filter((r) => r.status === 'VALIDATED' || r.status === 'IN_PROGRESS');
  }, [todayRoutes]);

  // Stable colour per active route by position — two routes never share a colour.
  const routeColorMap = useMemo(() => createRouteColorMap(activeRoutes), [activeRoutes]);

  const { data: drivers = [] } = useQuery<LiveDriver[]>({
    queryKey: ['global-map-drivers'],
    queryFn: async () => {
      const res = await api.get('/admin/fleet/drivers');
      const data = res.data;
      return Array.isArray(data) ? data : (data?.content ?? data?.drivers ?? []);
    },
    enabled: !isHiddenPage && mapMode !== 'hidden',
    refetchInterval: mapMode === 'floating' ? 15_000 : 45_000,
    staleTime: 10_000,
  });

  const avatarMap = useDriverAvatars();
  const safeDrivers = useMemo(() => {
    return drivers.map((d) => ({
      ...d,
      currentLat: Number(d.currentLat) || null,
      currentLng: Number(d.currentLng) || null,
      photoUrl: avatarMap[d.id] ?? (d as LiveDriver & { photoUrl?: string | null }).photoUrl ?? null,
    }));
  }, [drivers, avatarMap]);

  const driverRoute = useMemo(() => {
    const m = new Map<string, MapRoute>();
    activeRoutes.forEach(r => { if (r.driverId) m.set(r.driverId, r); });
    return m;
  }, [activeRoutes]);

  const filteredDrivers = useMemo(() => {
    if (!searchQuery.trim()) return safeDrivers;
    const q = searchQuery.toLowerCase();
    return safeDrivers.filter((d) => d.name.toLowerCase().includes(q));
  }, [safeDrivers, searchQuery]);

  const filteredRoutes = useMemo(() => {
    if (!searchQuery.trim()) return activeRoutes;
    const q = searchQuery.toLowerCase();
    return activeRoutes.filter((r) =>
      r.name.toLowerCase().includes(q) ||
      (r.driverName && r.driverName.toLowerCase().includes(q)) ||
      r.stops.some((s) => s.clientName && s.clientName.toLowerCase().includes(q))
    );
  }, [activeRoutes, searchQuery]);

  const DASHBOARD_EVENTS = [
    'delivery.created', 'delivery.scheduled', 'delivery.completed', 'delivery.failed',
    'delivery.cancelled', 'delivery.in_transit', 'delivery.reassigned', 'sla.breach',
    'erp.orders_ready', 'route.validated',
  ] as const;

  useRealtimeEvent(DASHBOARD_EVENTS, () => {
    queryClient.invalidateQueries({ queryKey: ['global-map-routes'] });
    queryClient.invalidateQueries({ queryKey: ['global-map-drivers'] });
  });

  /**
   * Move the pucks as the vans move.
   *
   * <p>Positions came only from the poll above — every 15s floating, every 45s expanded — so the
   * live map was a slideshow, and the further it was opened the staler it got. A van on the ring
   * road covers most of a kilometre between two frames of the expanded view.
   *
   * <p>The cache is written directly instead of invalidated. {@code driver.location_updated} is the
   * highest-frequency event on the bus; invalidating on each one would put a full fleet request
   * behind every GPS ping from every driver — which is exactly why this event is kept out of
   * {@code DASHBOARD_EVENTS}. Patching costs nothing and leaves the poll as the reconciler.
   *
   * <p>{@code lastLocationAt} moves with the fix: the map dims a marker whose GPS has gone stale,
   * and a puck that keeps moving while being greyed out as unreachable would be worse than one
   * that lags.
   *
   */
  useRealtimeEvent(['driver.location_updated'], evt => {
    const { driverId, lat, lng } = evt.payload ?? {};
    if (!driverId || lat == null || lng == null) return;
    queryClient.setQueryData<LiveDriver[]>(['global-map-drivers'], prev =>
      prev?.map(d => (d.id === driverId
        ? { ...d, currentLat: Number(lat), currentLng: Number(lng), lastLocationAt: new Date().toISOString() }
        : d)),
    );
  });

  /**
   * Follow the going online and offline, the same way the drivers page does.
   *
   * <p>Position and availability are two different facts and arrive on two different events. This
   * map only listened to the first, so a driver's status waited for the poll — 15s floating, 45s
   * expanded. That delay was invisible in itself, except the marker filter drops OFFLINE drivers:
   * a driver connecting while the map was open kept moving on a map where nobody could see him.
   *
   * <p>Refetched rather than patched, and debounced: the event carries the change, not the row, and
   * a fleet coming online at the start of a shift would otherwise fire one request per driver.
   */
  const driverStatusTimer = useRef<number | null>(null);
  useRealtimeEvent(['driver.status_changed', 'driver.events'], () => {
    if (driverStatusTimer.current != null) return;
    driverStatusTimer.current = window.setTimeout(() => {
      driverStatusTimer.current = null;
      void queryClient.invalidateQueries({ queryKey: ['global-map-drivers'] });
    }, 1500);
  });

  useEffect(() => {
    if (mapMode === 'floating') {
      const timer = setTimeout(() => {
        window.dispatchEvent(new Event('resize'));
      }, 350);
      return () => clearTimeout(timer);
    }
  }, [mapSize, mapMode]);

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement;
    if (target.closest('button') || target.closest('input') || target.closest('a')) {
      return;
    }

    if (!containerRef.current) return;
    e.currentTarget.setPointerCapture(e.pointerId);

    const rect = containerRef.current.getBoundingClientRect();
    dragStartRef.current = {
      x: e.clientX - rect.left,
      y: e.clientY - rect.top,
    };
    setIsDragging(true);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isDragging || !containerRef.current) return;

    const rect = containerRef.current.getBoundingClientRect();
    const newLeft = e.clientX - dragStartRef.current.x;
    const newTop = e.clientY - dragStartRef.current.y;

    const maxLeft = window.innerWidth - rect.width;
    const maxTop = window.innerHeight - rect.height;

    const clampedLeft = Math.max(0, Math.min(newLeft, maxLeft));
    const clampedTop = Math.max(0, Math.min(newTop, maxTop));

    setPosition({ x: clampedLeft, y: clampedTop });
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isDragging) return;
    setIsDragging(false);
    e.currentTarget.releasePointerCapture(e.pointerId);
  };

  useEffect(() => {
    if (!position || !containerRef.current || mapMode !== 'floating') return;

    const handleResize = () => {
      if (!containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const maxLeft = window.innerWidth - rect.width;
      const maxTop = window.innerHeight - rect.height;

      setPosition((prev) => {
        if (!prev) return null;
        return {
          x: Math.max(0, Math.min(prev.x, maxLeft)),
          y: Math.max(0, Math.min(prev.y, maxTop)),
        };
      });
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [position, mapMode]);

  useEffect(() => {
    if (!position || !containerRef.current || mapMode !== 'floating') return;

    const timer = setTimeout(() => {
      if (!containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const maxLeft = window.innerWidth - rect.width;
      const maxTop = window.innerHeight - rect.height;

      setPosition((prev) => {
        if (!prev) return null;
        return {
          x: Math.max(0, Math.min(prev.x, maxLeft)),
          y: Math.max(0, Math.min(prev.y, maxTop)),
        };
      });
    }, 50);

    return () => clearTimeout(timer);
  }, [mapSize]);

  // Being connected and being locatable are two different facts, and this counted the second while
  // calling it the first. A driver who starts his shift is online at once; his phone sends a first
  // fix seconds later, and until then currentLat is null. He was counted as offline the whole time
  // — no refresh could fix it, because the status was never what was being read.
  const onlineDriversCount = safeDrivers.filter((d) => d.onlineStatus !== 'OFFLINE').length;

  const bubbleTooltipText = useMemo(() => {
    return t.globalMap.bubbleTooltip
      .replace('{routes}', String(activeRoutes.length))
      .replace('{drivers}', String(onlineDriversCount));
  }, [t, activeRoutes.length, onlineDriversCount]);

  if (isHiddenPage || mapMode === 'hidden') {
    return null;
  }

  if (mapMode === 'collapsed') {
    return (
      <div className="fixed bottom-6 right-6 rtl:right-auto rtl:left-6 z-50">
        <button
          type="button"
          onClick={() => setMapMode('floating')}
          className="relative flex items-center justify-center w-11 h-11 rounded-full bg-[var(--surface)] border border-[var(--border)] shadow-[var(--shadow-card)] text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)] transition-colors"
          title={bubbleTooltipText}
          aria-label={bubbleTooltipText}
        >
          <IconMap2 size={19} />
          {onlineDriversCount > 0 && (
            <span className="absolute top-1 right-1 w-2.5 h-2.5 rounded-full bg-[var(--success)] ring-2 ring-[var(--surface)]" />
          )}
        </button>
      </div>
    );
  }

  const style = {
    isolation: 'isolate',
    left: position ? position.x : undefined,
    top: position ? position.y : undefined,
    bottom: position ? 'auto' : undefined,
    right: position ? 'auto' : undefined,
    transition: isDragging
      ? 'none'
      : 'width 0.3s ease-in-out, height 0.3s ease-in-out, left 0.3s ease-in-out, top 0.3s ease-in-out',
  } as React.CSSProperties;

  return (
    <div
      ref={containerRef}
      className={`fixed z-50 flex flex-col bg-[var(--surface)] border border-[var(--border)] shadow-[var(--shadow-card)] rounded-[var(--radius-xl)] overflow-hidden max-w-[calc(100vw-48px)] max-h-[calc(100vh-48px)] ${
        position ? '' : 'bottom-6 right-6 rtl:right-auto rtl:left-6'
      } ${MAP_SIZES[mapSize]}`}
      style={style}
    >
      <div
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        style={{ cursor: isDragging ? 'grabbing' : 'grab' }}
        className="h-10 ps-2.5 pe-2 border-b border-[var(--border)] flex items-center justify-between shrink-0 select-none bg-[var(--surface-sunken)] touch-none"
      >
        <div className="flex items-center gap-2.5 min-w-0">
          <IconGripVertical size={14} className="text-[var(--text-soft)] shrink-0 cursor-grab active:cursor-grabbing" />
          <div className="flex items-center gap-1.5 min-w-0">
            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${onlineDriversCount > 0 ? 'bg-[var(--success)]' : 'bg-[var(--text-soft)]'}`} />
            <span className="text-xs font-bold text-[var(--text-primary)] shrink-0">{t.globalMap.title}</span>
          </div>
          <span className="hidden sm:inline-flex items-center gap-1.5 text-2xs font-semibold text-[var(--text-muted)] tabular-nums whitespace-nowrap">
            <span>{activeRoutes.length} {t.globalMap.routesLabel}</span>
            <span aria-hidden className="text-[var(--border-strong)]">·</span>
            <span>{onlineDriversCount} {t.globalMap.onlineLabel}</span>
          </span>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          {mapSize !== 'S' && (
            <div className="relative flex items-center">
              <IconSearch size={12} className="absolute start-2 text-[var(--text-soft)] pointer-events-none" />
              <input
                type="text"
                placeholder={t.globalMap.searchPlaceholder}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="text-2xs w-36 h-7 ps-6 pe-5 rounded-md border border-[var(--border)] bg-[var(--surface)] text-[var(--text-primary)] placeholder-[var(--text-soft)] focus:outline-none focus:border-[var(--brand)] focus:ring-2 focus:ring-[var(--brand-bg)] transition-all"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery('')}
                  className="absolute end-1.5 text-[var(--text-soft)] hover:text-[var(--text-primary)]"
                >
                  <IconX size={11} />
                </button>
              )}
            </div>
          )}

          <div className="flex items-center gap-0.5 p-0.5 rounded-md bg-[var(--surface)] border border-[var(--border)]">
            {(['S', 'M', 'L'] as const).map((size) => {
              const active = mapSize === size;
              return (
                <button
                  key={size}
                  type="button"
                  onClick={() => setMapSize(size)}
                  aria-pressed={active}
                  className="w-6 h-6 rounded text-2xs font-bold transition-colors"
                  style={{
                    background: active ? 'var(--surface-sunken)' : 'transparent',
                    color: active ? 'var(--text-primary)' : 'var(--text-muted)',
                    border: active ? '1px solid var(--border-strong)' : '1px solid transparent',
                    boxShadow: active ? 'var(--shadow-xs)' : 'none',
                  }}
                >
                  {size}
                </button>
              );
            })}
          </div>

          <div className="h-5 w-px bg-[var(--border)]" />

          <div className="flex items-center gap-0.5">
            {mapSize !== 'S' && (
              <button
                type="button"
                onClick={() => setIsSidebarOpen(!isSidebarOpen)}
                aria-pressed={isSidebarOpen}
                className="w-7 h-7 flex items-center justify-center rounded-md transition-colors"
                style={{
                  background: isSidebarOpen ? 'var(--brand-bg)' : 'transparent',
                  color: isSidebarOpen ? 'var(--brand)' : 'var(--text-soft)',
                }}
                title={isSidebarOpen ? t.globalMap.hideList : t.globalMap.showList}
              >
                <IconLayoutSidebar size={15} />
              </button>
            )}

            <button
              type="button"
              onClick={() => setMapMode('collapsed')}
              className="w-7 h-7 flex items-center justify-center rounded-md text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] transition-colors"
              title={t.globalMap.minimize}
            >
              <IconMinus size={15} />
            </button>
            <button
              type="button"
              onClick={() => setMapMode('hidden')}
              className="w-7 h-7 flex items-center justify-center rounded-md text-[var(--text-soft)] hover:text-[var(--danger)] hover:bg-[var(--danger-bg)] transition-colors"
              title={t.globalMap.hideCompletely}
            >
              <IconX size={15} />
            </button>
          </div>
        </div>
      </div>

      <div className="flex-1 min-h-0 flex relative">
        <div className="flex-1 h-full min-w-0 relative">
          <DispatchLiveMap
            key={mapSize}
            routes={filteredRoutes}
            drivers={filteredDrivers}
            focusedRouteId={focusedRouteId}
            focusedDriverId={focusedDriverId}
            onFocusRoute={setFocusedRouteId}
            onFocusDriver={setFocusedDriverId}
            routeColorMap={routeColorMap}
          />
        </div>

        {mapSize !== 'S' && isSidebarOpen && (
          <div className="w-[216px] shrink-0 border-s border-[var(--border)] bg-[var(--surface)] flex flex-col h-full overflow-hidden select-none">
            <div className="px-3 h-9 border-b border-[var(--border)] flex items-center shrink-0 bg-[var(--surface-sunken)]">
              <span className="text-2xs font-bold text-[var(--text-secondary)]">
                {t.pages?.drivers?.title} <span className="text-[var(--text-muted)] tabular-nums">{filteredDrivers.length}</span>
              </span>
            </div>
            <div className="flex-1 overflow-y-auto p-1.5 flex flex-col gap-0.5">
              {filteredDrivers.length === 0 ? (
                <div className="p-6 text-center text-2xs text-[var(--text-muted)]">
                  {t.globalMap.noDrivers}
                </div>
              ) : (
                filteredDrivers.map((driver) => {
                  const r = driverRoute.get(driver.id);
                  const color = r ? routeColorFromMap(routeColorMap, r.id) : 'var(--text-soft)';
                  const isFocused = driver.id === focusedDriverId;
                  const stale = isGpsStale(driver.lastLocationAt);
                  // Status only. The marker below still needs coordinates — one cannot pin a driver
                  // whose position is unknown — but the dot answers "is he connected?", and a
                  // driver waiting for his first GPS fix is connected.
                  const online = driver.onlineStatus !== 'OFFLINE';

                  return (
                    <button
                      key={driver.id}
                      type="button"
                      onClick={() => setFocusedDriverId(isFocused ? null : driver.id)}
                      className={`w-full text-start px-2 py-1.5 rounded-md flex items-center gap-2.5 transition-colors ${
                        isFocused ? 'bg-[var(--brand-bg)] ring-1 ring-inset ring-[var(--brand)]' : 'hover:bg-[var(--hover-bg)]'
                      }`}
                    >
                      <div className="relative shrink-0">
                        <DriverAvatarById driverId={driver.id} name={driver.name} size={28} />
                        <span className={`absolute -bottom-0.5 -end-0.5 w-2.5 h-2.5 rounded-full ring-2 ring-[var(--surface)] ${
                          online ? 'bg-[var(--success)]' : 'bg-[var(--text-soft)]'
                        }`} />
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5">
                          <span className="text-xs font-semibold text-[var(--text-primary)] truncate">{driver.name}</span>
                          {stale && online && (
                            <span className="inline-flex items-center gap-0.5 text-3xs font-bold px-1 py-0.5 rounded shrink-0" style={{ color: 'var(--danger)', background: 'var(--danger-bg)' }}>
                              <IconAlertTriangle size={9} /> {t.globalMap.staleGps}
                            </span>
                          )}
                        </div>
                        {r ? (
                          <div className="flex items-center gap-1.5 mt-0.5">
                            <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: color }} />
                            <span className="text-2xs font-medium text-[var(--text-soft)] truncate">{r.name}</span>
                          </div>
                        ) : (
                          <span className="text-2xs text-[var(--text-muted)]">{t.globalMap.offRoute}</span>
                        )}
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
