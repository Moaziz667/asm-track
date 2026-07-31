import { useMemo, useState } from 'react';
import { IconMapPinFilled } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { useZones } from '@/hooks/useZones';
import { useIsDark } from '@/lib/ui/theme';
import type { Zone } from '@/types';

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

type Props = {
  kpi: { ordersByZone?: Record<string, number> } | null;
  heatmap?: { points?: HeatmapPoint[] };
};

type Ring = [number, number][];

function parseGeometry(geo?: string): Ring | null {
  if (!geo) return null;
  try {
    const obj = JSON.parse(geo);
    if (obj.type === 'Polygon' && Array.isArray(obj.coordinates?.[0]))
      return obj.coordinates[0];
    if (obj.type === 'MultiPolygon' && Array.isArray(obj.coordinates?.[0]?.[0]))
      return obj.coordinates[0][0];
    if (Array.isArray(obj) && typeof obj[0]?.lat === 'number')
      return obj.map((p: { lat: number; lng: number }) => [p.lng, p.lat] as [number, number]);
  } catch { /* ignore */ }
  return null;
}

function projectPoint(lng: number, lat: number, bounds: { minLng: number; maxLng: number; minLat: number; maxLat: number }, w: number, h: number, pad = 30): [number, number] {
  const { minLng, maxLng, minLat, maxLat } = bounds;
  const lngR = maxLng - minLng || 1;
  const latR = maxLat - minLat || 1;
  return [
    pad + ((lng - minLng) / lngR) * (w - 2 * pad),
    h - pad - ((lat - minLat) / latR) * (h - 2 * pad),
  ];
}

function projectRing(coords: Ring, bounds: { minLng: number; maxLng: number; minLat: number; maxLat: number }, w: number, h: number, pad = 30): [number, number][] {
  return coords.map(([lng, lat]) => projectPoint(lng, lat, bounds, w, h, pad));
}

function toPath(pts: [number, number][]): string {
  return pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ') + ' Z';
}

function centroid(pts: [number, number][]): [number, number] {
  return [pts.reduce((s, p) => s + p[0], 0) / pts.length, pts.reduce((s, p) => s + p[1], 0) / pts.length];
}

export default function ZoneDensityMapWidget({ kpi, heatmap }: Props) {
  const t = useT();
  const { data: zones = [] } = useZones();
  const isDark = useIsDark();
  const [hovered, setHovered] = useState<string | null>(null);
  const [hoveredPoint, setHoveredPoint] = useState<HeatmapPoint | null>(null);
  const [tooltipPos, setTooltipPos] = useState<{ x: number; y: number } | null>(null);

  const W = 400, H = 500;
  const points = heatmap?.points ?? [];
  const totalOrders = points.reduce((s, p) => s + p.ordersCount, 0);
  const maxOrders = Math.max(...points.map(p => p.ordersCount), 1);

  // Zone polygons from real geometry or fallback
  const useMock = zones.filter((z: Zone) => z.isActive && parseGeometry(z.geometry)).length === 0;

  const MOCK_ZONES: { name: string; color: string; ring: Ring }[] = [
    { name: 'Grand Tunis', color: '#5E6AD2', ring: [[10.05,36.72],[10.18,36.78],[10.32,36.83],[10.22,36.89],[10.08,36.85],[9.95,36.8],[10.05,36.72]] },
    { name: 'Ariana', color: '#C7372F', ring: [[10.12,36.86],[10.22,36.89],[10.28,36.95],[10.18,36.98],[10.08,36.93],[10.12,36.86]] },
    { name: 'Ben Arous', color: '#4CAF82', ring: [[10.18,36.68],[10.32,36.72],[10.38,36.78],[10.28,36.83],[10.15,36.78],[10.18,36.68]] },
    { name: 'Manouba', color: '#7B6FCC', ring: [[10.05,36.78],[10.12,36.82],[10.08,36.88],[9.98,36.85],[9.95,36.8],[10.05,36.78]] },
    { name: 'Sfax', color: '#C4881A', ring: [[10.68,34.68],[10.88,34.72],[10.95,34.82],[10.78,34.88],[10.62,34.82],[10.68,34.68]] },
    { name: 'Sousse', color: '#2594B8', ring: [[10.58,35.78],[10.72,35.82],[10.78,35.9],[10.65,35.95],[10.52,35.88],[10.58,35.78]] },
    { name: 'Monastir', color: '#D45E8B', ring: [[10.72,35.72],[10.82,35.75],[10.85,35.82],[10.75,35.85],[10.68,35.78],[10.72,35.72]] },
    { name: 'Nabeul', color: '#8CB83E', ring: [[10.55,36.42],[10.72,36.48],[10.78,36.55],[10.62,36.6],[10.48,36.52],[10.55,36.42]] },
  ];

  const zonePolygons = useMemo(() => {
    if (useMock) {
      return MOCK_ZONES.map(m => ({
        name: m.name,
        color: m.color,
        ring: m.ring,
        count: points.filter(p => p.zoneName === m.name).reduce((s, p) => s + p.ordersCount, 0),
      }));
    }
    return zones
      .filter((z: Zone) => z.isActive && parseGeometry(z.geometry))
      .map((z: Zone) => ({
        name: z.name,
        color: z.color || '#5E6AD2',
        ring: parseGeometry(z.geometry)!,
        count: points.filter(p => p.zoneId === z.id).reduce((s, p) => s + p.ordersCount, 0),
      }));
  }, [zones, points, useMock]);

  const bounds = useMemo(() => {
    let minLng = Infinity, maxLng = -Infinity, minLat = Infinity, maxLat = -Infinity;
    for (const { ring } of zonePolygons) {
      for (const [lng, lat] of ring) {
        if (lng < minLng) minLng = lng;
        if (lng > maxLng) maxLng = lng;
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
      }
    }
    // Include heatmap points in bounds
    for (const p of points) {
      if (p.lng < minLng) minLng = p.lng;
      if (p.lng > maxLng) maxLng = p.lng;
      if (p.lat < minLat) minLat = p.lat;
      if (p.lat > maxLat) maxLat = p.lat;
    }
    // Padding
    const lngPad = (maxLng - minLng) * 0.05 || 0.1;
    const latPad = (maxLat - minLat) * 0.05 || 0.1;
    return { minLng: minLng - lngPad, maxLng: maxLng + lngPad, minLat: minLat - latPad, maxLat: maxLat + latPad };
  }, [zonePolygons, points]);

  const projectedZones = useMemo(() => {
    return zonePolygons.map(z => {
      const pts = projectRing(z.ring, bounds, W, H);
      return { ...z, path: toPath(pts), center: centroid(pts) };
    });
  }, [zonePolygons, bounds]);

  const projectedPoints = useMemo(() => {
    return points.map(p => {
      const [x, y] = projectPoint(p.lng, p.lat, bounds, W, H);
      const intensity = maxOrders > 0 ? p.ordersCount / maxOrders : 0;
      const radius = 4 + intensity * 14;
      return { ...p, x, y, radius, intensity };
    });
  }, [points, bounds, maxOrders]);

  const mutedColor = isDark ? '#8D99A8' : '#5F6B7A';

  function zoneFill(color: string, count: number): string {
    if (count === 0) return isDark ? 'rgba(27,37,48,0.3)' : 'rgba(244,244,244,0.5)';
    const hex = color.replace('#', '');
    const r = parseInt(hex.substring(0, 2), 16);
    const g = parseInt(hex.substring(2, 4), 16);
    const b = parseInt(hex.substring(4, 6), 16);
    const t = Math.min(count / (totalOrders || 1), 1);
    return `rgba(${r},${g},${b},${0.12 + t * 0.25})`;
  }

  function heatColor(color: string, intensity: number): string {
    const hex = color.replace('#', '');
    const r = parseInt(hex.substring(0, 2), 16);
    const g = parseInt(hex.substring(2, 4), 16);
    const b = parseInt(hex.substring(4, 6), 16);
    return `rgba(${r},${g},${b},${0.35 + intensity * 0.55})`;
  }

  return (
    <div className="border border-[var(--border)] rounded-lg overflow-hidden flex flex-col h-full relative">
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconMapPinFilled size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">
          {t.performancePage.densityByZone}
        </span>
      </div>
      <div className="flex-1 min-h-0 flex items-center justify-center p-2">
        <svg viewBox={`0 0 ${W} ${H}`} className="h-full w-full max-h-full" style={{ maxWidth: '100%' }}>
          <defs>
            <filter id="heat-glow">
              <feGaussianBlur stdDeviation="2" result="blur" />
              <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
            </filter>
          </defs>

          {/* Zone polygons */}
          {projectedZones.map(({ name, color, path, center, count }) => {
            const isZoneHovered = hovered === name;
            return (
              <g key={name}>
                <path
                  d={path}
                  fill={zoneFill(color, count)}
                  stroke={isZoneHovered ? color : isDark ? '#2B3640' : '#E9EBED'}
                  strokeWidth={isZoneHovered ? 1.5 : 0.8}
                  style={{ transition: 'all 0.15s ease-out' }}
                  onMouseEnter={() => setHovered(name)}
                  onMouseLeave={() => setHovered(null)}
                />
                {count > 0 && (
                  <text
                    x={center[0]} y={center[1]}
                    textAnchor="middle" dominantBaseline="central"
                    fill={mutedColor} fontSize="9" fontWeight="600"
                    fontFamily="Open Sans, sans-serif"
                    style={{ pointerEvents: 'none', opacity: 0.8 }}
                  >{name}</text>
                )}
              </g>
            );
          })}

          {/* Heatmap points */}
          {projectedPoints.map((p) => (
            <circle
              key={p.zipcode}
              cx={p.x} cy={p.y} r={p.radius}
              fill={heatColor(p.zoneColor, p.intensity)}
              stroke={p.zoneColor}
              strokeWidth={hoveredPoint?.zipcode === p.zipcode ? 1.5 : 0.5}
              filter={p.intensity > 0.5 ? 'url(#heat-glow)' : undefined}
              style={{ transition: 'all 0.15s ease-out', cursor: 'pointer' }}
              onMouseEnter={(e) => {
                setHoveredPoint(p);
                setTooltipPos({ x: e.clientX, y: e.clientY });
              }}
              onMouseMove={(e) => setTooltipPos({ x: e.clientX, y: e.clientY })}
              onMouseLeave={() => { setHoveredPoint(null); setTooltipPos(null); }}
            />
          ))}
        </svg>
      </div>

      {/* Tooltip */}
      {hoveredPoint && tooltipPos && (
        <div
          className="fixed z-50 pointer-events-none px-3 py-2 rounded-lg border shadow-lg text-left"
          style={{
            left: tooltipPos.x + 12,
            top: tooltipPos.y - 10,
            background: 'var(--surface)',
            borderColor: 'var(--border)',
            minWidth: 160,
          }}
        >
          <div className="text-xs font-mono font-semibold text-[var(--text-primary)]">{hoveredPoint.zipcode}</div>
          <div className="text-2xs text-[var(--text-muted)]">{hoveredPoint.zoneName}</div>
          <div className="mt-1 flex items-center gap-2">
            <span className="w-2 h-2 rounded-full shrink-0" style={{ background: hoveredPoint.zoneColor }} />
            <span className="text-2xs font-mono text-[var(--text-primary)]">{hoveredPoint.ordersCount.toLocaleString()} commandes</span>
          </div>
          {hoveredPoint.delayedOrders > 0 && (
            <div className="text-2xs font-mono" style={{ color: 'var(--danger)' }}>
              {hoveredPoint.delayedOrders} en retard
            </div>
          )}
          {totalOrders > 0 && (
            <div className="text-2xs text-[var(--text-soft)]">
              {((hoveredPoint.ordersCount / totalOrders) * 100).toFixed(1)}% du total
            </div>
          )}
        </div>
      )}
    </div>
  );
}
