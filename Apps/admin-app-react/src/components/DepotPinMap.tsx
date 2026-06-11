
import { useEffect } from 'react';
import { MapContainer, TileLayer, Marker, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useIsDark } from '@/lib/theme';

type DepotPinMapProps = {
  lat?: number;
  lng?: number;
  onPick?: (lat: number, lng: number) => void;
  height?: number;
};

// Fix for default marker icons in Leaflet with Next.js
const CDN = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images';
const defaultIcon = L.icon({
  iconUrl: `${CDN}/marker-icon.png`,
  iconRetinaUrl: `${CDN}/marker-icon-2x.png`,
  shadowUrl: `${CDN}/marker-shadow.png`,
  iconSize: [25, 41],
  iconAnchor: [12, 41],
});

function ClickHandler({ onPick }: { onPick?: (lat: number, lng: number) => void }) {
  useMapEvents({
    click(e) {
      if (onPick) {
        onPick(Number(e.latlng.lat.toFixed(7)), Number(e.latlng.lng.toFixed(7)));
      }
    },
  });
  return null;
}

function MapUpdater({ lat, lng }: { lat?: number; lng?: number }) {
  const map = useMap();
  useEffect(() => {
    if (lat != null && lng != null) {
      map.setView([lat, lng], map.getZoom());
    }
  }, [lat, lng, map]);
  return null;
}

export default function DepotPinMap({ lat, lng, onPick, height = 300 }: DepotPinMapProps) {
  const center: [number, number] = lat != null && lng != null ? [lat, lng] : [36.8065, 10.1815]; // Default to Tunis
  const isDark = useIsDark();

  return (
    <div style={{ height, width: '100%', position: 'relative', zIndex: 1 }}>
      <MapContainer 
        center={center} 
        zoom={13} 
        style={{ height: '100%', width: '100%' }}
      >
        <TileLayer
          attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
        />
        <ClickHandler onPick={onPick} />
        <MapUpdater lat={lat} lng={lng} />
        
        {lat != null && lng != null && (
          <Marker position={[lat, lng]} icon={defaultIcon} />
        )}
      </MapContainer>
      
      {/* Overlay instruction */}
      <div className="absolute top-3 right-3 z-[1000] px-3 py-1.5 bg-[var(--surface)]/90 backdrop-blur-sm border border-[var(--border)] rounded-lg shadow-sm pointer-events-none">
        <span className="text-2xs font-bold uppercase tracking-widest text-[var(--text-primary)]">
          Cliquez pour positionner
        </span>
      </div>
    </div>
  );
}

