import { useEffect, useRef, useState, useMemo } from 'react';
import { MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ErrorBoundary from '@/components/ErrorBoundary';
import { useIsDark } from '@/lib/theme';
import voitureFourgon from '../../icons/voiture-fourgon.png';

/** "Vu il y a 5 min" style relative label for a driver's last GPS fix (the map UI is FR). */
function lastSeenFr(iso?: string | null): string {
  if (!iso) return 'Position inconnue';
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (mins < 1) return "Vu à l'instant";
  if (mins < 60) return `Vu il y a ${mins} min`;
  const h = Math.floor(mins / 60);
  if (h < 24) return `Vu il y a ${h} h`;
  return `Vu il y a ${Math.floor(h / 24)} j`;
}

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
  stops: MapRouteStop[];
};

interface Props {
  routes: MapRoute[];
  drivers: LiveDriver[];
  focusedRouteId?: string | null;
  onFocusRoute?: (routeId: string | null) => void;
  routeColor: (routeId?: string | null) => string;
}

const STATUS_LABEL: Record<string, string> = {
  ONLINE: 'En service', ON_BREAK: 'En pause', OFFLINE: 'Hors ligne',
};

// ── Helpers ────────────────────────────────────────────────────────────────────
function isGpsStale(lastLocationAt?: string | null): boolean {
  if (!lastLocationAt) return true;
  return Date.now() - new Date(lastLocationAt).getTime() > 10 * 60 * 1000;
}

function stopSymbol(status: string): string {
  if (status === 'IN_TRANSIT') return '▶';
  if (status === 'PICKED_UP') return '↑';
  if (status === 'DELIVERED') return '✓';
  if (status === 'FAILED' || status === 'CANCELLED') return '✗';
  if (status === 'PARTIALLY_DELIVERED' || status === 'PARTIAL') return '◑';
  return '●';
}

// ── Icon factories (coloured by ROUTE, not status) ───────────────────────────────
function makeStopIcon(color: string, status: string, dim: boolean, focused: boolean) {
  const symbol = stopSymbol(status);
  const scale = focused ? 'scale(1.2)' : 'scale(1)';
  const opacity = dim ? 0.35 : 1;
  return L.divIcon({
    className: '',
    iconSize: [26, 34], iconAnchor: [13, 34], popupAnchor: [0, -36],
    html: `<div style="width:26px;height:34px;transform:${scale};transform-origin:50% 100%;opacity:${opacity};filter:drop-shadow(0 2px 5px rgba(0,0,0,0.28));transition:transform 0.15s,opacity 0.15s;">
  <svg width="26" height="34" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
    <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" fill="${color}"/>
    <text x="14" y="15" text-anchor="middle" dominant-baseline="middle" fill="white" font-size="10" font-weight="800" font-family="system-ui,sans-serif">${symbol}</text>
  </svg>
</div>`,
  });
}

// Driver van icon — the SAME artwork as the per-route page (voiture-fourgon.png), wrapped in a
// ring tinted to the route colour so the car matches its route's pins. Stale GPS dims it.
function makeDriverIcon(ring: string, dim: boolean, focused: boolean) {
  const size = focused ? 42 : 36;
  const opacity = dim ? 0.4 : 1;
  return L.divIcon({
    className: '',
    iconSize: [size + 8, size + 8], iconAnchor: [(size + 8) / 2, (size + 8) / 2], popupAnchor: [0, -(size / 2) - 6],
    html: `<div style="width:${size + 8}px;height:${size + 8}px;display:flex;align-items:center;justify-content:center;opacity:${opacity};transition:opacity 0.15s;">
  <div style="width:${size + 8}px;height:${size + 8}px;border-radius:50%;background:#fff;border:2.5px solid ${ring};box-shadow:0 0 0 2px ${ring}33, 0 2px 6px rgba(0,0,0,0.3);display:flex;align-items:center;justify-content:center;">
    <img src="${voitureFourgon}" alt="" aria-hidden="true" style="width:${Math.round(size * 0.74)}px;height:${Math.round(size * 0.74)}px;object-fit:contain;display:block;" />
  </div>
</div>`,
  });
}

// ── Camera: fit everything on first load, fly to a route when focused ────────────
function Camera({ routes, drivers, focusedRouteId }: { routes: MapRoute[]; drivers: LiveDriver[]; focusedRouteId?: string | null }) {
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

  return null;
}

// ── Main ─────────────────────────────────────────────────────────────────────────
function DispatchLiveMapInner({ routes, drivers, focusedRouteId, onFocusRoute, routeColor }: Props) {
  const [mounted, setMounted] = useState(false);
  const isDark = useIsDark();
  useEffect(() => { setMounted(true); }, []);

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
    const out: JSX.Element[] = [];
    routes.forEach(route => {
      const color = routeColor(route.id);
      const dim = !!focusedRouteId && focusedRouteId !== route.id;
      route.stops.forEach((stop, i) => {
        if (stop.stopType === 'PICKUP') return; // pins are delivery destinations
        if (!stop.dropoffLat || !stop.dropoffLng) return;
        out.push(
          <Marker
            key={`${route.id}-${stop.deliveryId ?? i}`}
            position={[stop.dropoffLat, stop.dropoffLng]}
            icon={makeStopIcon(color, stop.status, dim, focusedRouteId === route.id)}
            eventHandlers={{ click: () => onFocusRoute?.(focusedRouteId === route.id ? null : route.id) }}
          >
            <Popup>
              <div style={{ fontFamily: '"IBM Plex Sans", sans-serif', minWidth: 160 }}>
                <div style={{ fontSize: 10, fontWeight: 800, color, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 4 }}>{route.name}</div>
                <div style={{ fontSize: 13, fontWeight: 700, color: '#09090B', marginBottom: 2 }}>{stop.clientName ?? '—'}</div>
                {stop.deliveryCity && <div style={{ fontSize: 11, color: '#71717A' }}>{stop.deliveryCity}</div>}
                <div style={{ fontSize: 10, color: '#A1A1AA', marginTop: 4, fontWeight: 600, textTransform: 'uppercase' }}>{stop.status}</div>
              </div>
            </Popup>
          </Marker>
        );
      });
    });
    return out;
  }, [routes, focusedRouteId, onFocusRoute, routeColor]);

  const driverMarkers = useMemo(() => {
    return visibleDrivers.map(driver => {
      const r = driverRoute.get(driver.id);
      const color = r ? routeColor(r.id) : '#71717A';
      const dim = isGpsStale(driver.lastLocationAt) || (!!focusedRouteId && (!r || r.id !== focusedRouteId));
      return (
        <Marker
          key={driver.id}
          position={[driver.currentLat!, driver.currentLng!]}
          icon={makeDriverIcon(color, dim, !!r && r.id === focusedRouteId)}
          eventHandlers={{ click: () => onFocusRoute?.(r ? (focusedRouteId === r.id ? null : r.id) : null) }}
        >
          <Popup>
            <div style={{ fontFamily: '"IBM Plex Sans", sans-serif' }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: '#09090B' }}>{driver.name}</div>
              {r && <div style={{ fontSize: 11, color, fontWeight: 700, marginTop: 2 }}>{r.name}</div>}
              <div style={{ fontSize: 10, color: '#A1A1AA', marginTop: 2 }}>{STATUS_LABEL[driver.onlineStatus ?? 'OFFLINE']}</div>
              <div style={{ fontSize: 10, color: isGpsStale(driver.lastLocationAt) ? '#C7372F' : '#71717A', marginTop: 3, fontWeight: 600 }}>
                {lastSeenFr(driver.lastLocationAt)}
              </div>
            </div>
          </Popup>
        </Marker>
      );
    });
  }, [visibleDrivers, driverRoute, focusedRouteId, onFocusRoute, routeColor]);

  if (!mounted) {
    return (
      <div style={{ width: '100%', height: '100%', background: '#F4F4F5', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
        <div style={{ width: 32, height: 32, borderRadius: '50%', border: '3px solid #E4E4E7', borderTopColor: 'var(--brand)', animation: 'spin 0.8s linear infinite' }} />
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
        <span style={{ fontSize: 11, color: '#A1A1AA', fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase' }}>Chargement de la carte…</span>
      </div>
    );
  }

  const center: [number, number] = [36.8065, 10.1815];
  const stopCount = routes.reduce((n, r) => n + r.stops.filter(s => s.stopType !== 'PICKUP' && s.dropoffLat && s.dropoffLng).length, 0);

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative', zIndex: 0, isolation: 'isolate' }}>
      <MapContainer center={center} zoom={11} style={{ width: '100%', height: '100%' }} zoomControl>
        <TileLayer
          attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
          maxZoom={19}
        />
        <Camera routes={routes} drivers={visibleDrivers} focusedRouteId={focusedRouteId} />
        {stopMarkers}
        {driverMarkers}
      </MapContainer>

      {/* Overlay counts + a "reset focus" affordance */}
      <div style={{ position: 'absolute', bottom: 10, left: 10, zIndex: 800, display: 'flex', gap: 6 }}>
        <div style={{ background: 'rgba(9,9,11,0.72)', borderRadius: 4, padding: '3px 8px', display: 'flex', alignItems: 'center', gap: 5, pointerEvents: 'none' }}>
          <div style={{ width: 5, height: 5, borderRadius: '50%', background: '#10B981' }} />
          <span style={{ fontSize: 10, color: '#D4D4D8', fontWeight: 700, letterSpacing: '0.05em' }}>
            {routes.length} tournée{routes.length !== 1 ? 's' : ''} · {onlineCount} en ligne · {stopCount} arrêts
          </span>
        </div>
        {focusedRouteId && (
          <button
            type="button"
            onClick={() => onFocusRoute?.(null)}
            style={{ background: 'rgba(9,9,11,0.72)', borderRadius: 4, padding: '3px 8px', fontSize: 10, color: '#fff', fontWeight: 700, letterSpacing: '0.05em', border: 'none', cursor: 'pointer' }}
          >
            Tout afficher
          </button>
        )}
      </div>
    </div>
  );
}

export default function DispatchLiveMap(props: Props) {
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[350px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-xs p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">Carte de Dispatch Live Indisponible</p>
        <p className="text-2xs text-[var(--text-muted)] mb-4">Une erreur d'affichage s'est produite sur le tableau de dispatch.</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-2xs rounded-xs font-medium hover:opacity-90 transition">
          Actualiser la page
        </button>
      </div>
    }>
      <DispatchLiveMapInner {...props} />
    </ErrorBoundary>
  );
}
