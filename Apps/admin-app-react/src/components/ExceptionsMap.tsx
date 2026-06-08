
import { useEffect, useRef, useState, useMemo } from 'react';
import { MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ErrorBoundary from '@/components/ErrorBoundary';
import { useIsDark } from '@/lib/theme';

export type MapException = {
  deliveryId: string;
  clientName?: string;
  city?: string;
  motif: string;
  severity: string;
  status: string;
  driverName?: string;
  dropoffLat?: number;
  dropoffLng?: number;
};

export type MapDriver = {
  id: string;
  name: string;
  currentLat?: number;
  currentLng?: number;
};

interface Props {
  exceptions: MapException[];
  drivers: MapDriver[];
  selectedId?: string | null;
  onSelect?: (deliveryId: string) => void;
}

// ── Icon factories ─────────────────────────────────────────────────────────────

const SEVERITY_COLORS: Record<string, string> = {
  CRITICAL: '#EF4444',
  HIGH:     '#F97316',
  MEDIUM:   '#EAB308',
  LOW:      '#71717A',
};

function makeExceptionIcon(severity: string, selected: boolean) {
  const color = SEVERITY_COLORS[severity] ?? '#71717A';
  const size = selected ? 20 : 14;
  const ring = selected ? `box-shadow:0 0 0 3px ${color}40;` : '';
  return L.divIcon({
    className: '',
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
    html: `<div style="
      width:${size}px;height:${size}px;border-radius:50%;
      background:${color};border:2px solid white;
      ${ring}
      box-sizing:border-box;
    "></div>`,
  });
}

function makeDriverIcon(name: string) {
  const initials = name.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase();
  return L.divIcon({
    className: '',
    iconSize: [28, 28],
    iconAnchor: [14, 14],
    html: `<div style="
      width:28px;height:28px;border-radius:50%;
      background:#09090B;border:2px solid white;
      display:flex;align-items:center;justify-content:center;
      font-size:9px;font-weight:800;color:white;font-family:inherit;
    ">${initials}</div>`,
  });
}

// ── Auto-fit bounds ────────────────────────────────────────────────────────────

function BoundsController({ exceptions, drivers }: { exceptions: MapException[]; drivers: MapDriver[] }) {
  const map = useMap();
  const fitted = useRef(false);

  useEffect(() => {
    const points: [number, number][] = [
      ...exceptions.filter(e => e.dropoffLat && e.dropoffLng).map(e => [e.dropoffLat!, e.dropoffLng!] as [number, number]),
      ...drivers.filter(d => d.currentLat && d.currentLng).map(d => [d.currentLat!, d.currentLng!] as [number, number]),
    ];
    if (points.length === 0) return;
    const bounds = L.latLngBounds(points);
    map.fitBounds(bounds, { padding: [32, 32], maxZoom: 13 });
    fitted.current = true;
  }, [map, exceptions, drivers]);

  return null;
}

// ── Main component ─────────────────────────────────────────────────────────────

function ExceptionsMapInner({ exceptions, drivers, selectedId, onSelect }: Props) {
  const [mounted, setMounted] = useState(false);
  const isDark = useIsDark();
  useEffect(() => {
    setMounted(true);
  }, []);

  if (!mounted) {
    return (
      <div style={{ width: '100%', height: '100%', background: '#F4F4F5', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ fontSize: 11, color: '#A1A1AA' }}>Chargement de la carte…</span>
      </div>
    );
  }

  const withCoords = exceptions.filter(e => e.dropoffLat && e.dropoffLng);
  const activeDrivers = drivers.filter(d => d.currentLat && d.currentLng);

  // Default center: Tunis
  const center: [number, number] = [36.8065, 10.1815];

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative', zIndex: 0, isolation: 'isolate' }}>
      <MapContainer
        center={center}
        zoom={11}
        style={{ width: '100%', height: '100%', borderRadius: 2 }}
        zoomControl={true}
      >
        <TileLayer
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'}
          attribution='&copy; <a href="https://carto.com">CARTO</a> &copy; <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>'
        />

        <BoundsController exceptions={withCoords} drivers={activeDrivers} />

        {useMemo(() => {
          return withCoords.map(ex => (
            <Marker
              key={ex.deliveryId}
              position={[ex.dropoffLat!, ex.dropoffLng!]}
              icon={makeExceptionIcon(ex.severity, ex.deliveryId === selectedId)}
              eventHandlers={{ click: () => onSelect?.(ex.deliveryId) }}
            >
              <Popup>
                <div style={{ fontFamily: 'inherit', minWidth: 160 }}>
                  <div style={{ fontSize: 10, fontWeight: 800, color: SEVERITY_COLORS[ex.severity] ?? '#71717A', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 4 }}>
                    {ex.severity} · {ex.status}
                  </div>
                  <div style={{ fontSize: 12, fontWeight: 700, color: '#09090B', marginBottom: 2 }}>{ex.clientName ?? 'Client'}</div>
                  <div style={{ fontSize: 10, color: '#71717A', marginBottom: 4 }}>{ex.city}</div>
                  <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--brand)', background: '#FFF5F3', padding: '2px 6px', borderRadius: 2, display: 'inline-block' }}>
                    {ex.motif}
                  </div>
                  {ex.driverName && (
                    <div style={{ fontSize: 10, color: '#A1A1AA', marginTop: 4 }}>Chauffeur: {ex.driverName}</div>
                  )}
                </div>
              </Popup>
            </Marker>
          ));
        }, [withCoords, selectedId, onSelect])}

        {useMemo(() => {
          return activeDrivers.map(driver => (
            <Marker
              key={driver.id}
              position={[driver.currentLat!, driver.currentLng!]}
              icon={makeDriverIcon(driver.name)}
            >
              <Popup>
                <div style={{ fontFamily: 'inherit' }}>
                  <div style={{ fontSize: 11, fontWeight: 700, color: '#09090B' }}>{driver.name}</div>
                  <div style={{ fontSize: 9, color: '#71717A', marginTop: 2 }}>Position en temps réel</div>
                </div>
              </Popup>
            </Marker>
          ));
        }, [activeDrivers])}
      </MapContainer>
    </div>
  );
}

export default function ExceptionsMap(props: Props) {
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[300px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-[2px] p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">Carte d'Exceptions Indisponible</p>
        <p className="text-[10px] text-[var(--text-muted)] mb-4">Le service de suivi cartographique des anomalies a rencontré une exception.</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-[10px] rounded-[2px] font-medium hover:opacity-90 transition">
          Actualiser la page
        </button>
      </div>
    }>
      <ExceptionsMapInner {...props} />
    </ErrorBoundary>
  );
}

