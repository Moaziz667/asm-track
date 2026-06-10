
import { useEffect, useState, useMemo } from 'react';
import { MapContainer, Marker, Popup, Polyline, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ErrorBoundary from '@/components/ErrorBoundary';
import { useIsDark } from '@/lib/theme';
import { STATUS_COLORS as BADGE_STATUS_COLORS } from '@/components/StatusBadge';
import voitureFourgon from '../../icons/voiture-fourgon.png';

type RouteStop = {
  id: string;
  deliveryId: string;
  stopType?: 'PICKUP' | 'DELIVERY';
  stopOrder: number;
  status: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  sourceDepotLat?: number;
  sourceDepotLng?: number;
  sourceDepotName?: string;
  routeGeometry?: string;
  clientName?: string;
  order?: { clientName?: string; erpOrderId?: string };
};

type DriverLocation = {
  id: string;
  name: string;
  lat?: number;
  lng?: number;
  lastLocationAt?: string | null;
};

/** "Vu il y a 5 min" relative label for the driver's last GPS fix. */
function lastSeenFr(iso?: string | null): string {
  if (!iso) return 'Position inconnue';
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (mins < 1) return "Vu à l'instant";
  if (mins < 60) return `Vu il y a ${mins} min`;
  const h = Math.floor(mins / 60);
  if (h < 24) return `Vu il y a ${h} h`;
  return `Vu il y a ${Math.floor(h / 24)} j`;
}
function isGpsStaleIso(iso?: string | null): boolean {
  if (!iso) return true;
  return Date.now() - new Date(iso).getTime() > 10 * 60 * 1000;
}

type Props = {
  stops: RouteStop[];
  driver?: DriverLocation | null;
  depot?: { lat: number; lng: number; name?: string } | null;
  height?: number | string;
  zoom?: number;
  pinLat?: number | null;
  pinLng?: number | null;
  onPick?: (lat: number, lng: number) => void;
  onStopClick?: (stopId: string) => void;
  center?: [number, number];
};

// ── Icon factories ───────────────────────────────────────────────────────────

const DEFAULT_STATUS_COLOR = '#A1A1AA';

function getStatusColor(status: string) {
  return BADGE_STATUS_COLORS[status.toUpperCase()] || DEFAULT_STATUS_COLOR;
}

// Stop Pin (Teardrop) - generates SVG HTML for Leaflet with status color
function createStopIcon(label: string, status: string = 'PENDING', selected = false) {
  const fontSize = String(label).length > 2 ? 8 : String(label).length > 1 ? 10 : 12;
  const color = getStatusColor(status);
  const scale = selected ? 'scale(1.12)' : 'scale(1)';
  const filter = selected
    ? 'drop-shadow(0 2px 5px rgba(0, 0, 0, 0.25))'
    : 'drop-shadow(0 1px 3px rgba(0, 0, 0, 0.2))';

  return L.divIcon({
    className: '',
    iconSize: [28, 36],
    iconAnchor: [14, 36],
    popupAnchor: [0, -38],
    html: `<div style="width:28px;height:36px;transform:${scale};transform-origin:50% 100%;filter:${filter};transition:transform 0.15s,filter 0.15s;">
  <svg width="28" height="36" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
    <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" fill="${color}"/>
    <text x="14" y="15" text-anchor="middle" dominant-baseline="middle" fill="white" font-size="${fontSize}" font-weight="800" font-family="system-ui,sans-serif" letter-spacing="-0.3">${label}</text>
  </svg>
</div>`,
  });
}

// Driver Pin - PNG van icon from the app assets
function createDriverIcon() {
  return L.divIcon({
    className: '',
    iconSize: [36, 36],
    iconAnchor: [18, 18],
    popupAnchor: [0, -20],
    html: `<div style="width:36px;height:36px;display:flex;align-items:center;justify-content:center;filter:drop-shadow(0 1px 3px rgba(0, 0, 0, 0.2));animation:pulse 2.5s ease-in-out infinite;">
  <img src="${voitureFourgon}" alt="" aria-hidden="true" style="width:36px;height:36px;object-fit:contain;display:block;" />
</div>`,
  });
}

// Depot Pin - generates SVG HTML for Leaflet
function createDepotIcon() {
  return L.divIcon({
    className: '',
    iconSize: [42, 42],
    iconAnchor: [21, 21],
    popupAnchor: [0, -22],
    html: `<div style="width:42px;height:42px;filter:drop-shadow(0 3px 8px rgba(0,0,0,0.45));">
  <svg width="42" height="42" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg">
    <circle cx="21" cy="21" r="21" fill="#111827"/>
    <circle cx="21" cy="21" r="19" fill="none" stroke="white" stroke-width="1.5" stroke-opacity="0.4"/>
    <polygon points="21,10 10,19 32,19" fill="white" fill-opacity="0.95"/>
    <rect x="12" y="19" width="18" height="11" fill="white" fill-opacity="0.9" rx="1"/>
    <rect x="18" y="23" width="6" height="7" fill="#111827" rx="1"/>
  </svg>
</div>`,
  });
}

/** Cyan depot icon for secondary depots (PICKUP stops). */
function createPickupDepotIcon() {
  return L.divIcon({
    className: '',
    iconSize: [42, 42],
    iconAnchor: [21, 21],
    popupAnchor: [0, -22],
    html: `<div style="width:42px;height:42px;filter:drop-shadow(0 3px 8px rgba(0,0,0,0.45));">
  <svg width="42" height="42" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg">
    <circle cx="21" cy="21" r="21" fill="#0891B2"/>
    <circle cx="21" cy="21" r="19" fill="none" stroke="white" stroke-width="1.5" stroke-opacity="0.4"/>
    <polygon points="21,10 10,19 32,19" fill="white" fill-opacity="0.95"/>
    <rect x="12" y="19" width="18" height="11" fill="white" fill-opacity="0.9" rx="1"/>
    <rect x="18" y="23" width="6" height="7" fill="#0891B2" rx="1"/>
  </svg>
</div>`,
  });
}

function makePinDrop() {
  return L.divIcon({
    className: '',
    iconSize: [32, 44],
    iconAnchor: [16, 44],
    popupAnchor: [0, -42],
    html: `<div style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.4));">
  <svg width="32" height="44" viewBox="0 0 32 44" xmlns="http://www.w3.org/2000/svg">
    <path d="M16 0C7.163 0 0 7.163 0 16c0 6.193 3.56 11.563 8.765 14.257L16 44l7.235-13.743C28.44 27.563 32 22.193 32 16 32 7.163 24.837 0 16 0z" fill="#ef4444"/>
    <circle cx="16" cy="16" r="7" fill="white" fill-opacity="0.9"/>
  </svg>
</div>`,
  });
}

// ── Inner map helpers ────────────────────────────────────────────────────────
function MapClickHandler({ onPick }: { onPick: (lat: number, lng: number) => void }) {
  useMapEvents({ click(e) { onPick(e.latlng.lat, e.latlng.lng); } });
  return null;
}

function FlyToMarker({ target }: { target: [number, number] | null }) {
  const map = useMap();
  useEffect(() => {
    if (!target) return;
    const lat = Number(target[0]);
    const lng = Number(target[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    try {
      map.flyTo([lat, lng], Math.max(map.getZoom(), 16), { animate: true, duration: 0.8 });
    } catch { /* invalid coords, skip */ }
  }, [target]);
  return null;
}

function MapBehavior({ center, zoom }: { center?: [number, number], zoom?: number }) {
  const map = useMap();
  useEffect(() => {
    if (!center) return;
    const lat = Number(center[0]);
    const lng = Number(center[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    try {
      map.flyTo([lat, lng], zoom || 16, { animate: true, duration: 0.8 });
    } catch { /* invalid coords, skip */ }
  }, [center, zoom, map]);
  return null;
}

function FitBounds({ stops, depot }: { stops: RouteStop[]; depot?: { lat: number; lng: number } | null }) {
  const map = useMap();
  useEffect(() => {
    const pts: [number, number][] = stops
      .filter((s) => {
        if (s.dropoffLat != null && s.dropoffLng != null) return true;
        if (s.stopType === 'PICKUP' && s.sourceDepotLat != null && s.sourceDepotLng != null) return true;
        return false;
      })
      .map((s) => {
        if (s.dropoffLat != null && s.dropoffLng != null) return [s.dropoffLat!, s.dropoffLng!] as [number, number];
        return [s.sourceDepotLat!, s.sourceDepotLng!] as [number, number];
      });
    if (depot?.lat != null) pts.push([depot.lat, depot.lng]);
    if (pts.length < 2) return;
    try {
      const b = L.latLngBounds(pts);
      if (b.isValid()) map.fitBounds(b, { padding: [40, 40], maxZoom: 15 });
    } catch { /* ignore */ }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return null;
}

// ── Main component ───────────────────────────────────────────────────────────
function RouteTrackingMapInner({
  stops = [],
  driver,
  depot,
  height = 420,
  zoom = 11,
  pinLat,
  pinLng,
  onPick,
  onStopClick,
  center: manualCenter,
}: Props) {
  const [flyTarget, setFlyTarget] = useState<[number, number] | null>(null);
  const isDark = useIsDark();

  const pinnedStops = stops
    .filter((s) => s.dropoffLat != null && s.dropoffLng != null
      && !isNaN(Number(s.dropoffLat)) && !isNaN(Number(s.dropoffLng)))
    .sort((a, b) => a.stopOrder - b.stopOrder);

  const center: [number, number] = manualCenter
    ?? (pinLat != null && pinLng != null ? [pinLat, pinLng]
      : driver?.lat != null && driver?.lng != null ? [driver.lat, driver.lng]
      : depot?.lat != null && depot?.lng != null ? [depot.lat, depot.lng]
      : pinnedStops.length > 0 ? [pinnedStops[0].dropoffLat!, pinnedStops[0].dropoffLng!]
      : [36.8065, 10.1815]);

  const routeGeometryPaths: [number, number][][] = stops
    .filter((s) => {
      if (s.dropoffLat != null && s.dropoffLng != null) return true;
      if (s.stopType === 'PICKUP' && s.sourceDepotLat != null && s.sourceDepotLng != null) return true;
      return false;
    })
    .sort((a, b) => a.stopOrder - b.stopOrder)
    .map((stop) => {
      if (!stop.routeGeometry) return [] as [number, number][];
      try {
        const parsed = JSON.parse(stop.routeGeometry);
        if (!Array.isArray(parsed)) return [] as [number, number][];
        return parsed
          .filter((p: unknown) => Array.isArray(p) && p.length >= 2)
          .map((p: unknown) => {
            const [lat, lng] = p as [unknown, unknown];
            return [Number(lat), Number(lng)] as [number, number];
          })
          .filter(([lat, lng]) => Number.isFinite(lat) && Number.isFinite(lng));
      } catch { return [] as [number, number][]; }
    })
    .filter((path) => path.length > 1);

  const fallbackPath: [number, number][] = [];
  if (routeGeometryPaths.length === 0) {
    if (driver?.lat != null && driver?.lng != null) fallbackPath.push([driver.lat, driver.lng]);
    stops
      .filter((s) => {
        if (s.dropoffLat != null && s.dropoffLng != null) return true;
        if (s.stopType === 'PICKUP' && s.sourceDepotLat != null && s.sourceDepotLng != null) return true;
        return false;
      })
      .sort((a, b) => a.stopOrder - b.stopOrder)
      .forEach((s) => {
        if (s.dropoffLat != null && s.dropoffLng != null) fallbackPath.push([s.dropoffLat!, s.dropoffLng!]);
        else if (s.sourceDepotLat != null && s.sourceDepotLng != null) fallbackPath.push([s.sourceDepotLat!, s.sourceDepotLng!]);
      });
  }

  const h = typeof height === 'number' ? `${height}px` : height;

  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden', background: 'var(--surface)', position: 'relative', zIndex: 1, height: h }}>
      <MapContainer center={center} zoom={zoom} style={{ width: '100%', height: h, position: 'relative', zIndex: 0 }}>
        <TileLayer
          attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
          maxZoom={19}
        />

        <FlyToMarker target={flyTarget} />
        <MapBehavior center={center} zoom={zoom} />
        <FitBounds stops={stops} depot={depot} />
        {onPick && <MapClickHandler onPick={onPick} />}

        {/* Pinning-mode draggable marker */}
        {pinLat != null && pinLng != null && (
          <Marker
            position={[pinLat, pinLng]}
            icon={makePinDrop()}
            draggable
            eventHandlers={{
              dragend(e) {
                const { lat, lng } = e.target.getLatLng();
                onPick?.(lat, lng);
              },
            }}
          >
            <Popup>
              <div style={{ fontWeight: 700, fontSize: 13 }}>Position sélectionnée</div>
              <div style={{ fontSize: 11, color: '#6b7280' }}>Faites glisser pour ajuster</div>
            </Popup>
          </Marker>
        )}

        {/* Depot */}
        {depot?.lat != null && depot?.lng != null && (
          <Marker position={[depot.lat, depot.lng]} icon={createDepotIcon()}>
            <Popup>
              <div style={{ fontWeight: 700, fontSize: 13, color: '#111827' }}>{depot.name ?? 'Dépôt'}</div>
              <div style={{ fontSize: 11, color: '#6b7280' }}>Point de départ</div>
            </Popup>
          </Marker>
        )}

        {/* PICKUP stops — cyan depot markers for secondary depots */}
        {stops
          .filter((s) => s.stopType === 'PICKUP' && s.sourceDepotLat != null && s.sourceDepotLng != null)
          .map((stop) => (
            <Marker
              key={stop.id}
              position={[stop.sourceDepotLat!, stop.sourceDepotLng!]}
              icon={createPickupDepotIcon()}
              eventHandlers={{
                click() {
                  setFlyTarget([stop.sourceDepotLat!, stop.sourceDepotLng!]);
                  onStopClick?.(stop.id);
                },
              }}
            >
              <Popup>
                <div style={{ minWidth: 180 }}>
                  <div style={{ fontWeight: 800, fontSize: 13, color: '#0891B2' }}>
                    #{stop.stopOrder} · {stop.sourceDepotName || 'Chargement'}
                  </div>
                  <div style={{ fontSize: 11, color: '#374151', marginTop: 4, borderTop: '1px solid #F4F4F5', paddingTop: 4 }}>
                    Point de chargement
                  </div>
                  <div style={{ fontSize: 10, color: '#6b7280', marginTop: 2 }}>{stop.status}</div>
                </div>
              </Popup>
            </Marker>
          ))}

        {/* Route geometry */}
        {routeGeometryPaths.map((path, i) => (
          <Polyline key={`geo-${i}`} positions={path} pathOptions={{ color: '#2563eb', weight: 4, opacity: 0.85 }} />
        ))}
        {fallbackPath.length >= 2 && (
          <Polyline positions={fallbackPath} pathOptions={{ color: '#2563eb', weight: 3, opacity: 0.5, dashArray: '8 6' }} />
        )}

        {/* Driver */}
        {driver?.lat != null && driver?.lng != null && (
          <Marker position={[driver.lat, driver.lng]} icon={createDriverIcon()}>
            <Popup>
              <div style={{ fontWeight: 700, fontSize: 13 }}>{driver.name}</div>
              <div style={{ fontSize: 11, color: '#6b7280' }}>
                {driver.lat.toFixed(5)}, {driver.lng.toFixed(5)}
              </div>
              <div style={{ fontSize: 11, marginTop: 3, fontWeight: 600, color: isGpsStaleIso(driver.lastLocationAt) ? '#C7372F' : '#16a34a' }}>
                {lastSeenFr(driver.lastLocationAt)}
              </div>
            </Popup>
          </Marker>
        )}

        {/* Stop pins — click zooms in */}
        {useMemo(() => {
          return pinnedStops.map((stop) => {
            const clientName = stop.clientName ?? stop.order?.clientName ?? '';
            const erpId = stop.order?.erpOrderId ?? '';
            return (
              <Marker
                key={stop.id}
                position={[stop.dropoffLat!, stop.dropoffLng!]}
                icon={createStopIcon(String(stop.stopOrder), stop.status)}
                eventHandlers={{
                  click() {
                    setFlyTarget([stop.dropoffLat!, stop.dropoffLng!]);
                    onStopClick?.(stop.id);
                  },
                }}
              >
                <Popup>
                  <div style={{ minWidth: 180 }}>
                    <div style={{ fontWeight: 800, fontSize: 13, color: '#09090B' }}>
                      #{stop.stopOrder} {clientName && `· ${clientName}`}
                    </div>
                    {erpId && (
                      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand)', fontFamily: 'monospace', marginTop: 2 }}>
                        {erpId}
                      </div>
                    )}
                    <div style={{ fontSize: 11, color: '#374151', marginTop: 4, borderTop: '1px solid #F4F4F5', paddingTop: 4 }}>
                      {stop.deliveryAddress ?? stop.deliveryCity ?? '—'}
                    </div>
                    <div style={{ fontSize: 10, color: '#6b7280', marginTop: 2 }}>{stop.status}</div>
                  </div>
                </Popup>
              </Marker>
            );
          });
        }, [pinnedStops, onStopClick])}
      </MapContainer>
    </div>
  );
}

export default function RouteTrackingMap(props: Props) {
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[300px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-[2px] p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">Suivi Cartographique Indisponible (Crash)</p>
        <p className="text-[10px] text-[var(--text-muted)] mb-4">Une exception s'est produite lors de l'affichage du suivi de tournée.</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-[10px] rounded-[2px] font-medium hover:opacity-90 transition">
          Actualiser la page
        </button>
      </div>
    }>
      <RouteTrackingMapInner {...props} />
    </ErrorBoundary>
  );
}

