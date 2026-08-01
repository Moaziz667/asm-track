

import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useLocaleStore } from '@/lib/i18n';
import { useT, getCopy } from '@/lib/i18n/LocaleContext';
import { MapContainer, Marker, Popup, Polyline, useMap } from 'react-leaflet';
import ErrorBoundary from '@/components/ErrorBoundary';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useIsDark } from '@/lib/ui/theme';
import 'leaflet-draw/dist/leaflet.draw.css';
import 'leaflet-draw';
import { useRouteBuilderContext } from '@/pages/route-builder/hooks/useRouteBuilder';
import { formatAddress } from '@/lib/utils/address';

// Minimal typing for the untyped leaflet-draw plugin surface we use.
type DrawEvent = { layer: L.Layer };
type LeafletDraw = {
  Control: { Draw: new (opts: unknown) => L.Control };
  Draw: { Event: { CREATED: string; DELETED: string } };
};
const LDraw = L as unknown as LeafletDraw;

if (typeof window !== 'undefined') {
  // Fix for leaflet-draw ReferenceError: type is not defined in strict mode
  (window as unknown as { type: string }).type = '';
}

type BuilderOrder = {
  id: string;
  erpOrderId?: string;
  clientName?: string;
  dropoffAddress?: string;
  dropoffCity?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  itemsSummary?: string;
};



type Props = {
  onPinDragStart?: (orderId: string, orderIds: string[], clientName: string, e: MouseEvent) => void;
  getDragging?: () => boolean;
  mapLayer?: 'street' | 'satellite' | 'hot';
  showDepot?: boolean;
};

const ROUTE_COLORS = [
  '#2563eb', '#16a34a', '#7c3aed', '#dc2626',
  '#0d9488', '#db2777', '#d97706', '#4f46e5',
];

// ── SVG pin helpers ─────────────────────────────────────────────────────────

function makePinIcon(color: string, label: string, selected: boolean, dragging = false) {
  const opacity = dragging ? 0.35 : 1;
  const isDot = label === '•';

  if (isDot) {
    // Unassigned orders — small circle, no tail
    const r = selected ? 9 : 7;
    const size = r * 2;
    const shadow = selected
      ? 'drop-shadow(0 0 5px rgba(255,87,34,0.85))'
      : 'drop-shadow(0 1px 3px rgba(0,0,0,0.35))';
    return L.divIcon({
      className: '',
      iconSize: [size, size],
      iconAnchor: [r, r],
      popupAnchor: [0, -r - 4],
      html: `<div style="width:${size}px;height:${size}px;filter:${shadow};opacity:${opacity};transition:filter 0.15s;">
  <svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" xmlns="http://www.w3.org/2000/svg">
    <circle cx="${r}" cy="${r}" r="${r}" fill="${color}"/>
    <circle cx="${r}" cy="${r}" r="${r - 2.5}" fill="none" stroke="white" stroke-width="1.5" stroke-opacity="0.55"/>
  </svg>
</div>`,
    });
  }

  // Assigned stops — compact teardrop: large circle head + short tail
  const shadow = selected
    ? 'drop-shadow(0 0 6px rgba(255,87,34,0.9)) drop-shadow(0 2px 8px rgba(0,0,0,0.45))'
    : 'drop-shadow(0 2px 5px rgba(0,0,0,0.32))';
  const scale = selected ? 'scale(1.18)' : 'scale(1)';
  const fs = label.length > 2 ? 8 : label.length > 1 ? 10 : 12;

  return L.divIcon({
    className: '',
    iconSize: [28, 36],
    iconAnchor: [14, 36],
    popupAnchor: [0, -38],
    html: `<div style="width:28px;height:36px;transform:${scale};transform-origin:50% 100%;filter:${shadow};transition:transform 0.15s,filter 0.15s;opacity:${opacity};">
  <svg width="28" height="36" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
    <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" fill="${color}"/>
    <text x="14" y="15" text-anchor="middle" dominant-baseline="middle" fill="white" font-size="${fs}" font-weight="800" font-family="system-ui,sans-serif" letter-spacing="-0.3">${label}</text>
  </svg>
</div>`,
  });
}

function makeDepotIcon() {
  return L.divIcon({
    className: '',
    iconSize: [42, 42],
    iconAnchor: [21, 21],
    popupAnchor: [0, -22],
    html: `<div style="width:42px;height:42px;filter:drop-shadow(0 3px 8px rgba(0,0,0,0.45));">
  <svg width="42" height="42" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg">
    <circle cx="21" cy="21" r="21" fill="#111827"/>
    <circle cx="21" cy="21" r="19" fill="none" stroke="white" stroke-width="1.5" stroke-opacity="0.4"/>
    <!-- warehouse: roof + body + door -->
    <polygon points="21,10 10,19 32,19" fill="white" fill-opacity="0.95"/>
    <rect x="12" y="19" width="18" height="11" fill="white" fill-opacity="0.9" rx="1"/>
    <rect x="18" y="23" width="6" height="7" fill="#111827" rx="1"/>
  </svg>
</div>`,
  });
}

/** Pickup-depot marker (cyan) — a non-home source depot the driver loads from. */
function makePickupDepotIcon() {
  return L.divIcon({
    className: '',
    iconSize: [38, 38],
    iconAnchor: [19, 19],
    popupAnchor: [0, -20],
    html: `<div style="width:38px;height:38px;filter:drop-shadow(0 3px 8px rgba(0,0,0,0.4));">
  <svg width="38" height="38" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg">
    <circle cx="21" cy="21" r="21" fill="#0891B2"/>
    <circle cx="21" cy="21" r="19" fill="none" stroke="white" stroke-width="1.5" stroke-opacity="0.5"/>
    <polygon points="21,10 10,19 32,19" fill="white" fill-opacity="0.95"/>
    <rect x="12" y="19" width="18" height="11" fill="white" fill-opacity="0.9" rx="1"/>
    <rect x="18" y="23" width="6" height="7" fill="#0891B2" rx="1"/>
  </svg>
</div>`,
  });
}

// ── Utilities ────────────────────────────────────────────────────────────────

function pointInPolygon(point: { lat: number; lng: number }, polygon: Array<{ lat: number; lng: number }>): boolean {
  let inside = false;
  const x = point.lng;
  const y = point.lat;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const xi = polygon[i].lng, yi = polygon[i].lat;
    const xj = polygon[j].lng, yj = polygon[j].lat;
    const intersect = yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / ((yj - yi) || 1e-9) + xi;
    if (intersect) inside = !inside;
  }
  return inside;
}

// ── Inner components ─────────────────────────────────────────────────────────

function DrawSelector({ orders, onSelectionChange }: { orders: BuilderOrder[]; onSelectionChange: (ids: string[]) => void }) {
  const map = useMap();
  const featureGroupRef = useRef<L.FeatureGroup | null>(null);
  const drawControlRef = useRef<L.Control | null>(null);

  useEffect(() => {
    const fg = new L.FeatureGroup();
    featureGroupRef.current = fg;
    map.addLayer(fg);

    const drawControl = new LDraw.Control.Draw({
      draw: {
        polyline: false, circle: false, marker: false, circlemarker: false,
        rectangle: true,
        polygon: { allowIntersection: false, showArea: true },
      },
      edit: { featureGroup: fg, edit: false, remove: true },
    });
    drawControlRef.current = drawControl;
    map.addControl(drawControl);

    const onCreated = (event: DrawEvent) => {
      fg.clearLayers();
      fg.addLayer(event.layer);
      const selected = orders.filter((order) => {
        if (typeof order.dropoffLat !== 'number' || typeof order.dropoffLng !== 'number') return false;
        const latlng = L.latLng(order.dropoffLat, order.dropoffLng);
        if (event.layer instanceof L.Rectangle) return event.layer.getBounds().contains(latlng);
        if (event.layer instanceof L.Polygon) {
          const ring = (event.layer.getLatLngs()?.[0] ?? []) as L.LatLng[];
          const polygon = ring.map((p) => ({ lat: p.lat, lng: p.lng }));
          if (polygon.length < 3) return false;
          return pointInPolygon({ lat: latlng.lat, lng: latlng.lng }, polygon);
        }
        return false;
      });
      onSelectionChange(selected.map((o) => o.id));
    };
    const onDeleted = () => onSelectionChange([]);

    map.on(LDraw.Draw.Event.CREATED, onCreated);
    map.on(LDraw.Draw.Event.DELETED, onDeleted);
    return () => {
      map.off(LDraw.Draw.Event.CREATED, onCreated);
      map.off(LDraw.Draw.Event.DELETED, onDeleted);
      if (drawControlRef.current) map.removeControl(drawControlRef.current);
      if (featureGroupRef.current) map.removeLayer(featureGroupRef.current);
    };
  }, [map, onSelectionChange, orders]);

  return null;
}

function BaseTiles({ mapLayer }: { mapLayer?: 'street' | 'satellite' | 'hot' }) {
  const map = useMap();
  const isDark = useIsDark();
  useEffect(() => {
    const panes = (map as unknown as { _panes?: Record<string, HTMLElement> })._panes;
    if (!panes || !panes.tilePane) return;

    let url: string;
    let attribution: string;
    if (mapLayer === 'satellite') {
      url = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
      attribution = 'Tiles &copy; Esri';
    } else if (mapLayer === 'hot') {
      url = 'https://tile.openstreetmap.fr/hot/{z}/{x}/{y}.png';
      attribution = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors, Tiles courtesy of <a href="https://hot.openstreetmap.org/">Humanitarian OpenStreetMap Team</a>';
    } else {
      if (isDark) {
        url = 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png';
        attribution = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>';
      } else {
        url = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
        attribution = '&copy; OpenStreetMap contributors';
      }
    }

    const layer = L.tileLayer(url, { attribution, maxZoom: 19 });
    layer.addTo(map);
    return () => { if (map.hasLayer(layer)) map.removeLayer(layer); };
  }, [map, mapLayer]);
  return null;
}

function FlyToMarker({ target }: { target: [number, number] | null }) {
  const map = useMap();
  useEffect(() => {
    if (!target) return;
    map.flyTo(target, Math.max(map.getZoom(), 16), { animate: true, duration: 0.5 });
  }, [target]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

function FitToRoute({ routeLines, highlightedRouteId }: {
  routeLines: { id: string; points: [number, number][] }[];
  highlightedRouteId?: string | null;
}) {
  const map = useMap();
  useEffect(() => {
    if (!highlightedRouteId) return;
    const line = routeLines.find((l) => l.id === highlightedRouteId);
    if (!line || line.points.length < 2) return;
    try {
      const bounds = L.latLngBounds(line.points);
      if (bounds.isValid()) map.fitBounds(bounds, { padding: [48, 48], maxZoom: 15, animate: true });
    } catch { /* bounds invalid */ }
  }, [highlightedRouteId]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

// ── Nominatim search overlay ─────────────────────────────────────────────────

interface NominatimResult { place_id: number; display_name: string; lat: string; lon: string }

function MapSearch() {
  const t = useT();
  const map = useMap();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<NominatimResult[]>([]);
  const [open, setOpen] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const search = (q: string) => {
    setQuery(q);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (q.trim().length < 3) { setResults([]); setOpen(false); return; }
    debounceRef.current = setTimeout(async () => {
      try {
        const res = await fetch(
          `https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(q)}&limit=5`,
          { headers: { 'Accept-Language': 'fr' } }
        );
        const data: NominatimResult[] = await res.json();
        setResults(data);
        setOpen(data.length > 0);
      } catch { /* ignore */ }
    }, 350);
  };

  const pick = (r: NominatimResult) => {
    map.flyTo([parseFloat(r.lat), parseFloat(r.lon)], 15, { animate: true, duration: 0.6 });
    setQuery(r.display_name.split(',')[0]);
    setOpen(false);
    setResults([]);
  };

  return (
    <div style={{ position: 'absolute', top: 10, left: '50%', transform: 'translateX(-50%)', zIndex: 1000, width: 320 }}>
      <input
        value={query}
        onChange={e => search(e.target.value)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        placeholder={t.routeBuilderPage.mapSearchPlaceholder}
        style={{
          width: '100%', padding: '7px 12px', borderRadius: 6,
          border: '1px solid var(--border-color, #d1d5db)',
          fontSize: 13, boxShadow: '0 2px 8px rgba(0,0,0,0.18)', outline: 'none',
          background: 'var(--surface-1, #fff)', color: 'var(--text-strong, #0f172a)',
        }}
      />
      {open && (
        <ul style={{
          margin: 0, padding: 0, listStyle: 'none',
          background: 'var(--surface-1, #fff)',
          border: '1px solid var(--border-color, #e5e7eb)', borderRadius: 6, marginTop: 2,
          boxShadow: '0 4px 12px rgba(0,0,0,0.15)', maxHeight: 220, overflowY: 'auto',
        }}>
          {results.map(r => (
            <li key={r.place_id}
              onMouseDown={() => pick(r)}
              style={{
                padding: '7px 12px', fontSize: 12, cursor: 'pointer',
                borderBottom: '1px solid var(--border-subtle, #f3f4f6)',
                color: 'var(--text-strong, #0f172a)',
              }}
            >
              {r.display_name}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ── Main component ───────────────────────────────────────────────────────────

function RouteBuilderMapInner({
  onPinDragStart,
  getDragging,
  mapLayer = 'street',
  showDepot = true,
}: Props) {
  const rb = useRouteBuilderContext();
  const t = useT();
  const {
    waitingDeliveries: orders,
    routes,
    selectedOrderIds,
    setSelectedOrderIds: onSelectionChange,
    selectedRouteId: highlightedRouteId,
    setSelectedRouteId: onRouteClick,
    showRouteTrajet = true,
  } = rb;

  const suggestedStops = rb.suggestion?.optimizedStops ?? [];
  const suggestedRouteGeometry = rb.suggestion?.routeGeometry;
  const depot = rb.selectedDepot ? {
    name: rb.selectedDepot.name,
    latitude: rb.selectedDepot.latitude,
    longitude: rb.selectedDepot.longitude,
  } : null;
  const parseGeometry = (geometry?: string): [number, number][] => {
    if (!geometry) return [];
    try {
      const parsed = JSON.parse(geometry);
      if (!Array.isArray(parsed)) return [];
      return parsed
        .filter((point: unknown) => Array.isArray(point) && point.length >= 2)
        .map((point: number[]) => [Number(point[0]), Number(point[1])] as [number, number])
        .filter(([lat, lng]) => Number.isFinite(lat) && Number.isFinite(lng));
    } catch { return []; }
  };

  const center = useMemo<[number, number]>(() => {
    const firstPinned = orders.find((o) => typeof o.dropoffLat === 'number' && typeof o.dropoffLng === 'number');
    if (firstPinned && typeof firstPinned.dropoffLat === 'number' && typeof firstPinned.dropoffLng === 'number') {
      return [firstPinned.dropoffLat, firstPinned.dropoffLng];
    }
    return [36.8065, 10.1815];
  }, [orders]);

  const routeLines = useMemo(() => {
    return routes
      .map((route, idx) => {
        const stops = Array.isArray(route.stops) ? route.stops : [];
        if (stops.length === 0) return null;
        const geometryPoints = parseGeometry(route.routeGeometry);
        const fallbackPoints = [...(route.stops ?? [])]
          .sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0))
          .filter((s) => typeof s.dropoffLat === 'number' && typeof s.dropoffLng === 'number')
          .map((s) => [s.dropoffLat as number, s.dropoffLng as number] as [number, number]);
        const points = geometryPoints.length > 1 ? geometryPoints : fallbackPoints;
        return points.length > 1
          ? { id: route.id, points, color: ROUTE_COLORS[idx % ROUTE_COLORS.length] }
          : null;
      })
      .filter((line): line is { id: string; points: [number, number][]; color: string } => line !== null);
  }, [routes]);

  // Multi-depot: one marker per distinct non-home source depot among PICKUP stops
  // (limited to the highlighted route when one is selected). Count = deliveries loaded there.
  const pickupDepotMarkers = useMemo(() => {
    const byId = new Map<string, { name: string; lat: number; lng: number; count: number }>();
    routes.forEach((route) => {
      if (highlightedRouteId && route.id !== highlightedRouteId) return;
      const stops = route.stops ?? [];
      stops.forEach((s) => {
        if (s.stopType === 'PICKUP' && s.sourceDepotId
            && typeof s.sourceDepotLat === 'number' && typeof s.sourceDepotLng === 'number') {
          const count = stops.filter((d) => d.stopType !== 'PICKUP' && d.sourceDepotId === s.sourceDepotId).length;
          byId.set(s.sourceDepotId, {
            name: s.sourceDepotName ?? '',
            lat: s.sourceDepotLat,
            lng: s.sourceDepotLng,
            count,
          });
        }
      });
    });
    return Array.from(byId.values());
  }, [routes, highlightedRouteId]);

  // Phase-colored legs for the highlighted route: a straight "spine" from the home depot
  // through each stop, where pickup legs (→ a source depot) are cyan and delivery legs use
  // the route colour. Makes the loading phase visually distinct from the delivery phase.
  const phaseLegs = useMemo(() => {
    if (!highlightedRouteId) return [] as { id: string; points: [number, number][]; pickup: boolean }[];
    const route = routes.find((r) => r.id === highlightedRouteId);
    if (!route) return [];
    const ordered = [...(route.stops ?? [])].sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0));
    const coordOf = (s: typeof ordered[number]): [number, number] | null => {
      if (s.stopType === 'PICKUP') {
        return (typeof s.sourceDepotLat === 'number' && typeof s.sourceDepotLng === 'number')
          ? [s.sourceDepotLat, s.sourceDepotLng] : null;
      }
      return (typeof s.dropoffLat === 'number' && typeof s.dropoffLng === 'number')
        ? [s.dropoffLat, s.dropoffLng] : null;
    };
    const segs: { id: string; points: [number, number][]; pickup: boolean }[] = [];
    let prev: [number, number] | null = (depot && Number.isFinite(depot.latitude) && Number.isFinite(depot.longitude))
      ? [depot.latitude, depot.longitude] : null;
    for (const s of ordered) {
      const c = coordOf(s);
      if (prev && c) segs.push({ id: s.id, points: [prev, c], pickup: s.stopType === 'PICKUP' });
      if (c) prev = c;
    }
    return segs;
  }, [routes, highlightedRouteId, depot]);

  const suggestedGeometryPoints = useMemo(() => {
    const parsed = parseGeometry(suggestedRouteGeometry);
    if (parsed.length > 1) return parsed;
    return (suggestedStops ?? [])
      .filter((s) => typeof s.dropoffLat === 'number' && typeof s.dropoffLng === 'number')
      .map((stop) => [stop.dropoffLat, stop.dropoffLng] as [number, number]);
  }, [suggestedRouteGeometry, suggestedStops]);

  const orderMeta = useMemo(() => {
    const map = new Map<string, { stopOrder?: number; color: string }>();
    routes.forEach((route, routeIndex) => {
      const color = ROUTE_COLORS[routeIndex % ROUTE_COLORS.length];
      route.stops?.forEach((stop) => { map.set(stop.deliveryId, { stopOrder: stop.stopOrder, color }); });
    });
    return map;
  }, [routes]);

  const [flyTarget, setFlyTarget] = useState<[number, number] | null>(null);

  const toggleOrderSelection = useCallback((orderId: string, lat?: number, lng?: number) => {
    if (selectedOrderIds.includes(orderId)) {
      onSelectionChange(selectedOrderIds.filter((id) => id !== orderId));
    } else {
      onSelectionChange([...selectedOrderIds, orderId]);
    }
    if (lat != null && lng != null) setFlyTarget([lat, lng]);
  }, [selectedOrderIds, onSelectionChange]);

  const suggestedStopMarkers = useMemo(() => {
    return (suggestedStops ?? [])
      .filter((s) => typeof s.dropoffLat === 'number' && typeof s.dropoffLng === 'number')
      .map((stop) => (
        <Marker
          key={`suggestion-${stop.stopId}`}
          position={[stop.dropoffLat, stop.dropoffLng]}
          icon={makePinIcon('#000000', String(stop.sequenceOrder), false)}
        >
          <Popup>
            <div style={{ fontSize: 12, fontWeight: 700 }}>{stop.clientName ?? 'Stop'}</div>
            <div style={{ fontSize: 11, color: '#334155', marginTop: 4 }}>Ordre suggéré #{stop.sequenceOrder}</div>
            <div style={{ fontSize: 11, color: '#0f172a', marginTop: 2 }}>
              ETA {stop.etaAt ? new Date(stop.etaAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '--:--'}
            </div>
          </Popup>
        </Marker>
      ));
  }, [suggestedStops]);

  const orderMarkers = useMemo(() => {
    return orders.map((order) => {
      if (typeof order.dropoffLat !== 'number' || typeof order.dropoffLng !== 'number') return null;
      const selected = selectedOrderIds.includes(order.id);
      const pinned = order.dropoffPinned !== false;
      const meta = orderMeta.get(order.id);
      const color = meta?.color ?? (pinned ? '#2563eb' : '#94a3b8');
      const label = meta?.stopOrder != null ? String(meta.stopOrder) : '•';

      return (
        <Marker
          key={`${order.id}-${highlightedRouteId ?? 'none'}`}
          position={[order.dropoffLat, order.dropoffLng]}
          icon={makePinIcon(color, label, selected)}
          eventHandlers={{
            click: () => {},
            mousedown: (e) => {
              const native = e.originalEvent as MouseEvent;
              native.stopPropagation();

              const startX = native.clientX;
              const startY = native.clientY;
              const orderIds = selectedOrderIds.includes(order.id) && selectedOrderIds.length > 1
                ? [...selectedOrderIds]
                : [order.id];
              let activated = false;

              const onMove = (me: MouseEvent) => {
                if (activated) return;
                if (Math.hypot(me.clientX - startX, me.clientY - startY) > 8) {
                  activated = true;
                  onPinDragStart?.(order.id, orderIds, order.clientName ?? 'Commande', me);
                }
              };

              const onUp = () => {
                window.removeEventListener('mousemove', onMove);
                window.removeEventListener('mouseup', onUp);
                if (!activated && !getDragging?.()) {
                  toggleOrderSelection(order.id, order.dropoffLat, order.dropoffLng);
                }
              };

              window.addEventListener('mousemove', onMove);
              window.addEventListener('mouseup', onUp);
            },
          }}
        >
          <Popup>
            <div style={{ fontSize: 13, fontWeight: 800, color: '#09090B', textTransform: 'uppercase' }}>
              {order.clientName?.trim() ? order.clientName : (t.common?.clientInconnu ?? 'Unknown Client')}
            </div>
            {order.erpOrderId && (
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand)', marginTop: 2, fontFamily: 'monospace' }}>
                {order.erpOrderId}
              </div>
            )}
            <div style={{ fontSize: 11, color: '#18181B', fontWeight: 600, marginTop: 6, borderTop: '1px solid #F4F4F5', paddingTop: 4 }}>
              {formatAddress(order.dropoffAddress) || '-'}
            </div>
            <div style={{ fontSize: 10, color: '#71717A', fontWeight: 500 }}>
              {order.dropoffCity || ''}
            </div>
            {order.itemsSummary && (
              <div style={{ fontSize: 10, color: '#09090B', fontWeight: 700, marginTop: 8, padding: '4px 6px', background: '#F4F4F5', borderRadius: '2px' }}>
                {order.itemsSummary}
              </div>
            )}
            {meta?.stopOrder != null && (
              <div style={{ fontSize: 10, fontWeight: 800, color: '#7c3aed', marginTop: 8 }}>
                STOP #{meta.stopOrder}
              </div>
            )}
          </Popup>
        </Marker>
      );
    });
  }, [orders, selectedOrderIds, orderMeta, highlightedRouteId, onPinDragStart, getDragging, toggleOrderSelection]);

  return (
    <div style={{ height: '100%', width: '100%', position: 'relative', zIndex: 1 }}>
      <MapContainer center={center} zoom={11} style={{ height: '100%', width: '100%' }}>
        <MapSearch />
        <BaseTiles mapLayer={mapLayer} />
        <DrawSelector orders={orders} onSelectionChange={onSelectionChange} />
        <FitToRoute routeLines={routeLines} highlightedRouteId={highlightedRouteId} />
        <FlyToMarker target={flyTarget} />

        {/* Route polylines — animated flow on highlighted route shows direction of travel */}
        {showRouteTrajet && routeLines.map((line) => {
          const isHighlighted = highlightedRouteId === line.id;
          return (
            <Polyline
              key={`${line.id}-${isHighlighted ? 'hi' : 'lo'}`}
              positions={line.points}
              color={line.color}
              weight={isHighlighted ? 5 : 3}
              opacity={isHighlighted ? 1 : 0.45}
              className={isHighlighted ? 'route-flow' : undefined}
              eventHandlers={{
                click: () => onRouteClick?.(line.id),
              }}
            />
          );
        })}

        {/* Pickup phase legs (cyan dashed) — the "loading" legs to source depots on the highlighted route */}
        {showRouteTrajet && phaseLegs.filter((l) => l.pickup).map((leg) => (
          <Polyline
            key={`phase-${leg.id}`}
            positions={leg.points}
            color="#0891B2"
            weight={4}
            opacity={0.9}
            dashArray="6, 8"
          />
        ))}

        {/* Suggested/preview route — dashed black */}
        {suggestedGeometryPoints.length > 1 && (
          <Polyline
            positions={suggestedGeometryPoints}
            color="#000000"
            weight={5}
            opacity={0.9}
            dashArray="7, 7"
          />
        )}

        {/* Suggested stop pins */}
        {suggestedStopMarkers}

        {/* Depot marker */}
        {showDepot && depot && Number.isFinite(depot.latitude) && Number.isFinite(depot.longitude) && (
          <Marker position={[depot.latitude, depot.longitude]} icon={makeDepotIcon()}>
            <Popup>
              <div style={{ fontSize: 12, fontWeight: 800, color: '#111827' }}>{depot.name}</div>
              <div style={{ fontSize: 11, color: '#475569', marginTop: 2 }}>{t.common?.depart ?? 'Depot departure'}</div>
            </Popup>
          </Marker>
        )}

        {/* Pickup-depot markers (multi-depot: one per non-home source depot) */}
        {showDepot && pickupDepotMarkers.map((d, i) => (
          <Marker key={`pickup-depot-${i}`} position={[d.lat, d.lng]} icon={makePickupDepotIcon()}>
            <Popup>
              <div style={{ fontSize: 12, fontWeight: 800, color: '#0891B2' }}>{d.name || '—'}</div>
              <div style={{ fontSize: 11, color: '#475569', marginTop: 2 }}>
                {t.routeBuilderPage.pickupDepotPopup
                  .replace('{count}', String(d.count))
                  .replace('{depot}', d.name || '—')}
              </div>
            </Popup>
          </Marker>
        ))}

        {/* Order/stop markers */}
        {orderMarkers}
      </MapContainer>
    </div>
  );
}

export default function RouteBuilderMap(props: Props) {
  const locale = useLocaleStore(s => s.locale);
  const t = getCopy(locale);
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[400px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-xs p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">{t.errorBoundary?.mapUnavailable ?? 'Interface Cartographique Indisponible (Crash)'}</p>
        <p className="text-2xs text-[var(--text-muted)] mb-4">{t.errorBoundary?.mapUnavailableDesc ?? 'Une exception s\'est produite lors du rendu de la carte Leaflet.'}</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-2xs rounded-xs font-medium hover:opacity-90 transition">
          {t.errorBoundary?.refreshApp ?? 'Actualiser l\'application'}
        </button>
      </div>
    }>
      <RouteBuilderMapInner {...props} />
    </ErrorBoundary>
  );
}

