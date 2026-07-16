import { useEffect } from 'react';
import { MapContainer, Marker, Popup, Polyline, useMap } from 'react-leaflet';
import { TileLayer } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ErrorBoundary from '@/components/ErrorBoundary';

interface TrackingData {
  dropoffLat?: number; dropoffLng?: number; dropoffAddress?: string; dropoffCity?: string
  depotLat?: number;   depotLng?: number;   depotName?: string
  driverLat?: number;  driverLng?: number;  driverName?: string
  routeGeometry?: string
  kind?: string  // FORWARD | RETURN_PICKUP — flips pickup/destination roles (ADR-033)
}

// Markers ported 1:1 from the admin route map (RouteTrackingMap) so the client sees the same visual
// language: an avatar puck for the driver, a dark warehouse puck for the depot, a red teardrop for the
// destination. A subtle live-pulse ring is layered under the driver only (it's the moving actor).

/** Driver puck — brand-ringed avatar (initials) with an online status dot + live pulse. */
function driverIcon(name?: string) {
  const size = 40, wrap = size + 8, dot = 12;
  const initials = (name || '').split(/\s+/).map(p => p[0]).filter(Boolean).join('').slice(0, 2).toUpperCase() || '—';
  return L.divIcon({
    className: '',
    iconSize: [wrap, wrap], iconAnchor: [wrap / 2, wrap / 2], popupAnchor: [0, -(size / 2) - 6],
    html: `<div style="position:relative;width:${wrap}px;height:${wrap}px;">
  <div style="position:absolute;top:4px;left:4px;width:${size}px;height:${size}px;border-radius:50%;background:var(--brand);opacity:0.18;animation:mPulse 1.6s ease-out infinite;"></div>
  <div style="position:absolute;top:4px;left:4px;width:${size}px;height:${size}px;border-radius:50%;background:var(--surface,#fff);border:2.5px solid var(--brand);box-shadow:0 0 0 2px color-mix(in srgb, var(--brand) 22%, transparent),0 2px 8px rgba(0,0,0,0.22);overflow:hidden;display:flex;align-items:center;justify-content:center;">
    <span style="font-family:var(--font-sans),system-ui;font-weight:700;font-size:${Math.round(size * 0.36)}px;line-height:1;color:var(--text-primary,#0f172a);">${initials}</span>
  </div>
  <span style="position:absolute;bottom:3px;right:3px;width:${dot}px;height:${dot}px;border-radius:50%;background:var(--success,#16a34a);border:2px solid var(--surface,#fff);"></span>
  <style>@keyframes mPulse{0%{transform:scale(1);opacity:0.22}100%{transform:scale(2.4);opacity:0}}</style>
</div>`,
  });
}

/** Depot puck — dark circle with a white warehouse glyph. */
function depotIcon() {
  return L.divIcon({
    className: '', iconSize: [42, 42], iconAnchor: [21, 21], popupAnchor: [0, -22],
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

/** Destination — red teardrop pin with a white center. */
function destinationIcon() {
  return L.divIcon({
    className: '', iconSize: [32, 44], iconAnchor: [16, 44], popupAnchor: [0, -42],
    html: `<div style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.4));">
  <svg width="32" height="44" viewBox="0 0 32 44" xmlns="http://www.w3.org/2000/svg">
    <path d="M16 0C7.163 0 0 7.163 0 16c0 6.193 3.56 11.563 8.765 14.257L16 44l7.235-13.743C28.44 27.563 32 22.193 32 16 32 7.163 24.837 0 16 0z" fill="#ef4444"/>
    <circle cx="16" cy="16" r="7" fill="white" fill-opacity="0.9"/>
  </svg>
</div>`,
  });
}

function FitBounds({ points }: { points: [number, number][] }) {
  const map = useMap();
  useEffect(() => {
    if (!points.length) return;
    try {
      const bounds = L.latLngBounds(points);
      if (bounds.isValid()) map.fitBounds(bounds, { paddingTopLeft: [48, 64], paddingBottomRight: [48, 48], maxZoom: 15 });
    } catch { /* ignore */ }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

function InvalidateOnMount() {
  const map = useMap();
  useEffect(() => { setTimeout(() => map.invalidateSize(), 100); }, [map]);
  return null;
}

function TrackingMapInner({ data }: { data: TrackingData }) {
  // Client-facing page: always light basemap, independent of the admin dashboard theme.
  const defaultCenter: [number, number] = [36.8065, 10.1815];

  const points: [number, number][] = [];
  if (data.driverLat  && data.driverLng)  points.push([data.driverLat,  data.driverLng]);
  if (data.dropoffLat && data.dropoffLng) points.push([data.dropoffLat, data.dropoffLng]);
  if (data.depotLat   && data.depotLng)   points.push([data.depotLat,   data.depotLng]);

  let polyline: [number, number][] = [];
  if (data.routeGeometry) {
    try { polyline = JSON.parse(data.routeGeometry) as [number, number][]; } catch { /* ignore */ }
  }

  const center = points[0] ?? defaultCenter;

  return (
    <MapContainer
      center={center}
      zoom={13}
      zoomControl={false}
      style={{ width: '100%', height: '100%', position: 'absolute', inset: 0 }}
    >
      <InvalidateOnMount />
      <FitBounds points={points} />

      <TileLayer
        url="https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"
        attribution="© OpenStreetMap © CARTO"
      />

      {polyline.length > 1 && (
        <Polyline positions={polyline} color="var(--brand, #2563eb)" weight={4} opacity={0.85} />
      )}

      {(() => { const isReturn = data.kind === 'RETURN_PICKUP'; return <>
      {data.depotLat && data.depotLng && (
        <Marker position={[data.depotLat, data.depotLng]} icon={depotIcon()}>
          <Popup><strong>{isReturn ? 'Destination (dépôt)' : (data.depotName ?? 'Dépôt')}</strong></Popup>
        </Marker>
      )}

      {data.dropoffLat && data.dropoffLng && (
        <Marker position={[data.dropoffLat, data.dropoffLng]} icon={destinationIcon()}>
          <Popup>
            <strong>{isReturn ? 'Point de collecte' : 'Destination'}</strong>
            {data.dropoffAddress && <><br />{data.dropoffAddress}</>}
          </Popup>
        </Marker>
      )}
      </>; })()}

      {data.driverLat && data.driverLng && (
        <Marker position={[data.driverLat, data.driverLng]} icon={driverIcon(data.driverName)}>
          <Popup><strong>{data.driverName ?? 'Livreur'}</strong><br />Position actuelle</Popup>
        </Marker>
      )}
    </MapContainer>
  );
}

export default function TrackingMap(props: { data: TrackingData }) {
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[350px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-xs p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">Suivi Temps Réel Indisponible</p>
        <p className="text-2xs text-[var(--text-muted)] mb-4">Le service de cartographie n'a pas pu s'initialiser correctement.</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-2xs rounded-xs font-medium hover:opacity-90 transition">
          Actualiser la page
        </button>
      </div>
    }>
      <TrackingMapInner {...props} />
    </ErrorBoundary>
  );
}
