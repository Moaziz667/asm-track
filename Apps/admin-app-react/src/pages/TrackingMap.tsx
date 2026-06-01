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
}

const SVG_TRUCK = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M1 3h15v13H1z"/><path d="M16 8h4l3 3v5h-7V8z"/><circle cx="5.5" cy="18.5" r="2.5"/><circle cx="18.5" cy="18.5" r="2.5"/></svg>`;
const SVG_PIN   = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="#fff" stroke="#fff" stroke-width="1"><path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/></svg>`;
const SVG_DEPOT = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>`;

function makeIcon(color: string, svg: string, pulse = false) {
  return L.divIcon({
    className: '',
    iconSize: [36, 36],
    iconAnchor: [18, 36],
    popupAnchor: [0, -40],
    html: `
      ${pulse ? `<div style="position:absolute;width:36px;height:36px;border-radius:50%;background:${color};opacity:0.2;animation:mPulse 1.6s ease-out infinite;"></div>` : ''}
      <div style="width:36px;height:36px;background:${color};border-radius:50% 50% 50% 0;transform:rotate(-45deg);box-shadow:0 2px 8px rgba(0,0,0,0.22);border:2.5px solid #fff;display:flex;align-items:center;justify-content:center;position:relative;">
        <span style="transform:rotate(45deg);display:flex;align-items:center;justify-content:center;">${svg}</span>
      </div>
      <style>@keyframes mPulse{0%{transform:scale(1);opacity:0.25}100%{transform:scale(2.6);opacity:0}}</style>
    `,
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
        url="https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png"
        attribution="© OpenStreetMap © CARTO"
      />

      {polyline.length > 1 && (
        <Polyline positions={polyline} color="#f97316" weight={4} opacity={0.85} />
      )}

      {data.depotLat && data.depotLng && (
        <Marker position={[data.depotLat, data.depotLng]} icon={makeIcon('#334155', SVG_DEPOT)}>
          <Popup><strong>{data.depotName ?? 'Dépôt'}</strong></Popup>
        </Marker>
      )}

      {data.dropoffLat && data.dropoffLng && (
        <Marker position={[data.dropoffLat, data.dropoffLng]} icon={makeIcon('#16a34a', SVG_PIN)}>
          <Popup>
            <strong>Destination</strong>
            {data.dropoffAddress && <><br />{data.dropoffAddress}</>}
          </Popup>
        </Marker>
      )}

      {data.driverLat && data.driverLng && (
        <Marker position={[data.driverLat, data.driverLng]} icon={makeIcon('#f97316', SVG_TRUCK, true)}>
          <Popup><strong>{data.driverName ?? 'Livreur'}</strong><br />Position actuelle</Popup>
        </Marker>
      )}
    </MapContainer>
  );
}

export default function TrackingMap(props: { data: TrackingData }) {
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[350px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-[2px] p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">Suivi Temps Réel Indisponible</p>
        <p className="text-[10px] text-[var(--text-muted)] mb-4">Le service de cartographie n'a pas pu s'initialiser correctement.</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-[10px] rounded-[2px] font-medium hover:opacity-90 transition">
          Actualiser la page
        </button>
      </div>
    }>
      <TrackingMapInner {...props} />
    </ErrorBoundary>
  );
}
