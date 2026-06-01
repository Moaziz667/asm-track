
import { MapContainer, Marker, Popup, Polyline, TileLayer, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

type MapStop = {
  deliveryId: string;
  stopOrder: number;
  clientName?: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  routeGeometry?: string;
};

type Props = {
  stops: MapStop[];
  activeStopDeliveryId: string | null;
  onMapPick: (lat: number, lng: number) => void;
  depotLat?: number;
  depotLng?: number;
  fullRouteGeometry?: string;
  height?: number;
};

const CDN = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images';
L.Icon.Default.mergeOptions({
  iconUrl: `${CDN}/marker-icon.png`,
  iconRetinaUrl: `${CDN}/marker-icon-2x.png`,
  shadowUrl: `${CDN}/marker-shadow.png`,
});

function depotIcon() {
  return L.divIcon({
    className: '',
    iconSize: [28, 28],
    iconAnchor: [14, 14],
    popupAnchor: [0, -15],
    html: `<div style="width:28px;height:28px;border-radius:50%;background:#4b5563;color:#fff;display:flex;align-items:center;justify-content:center;border:2px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.25);">🏭</div>`,
  });
}

function stopIcon(active: boolean, pinned: boolean, stopOrder: number) {
  const bg = active ? '#2563eb' : pinned ? '#16a34a' : '#f59e0b';
  return L.divIcon({
    className: '',
    iconSize: [28, 28],
    iconAnchor: [14, 14],
    popupAnchor: [0, -16],
    html: `<div style="width:28px;height:28px;border-radius:50%;background:${bg};color:#fff;display:flex;align-items:center;justify-content:center;border:2px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.25);font-size:11px;font-weight:700;">${stopOrder}</div>`,
  });
}

function ClickHandler({ onMapPick }: { onMapPick: (lat: number, lng: number) => void }) {
  useMapEvents({
    click(event) {
      onMapPick(Number(event.latlng.lat.toFixed(7)), Number(event.latlng.lng.toFixed(7)));
    },
  });
  return null;
}

export default function RouteStopPinMap({ stops, activeStopDeliveryId, onMapPick, depotLat, depotLng, fullRouteGeometry, height = 280 }: Props) {
  const pinned = stops.filter((s) => s.dropoffLat != null && s.dropoffLng != null);
  const activeStop = stops.find((s) => s.deliveryId === activeStopDeliveryId) ?? null;

  const activeRoutePath: [number, number][] = (() => {
    const geometry = activeStop?.routeGeometry || fullRouteGeometry;
    if (!geometry) return [];
    try {
      const parsed = JSON.parse(geometry);
      if (!Array.isArray(parsed)) return [];
      return parsed
        .filter((point: unknown) => Array.isArray(point) && point.length >= 2)
        .map((point: unknown) => {
          const lat = Number((point as [unknown, unknown])[0]);
          const lng = Number((point as [unknown, unknown])[1]);
          return [lat, lng] as [number, number];
        })
        .filter((point: [number, number]) => Number.isFinite(point[0]) && Number.isFinite(point[1]));
    } catch {
      return [];
    }
  })();

  const center: [number, number] = activeStop?.dropoffLat != null && activeStop?.dropoffLng != null
    ? [activeStop.dropoffLat, activeStop.dropoffLng]
    : depotLat != null && depotLng != null
      ? [depotLat, depotLng]
      : pinned.length > 0
        ? [pinned[0].dropoffLat!, pinned[0].dropoffLng!]
        : [36.8065, 10.1815];

  return (
    <div style={{ border: '1px solid #e5e7eb', borderRadius: 8, overflow: 'hidden', background: '#fff' }}>
      <MapContainer center={center} zoom={11} style={{ width: '100%', height }}>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <ClickHandler onMapPick={onMapPick} />

        {stops
          .filter((s) => s.dropoffLat != null && s.dropoffLng != null)
          .map((stop) => {
            const isActive = stop.deliveryId === activeStopDeliveryId;
            const isPinned = stop.dropoffPinned ?? false;
            return (
              <Marker
                key={`${stop.deliveryId}-${stop.stopOrder}`}
                position={[stop.dropoffLat!, stop.dropoffLng!]}
                icon={stopIcon(isActive, isPinned, stop.stopOrder)}
              >
                <Popup>
                  <div style={{ minWidth: 180 }}>
                    <div style={{ fontWeight: 700, fontSize: 12 }}>Stop #{stop.stopOrder}</div>
                    <div style={{ fontSize: 12, color: '#374151' }}>{stop.clientName ?? 'Client'}</div>
                    <div style={{ fontSize: 11, color: '#6b7280' }}>
                      {stop.deliveryAddress ?? 'Address not available'}{stop.deliveryCity ? `, ${stop.deliveryCity}` : ''}
                    </div>
                    <div style={{ fontSize: 11, color: '#6b7280', marginTop: 4 }}>
                      {stop.dropoffLat?.toFixed(5)}, {stop.dropoffLng?.toFixed(5)}
                    </div>
                  </div>
                </Popup>
              </Marker>
            );
          })}

        {activeRoutePath.length > 1 && (
          <Polyline
            positions={activeRoutePath}
            pathOptions={{ color: '#2563eb', weight: 5, opacity: 0.8 }}
          />
        )}
      </MapContainer>
    </div>
  );
}

