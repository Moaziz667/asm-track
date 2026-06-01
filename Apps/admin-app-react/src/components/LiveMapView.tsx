import { useEffect } from 'react';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Driver, Delivery } from '@/types';
import { IconPhone as Phone, IconPackage as Package } from '@tabler/icons-react';

/* Fix default marker icons for webpack/next bundling */
const CDN = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images';
L.Icon.Default.mergeOptions({
  iconUrl: `${CDN}/marker-icon.png`,
  iconRetinaUrl: `${CDN}/marker-icon-2x.png`,
  shadowUrl: `${CDN}/marker-shadow.png`,
});

const DRIVER_COLORS = ['#2563eb', '#16a34a', '#ea580c', '#7c3aed', '#db2777', '#0f766e', '#ca8a04', '#dc2626'];

function colorForDriver(driverId?: string): string {
  if (!driverId) return '#2563eb';
  let hash = 0;
  for (let i = 0; i < driverId.length; i += 1) {
    hash = ((hash << 5) - hash + driverId.charCodeAt(i)) | 0;
  }
  return DRIVER_COLORS[Math.abs(hash) % DRIVER_COLORS.length];
}

/* Custom div-based markers */
function driverIcon(color: string, pulse: boolean) {
  return L.divIcon({
    className: '',
    iconSize: [34, 34],
    iconAnchor: [17, 17],
    popupAnchor: [0, -20],
    html: `
      <div style="
        width:34px; height:34px; border-radius:50%;
        background:${color}; color:#fff;
        display:flex; align-items:center; justify-content:center;
        border:3px solid #fff;
        box-shadow:0 2px 8px rgba(0,0,0,0.2);
        ${pulse ? 'animation:live-pulse 1.8s ease-in-out infinite;' : ''}
      ">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
          <path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"/>
        </svg>
      </div>
    `,
  });
}

function customerIcon(pulse: boolean) {
  return L.divIcon({
    className: '',
    iconSize: [34, 34],
    iconAnchor: [17, 17],
    popupAnchor: [0, -20],
    html: `
      <div style="
        width:34px; height:34px; border-radius:50%;
        background:#2563eb; color:#fff;
        display:flex; align-items:center; justify-content:center;
        border:3px solid #fff;
        box-shadow:0 2px 8px rgba(0,0,0,0.3);
        ${pulse ? 'animation:live-pulse 1.8s ease-in-out infinite;' : ''}
      ">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
        </svg>
      </div>
    `,
  });
}

function waitingIcon() {
  return L.divIcon({
    className: '',
    iconSize: [30, 30],
    iconAnchor: [15, 15],
    popupAnchor: [0, -18],
    html: `
      <div style="
        width:30px; height:30px; border-radius:50%;
        background:#f59e0b; color:#fff;
        display:flex; align-items:center; justify-content:center;
        border:3px solid #fff;
        box-shadow:0 2px 8px rgba(0,0,0,0.2);
      ">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12.89 1.45l8 4A2 2 0 0 1 22 7.24v9.53a2 2 0 0 1-1.11 1.79l-8 4a2 2 0 0 1-1.79 0l-8-4a2 2 0 0 1-1.1-1.8V7.24a2 2 0 0 1 1.11-1.79l8-4a2 2 0 0 1 1.78 0z"/>
        </svg>
      </div>
    `,
  });
}

function stopIcon(color: string, stopOrder: number) {
  return L.divIcon({
    className: '',
    iconSize: [30, 30],
    iconAnchor: [15, 15],
    popupAnchor: [0, -18],
    html: `
      <div style="
        width:30px; height:30px; border-radius:50%;
        background:${color}; color:#fff;
        display:flex; align-items:center; justify-content:center;
        border:2px solid #fff;
        box-shadow:0 2px 8px rgba(0,0,0,0.2);
        font-size:11px; font-weight:700;
      ">
        ${stopOrder}
      </div>
    `,
  });
}

/* Auto-fit bounds */
function FitBounds({ positions }: { positions: [number, number][] }) {
  const map = useMap();
  useEffect(() => {
    if (positions.length === 0) return;
    const bounds = L.latLngBounds(positions.map(([lat, lng]) => [lat, lng]));
    map.fitBounds(bounds, { padding: [40, 40], maxZoom: 13 });
  }, [positions, map]);
  return null;
}

interface LiveMapViewProps {
  drivers: Driver[];
  waitingDeliveries: Delivery[];
  allDeliveries?: Delivery[];
  routeStops?: Array<{
    key: string;
    routeId: string;
    routeName: string;
    stopId: string;
    deliveryId: string;
    stopOrder: number;
    status: string;
    lat: number;
    lng: number;
    driverId?: string;
    driverName?: string;
    clientName?: string;
    deliveryAddress?: string;
    deliveryCity?: string;
  }>;
  center?: [number, number];
}

export default function LiveMapView({
  drivers,
  allDeliveries = [],
  routeStops = [],
  center = [33.5731, -7.5898], // Casablanca default
}: LiveMapViewProps) {
  const driverPositions: [number, number][] = drivers
    .filter((d) => (d.location?.lat && d.location?.lng) || (d.currentLat && d.currentLng))
    .map((d) => [d.location?.lat ?? d.currentLat!, d.location?.lng ?? d.currentLng!]);

  const customerPositions: [number, number][] = allDeliveries
    .filter((del) => del.dropoffLat && del.dropoffLng)
    .map((del) => [del.dropoffLat!, del.dropoffLng!]);

  const routeStopPositions: [number, number][] = routeStops.map((s) => [s.lat, s.lng]);

  const allPositions = [...driverPositions, ...customerPositions, ...routeStopPositions];

  const driverKey = (drv: Driver, lat: number, lng: number, index: number) =>
    `${drv.id ?? 'driver'}-${lat.toFixed(6)}-${lng.toFixed(6)}-${index}`;

  const deliveryKey = (del: Delivery, index: number) =>
    `${del.id ?? 'delivery'}-${del.dropoffLat?.toFixed(6) ?? 'na'}-${del.dropoffLng?.toFixed(6) ?? 'na'}-${index}`;

  const driverLocationById = new Map<string, [number, number]>();
  drivers.forEach((driver) => {
    const lat = driver.location?.lat ?? driver.currentLat;
    const lng = driver.location?.lng ?? driver.currentLng;
    if (driver.id && lat != null && lng != null) {
      driverLocationById.set(driver.id, [lat, lng]);
    }
  });

  const stopsByDriver = new Map<string, typeof routeStops>();
  routeStops.forEach((stop) => {
    if (!stop.driverId) return;
    const bucket = stopsByDriver.get(stop.driverId) ?? [];
    bucket.push(stop);
    stopsByDriver.set(stop.driverId, bucket);
  });

  return (
    <MapContainer
      center={center}
      zoom={12}
      style={{ height: '100%', width: '100%' }}
      zoomControl={false}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />

      {allPositions.length > 0 && <FitBounds positions={allPositions} />}

      {[...stopsByDriver.entries()].map(([driverId, stops]) => {
        const driverPosition = driverLocationById.get(driverId);
        if (!driverPosition) return null;
        const sortedStops = [...stops].sort((a, b) => a.stopOrder - b.stopOrder);
        const points: [number, number][] = [driverPosition, ...sortedStops.map((s) => [s.lat, s.lng] as [number, number])];
        return (
          <Polyline
            key={`route-line-${driverId}`}
            positions={points}
            pathOptions={{ color: colorForDriver(driverId), weight: 4, opacity: 0.85 }}
          />
        );
      })}

      {/* Driver markers */}
      {drivers
        .filter((d) => (d.location?.lat && d.location?.lng) || (d.currentLat && d.currentLng))
        .map((drv, index) => {
          const inTransit = !!drv.activeDeliveryId;
          const lat = drv.location?.lat ?? drv.currentLat!;
          const lng = drv.location?.lng ?? drv.currentLng!;
          const color = colorForDriver(drv.id);
          return (
            <Marker
              key={driverKey(drv, lat, lng, index)}
              position={[lat, lng]}
              icon={driverIcon(color, inTransit)}
              zIndexOffset={1000}
            >
              <Popup>
                <div style={{ padding: '10px 12px', fontFamily: "'Plus Jakarta Sans', sans-serif", minWidth: 180 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: '#111827', marginBottom: 4 }}>{drv.name}</div>
                  <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 4 }}>
                    <Phone size={11} /> {drv.phone}
                  </div>
                  <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 6 }}>
                    Route color: <span style={{ color, fontWeight: 700 }}>{color}</span>
                  </div>
                  {drv.activeDeliveryId && (
                    <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 6 }}>
                      Delivery: <span style={{ fontFamily: "'JetBrains Mono', monospace", color: '#16a34a' }}>{drv.activeDeliveryId}</span>
                    </div>
                  )}
                  <span
                    style={{
                      padding: '3px 8px', borderRadius: 9999, fontSize: 11, fontWeight: 500,
                      background: inTransit ? '#fff7ed' : '#dcfce7',
                      color: inTransit ? '#9a3412' : '#166534',
                    }}
                  >
                    {inTransit ? 'In Transit' : 'Available'}
                  </span>
                </div>
              </Popup>
            </Marker>
          );
        })}

      {/* Customer / Delivery markers */}
      {(allDeliveries || [])
        .filter((del) => del.status !== 'CANCELLED' && del.status !== 'DELIVERED')
        .filter((del) => del.dropoffLat && del.dropoffLng)
        .map((del, index) => {
          const isPending = del.status === 'UNSCHEDULED';
          return (
            <Marker
              key={deliveryKey(del, index)}
              position={[del.dropoffLat!, del.dropoffLng!]}
              icon={customerIcon(isPending)}
              zIndexOffset={100}
            >
              <Popup>
                <div style={{ padding: '10px 12px', fontFamily: "'Plus Jakarta Sans', sans-serif", minWidth: 200 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: '#111827', marginBottom: 4 }}>{del.clientName}</div>
                  {del.clientPhone && (
                    <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 4 }}>
                      <Phone size={11} /> {del.clientPhone}
                    </div>
                  )}
                  <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 4 }}>
                    <Package size={11} /> {del.items?.length || 0} items
                  </div>
                  <div style={{ fontSize: 12, color: '#374151', marginBottom: 6, fontWeight: 500 }}>
                    Status: {del.status.replace('_', ' ')}
                  </div>
                  <div style={{ fontSize: 11, color: '#9ca3af', lineHeight: 1.4 }}>
                    {del.dropoffAddress}, {del.dropoffCity}
                  </div>
                </div>
              </Popup>
            </Marker>
          );
        })}

      {/* Route stop markers */}
      {routeStops.map((stop) => (
        <Marker
          key={stop.key}
          position={[stop.lat, stop.lng]}
          icon={stop.driverId ? stopIcon(colorForDriver(stop.driverId), stop.stopOrder) : waitingIcon()}
          zIndexOffset={500}
        >
          <Popup>
            <div style={{ padding: '10px 12px', fontFamily: "'Plus Jakarta Sans', sans-serif", minWidth: 210 }}>
              <div style={{ fontWeight: 700, fontSize: 13, color: '#111827', marginBottom: 4 }}>
                {stop.routeName} • Stop #{stop.stopOrder}
              </div>
              <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 4 }}>
                Delivery: <span style={{ fontFamily: "'JetBrains Mono', monospace" }}>{stop.deliveryId.slice(0, 8)}</span>
              </div>
              <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 4 }}>
                Client: {stop.clientName ?? 'N/A'}
              </div>
              <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 4 }}>
                Driver: {stop.driverName ?? 'N/A'}
              </div>
              <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 4 }}>
                {stop.deliveryAddress ?? 'Address not set'}{stop.deliveryCity ? `, ${stop.deliveryCity}` : ''}
              </div>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#92400e' }}>
                Stop status: {stop.status}
              </div>
            </div>
          </Popup>
        </Marker>
      ))}
    </MapContainer>
  );
}

