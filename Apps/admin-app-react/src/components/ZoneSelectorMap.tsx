
import { useEffect, useRef, useState, useMemo } from 'react';
import { MapContainer, TileLayer, CircleMarker, Popup, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useIsDark } from '@/lib/ui/theme';
import 'leaflet-draw';
import 'leaflet-draw/dist/leaflet.draw.css';
import { useT } from '@/lib/i18n/LocaleContext';
import { IconSearch } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import ErrorBoundary from '@/components/ErrorBoundary';

// Minimal typing for the untyped leaflet-draw plugin surface we use.
type DrawEvent = { layer: L.Layer };
type LeafletDraw = { Control: { Draw: new (opts: unknown) => L.Control } };
const LDraw = L as unknown as LeafletDraw;

// ── Leaflet icon fix for Next.js ────────────────────────────────────────────
if (typeof window !== 'undefined') {
  // Fix for leaflet-draw ReferenceError: type is not defined in strict mode
  (window as unknown as { type: string }).type = '';

  // @ts-ignore
  delete L.Icon.Default.prototype._getIconUrl;
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png',
    iconUrl:       'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png',
    shadowUrl:     'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png',
  });
}

// Shared mutable ref for draw state — avoids prop drilling between GeofenceHandler and MapClickResolver
const drawingState = { current: false };

// ── Types ────────────────────────────────────────────────────────────────────
export type ResolvedCode = { lat: number; lng: number; name?: string };
export type CoordMap = Record<string, ResolvedCode>;

export type ZoneSelectorMapProps = {
  selectedCodes:       string[];
  geometry?:           string;
  color?:              string;
  zoneName?:           string;
  externalCoords?:     CoordMap;
  onGeometryChange:    (geo: string)     => void;
  onPostalCodesChange: (codes: string[]) => void;
  onCoordsFound:       (coords: CoordMap) => void;
};

type CodeResult = { code: string; lat: number; lng: number; name?: string };

// ── Geometry helper ───────────────────────────────────────────────────────────
function pointInRing(pt: L.LatLng, ring: L.LatLng[]): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i].lng, yi = ring[i].lat;
    const xj = ring[j].lng, yj = ring[j].lat;
    if ((yi > pt.lat) !== (yj > pt.lat) &&
        pt.lng < ((xj - xi) * (pt.lat - yi)) / (yj - yi) + xi)
      inside = !inside;
  }
  return inside;
}

// ── Overpass detection ────────────────────────────────────────────────────────
async function detectCodes(
  fg: L.FeatureGroup,
  onProgress: (pct: number, msg: string) => void,
  t: ReturnType<typeof import('@/lib/i18n/LocaleContext').useT>,
): Promise<CodeResult[]> {
  const layers: L.Polygon[] = [];
  fg.eachLayer((l: L.Layer & { getLatLngs?: () => unknown; getBounds?: () => unknown }) => { if (l.getLatLngs && l.getBounds) layers.push(l as unknown as L.Polygon); });
  if (layers.length === 0) return [];

    onProgress(10, (t as any).zoneSelectorMap?.searchingCodes ?? 'Searching postal codes in zone…');
  const allResults: CodeResult[] = [];

  for (const polygon of layers) {
    const bounds = polygon.getBounds();
    const ring   = (polygon.getLatLngs() as L.LatLng[][])[0];
    const s = bounds.getSouth().toFixed(5), w = bounds.getWest().toFixed(5);
    const n = bounds.getNorth().toFixed(5), e = bounds.getEast().toFixed(5);
    const query = `[out:json][timeout:15];(node["addr:postcode"](${s},${w},${n},${e});way["addr:postcode"](${s},${w},${n},${e}););out center tags;`;

    let codes: CodeResult[] = [];
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 15000); // 15 seconds max
      const res = await fetch(`https://overpass-api.de/api/interpreter?data=${encodeURIComponent(query)}`, {
        signal: controller.signal
      });
      clearTimeout(timeoutId);
      onProgress(60, (t as any).zoneSelectorMap?.readingData ?? 'Reading geographic data…');
      if (!res.ok) throw new Error(`Overpass HTTP ${res.status}`);
      const data = await res.json();
      if (data.elements?.length) {
        const seen = new Map<string, CodeResult>();
        for (const el of data.elements) {
          const pc = el.tags?.['addr:postcode'] as string | undefined;
          if (!pc) continue;
          const lat = el.lat ?? el.center?.lat, lng = el.lon ?? el.center?.lon;
          if (lat === undefined || lng === undefined) continue;
          if (!pointInRing(L.latLng(lat, lng), ring)) continue;
          const localityName: string | undefined = el.tags?.['addr:suburb'] || el.tags?.['addr:city'] || el.tags?.['addr:town'] || el.tags?.['addr:village'];
          if (!seen.has(pc)) seen.set(pc, { code: pc, lat, lng, name: localityName });
          else if (!seen.get(pc)!.name && localityName) seen.set(pc, { ...seen.get(pc)!, name: localityName });
        }
        codes = Array.from(seen.values());
      }
    } catch {
      // Silently catch the error so we can gracefully fall back to Nominatim
      // The browser will inherently log network failures (like 504), but we don't need to throw our own warning.
    }

    if (!codes.length) {
      onProgress(70, (t as any).zoneSelectorMap?.fallbackAttempt ?? 'Unavailable — fallback attempt…');
      const cLat = (bounds.getNorth() + bounds.getSouth()) / 2;
      const cLng = (bounds.getEast()  + bounds.getWest())  / 2;
      try {
        const fb = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${cLat}&lon=${cLng}&zoom=12`);
        const d  = await fb.json();
        const pc = d.address?.postcode as string | undefined;
        const name = d.address?.suburb || d.address?.city || d.address?.town;
        if (pc) codes.push({ code: pc, lat: cLat, lng: cLng, name });
      } catch { /* ignore */ }
    }

    codes.forEach(r => allResults.push(r));
    onProgress(95, (t as any).zoneSelectorMap?.finalizingList ?? 'Finalizing list…');
  }

  const deduped = new Map<string, CodeResult>();
  allResults.forEach(r => { if (!deduped.has(r.code)) deduped.set(r.code, r); });
  onProgress(100, `${deduped.size} ${(t as any).zoneSelectorMap?.codesFound ?? 'postal code(s) identified ✓'}`);
  return Array.from(deduped.values());
}

// ── GeofenceHandler ───────────────────────────────────────────────────────────
type GeoProps = {
  color?: string;
  initialGeometry?: string;
  onGeometryChange: (geo: string) => void;
  onPostalCodesChange: (codes: string[]) => void;
  onCoordsFound: (c: CoordMap) => void;
  onProcessing: (active: boolean, pct?: number, msg?: string) => void;
  t: ReturnType<typeof import('@/lib/i18n/LocaleContext').useT>;
};

function GeofenceHandler({ color, initialGeometry, onGeometryChange, onPostalCodesChange, onCoordsFound, onProcessing, t }: GeoProps) {
  const map = useMap();
  const geoRef   = useRef(onGeometryChange);
  const codeRef  = useRef(onPostalCodesChange);
  const coordRef = useRef(onCoordsFound);
  const procRef  = useRef(onProcessing);
  useEffect(() => { geoRef.current   = onGeometryChange;    }, [onGeometryChange]);
  useEffect(() => { codeRef.current  = onPostalCodesChange; }, [onPostalCodesChange]);
  useEffect(() => { coordRef.current = onCoordsFound;       }, [onCoordsFound]);
  useEffect(() => { procRef.current  = onProcessing;        }, [onProcessing]);

  useEffect(() => {
    const fg = new L.FeatureGroup();
    map.addLayer(fg);
    const zoneColor = color || '#2563eb';

    if (initialGeometry) {
      try {
        const geojson = JSON.parse(initialGeometry);
        L.geoJSON(geojson, {
          style: () => ({ fillOpacity: 0, opacity: 0, weight: 0 }),
          onEachFeature: (_f, layer) => fg.addLayer(layer),
        });
        if (fg.getLayers().length > 0) {
          map.fitBounds(fg.getBounds(), { padding: [30, 30] });
          const fgRef = fg;
          setTimeout(async () => {
            procRef.current(true, 0, (t as any).zoneSelectorMap?.loadingPins ?? 'Loading pins…');
            const results = await detectCodes(fgRef, (pct, msg) => procRef.current(true, pct, msg), t);
            const newCoords: CoordMap = {};
            results.forEach(r => { newCoords[r.code] = { lat: r.lat, lng: r.lng, name: r.name }; });
            coordRef.current(newCoords);
            procRef.current(false);
          }, 400);
        }
      } catch (e) { console.error('Geometry parse error:', e); }
    }

    const drawCtrl = new LDraw.Control.Draw({
      draw: {
        polyline: false, marker: false, circlemarker: false, circle: false,
        rectangle: { shapeOptions: { color: zoneColor, fillOpacity: 0, opacity: 0 } },
        polygon:   { allowIntersection: false, showArea: true, shapeOptions: { color: zoneColor, fillOpacity: 0, opacity: 0 } },
      },
      edit: { featureGroup: fg, remove: true },
    });
    map.addControl(drawCtrl);

    const onDrawStart = () => { drawingState.current = true; };
    const onDrawStop  = () => { setTimeout(() => { drawingState.current = false; }, 300); };
    const runUpdate = async (currentFg: L.FeatureGroup) => {
      geoRef.current(JSON.stringify(currentFg.toGeoJSON()));
      procRef.current(true, 0, (t as any).zoneSelectorMap?.analyzingPerimeter ?? 'Analyzing perimeter…');
      const results = await detectCodes(currentFg, (pct, msg) => procRef.current(true, pct, msg), t);
      codeRef.current(results.map(r => r.code));
      const newCoords: CoordMap = {};
      results.forEach(r => { newCoords[r.code] = { lat: r.lat, lng: r.lng, name: r.name }; });
      coordRef.current(newCoords);
      procRef.current(false);
    };

    const onCreated = (e: DrawEvent) => { fg.addLayer(e.layer); runUpdate(fg); };
    const onEdited  = () => runUpdate(fg);
    const onDeleted = () => runUpdate(fg);

    map.on('draw:drawstart', onDrawStart);
    map.on('draw:drawstop',  onDrawStop);
    map.on('draw:created',   onCreated);
    map.on('draw:edited',    onEdited);
    map.on('draw:deleted',   onDeleted);

    return () => {
      map.off('draw:drawstart', onDrawStart);
      map.off('draw:drawstop',  onDrawStop);
      map.off('draw:created',   onCreated);
      map.off('draw:edited',    onEdited);
      map.off('draw:deleted',   onDeleted);
      map.removeControl(drawCtrl);
      map.removeLayer(fg);
    };
  }, [map]);

  return null;
}

// ── Search bar ────────────────────────────────────────────────────────────────
function SearchControl() {
  const map = useMap();
  const t = useT();
  const isDark = useIsDark();
  const [query,   setQuery]   = useState('');
  const [loading, setLoading] = useState(false);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setLoading(true);
    try {
      const res  = await fetch(`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query + ', Tunisie')}&limit=1&countrycodes=tn`);
      const data = await res.json();
      if (data?.[0]) map.flyTo([parseFloat(data[0].lat), parseFloat(data[0].lon)], 13, { duration: 1 });
    } catch { /* ignore */ } finally { setLoading(false); }
  };

  return (
    <div style={{ position: 'absolute', top: 10, left: 50, zIndex: 1000, width: 260 }}>
      <div className="flex items-center gap-1 px-2 py-1.5 rounded-lg border shadow-md" style={{ background: 'var(--surface)', borderColor: 'var(--border)' }}>
        <input
          className="flex-1 text-base outline-none bg-transparent placeholder:text-[var(--text-soft)] text-[var(--text-primary)]"
          placeholder={t.placeholders.city}
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && handleSearch()}
        />
        <Button
          type="button"
          size="sm"
          variant="default"
          onClick={handleSearch}
          disabled={loading}
          className="h-6 w-6 p-0 shrink-0"
        >
          {loading ? (
            <svg className="animate-spin h-3 w-3" viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
            </svg>
          ) : (
            <IconSearch size={12} />
          )}
        </Button>
      </div>
    </div>
  );
}

// ── Map click → reverse-geocode ───────────────────────────────────────────────
function MapClickResolver({ selectedCodes, onPostalCodesChange, onCoordsFound }: {
  selectedCodes: string[];
  onPostalCodesChange: (codes: string[]) => void;
  onCoordsFound: (c: CoordMap) => void;
}) {
  const map      = useMap();
  const codesRef = useRef(selectedCodes);
  const cbRef    = useRef(onPostalCodesChange);
  const coordRef = useRef(onCoordsFound);
  useEffect(() => { codesRef.current = selectedCodes;       }, [selectedCodes]);
  useEffect(() => { cbRef.current    = onPostalCodesChange; }, [onPostalCodesChange]);
  useEffect(() => { coordRef.current = onCoordsFound;       }, [onCoordsFound]);

  useEffect(() => {
    const handler = async (e: L.LeafletMouseEvent) => {
      if (drawingState.current) return;
      const { lat, lng } = e.latlng;
      try {
        const res  = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}&zoom=12`);
        const data = await res.json();
        const pc: string | undefined = data.address?.postcode;
        const name: string | undefined = data.address?.suburb || data.address?.city || data.address?.town;
        if (pc && !codesRef.current.includes(pc)) {
          cbRef.current([...codesRef.current, pc]);
          coordRef.current({ [pc]: { lat, lng, name } });
        }
      } catch { /* ignore */ }
    };
    map.on('click', handler);
    return () => { map.off('click', handler); };
  }, [map]);

  return null;
}

// ── Postal code markers (extracted to respect hooks rules) ─────────────────────
function ZoneMarkers({ selectedCodes, allCoords, color, zoneName, t }: {
  selectedCodes: string[];
  allCoords: CoordMap;
  color?: string;
  zoneName?: string;
  t: ReturnType<typeof import('@/lib/i18n/LocaleContext').useT>;
}) {
  return useMemo(() => {
    return selectedCodes
      .filter(c => allCoords[c])
      .map(c => {
        const resolved = allCoords[c];
        return (
          <CircleMarker
            key={c}
            center={[resolved.lat, resolved.lng]}
            radius={6}
            pathOptions={{
              fillColor: color || '#2563eb',
              color: '#ffffff',
              weight: 2,
              fillOpacity: 0.9,
            }}
          >
            <Popup>
              {resolved.name && (
                <p className="text-base font-bold mb-0.5">{resolved.name}</p>
              )}
              <p className="text-sm text-gray-500">{(t as any).zoneSelectorMap?.postalCode ?? 'Postal code'}: <strong>{c}</strong></p>
              {zoneName && (
                <p className="text-sm text-gray-500 mt-1">{(t as any).zoneSelectorMap?.zone ?? 'Zone'}: {zoneName}</p>
              )}
            </Popup>
          </CircleMarker>
        );
      });
  }, [selectedCodes, allCoords, color, zoneName]);
}

// ── Main export ───────────────────────────────────────────────────────────────
function ZoneSelectorMapInner({
  selectedCodes, geometry, color, zoneName,
  externalCoords = {},
  onGeometryChange, onPostalCodesChange, onCoordsFound,
}: ZoneSelectorMapProps) {
  const t = useT();
  const isDark = useIsDark();
  const [processing,  setProcessing]  = useState(false);
  const [progress,    setProgress]    = useState(0);
  const [progressMsg, setProgressMsg] = useState('');
  const [coordsMap,   setCoordsMap]   = useState<CoordMap>({});

  const allCoords: CoordMap = { ...coordsMap, ...externalCoords };

  const handleCoordsFound = (newCoords: CoordMap) => {
    setCoordsMap(prev => ({ ...prev, ...newCoords }));
    onCoordsFound(newCoords);
  };

  const center: [number, number] = [33.8869, 9.5375];

  return (
    <div style={{
      height: 'calc(90vh - 70px)', width: '100%', position: 'relative',
      borderRadius: 12, overflow: 'hidden',
      border: '1px solid var(--border)',
    }}>
      {processing && (
        <div style={{
          position: 'absolute', inset: 0, zIndex: 2000,
          background: isDark ? 'rgba(23, 26, 32, 0.85)' : 'rgba(255,255,255,0.82)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div className="flex flex-col items-center gap-3">
            <svg className="animate-spin h-6 w-6" style={{ color: 'var(--brand)' }} viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
            </svg>
            <p className="text-base font-bold text-[var(--text-primary)]">{progressMsg}</p>
            <p className="text-xs text-[var(--text-muted)]">{progress}%</p>
          </div>
        </div>
      )}

      <MapContainer center={center} zoom={7} style={{ height: '100%', width: '100%' }}>
        <TileLayer
          url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'}
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/">CARTO</a>'
        />

        <SearchControl />

        <GeofenceHandler
          color={color}
          initialGeometry={geometry}
          onGeometryChange={onGeometryChange}
          onPostalCodesChange={onPostalCodesChange}
          onCoordsFound={handleCoordsFound}
          onProcessing={(active, pct = 0, msg = '') => {
            setProcessing(active);
            setProgress(pct);
            setProgressMsg(msg);
          }}
          t={t}
        />

        <MapClickResolver
          selectedCodes={selectedCodes}
          onPostalCodesChange={onPostalCodesChange}
          onCoordsFound={handleCoordsFound}
        />

        <ZoneMarkers
          selectedCodes={selectedCodes}
          allCoords={allCoords}
          color={color}
          zoneName={zoneName}
          t={t}
        />
      </MapContainer>
    </div>
  );
}

export default function ZoneSelectorMap(props: ZoneSelectorMapProps) {
  const t = useT();
  return (
    <ErrorBoundary fallback={
      <div className="w-full h-full min-h-[400px] bg-[var(--surface-2)] flex flex-col items-center justify-center border border-[var(--border)] rounded-xs p-6 text-center">
        <p className="text-xs text-[var(--text-strong)] font-bold mb-2">{(t as any).zoneSelectorMap?.errorTitle ?? 'Zone Selector Unavailable'}</p>
        <p className="text-2xs text-[var(--text-muted)] mb-4">{(t as any).zoneSelectorMap?.errorDesc ?? 'The geographic zoning module could not be loaded.'}</p>
        <button onClick={() => window.location.reload()} className="px-3 py-1 bg-[var(--brand)] text-white text-2xs rounded-xs font-medium hover:opacity-90 transition">
          {(t as any).zoneSelectorMap?.errorRefresh ?? 'Refresh page'}
        </button>
      </div>
    }>
      <ZoneSelectorMapInner {...props} />
    </ErrorBoundary>
  );
}

