import { useEffect, useRef, useState, useMemo } from 'react';
import { MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ErrorBoundary from '@/components/ErrorBoundary';
import { useIsDark } from '@/lib/ui/theme';
import { useLocaleContext, useT } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { routeColorFromMap } from '@/lib/utils';

// ── Types ────────────────────────────────────────────────────────────────────
export type LiveDriver = {
  id: string;
  name: string;
  currentLat?: number | null;
  currentLng?: number | null;
  lastLocationAt?: string | null;
  onlineStatus?: string;
};

export type MapRouteStop = {
  deliveryId?: string;
  status: string;
  clientName?: string;
  deliveryCity?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  stopType?: 'PICKUP' | 'DELIVERY';
};

export type MapRoute = {
  id: string;
  name: string;
  status: string;
  driverId?: string;
  driverName?: string;
  stops: MapRouteStop[];
};

interface Props {
  routes: MapRoute[];
  drivers: LiveDriver[];
  focusedRouteId?: string | null;
  focusedDriverId?: string | null;
  onFocusRoute?: (routeId: string | null) => void;
  onFocusDriver?: (driverId: string | null) => void;
  routeColorMap: Map<string, string>;
}

// ── Helpers ────────────────────────────────────────────────────────────────────
function isGpsStale(lastLocationAt?: string | null): boolean {
  if (!lastLocationAt) return true;
  return Date.now() - new Date(lastLocationAt).getTime() > 10 * 60 * 1000;
}

// ── Icon factories (coloured by ROUTE) ───────────────────────────────────────────
// Numbered teardrop — identical to the per-route page marker (RouteTrackingMap), so the
// live map and the route detail show the same pin. The label is the stop's order number.
function makeStopIcon(color: string, label: string, dim: boolean, focused: boolean) {
  const scale = focused ? 'scale(1.2)' : 'scale(1)';
  const opacity = dim ? 0.35 : 1;
  const fontSize = label.length > 2 ? 9 : 11;
  return L.divIcon({
    className: '',
    iconSize: [26, 34], iconAnchor: [13, 34], popupAnchor: [0, -36],
    html: `<div style="width:26px;height:34px;transform:${scale};transform-origin:50% 100%;opacity:${opacity};filter:drop-shadow(0 2px 5px rgba(0,0,0,0.28));transition:transform 0.15s,opacity 0.15s;">
  <svg width="26" height="34" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
    <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" fill="${color}"/>
    <text x="14" y="15" text-anchor="middle" dominant-baseline="middle" fill="white" font-size="${fontSize}" font-weight="800" font-family="system-ui,sans-serif">${label}</text>
  </svg>
</div>`,
  });
}

// Driver marker = the driver's avatar photo in a ring tinted to its route colour (so the puck matches
// its route's pins), with an online/offline status dot. Falls back to mono initials when no photo.
// All chrome is token-based (surface disc + status tokens) so it reads correctly in light AND dark.
// Stale GPS dims the whole puck.
function makeDriverIcon(ring: string, dim: boolean, focused: boolean, photoUrl: string | null | undefined, name: string, online: boolean) {
  const size = focused ? 42 : 36;
  const wrap = size + 8;
  const opacity = dim ? 0.45 : 1;
  const dot = Math.max(10, Math.round(size * 0.3));
  const initials = (name || '').split(/\s+/).map(p => p[0]).filter(Boolean).join('').slice(0, 2).toUpperCase() || '—';
  const inner = photoUrl
    ? `<img src="${photoUrl}" alt="" aria-hidden="true" style="width:100%;height:100%;object-fit:cover;display:block;" />`
    : `<span style="font-family:var(--font-sans);font-weight:700;font-size:${Math.round(size * 0.36)}px;line-height:1;color:var(--text-primary);">${initials}</span>`;
  return L.divIcon({
    className: '',
    iconSize: [wrap, wrap], iconAnchor: [wrap / 2, wrap / 2], popupAnchor: [0, -(size / 2) - 6],
    html: `<div style="position:relative;width:${wrap}px;height:${wrap}px;opacity:${opacity};transition:opacity 0.15s;">
  <div style="position:absolute;top:4px;left:4px;width:${size}px;height:${size}px;border-radius:50%;background:var(--surface);border:2.5px solid ${ring};box-shadow:0 0 0 2px ${ring}33, var(--shadow-card);overflow:hidden;display:flex;align-items:center;justify-content:center;">
    ${inner}
  </div>
  <span style="position:absolute;bottom:3px;right:3px;width:${dot}px;height:${dot}px;border-radius:50%;background:${online ? 'var(--success)' : 'var(--text-soft)'};border:2px solid var(--surface);"></span>
</div>`,
  });
}

// ── Camera: fit everything on first load, fly to a route when focused ────────────
function Camera({ routes, drivers, focusedRouteId, focusedDriverId }: { routes: MapRoute[]; drivers: LiveDriver[]; focusedRouteId?: string | null; focusedDriverId?: string | null }) {
  const map = useMap();
  const fitted = useRef(false);

  // Initial fit to all stops + drivers.
  useEffect(() => {
    if (fitted.current) return;
    const pts: [number, number][] = [];
    routes.forEach(r => r.stops.forEach(s => { if (s.dropoffLat && s.dropoffLng) pts.push([s.dropoffLat, s.dropoffLng]); }));
    drivers.forEach(d => { if (d.currentLat && d.currentLng && !isGpsStale(d.lastLocationAt)) pts.push([d.currentLat, d.currentLng]); });
    if (pts.length === 0) return;
    try {
      const b = L.latLngBounds(pts);
      if (b.isValid()) { map.fitBounds(b, { padding: [48, 48], maxZoom: 14 }); fitted.current = true; }
    } catch { /* ignore */ }
  }, [map, routes, drivers]);

  // Fly to the focused route.
  useEffect(() => {
    if (!focusedRouteId) return;
    const r = routes.find(x => x.id === focusedRouteId);
    if (!r) return;
    const pts: [number, number][] = [];
    r.stops.forEach(s => { if (s.dropoffLat && s.dropoffLng) pts.push([s.dropoffLat, s.dropoffLng]); });
    const drv = r.driverId ? drivers.find(d => d.id === r.driverId) : null;
    if (drv?.currentLat && drv?.currentLng && !isGpsStale(drv.lastLocationAt)) pts.push([drv.currentLat, drv.currentLng]);
    if (pts.length === 0) return;
    try {
      const b = L.latLngBounds(pts);
      if (b.isValid()) map.flyToBounds(b, { padding: [60, 60], maxZoom: 15, duration: 0.6 });
    } catch { /* ignore */ }
  }, [map, focusedRouteId, routes, drivers]);

  // Fly to the focused driver.
  useEffect(() => {
    if (!focusedDriverId) return;
    const drv = drivers.find(d => d.id === focusedDriverId);
    if (drv?.currentLat && drv?.currentLng) {
      map.setView([drv.currentLat, drv.currentLng], 15, { animate: true, duration: 0.6 });
    }
  }, [map, focusedDriverId, drivers]);

  return null;
}

// ── Main ─────────────────────────────────────────────────────────────────────────
function DispatchLiveMapInner({ routes, drivers, focusedRouteId, focusedDriverId, onFocusRoute, routeColorMap }: Props) {
  const { locale } = useLocaleContext();
  const t = useT();
  const [mounted, setMounted] = useState(false);
  const isDark = useIsDark();
  useEffect(() => { setMounted(true); }, []);

  // Localized statuses
  const statusLabels: Record<string, string> = {
    ONLINE: locale === 'ar' ? 'متصل' : locale === 'en' ? 'Online' : 'En service',
    ON_BREAK: locale === 'ar' ? 'في استراحة' : locale === 'en' ? 'On Break' : 'En pause',
    OFFLINE: locale === 'ar' ? 'غير متصل' : locale === 'en' ? 'Offline' : 'Hors ligne',
  };

  // Localized relative time
  const lastSeen = (iso?: string | null): string => {
    if (!iso) {
      return locale === 'ar' ? 'الموقع غير معروف' : locale === 'en' ? 'Unknown position' : 'Position inconnue';
    }
    const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
    if (mins < 1) {
      return locale === 'ar' ? 'شوهد الآن' : locale === 'en' ? 'Seen just now' : "Vu à l'instant";
    }
    if (mins < 60) {
      if (locale === 'ar') return `شوهد منذ ${mins} دقيقة`;
      if (locale === 'en') return `Seen ${mins}m ago`;
      return `Vu il y a ${mins} min`;
    }
    const h = Math.floor(mins / 60);
    if (h < 24) {
      if (locale === 'ar') return `شوهد منذ ${h} ساعة`;
      if (locale === 'en') return `Seen ${h}h ago`;
      return `Vu il y a ${h} h`;
    }
    const days = Math.floor(h / 24);
    if (locale === 'ar') return `شوهد منذ ${days} يوم`;
    if (locale === 'en') return `Seen ${days}d ago`;
    return `Vu il y a ${days} j`;
  };

  const loadingText = t.common?.chargementCarte ?? 'Loading map…';
  const showAllText = t.common?.toutAfficher ?? 'Show all';

  // Show every driver with a known position (last-known included); staleness only dims + labels.
  const visibleDrivers = useMemo(
    () => drivers.filter(d => d.currentLat && d.currentLng),
    [drivers]
  );
  const onlineCount = useMemo(() => visibleDrivers.filter(d => !isGpsStale(d.lastLocationAt)).length, [visibleDrivers]);
  // driverId → the route (and colour) it belongs to, so the car matches its route's pins.
  const driverRoute = useMemo(() => {
    const m = new Map<string, MapRoute>();
    routes.forEach(r => { if (r.driverId) m.set(r.driverId, r); });
    return m;
  }, [routes]);

  const stopMarkers = useMemo(() => {
    const out: React.ReactElement[] = [];
    routes.forEach(route => {
      const color = routeColorFromMap(routeColorMap, route.id);
      const dim = !!focusedRouteId && focusedRouteId !== route.id;
      let stopNo = 0; // sequential delivery-stop number, like the route detail page
      route.stops.forEach((stop, i) => {
        if (stop.stopType === 'PICKUP') return; // pins are delivery destinations
        if (!stop.dropoffLat || !stop.dropoffLng) return;
        stopNo += 1;
        out.push(
          <Marker
            key={`${route.id}-${stop.deliveryId ?? i}`}
            position={[stop.dropoffLat, stop.dropoffLng]}
            icon={makeStopIcon(color, String(stopNo), dim, focusedRouteId === route.id)}
            eventHandlers={{ click: () => onFocusRoute?.(focusedRouteId === route.id ? null : route.id) }}
          >
            <Popup>
              <div style={{ fontFamily: 'var(--font-sans)', minWidth: 160 }}>
                <div style={{ fontSize: 10, fontWeight: 800, color, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 4 }}>{route.name}</div>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>{stop.clientName ?? '—'}</div>
                {stop.deliveryCity && <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{stop.deliveryCity}</div>}
                <div style={{ fontSize: 10, color: 'var(--text-muted)', marginTop: 4, fontWeight: 600 }}>{tlabel(t.statusLabels, stop.status) ?? stop.status}</div>
              </div>
            </Popup>
          </Marker>
        );
      });
    });
    return out;
  }, [routes, focusedRouteId, onFocusRoute, routeColorMap]);

  const driverMarkers = useMemo(() => {
    return visibleDrivers.map(driver => {
      const r = driverRoute.get(driver.id);
      const color = r ? routeColorFromMap(routeColorMap, r.id) : '#71717A';
      const isFocused = driver.id === focusedDriverId || (!!r && r.id === focusedRouteId);
      const dim = !isFocused && (isGpsStale(driver.lastLocationAt) || !!focusedRouteId || !!focusedDriverId);
      const online = (driver.onlineStatus ?? 'OFFLINE') !== 'OFFLINE' && !isGpsStale(driver.lastLocationAt);
      return (
        <Marker
          key={driver.id}
          position={[driver.currentLat!, driver.currentLng!]}
          icon={makeDriverIcon(color, dim, isFocused, (driver as LiveDriver & { photoUrl?: string | null }).photoUrl, driver.name, online)}
          eventHandlers={{ click: () => onFocusRoute?.(r ? (focusedRouteId === r.id ? null : r.id) : null) }}
        >
          <Popup>
            <div style={{ fontFamily: 'var(--font-sans)' }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>{driver.name}</div>
              {r && <div style={{ fontSize: 11, color, fontWeight: 700, marginTop: 2 }}>{r.name}</div>}
              <div style={{ fontSize: 10, color: 'var(--text-muted)', marginTop: 2 }}>{statusLabels[driver.onlineStatus ?? 'OFFLINE']}</div>
              <div style={{ fontSize: 10, color: isGpsStale(driver.lastLocationAt) ? 'var(--danger)' : 'var(--text-secondary)', marginTop: 3, fontWeight: 600 }}>
                {lastSeen(driver.lastLocationAt)}
              </div>
            </div>
          </Popup>
        </Marker>
      );
    });
  }, [visibleDrivers, driverRoute, focusedRouteId, focusedDriverId, onFocusRoute, routeColorMap, locale]);

  if (!mounted) {
    return (
      <div className="w-full h-full bg-[var(--app-bg)] flex flex-col items-center justify-center gap-2.5">
        <div className="w-8 h-8 rounded-full border-[3px] border-[var(--border)] border-t-[var(--brand)] animate-spin" />
        <span className="text-2xs text-[var(--text-muted)] font-bold tracking-wider uppercase">{loadingText}</span>
      </div>
    );
  }

  const center: [number, number] = [36.8065, 10.1815];
  const stopCount = routes.reduce((n, r) => n + r.stops.filter(s => s.stopType !== 'PICKUP' && s.dropoffLat && s.dropoffLng).length, 0);

  // Localized bottom overlay stats
  const statsLabel = (() => {
    const tourneeKey = t.common?.tournee ?? 'Route';
    const onlineKey = 'online';
    const arretsKey = t.common?.arrets ?? 'stops';
    const routeCount = `${routes.length} ${tourneeKey}${routes.length !== 1 ? 's' : ''}`;
    const onlineCountStr = `${onlineCount} ${onlineKey}`;
    const stopCountStr = `${stopCount} ${arretsKey}${stopCount !== 1 ? 's' : ''}`;
    return `${routeCount} · ${onlineCountStr} · ${stopCountStr}`;
  })();

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative', zIndex: 0, isolation: 'isolate' }}>
      <MapContainer center={center} zoom={11} style={{ width: '100%', height: '100%' }} zoomControl>
        <TileLayer
          attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
          maxZoom={19}
        />
        <Camera routes={routes} drivers={visibleDrivers} focusedRouteId={focusedRouteId} focusedDriverId={focusedDriverId} />
        {stopMarkers}
        {driverMarkers}
      </MapContainer>

      {/* Overlay counts + a "reset focus" affordance */}
      <div className="absolute bottom-3.5 left-3.5 z-[800] flex gap-1.5 font-sans">
        <div className="bg-[var(--surface)] border border-[var(--border)] rounded-[var(--radius-md)] px-2.5 py-1 flex items-center gap-1.5 shadow-[var(--shadow-dropdown)] pointer-events-none">
          <div className="w-1.5 h-1.5 rounded-full bg-[var(--success)] animate-pulse" />
          <span className="text-2xs text-[var(--text-primary)] font-bold tracking-wide">
            {statsLabel}
          </span>
        </div>
        {focusedRouteId && (
          <button
            type="button"
            onClick={() => onFocusRoute?.(null)}
            className="bg-[var(--surface)] border border-[var(--border)] rounded-[var(--radius-md)] px-2.5 py-1 text-2xs text-[var(--text-primary)] font-bold tracking-wide shadow-[var(--shadow-dropdown)] hover:bg-[var(--hover-bg)] active:scale-[0.98] transition-all cursor-pointer"
          >
            {showAllText}
          </button>
        )}
      </div>
    </div>
  );
}

export default function DispatchLiveMap(props: Props) {
  const { locale } = useLocaleContext();

  const errTitle = locale === 'ar' ? 'خارطة التوزيع المباشر غير متوفرة' : locale === 'en' ? 'Live Dispatch Map Unavailable' : 'Carte de Dispatch Live Indisponible';
  const errDesc = locale === 'ar' ? 'حدث خطأ في عرض لوحة التوزيع.' : locale === 'en' ? 'A rendering error occurred on the dispatch board.' : "Une erreur d'affichage s'est produite sur le tableau de dispatch.";
  const errRefresh = locale === 'ar' ? 'تحديث الصفحة' : locale === 'en' ? 'Refresh Page' : 'Actualiser la page';

  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[350px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-xs p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">{errTitle}</p>
        <p className="text-2xs text-[var(--text-muted)] mb-4">{errDesc}</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-2xs rounded-xs font-medium hover:opacity-90 transition">
          {errRefresh}
        </button>
      </div>
    }>
      <DispatchLiveMapInner {...props} />
    </ErrorBoundary>
  );
}
