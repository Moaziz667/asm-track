import React, { useMemo, useEffect, useState, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { getBusinessDayKey } from '@/lib/sla';
import { routeColor, routeColorByIndex } from '@/lib/utils';
import { useGlobalMapStore } from '@/lib/global-map-store';
import DispatchLiveMap from '@/components/DispatchLiveMap';
import { useT, useLocaleContext } from '@/lib/LocaleContext';
import {
  IconMap,
  IconMinus,
  IconX,
  IconTruck,
  IconGripVertical,
  IconLayoutSidebar,
} from '@tabler/icons-react';

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
  const { locale } = useLocaleContext();
  const isRtl = locale === 'ar';
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

  const { data: todayRoutes = [] } = useQuery({
    queryKey: ['global-map-routes', todayIso],
    queryFn: async () => {
      const res = await api.get('/api/admin/routes', { params: { from: todayIso, to: todayIso } });
      return Array.isArray(res.data) ? res.data : [];
    },
    enabled: !isHiddenPage && mapMode !== 'hidden',
    staleTime: 30_000,
  });

  const activeRoutes = useMemo(() => {
    return todayRoutes.filter((r: any) => r.status === 'VALIDATED' || r.status === 'IN_PROGRESS');
  }, [todayRoutes]);

  // Distinct colour per active route by position, so two routes never share a colour
  // (the hash-based routeColor could collide). Falls back to the hash for unknown ids.
  const routeColorFor = useMemo(() => {
    const idx = new Map<string, number>();
    activeRoutes.forEach((r: any, i: number) => idx.set(r.id, i));
    return (routeId?: string | null) =>
      routeId != null && idx.has(routeId) ? routeColorByIndex(idx.get(routeId)!) : routeColor(routeId);
  }, [activeRoutes]);

  const { data: drivers = [] } = useQuery({
    queryKey: ['global-map-drivers'],
    queryFn: async () => {
      const res = await api.get('/api/admin/fleet/drivers');
      const data = res.data;
      return Array.isArray(data) ? data : (data?.content ?? data?.drivers ?? []);
    },
    enabled: !isHiddenPage && mapMode !== 'hidden',
    refetchInterval: mapMode === 'floating' ? 15_000 : 45_000,
    staleTime: 10_000,
  });

  const safeDrivers = useMemo(() => {
    return drivers.map((d: any) => ({
      ...d,
      currentLat: Number(d.currentLat) || null,
      currentLng: Number(d.currentLng) || null,
    }));
  }, [drivers]);

  const driverRoute = useMemo(() => {
    const m = new Map<string, any>();
    activeRoutes.forEach(r => { if (r.driverId) m.set(r.driverId, r); });
    return m;
  }, [activeRoutes]);

  const filteredDrivers = useMemo(() => {
    if (!searchQuery.trim()) return safeDrivers;
    const q = searchQuery.toLowerCase();
    return safeDrivers.filter((d: any) => d.name.toLowerCase().includes(q));
  }, [safeDrivers, searchQuery]);

  const filteredRoutes = useMemo(() => {
    if (!searchQuery.trim()) return activeRoutes;
    const q = searchQuery.toLowerCase();
    return activeRoutes.filter((r: any) =>
      r.name.toLowerCase().includes(q) ||
      (r.driverName && r.driverName.toLowerCase().includes(q)) ||
      r.stops.some((s: any) => s.clientName && s.clientName.toLowerCase().includes(q))
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

  const onlineDriversCount = safeDrivers.filter(
    (d: any) => d.currentLat && d.currentLng && d.onlineStatus !== 'OFFLINE'
  ).length;

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
      <div className="fixed bottom-6 right-6 z-50">
        <button
          type="button"
          onClick={() => setMapMode('floating')}
          className="group relative w-12 h-12 rounded-[var(--map-radius)] flex items-center justify-center bg-[var(--map-bg)] border border-[var(--map-border)] shadow-[var(--map-shadow)] hover:scale-102 hover:bg-[var(--hover-bg)] active:scale-98 transition-all duration-200 backdrop-blur-md"
          title={bubbleTooltipText}
        >
          <IconMap size={20} className="text-[var(--text-secondary)]" />
          {activeRoutes.length > 0 && (
            <span className="absolute top-1 right-1 flex h-2.5 w-2.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500 border border-[var(--surface)]"></span>
            </span>
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
      className={`fixed z-50 flex flex-col bg-[var(--map-bg)]/95 border border-[var(--map-border)] shadow-[var(--map-shadow)] rounded-[var(--map-radius)] overflow-hidden backdrop-blur-md max-w-[calc(100vw-48px)] max-h-[calc(100vh-48px)] ${
        position ? '' : 'bottom-6 right-6 rtl:right-auto rtl:left-6'
      } ${MAP_SIZES[mapSize]}`}
      style={style}
    >
      <div
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        style={{ cursor: isDragging ? 'grabbing' : 'grab' }}
        className="h-9 px-3 border-b border-[var(--map-border)] flex items-center justify-between shrink-0 select-none bg-[var(--surface-2)]/90 touch-none active:bg-[var(--hover-bg)]/50 transition-colors"
      >
        <div className="flex items-center gap-2 min-w-0">
          <IconGripVertical size={13} className="text-[var(--text-soft)] shrink-0 cursor-grab active:cursor-grabbing" />
          <IconTruck size={14} className="text-[var(--brand)] animate-pulse shrink-0" />
          <span className="text-xs font-mono font-bold text-[var(--text-primary)] shrink-0 uppercase tracking-wide">
            {t.globalMap.title}
          </span>
          <span className="font-mono text-3xs bg-[var(--hover-bg)] border border-[var(--border-strong)] px-1.5 py-0.5 rounded text-[var(--text-secondary)] tracking-tight">
            {activeRoutes.length} {t.globalMap.routesLabel} · {onlineDriversCount} {t.globalMap.onlineLabel}
          </span>
        </div>

        <div className="flex items-center gap-3 shrink-0">
          {mapSize !== 'S' && (
            <div className="relative flex items-center">
              <input
                type="text"
                placeholder={t.globalMap.searchPlaceholder}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="font-mono text-3xs w-32 px-2 py-0.5 rounded border border-[var(--border-strong)] bg-[var(--surface)] text-[var(--text-primary)] placeholder-[var(--text-soft)] focus:outline-none focus:border-[var(--brand)] transition-all pr-5 rtl:pr-2 rtl:pl-5"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery('')}
                  className="absolute right-1.5 rtl:right-auto rtl:left-1.5 text-[var(--text-soft)] hover:text-[var(--text-primary)]"
                >
                  <IconX size={10} />
                </button>
              )}
            </div>
          )}

          <div className="flex items-center border border-[var(--border-strong)] rounded overflow-hidden divide-x divide-[var(--border-strong)] font-mono text-3xs">
            {(['S', 'M', 'L'] as const).map((size) => {
              const label = size === 'S' ? '380px' : size === 'M' ? '680px' : '1020px';
              const active = mapSize === size;
              return (
                <button
                  key={size}
                  type="button"
                  onClick={() => setMapSize(size)}
                  className={`px-2 py-0.5 transition-colors ${
                    active
                      ? 'bg-[var(--brand)] text-white font-bold shadow-inner'
                      : 'bg-[var(--surface)] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)]'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>

          <div className="h-4 w-px bg-[var(--border-strong)]" />

          <div className="flex items-center gap-1">
            {mapSize !== 'S' && (
              <button
                type="button"
                onClick={() => setIsSidebarOpen(!isSidebarOpen)}
                className={`p-1 rounded transition-colors ${
                  isSidebarOpen
                    ? 'text-[var(--brand)] bg-[var(--brand)]/10 hover:bg-[var(--brand)]/20'
                    : 'text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)]'
                }`}
                title={isSidebarOpen ? t.globalMap.hideList : t.globalMap.showList}
              >
                <IconLayoutSidebar size={14} />
              </button>
            )}

            <button
              type="button"
              onClick={() => setMapMode('collapsed')}
              className="p-1 rounded text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] transition-colors"
              title={t.globalMap.minimize}
            >
              <IconMinus size={14} />
            </button>
            <button
              type="button"
              onClick={() => setMapMode('hidden')}
              className="p-1 rounded text-[var(--text-soft)] hover:text-red-500 hover:bg-red-500/10 transition-colors"
              title={t.globalMap.hideCompletely}
            >
              <IconX size={14} />
            </button>
          </div>
        </div>
      </div>

      <div className="flex-1 min-h-0 flex relative">
        <div className="flex-1 h-full min-w-0 relative">
          <DispatchLiveMap
            key={mapSize}
            routes={filteredRoutes as any}
            drivers={filteredDrivers as any}
            focusedRouteId={focusedRouteId}
            focusedDriverId={focusedDriverId}
            onFocusRoute={setFocusedRouteId}
            onFocusDriver={setFocusedDriverId}
            routeColor={routeColorFor}
          />
        </div>

        {mapSize !== 'S' && isSidebarOpen && (
          <div className="w-[200px] shrink-0 border-l rtl:border-l-0 rtl:border-r border-[var(--border-strong)] bg-[var(--surface)] flex flex-col h-full overflow-hidden select-none">
            <div className="px-3 py-2 border-b border-[var(--border-strong)] flex items-center justify-between shrink-0 bg-[var(--surface-2)]">
              <span className="text-3xs font-mono font-bold text-[var(--text-secondary)] uppercase tracking-wider">
                {t.pages?.drivers?.title} ({filteredDrivers.length})
              </span>
            </div>
            <div className="flex-1 overflow-y-auto divide-y divide-[var(--border)]/30">
              {filteredDrivers.length === 0 ? (
                <div className="p-4 text-center font-mono text-3xs text-[var(--text-muted)]">
                  {t.globalMap.noDrivers}
                </div>
              ) : (
                filteredDrivers.map((driver: any) => {
                  const r = driverRoute.get(driver.id);
                  const color = r ? routeColorFor(r.id) : '#71717A';
                  const isFocused = driver.id === focusedDriverId;
                  const stale = isGpsStale(driver.lastLocationAt);
                  const online = driver.currentLat && driver.currentLng && driver.onlineStatus !== 'OFFLINE';

                  return (
                    <button
                      key={driver.id}
                      type="button"
                      onClick={() => setFocusedDriverId(isFocused ? null : driver.id)}
                      className={`w-full text-left rtl:text-right px-3 py-2 flex flex-col gap-0.5 hover:bg-[var(--hover-bg)]/40 transition-colors border-b border-[var(--border)]/10 ${
                        isFocused ? 'bg-[var(--brand)]/[0.04]' : ''
                      }`}
                      style={{
                        borderLeft: !isRtl && isFocused ? `3px solid ${color}` : undefined,
                        borderRight: isRtl && isFocused ? `3px solid ${color}` : undefined,
                      }}
                    >
                      <div className="flex items-center justify-between gap-1 w-full font-mono text-2xs">
                        <div className="flex items-center gap-1.5 min-w-0">
                          <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                            online ? 'bg-emerald-500' : 'bg-gray-400'
                          }`} />
                          <span className="font-semibold text-[var(--text-primary)] truncate">
                            {driver.name}
                          </span>
                        </div>
                        {stale && online && (
                          <span className="text-4xs bg-red-500/10 text-red-500 px-1 py-0.2 rounded font-bold uppercase shrink-0">
                            {t.globalMap.staleGps}
                          </span>
                        )}
                      </div>
                      {r ? (
                        <div className="flex items-center gap-1 font-mono text-3xs">
                          <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: color }} />
                          <span className="font-medium text-[var(--text-soft)] truncate">
                            {r.name}
                          </span>
                        </div>
                      ) : (
                        <span className="font-mono text-3xs text-[var(--text-muted)]">
                          {t.globalMap.offRoute}
                        </span>
                      )}
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
