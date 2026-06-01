
import { useEffect, useMemo } from 'react';
import { MapContainer, Marker, Popup, useMap, TileLayer } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { Depot } from '@/types';

type Props = {
  depots: Depot[];
  height?: number | string;
};

function makeDepotIcon(active: boolean) {
  const color = active ? '#0f766e' : '#94a3b8';
  return L.divIcon({
    className: '',
    iconSize: [28, 36],
    iconAnchor: [14, 34],
    popupAnchor: [0, -30],
    html: `
      <div style="position:relative;width:28px;height:36px;">
        <div style="position:absolute;left:50%;top:0;transform:translateX(-50%);width:24px;height:24px;border-radius:999px;background:${color};border:2px solid #ffffff;display:flex;align-items:center;justify-content:center;color:#ffffff;font-size:10px;font-weight:800;line-height:1;">D</div>
        <div style="position:absolute;left:50%;top:20px;transform:translateX(-50%);width:0;height:0;border-left:6px solid transparent;border-right:6px solid transparent;border-top:12px solid ${color};"></div>
      </div>
    `,
  });
}

function MapUpdater({ depots }: { depots: Depot[] }) {
  const map = useMap();
  
  useEffect(() => {
    if (depots.length === 0) return;
    
    const validDepots = depots.filter(d => typeof d.latitude === 'number' && typeof d.longitude === 'number');
    if (validDepots.length === 0) return;

    const bounds = L.latLngBounds(validDepots.map(d => [d.latitude, d.longitude]));
    map.fitBounds(bounds, { padding: [50, 50], maxZoom: 13 });
  }, [map, depots]);

  return null;
}

export default function DepotsOverviewMap({ depots, height = 400 }: Props) {
  const center: [number, number] = useMemo(() => {
    if (depots.length > 0) {
      const first = depots.find(d => typeof d.latitude === 'number' && typeof d.longitude === 'number');
      if (first) return [first.latitude, first.longitude];
    }
    return [36.8065, 10.1815]; // Tunis
  }, [depots]);

  return (
    <div style={{ height, width: '100%', borderRadius: 12, overflow: 'hidden', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)', position: 'relative', zIndex: 1 }}>
      <MapContainer center={center} zoom={11} style={{ height: '100%', width: '100%' }}>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <MapUpdater depots={depots} />
        {depots.map((depot) => {
          if (typeof depot.latitude !== 'number' || typeof depot.longitude !== 'number') return null;
          return (
            <Marker 
              key={depot.id} 
              position={[depot.latitude, depot.longitude]} 
              icon={makeDepotIcon(depot.isActive)}
            >
              <Popup>
                <div style={{ fontSize: 13, fontWeight: 700, color: '#111827' }}>{depot.name}</div>
                <div style={{ fontSize: 12, color: '#64748b', marginTop: 2 }}>{depot.address || 'Pas d\'adresse'}</div>
                <div style={{ fontSize: 11, fontWeight: 700, color: depot.isActive ? '#166534' : '#991b1b', marginTop: 4 }}>
                  {depot.isActive ? 'ACTIF' : 'INACTIF'}
                </div>
              </Popup>
            </Marker>
          );
        })}
      </MapContainer>
    </div>
  );
}

