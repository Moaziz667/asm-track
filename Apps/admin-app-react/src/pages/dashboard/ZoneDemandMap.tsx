import { useEffect, useRef, useMemo, useState, useCallback } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { IconMapPinFilled } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { useZones } from '@/hooks/useZones';
import { useIsDark } from '@/lib/ui/theme';
import type { Zone } from '@/types';

/* ── Types ──────────────────────────────────────────────────────────── */

type HeatmapPoint = {
  zipcode: string;
  lat: number;
  lng: number;
  ordersCount: number;
  delayedOrders: number;
  zoneId: string;
  zoneName: string;
  zoneColor: string;
};

type ZoneAgg = { name: string; color: string; orders: number; zipcodes: number; rank: number };

type Props = {
  kpi: { ordersByZone?: Record<string, number> } | null;
  heatmap?: { points?: HeatmapPoint[] };
};

/* ── Helpers ────────────────────────────────────────────────────────── */


function parseGeometry(geo?: string): GeoJSON.Feature | null {
  if (!geo) return null;
  try {
    const obj = JSON.parse(geo);
    if (obj.type === 'Polygon' || obj.type === 'MultiPolygon') {
      return { type: 'Feature', geometry: obj, properties: {} };
    }
  } catch { /* ignore */ }
  return null;
}

/* ── Mock zone boundaries (Tunisia governorates) ───────────────────── */

const MOCK_GEOJSON: GeoJSON.FeatureCollection = {
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', properties: { name: 'Grand Tunis', color: '#2563EB' }, geometry: { type: 'Polygon', coordinates: [[[10.05,36.72],[10.18,36.78],[10.32,36.83],[10.22,36.89],[10.08,36.85],[9.95,36.8],[10.05,36.72]]] } },
    { type: 'Feature', properties: { name: 'Ariana', color: '#7C3AED' }, geometry: { type: 'Polygon', coordinates: [[[10.12,36.86],[10.22,36.89],[10.28,36.95],[10.18,36.98],[10.08,36.93],[10.12,36.86]]] } },
    { type: 'Feature', properties: { name: 'Ben Arous', color: '#059669' }, geometry: { type: 'Polygon', coordinates: [[[10.18,36.68],[10.32,36.72],[10.38,36.78],[10.28,36.83],[10.15,36.78],[10.18,36.68]]] } },
    { type: 'Feature', properties: { name: 'Manouba', color: '#D97706' }, geometry: { type: 'Polygon', coordinates: [[[10.05,36.78],[10.12,36.82],[10.08,36.88],[9.98,36.85],[9.95,36.8],[10.05,36.78]]] } },
    { type: 'Feature', properties: { name: 'Sfax', color: '#DC2626' }, geometry: { type: 'Polygon', coordinates: [[[10.68,34.68],[10.88,34.72],[10.95,34.82],[10.78,34.88],[10.62,34.82],[10.68,34.68]]] } },
    { type: 'Feature', properties: { name: 'Sousse', color: '#0891B2' }, geometry: { type: 'Polygon', coordinates: [[[10.58,35.78],[10.72,35.82],[10.78,35.9],[10.65,35.95],[10.52,35.88],[10.58,35.78]]] } },
    { type: 'Feature', properties: { name: 'Nabeul', color: '#16A34A' }, geometry: { type: 'Polygon', coordinates: [[[10.55,36.42],[10.72,36.48],[10.78,36.55],[10.62,36.6],[10.48,36.52],[10.55,36.42]]] } },
    { type: 'Feature', properties: { name: 'Monastir', color: '#E11D48' }, geometry: { type: 'Polygon', coordinates: [[[10.72,35.72],[10.82,35.75],[10.85,35.82],[10.75,35.85],[10.68,35.78],[10.72,35.72]]] } },
  ],
};

/* ── Mock data: realistic Tunisian zipcodes per zone ────────────────── */

const MOCK_POINTS: HeatmapPoint[] = [
  // Grand Tunis (blue) — capital, highest volume
  { zipcode: '1000', lat: 36.8065, lng: 10.1815, ordersCount: 2400, delayedOrders: 48, zoneId: 'z-tunis', zoneName: 'Grand Tunis', zoneColor: '#2563EB' },
  { zipcode: '1002', lat: 36.8000, lng: 10.1730, ordersCount: 1850, delayedOrders: 37, zoneId: 'z-tunis', zoneName: 'Grand Tunis', zoneColor: '#2563EB' },
  { zipcode: '1005', lat: 36.8130, lng: 10.1980, ordersCount: 920, delayedOrders: 18, zoneId: 'z-tunis', zoneName: 'Grand Tunis', zoneColor: '#2563EB' },
  { zipcode: '1001', lat: 36.7950, lng: 10.1650, ordersCount: 680, delayedOrders: 14, zoneId: 'z-tunis', zoneName: 'Grand Tunis', zoneColor: '#2563EB' },
  { zipcode: '1010', lat: 36.7900, lng: 10.2100, ordersCount: 340, delayedOrders: 7, zoneId: 'z-tunis', zoneName: 'Grand Tunis', zoneColor: '#2563EB' },
  { zipcode: '1013', lat: 36.8200, lng: 10.1600, ordersCount: 150, delayedOrders: 3, zoneId: 'z-tunis', zoneName: 'Grand Tunis', zoneColor: '#2563EB' },

  // Ariana (purple) — north suburbs
  { zipcode: '2080', lat: 36.8900, lng: 10.1900, ordersCount: 1320, delayedOrders: 26, zoneId: 'z-ariana', zoneName: 'Ariana', zoneColor: '#7C3AED' },
  { zipcode: '2035', lat: 36.8700, lng: 10.1700, ordersCount: 740, delayedOrders: 15, zoneId: 'z-ariana', zoneName: 'Ariana', zoneColor: '#7C3AED' },
  { zipcode: '2092', lat: 36.9100, lng: 10.2200, ordersCount: 280, delayedOrders: 6, zoneId: 'z-ariana', zoneName: 'Ariana', zoneColor: '#7C3AED' },

  // Ben Arous (green) — south suburbs
  { zipcode: '2013', lat: 36.7500, lng: 10.2300, ordersCount: 860, delayedOrders: 17, zoneId: 'z-benarous', zoneName: 'Ben Arous', zoneColor: '#059669' },
  { zipcode: '2060', lat: 36.7300, lng: 10.2800, ordersCount: 420, delayedOrders: 8, zoneId: 'z-benarous', zoneName: 'Ben Arous', zoneColor: '#059669' },
  { zipcode: '2034', lat: 36.7100, lng: 10.1900, ordersCount: 190, delayedOrders: 4, zoneId: 'z-benarous', zoneName: 'Ben Arous', zoneColor: '#059669' },

  // Manouba (amber) — west suburbs
  { zipcode: '1110', lat: 36.8100, lng: 10.0600, ordersCount: 560, delayedOrders: 11, zoneId: 'z-manouba', zoneName: 'Manouba', zoneColor: '#D97706' },
  { zipcode: '1130', lat: 36.7900, lng: 10.0200, ordersCount: 310, delayedOrders: 6, zoneId: 'z-manouba', zoneName: 'Manouba', zoneColor: '#D97706' },
  { zipcode: '1140', lat: 36.8300, lng: 10.0000, ordersCount: 140, delayedOrders: 3, zoneId: 'z-manouba', zoneName: 'Manouba', zoneColor: '#D97706' },

  // Sfax (red) — second city, industrial
  { zipcode: '3000', lat: 34.7400, lng: 10.7600, ordersCount: 1680, delayedOrders: 34, zoneId: 'z-sfax', zoneName: 'Sfax', zoneColor: '#DC2626' },
  { zipcode: '3002', lat: 34.7300, lng: 10.7400, ordersCount: 920, delayedOrders: 18, zoneId: 'z-sfax', zoneName: 'Sfax', zoneColor: '#DC2626' },
  { zipcode: '3030', lat: 34.7600, lng: 10.7900, ordersCount: 480, delayedOrders: 10, zoneId: 'z-sfax', zoneName: 'Sfax', zoneColor: '#DC2626' },
  { zipcode: '3060', lat: 34.7100, lng: 10.7200, ordersCount: 210, delayedOrders: 4, zoneId: 'z-sfax', zoneName: 'Sfax', zoneColor: '#DC2626' },

  // Sousse (cyan) — tourist coast
  { zipcode: '4000', lat: 35.8300, lng: 10.6400, ordersCount: 1100, delayedOrders: 22, zoneId: 'z-sousse', zoneName: 'Sousse', zoneColor: '#0891B2' },
  { zipcode: '4010', lat: 35.8200, lng: 10.6200, ordersCount: 620, delayedOrders: 12, zoneId: 'z-sousse', zoneName: 'Sousse', zoneColor: '#0891B2' },
  { zipcode: '4050', lat: 35.8500, lng: 10.6700, ordersCount: 290, delayedOrders: 6, zoneId: 'z-sousse', zoneName: 'Sousse', zoneColor: '#0891B2' },

  // Nabeul (emerald) — cape bon
  { zipcode: '8000', lat: 36.4600, lng: 10.6200, ordersCount: 780, delayedOrders: 16, zoneId: 'z-nabeul', zoneName: 'Nabeul', zoneColor: '#16A34A' },
  { zipcode: '8010', lat: 36.4400, lng: 10.6000, ordersCount: 350, delayedOrders: 7, zoneId: 'z-nabeul', zoneName: 'Nabeul', zoneColor: '#16A34A' },
  { zipcode: '8050', lat: 36.4800, lng: 10.6500, ordersCount: 160, delayedOrders: 3, zoneId: 'z-nabeul', zoneName: 'Nabeul', zoneColor: '#16A34A' },

  // Monastir (rose) — sahel
  { zipcode: '5000', lat: 35.7800, lng: 10.8200, ordersCount: 540, delayedOrders: 11, zoneId: 'z-monastir', zoneName: 'Monastir', zoneColor: '#E11D48' },
  { zipcode: '5010', lat: 35.7600, lng: 10.8000, ordersCount: 280, delayedOrders: 6, zoneId: 'z-monastir', zoneName: 'Monastir', zoneColor: '#E11D48' },
  { zipcode: '5050', lat: 35.8000, lng: 10.8500, ordersCount: 130, delayedOrders: 3, zoneId: 'z-monastir', zoneName: 'Monastir', zoneColor: '#E11D48' },
];

/* ── Derived constants ──────────────────────────────────────────────── */

const MAX_ORDERS = Math.max(...MOCK_POINTS.map(p => p.ordersCount));
const TOTAL_ORDERS = MOCK_POINTS.reduce((s, p) => s + p.ordersCount, 0);

/* ── Sub-components ─────────────────────────────────────────────────── */

function HeatLegend({ zones }: { zones: ZoneAgg[] }) {
  return (
    <div className="absolute bottom-3 left-3 z-10 bg-[var(--surface)]/90 backdrop-blur-sm border border-[var(--border)] rounded-lg px-3 py-2 flex flex-col gap-1">
      <span className="text-2xs font-semibold text-[var(--text-muted)] uppercase tracking-wider">Légende</span>
      {zones.slice(0, 5).map(z => (
        <div key={z.name} className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full shrink-0" style={{ background: z.color }} />
          <span className="text-2xs text-[var(--text-secondary)]">{z.name}</span>
        </div>
      ))}
      <div className="mt-1 pt-1 border-t border-[var(--border)]">
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full shrink-0 bg-white/20" />
          <span className="text-2xs text-[var(--text-muted)]">Faible</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full shrink-0 bg-white/50" />
          <span className="text-2xs text-[var(--text-muted)]">Moyen</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full shrink-0 bg-white/90" />
          <span className="text-2xs text-[var(--text-muted)]">Élevé</span>
        </div>
      </div>
    </div>
  );
}

function TopDemandPanel({ zones, total }: { zones: ZoneAgg[]; total: number }) {
  return (
    <div className="w-52 border-l border-[var(--border)] p-3 flex flex-col gap-1 overflow-y-auto shrink-0">
      <span className="text-2xs font-semibold text-[var(--text-muted)] uppercase tracking-wider mb-1">
        Top Demandes
      </span>
      {zones.map(z => {
        const pct = total > 0 ? ((z.orders / total) * 100).toFixed(1) : '0';
        return (
          <div key={z.name} className="flex items-center gap-2 py-1.5">
            <span className="text-2xs font-mono text-[var(--text-muted)] w-4 text-right">#{z.rank}</span>
            <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ background: z.color }} />
            <div className="min-w-0 flex-1">
              <div className="text-2xs font-medium text-[var(--text-primary)] truncate">{z.name}</div>
              <div className="flex items-baseline gap-1.5">
                <span className="text-2xs font-mono text-[var(--text-muted)]">{z.orders.toLocaleString()}</span>
                <span className="text-3xs text-[var(--text-muted)]">{pct}%</span>
              </div>
            </div>
            <div className="w-12 h-1 rounded-full bg-[var(--border)] overflow-hidden shrink-0">
              <div
                className="h-full rounded-full"
                style={{ width: `${total > 0 ? (z.orders / total) * 100 : 0}%`, background: z.color }}
              />
            </div>
          </div>
        );
      })}
      {zones.length === 0 && (
        <div className="text-2xs text-[var(--text-muted)] py-2">Aucune donnée</div>
      )}
    </div>
  );
}

/* ── Main component ─────────────────────────────────────────────────── */

export default function ZoneDemandMap({ kpi, heatmap }: Props) {
  const t = useT();
  const { data: zones = [] } = useZones();
  const isDark = useIsDark();
  const mapContainer = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const [, forceUpdate] = useState(0);

  const rawPoints = heatmap?.points;
  const isMock = !rawPoints || rawPoints.length === 0;
  const points = isMock ? MOCK_POINTS : rawPoints;

  const topZones = useMemo(() => {
    const byZone = new Map<string, ZoneAgg>();
    for (const p of points) {
      const existing = byZone.get(p.zoneId);
      if (existing) {
        existing.orders += p.ordersCount;
        existing.zipcodes += 1;
      } else {
        byZone.set(p.zoneId, { name: p.zoneName, color: p.zoneColor, orders: p.ordersCount, zipcodes: 1, rank: 0 });
      }
    }
    const sorted = Array.from(byZone.values()).sort((a, b) => b.orders - a.orders);
    sorted.forEach((z, i) => { z.rank = i + 1; });
    return sorted;
  }, [points]);

  const zoneGeoJSON = useMemo((): GeoJSON.FeatureCollection => {
    const realFeatures = zones
      .filter((z: Zone) => z.isActive && parseGeometry(z.geometry))
      .map((z: Zone) => {
        const feature = parseGeometry(z.geometry)!;
        feature.properties = { ...feature.properties, name: z.name, color: z.color || '#2563EB', id: z.id };
        return feature;
      });
    return realFeatures.length > 0 ? { type: 'FeatureCollection', features: realFeatures } : MOCK_GEOJSON;
  }, [zones]);

  const pointsGeoJSON = useMemo((): GeoJSON.FeatureCollection => ({
    type: 'FeatureCollection',
    features: points.map(p => ({
      type: 'Feature',
      geometry: { type: 'Point' as const, coordinates: [p.lng, p.lat] },
      properties: {
        ordersCount: p.ordersCount,
        delayedOrders: p.delayedOrders,
        zoneName: p.zoneName,
        zoneColor: p.zoneColor,
        zipcode: p.zipcode,
        zoneId: p.zoneId,
        intensity: p.ordersCount / MAX_ORDERS,
      },
    })),
  }), [points]);

  const handleMapLoad = useCallback((map: maplibregl.Map) => {
    map.addSource('zones', { type: 'geojson', data: zoneGeoJSON });
    map.addSource('heatpoints', { type: 'geojson', data: pointsGeoJSON });

    // Zone fill
    map.addLayer({
      id: 'zones-fill',
      type: 'fill',
      source: 'zones',
      paint: {
        'fill-color': ['get', 'color'],
        'fill-opacity': 0.06,
      },
    });

    // Zone outline
    map.addLayer({
      id: 'zones-outline',
      type: 'line',
      source: 'zones',
      paint: {
        'line-color': ['get', 'color'],
        'line-opacity': 0.4,
        'line-width': 1.5,
      },
    });

    // Glow layer — large, blurred, zone-colored
    map.addLayer({
      id: 'glow-circles',
      type: 'circle',
      source: 'heatpoints',
      paint: {
        'circle-radius': [
          'interpolate', ['linear'], ['get', 'ordersCount'],
          100, 16, 500, 32, 1000, 48, 2500, 64,
        ],
        'circle-color': ['get', 'zoneColor'],
        'circle-opacity': [
          'interpolate', ['linear'], ['get', 'intensity'],
          0, 0.08, 0.5, 0.15, 1, 0.25,
        ],
        'circle-blur': 1.2,
      },
    });

    // Core circle — zone-colored with intensity-based opacity
    map.addLayer({
      id: 'heat-circles',
      type: 'circle',
      source: 'heatpoints',
      paint: {
        'circle-radius': [
          'interpolate', ['linear'], ['get', 'ordersCount'],
          100, 6, 500, 12, 1000, 18, 2500, 26,
        ],
        'circle-color': ['get', 'zoneColor'],
        'circle-opacity': [
          'interpolate', ['linear'], ['get', 'intensity'],
          0, 0.55, 0.5, 0.75, 1, 1,
        ],
        'circle-stroke-color': 'rgba(255,255,255,0.3)',
        'circle-stroke-width': 1,
      },
    });

    // Inner bright dot — white center for high-volume
    map.addLayer({
      id: 'hotspot-dot',
      type: 'circle',
      source: 'heatpoints',
      filter: ['>', ['get', 'ordersCount'], 800],
      paint: {
        'circle-radius': 3,
        'circle-color': 'rgba(255,255,255,0.85)',
        'circle-stroke-color': 'rgba(255,255,255,0.4)',
        'circle-stroke-width': 0.5,
      },
    });

    // Tooltip
    const popup = new maplibregl.Popup({
      closeButton: false,
      closeOnClick: false,
      offset: 14,
      maxWidth: '260px',
    });

    const zoneStats = new Map<string, { total: number; rank: number }>();
    for (const z of topZones) {
      zoneStats.set(z.name, { total: z.orders, rank: z.rank });
    }

    map.on('mouseenter', 'heat-circles', (e) => {
      map.getCanvas().style.cursor = 'pointer';
      const f = e.features?.[0];
      if (!f) return;
      const p = f.properties as Record<string, string | number>;
      const pct = TOTAL_ORDERS > 0 ? (((p.ordersCount as number) / TOTAL_ORDERS) * 100).toFixed(1) : '0';
      const zStat = zoneStats.get(p.zoneName as string);

      popup
        .setHTML(`
          <div style="padding:8px 10px;font-size:12px;line-height:1.5;font-family:inherit">
            <div style="display:flex;align-items:center;gap:6px;margin-bottom:4px">
              <span style="width:8px;height:8px;border-radius:50%;background:${p.zoneColor};flex-shrink:0"></span>
              <span style="font-weight:600;color:var(--text-primary,#e8e9ed)">${p.zoneName}</span>
            </div>
            <div style="color:var(--text-muted,#6b7082);font-size:11px">
              <div>Code postal: <span style="font-family:monospace;color:var(--text-secondary,#a1a5b0)">${p.zipcode}</span></div>
              <div>Commandes: <span style="font-family:monospace;color:var(--text-secondary,#a1a5b0)">${(p.ordersCount as number).toLocaleString()}</span></div>
              <div>% du total: <span style="font-family:monospace;color:var(--text-secondary,#a1a5b0)">${pct}%</span></div>
              ${zStat ? `<div>Rang zone: <span style="font-family:monospace;color:var(--text-secondary,#a1a5b0)">#${zStat.rank}</span></div>` : ''}
            </div>
          </div>
        `)
        .setLngLat((f.geometry as GeoJSON.Point).coordinates as [number, number])
        .addTo(map);
    });

    map.on('mouseleave', 'heat-circles', () => {
      map.getCanvas().style.cursor = '';
      popup.remove();
    });
  }, [zoneGeoJSON, pointsGeoJSON, topZones]);

  useEffect(() => {
    if (!mapContainer.current || mapRef.current) return;

    const el = mapContainer.current;
    if (el.clientWidth === 0 || el.clientHeight === 0) return;

    const map = new maplibregl.Map({
      container: el,
      style: {
        version: 8,
        sources: {
          osm: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: '&copy; OpenStreetMap',
          },
        },
        layers: [{ id: 'osm', type: 'raster', source: 'osm', minzoom: 0, maxzoom: 19 }],
      },
      center: [10.2, 35.6],
      zoom: 6.2,
      attributionControl: false,
    });

    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    map.on('error', (e) => console.error('[ZoneDemandMap]', e.error));
    map.on('load', () => handleMapLoad(map));

    const ro = new ResizeObserver(() => { map.resize(); });
    ro.observe(el);
    mapRef.current = map;

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, [isDark, handleMapLoad]);

  return (
    <div className="border border-[var(--border)] rounded-lg overflow-hidden flex flex-col" style={{ height: '100%' }}>
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconMapPinFilled size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">
          {t.performancePage.densityByZone}
        </span>
        {isMock && (
          <span className="ms-auto text-2xs text-[var(--text-muted)] italic">simulé</span>
        )}
      </div>

      <div className="flex-1 min-h-0 flex relative">
        <div ref={mapContainer} className="flex-1" />
        <HeatLegend zones={topZones} />

        <TopDemandPanel zones={topZones} total={TOTAL_ORDERS} />
      </div>
    </div>
  );
}
