
import { useEffect, useRef, useState, useMemo } from 'react';
import { MapContainer, Marker, Popup, Polyline, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ErrorBoundary from '@/components/ErrorBoundary';
import { useIsDark } from '@/lib/theme';

export type ActiveStop = {
  deliveryId: string;
  status: string;
  clientName?: string;
  city?: string;
  driverId?: string;
  dropoffLat: number;
  dropoffLng: number;
  updatedAt?: string;
};

export type LiveDriver = {
  id: string;
  name: string;
  currentLat?: number | null;
  currentLng?: number | null;
  lastLocationAt?: string | null;
  onlineStatus?: string;
};

interface Props {
  activeStops: ActiveStop[];
  drivers: LiveDriver[];
  selectedStopId?: string | null;
  selectedDriverId?: string | null;
  selectedDriverRoute?: [number, number][];
  onStopClick?: (deliveryId: string) => void;
  onDriverClick?: (driverId: string) => void;
}

// ── Color maps ─────────────────────────────────────────────────────────────────

const STOP_COLOR: Record<string, string> = {
  SCHEDULED:           '#2563eb',
  PICKED_UP:           '#f97316',
  IN_TRANSIT:          '#f97316',
  DELIVERED:           '#16a34a',
  PARTIALLY_DELIVERED: '#7c3aed',
  FAILED:              '#dc2626',
  CANCELLED:           '#dc2626',
  UNSCHEDULED:         '#71717a',
};

const STATUS_RING: Record<string, string> = {
  ONLINE:   '#10B981',
  ON_BREAK: '#F59E0B',
  OFFLINE:  '#9CA3AF',
};

const STATUS_LABEL: Record<string, string> = {
  ONLINE:   'En service',
  ON_BREAK: 'En pause',
  OFFLINE:  'Hors ligne',
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
  if (status === 'FAILED') return '✗';
  return '●';
}

// ── Icon factories ─────────────────────────────────────────────────────────────

function makeStopIcon(status: string, selected: boolean) {
  const color = STOP_COLOR[status] ?? '#71717a';
  const symbol = stopSymbol(status);
  const shadow = selected
    ? 'drop-shadow(0 0 7px rgba(255,87,34,0.85)) drop-shadow(0 2px 8px rgba(0,0,0,0.4))'
    : 'drop-shadow(0 2px 5px rgba(0,0,0,0.28))';
  const scale = selected ? 'scale(1.25)' : 'scale(1)';
  return L.divIcon({
    className: '',
    iconSize: [28, 36],
    iconAnchor: [14, 36],
    popupAnchor: [0, -40],
    html: `<div style="width:28px;height:36px;transform:${scale};transform-origin:50% 100%;filter:${shadow};transition:transform 0.15s,filter 0.15s;">
  <svg width="28" height="36" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
    <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" fill="${color}"/>
    <text x="14" y="15" text-anchor="middle" dominant-baseline="middle" fill="white" font-size="10" font-weight="800" font-family="system-ui,sans-serif">${symbol}</text>
  </svg>
</div>`,
  });
}

function makeDriverIcon(name: string, onlineStatus: string, selected: boolean) {
  const ring = STATUS_RING[onlineStatus] ?? STATUS_RING.OFFLINE;
  const size = selected ? 40 : 34;
  const ringW = selected ? 4 : 3;
  const glow = selected ? `box-shadow:0 0 0 3px ${ring}55,0 0 12px ${ring}44;` : `box-shadow:0 0 0 2px ${ring}33;`;
  return L.divIcon({
    className: '',
    iconSize: [size + 10, size + 10],
    iconAnchor: [(size + 10) / 2, (size + 10) / 2],
    popupAnchor: [0, -(size / 2) - 8],
    html: `<div style="width:${size + 10}px;height:${size + 10}px;display:flex;align-items:center;justify-content:center;filter:drop-shadow(0 2px 6px rgba(0,0,0,0.35));">
  <div style="width:${size}px;height:${size}px;border-radius:50%;background:#111827;border:${ringW}px solid ${ring};${glow}display:flex;align-items:center;justify-content:center;transition:all 0.15s;">
    <svg width="${Math.round(size * 0.56)}" height="${Math.round(size * 0.56)}" viewBox="0 -2 20 20" xmlns="http://www.w3.org/2000/svg" fill="none">
      <g transform="translate(-2 -4)">
        <path fill="#F08734" d="M20.24,10.81,19,10.5l-.79-2.77a1,1,0,0,0-1-.73H13V17h2a2,2,0,0,1,4,0h1a1,1,0,0,0,1-1V11.78A1,1,0,0,0,20.24,10.81Z"/>
        <path d="M9.17,17H13V6a1,1,0,0,0-1-1H5" stroke="white" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        <path d="M3,13v3a1,1,0,0,0,1,1h.87" stroke="white" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        <path d="M14.87,17H13V7h4.25a1,1,0,0,1,1,.73L19,10.5l1.24.31a1,1,0,0,1,.76,1V16a1,1,0,0,1-1,1h-.89" stroke="white" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        <path d="M9,17a2,2,0,1,1-2-2A2,2,0,0,1,9,17Zm8-2a2,2,0,1,0,2,2A2,2,0,0,0,17,15ZM3,9H9" stroke="white" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
      </g>
    </svg>
  </div>
</div>`,
  });
}

// ── Auto-fit on first load ─────────────────────────────────────────────────────

function FitBounds({ stops, drivers }: { stops: ActiveStop[]; drivers: LiveDriver[] }) {
  const map = useMap();
  const fitted = useRef(false);

  useEffect(() => {
    if (fitted.current) return;
    const pts: [number, number][] = [
      ...stops.filter(s => s.dropoffLat && s.dropoffLng).map(s => [s.dropoffLat, s.dropoffLng] as [number, number]),
      ...drivers
        .filter(d => d.currentLat && d.currentLng && !isGpsStale(d.lastLocationAt))
        .map(d => [d.currentLat!, d.currentLng!] as [number, number]),
    ];
    if (pts.length === 0) return;
    try {
      const bounds = L.latLngBounds(pts);
      if (bounds.isValid()) {
        map.fitBounds(bounds, { padding: [48, 48], maxZoom: 14 });
        fitted.current = true;
      }
    } catch { /* ignore */ }
  }, [map, stops, drivers]);

  return null;
}

// ── Main ───────────────────────────────────────────────────────────────────────

function DispatchLiveMapInner({
  activeStops,
  drivers,
  selectedStopId,
  selectedDriverId,
  selectedDriverRoute,
  onStopClick,
  onDriverClick,
}: Props) {
  const [mounted, setMounted] = useState(false);
  const isDark = useIsDark();
  useEffect(() => { setMounted(true); }, []);

  const visibleDrivers = drivers.filter(d => d.currentLat && d.currentLng && !isGpsStale(d.lastLocationAt));
  
  const activeStopMarkers = useMemo(() => {
    return activeStops
      .filter(s => s.dropoffLat && s.dropoffLng)
      .map(stop => (
        <Marker
          key={stop.deliveryId}
          position={[stop.dropoffLat, stop.dropoffLng]}
          icon={makeStopIcon(stop.status, stop.deliveryId === selectedStopId)}
          eventHandlers={{ click: () => onStopClick?.(stop.deliveryId) }}
        >
          <Popup>
            <div style={{ fontFamily: '"IBM Plex Sans", sans-serif', minWidth: 160 }}>
              <div style={{ fontSize: 10, fontWeight: 800, color: STOP_COLOR[stop.status] ?? '#71717a', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 4 }}>
                {stop.status}
              </div>
              <div style={{ fontSize: 13, fontWeight: 700, color: '#09090B', marginBottom: 2 }}>{stop.clientName ?? '—'}</div>
              {stop.city && <div style={{ fontSize: 11, color: '#71717A' }}>{stop.city}</div>}
            </div>
          </Popup>
        </Marker>
      ));
  }, [activeStops, selectedStopId, onStopClick]);

  const driverMarkers = useMemo(() => {
    return visibleDrivers.map(driver => (
      <Marker
        key={driver.id}
        position={[driver.currentLat!, driver.currentLng!]}
        icon={makeDriverIcon(driver.name, driver.onlineStatus ?? 'OFFLINE', driver.id === selectedDriverId)}
        eventHandlers={{ click: () => onDriverClick?.(driver.id) }}
      >
        <Popup>
          <div style={{ fontFamily: '"IBM Plex Sans", sans-serif' }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: '#09090B' }}>{driver.name}</div>
            <div style={{ fontSize: 10, color: STATUS_RING[driver.onlineStatus ?? 'OFFLINE'] ?? STATUS_RING.OFFLINE, fontWeight: 600, marginTop: 2 }}>
              {STATUS_LABEL[driver.onlineStatus ?? 'OFFLINE']}
            </div>
            <div style={{ fontSize: 9, color: '#A1A1AA', marginTop: 4 }}>
              {driver.id === selectedDriverId ? 'Cliquer pour masquer la tournée' : 'Cliquer pour voir la tournée'}
            </div>
          </div>
        </Popup>
      </Marker>
    ));
  }, [visibleDrivers, selectedDriverId, onDriverClick]);

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

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative', zIndex: 0, isolation: 'isolate' }}>
      <MapContainer center={center} zoom={11} style={{ width: '100%', height: '100%' }} zoomControl>
        <TileLayer
          attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
          maxZoom={19}
        />

        <FitBounds stops={activeStops} drivers={visibleDrivers} />

        {/* Selected driver route polyline */}
        {selectedDriverRoute && selectedDriverRoute.length > 1 && (
          <Polyline
            positions={selectedDriverRoute}
            pathOptions={{ color: 'var(--brand)', weight: 3, opacity: 0.72, dashArray: '10 6' }}
          />
        )}

        {/* Active stop markers */}
        {activeStopMarkers}

        {/* Driver markers */}
        {driverMarkers}
      </MapContainer>

      {/* Map overlay badges */}
      <div style={{ position: 'absolute', bottom: 10, left: 10, zIndex: 800, display: 'flex', gap: 6, pointerEvents: 'none' }}>
        <div style={{ background: 'rgba(9,9,11,0.72)', borderRadius: 4, padding: '3px 8px', display: 'flex', alignItems: 'center', gap: 5 }}>
          <div style={{ width: 5, height: 5, borderRadius: '50%', background: '#10B981', boxShadow: '0 0 0 2px rgba(16,185,129,0.3)' }} />
          <span style={{ fontSize: 10, color: '#D4D4D8', fontWeight: 700, letterSpacing: '0.05em' }}>
            {visibleDrivers.length} chauffeur{visibleDrivers.length !== 1 ? 's' : ''} actif{visibleDrivers.length !== 1 ? 's' : ''}
          </span>
        </div>
        <div style={{ background: 'rgba(9,9,11,0.72)', borderRadius: 4, padding: '3px 8px', display: 'flex', alignItems: 'center', gap: 5 }}>
          <div style={{ width: 5, height: 5, borderRadius: '50%', background: '#f97316' }} />
          <span style={{ fontSize: 10, color: '#D4D4D8', fontWeight: 700, letterSpacing: '0.05em' }}>
            {activeStops.filter(s => s.dropoffLat && s.dropoffLng).length} arrêt{activeStops.length !== 1 ? 's' : ''} en cours
          </span>
        </div>
      </div>
    </div>
  );
}

export default function DispatchLiveMap(props: Props) {
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[350px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-[2px] p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">Carte de Dispatch Live Indisponible</p>
        <p className="text-[10px] text-[var(--text-muted)] mb-4">Une erreur d'affichage s'est produite sur le tableau de dispatch.</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-[10px] rounded-[2px] font-medium hover:opacity-90 transition">
          Actualiser la page
        </button>
      </div>
    }>
      <DispatchLiveMapInner {...props} />
    </ErrorBoundary>
  );
}
