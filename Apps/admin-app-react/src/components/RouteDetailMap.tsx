
import { MapContainer, Marker, Polyline, Popup, TileLayer } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useIsDark } from '@/lib/theme';

type StopPoint = {
  id: string;
  stopOrder: number;
  clientName?: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  etaAt?: string;
  status?: string;
  lat?: number;
  lng?: number;
};

type RouteDetailMapProps = {
  stops: StopPoint[];
  geometry: Array<[number, number]>;
  driverLocation?: { lat: number; lng: number; updatedAt?: string } | null;
};

const CDN = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images';
L.Icon.Default.mergeOptions({
  iconUrl: `${CDN}/marker-icon.png`,
  iconRetinaUrl: `${CDN}/marker-icon-2x.png`,
  shadowUrl: `${CDN}/marker-shadow.png`,
});

function makeStopIcon(order: number) {
  const label = String(order);
  const fs = label.length > 2 ? 8 : label.length > 1 ? 10 : 12;
  return L.divIcon({
    className: '',
    iconSize: [28, 36],
    iconAnchor: [14, 36],
    popupAnchor: [0, -38],
    html: `<div style="width:28px;height:36px;transform-origin:50% 100%;filter:drop-shadow(0 2px 5px rgba(0,0,0,0.32));">
  <svg width="28" height="36" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
    <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" fill="#111827"/>
    <text x="14" y="15" text-anchor="middle" dominant-baseline="middle" fill="white" font-size="${fs}" font-weight="800" font-family="system-ui,sans-serif" letter-spacing="-0.3">${label}</text>
  </svg>
</div>`,
  });
}

function makeDriverIcon() {
  return L.divIcon({
    className: '',
    iconSize: [36, 36],
    iconAnchor: [18, 18],
    popupAnchor: [0, -20],
    html: `<div style="width:36px;height:36px;filter:drop-shadow(0 2px 5px rgba(0,0,0,0.32));">
  <svg width="36" height="36" viewBox="0 -2 20 20" xmlns="http://www.w3.org/2000/svg" fill="#000000">
    <g stroke-width="0"/>
    <g stroke-linecap="round" stroke-linejoin="round"/>
    <g>
      <g transform="translate(-2 -4)">
        <path fill="#F08734" d="M20.24,10.81,19,10.5l-.79-2.77a1,1,0,0,0-1-.73H13V17h2a2,2,0,0,1,4,0h1a1,1,0,0,0,1-1V11.78A1,1,0,0,0,20.24,10.81Z"/>
        <path d="M9.17,17H13V6a1,1,0,0,0-1-1H5" fill="none" stroke="#000000" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        <path d="M3,13v3a1,1,0,0,0,1,1h.87" fill="none" stroke="#000000" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        <path d="M14.87,17H13V7h4.25a1,1,0,0,1,1,.73L19,10.5l1.24.31a1,1,0,0,1,.76,1V16a1,1,0,0,1-1,1h-.89" fill="none" stroke="#000000" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        <path d="M9,17a2,2,0,1,1-2-2A2,2,0,0,1,9,17Zm8-2a2,2,0,1,0,2,2A2,2,0,0,0,17,15ZM3,9H9" fill="none" stroke="#000000" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
      </g>
    </g>
  </svg>
</div>`,
  });
}

export default function RouteDetailMap({ stops, geometry, driverLocation }: RouteDetailMapProps) {
  const isDark = useIsDark();
  const stopPoints = stops.filter((s) => typeof s.lat === 'number' && typeof s.lng === 'number');

  const center: [number, number] =
    geometry[0] ??
    (stopPoints[0] ? [stopPoints[0].lat as number, stopPoints[0].lng as number] : [36.8065, 10.1815]);

  return (
    <div style={{ height: 360, border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden' }}>
      <MapContainer center={center} zoom={12} style={{ height: '100%', width: '100%' }}>
        <TileLayer
          attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>'}
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
        />

        {geometry.length > 1 && <Polyline positions={geometry} pathOptions={{ color: '#111827', weight: 4 }} />}

        {stopPoints.map((stop) => (
          <Marker
            key={stop.id}
            position={[stop.lat as number, stop.lng as number]}
            icon={makeStopIcon(stop.stopOrder)}
          >
            <Popup>
              <div style={{ minWidth: 180 }}>
                <div style={{ fontWeight: 700 }}>Stop #{stop.stopOrder}</div>
                <div>{stop.clientName || 'Client'}</div>
                <div style={{ color: '#4b5563' }}>{stop.deliveryAddress || '-'}</div>
                <div style={{ color: '#4b5563' }}>{stop.deliveryCity || '-'}</div>
                <div style={{ color: '#4b5563' }}>Status: {stop.status || '-'}</div>
              </div>
            </Popup>
          </Marker>
        ))}

        {driverLocation && (
          <Marker position={[driverLocation.lat, driverLocation.lng]} icon={makeDriverIcon()}>
            <Popup>
              <div>
                <div style={{ fontWeight: 700 }}>Driver live location</div>
                <div style={{ color: '#4b5563' }}>{driverLocation.updatedAt || ''}</div>
              </div>
            </Popup>
          </Marker>
        )}
      </MapContainer>
    </div>
  );
}

