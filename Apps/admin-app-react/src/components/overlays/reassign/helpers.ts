import { api } from '@/lib/api';
import type { Stop, RouteData } from './types';

// ── Status sets ─────────────────────────────────────────────────────────────
export const DONE = new Set(['COMPLETED', 'FAILED', 'PARTIAL', 'FAILED_ATTEMPT']);
export const REMOVED = new Set(['REMOVED_CANCELLED', 'REMOVED_REPLANNED']);
export const CURRENT = new Set(['PICKED_UP', 'IN_TRANSIT', 'ARRIVED']);
export const ACTIVE_ROUTE = new Set(['IN_PROGRESS', 'VALIDATED']);

// ── Pure formatters ─────────────────────────────────────────────────────────
export const fmtEta = (s?: number | null) => s == null ? null : s < 60 ? '<1 min' : s < 3600 ? `${Math.round(s / 60)} min` : `${Math.floor(s / 3600)}h${String(Math.round((s % 3600) / 60)).padStart(2, '0')}`;
export const fmtKm = (m?: number | null) => m == null ? null : m < 1000 ? `${m} m` : `${(m / 1000).toFixed(1)} km`;
export const haversineKm = (a: number, b: number, c: number, d: number) => {
  const R = 6371, dLat = (c - a) * Math.PI / 180, dLng = (d - b) * Math.PI / 180;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a * Math.PI / 180) * Math.cos(c * Math.PI / 180) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
};
export const fmtSeen = (iso?: string | null): { text: string; fresh: boolean } | null => {
  if (!iso) return null;
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (mins < 1) return { text: 'MAJ à l’instant', fresh: true };
  if (mins < 60) return { text: `${mins <= 5 ? 'MAJ' : 'GPS'} ${mins} min`, fresh: mins <= 5 };
  return { text: `GPS ${Math.floor(mins / 60)} h`, fresh: false };
};
export const hhmm = (t?: string | null) => (t ? t.slice(0, 5) : '');
export const toMin = (t?: string | null) => { const v = t ? t.slice(0, 5) : ''; if (!v) return null; const [h, m] = v.split(':').map(Number); return h * 60 + m; };
export const winLabel = (s?: string, e?: string) => { const a = hhmm(s), b = hhmm(e); return a && b ? `${a}–${b}` : a ? `dès ${a}` : b ? `→ ${b}` : null; };
export const toLocalTime = (t: string) => (t.trim().length === 5 ? `${t.trim()}:00` : t.trim());

/** Insert a pseudo-stop at `order`, shifting real stops ≥ order down by one — mirrors how the backend
 *  `moveStopToDriverRoute` shifts stops on insert. Used to build the progressive working list so each
 *  batch delivery is conflict-checked against the siblings already placed before it. */
export function insertPseudo(stops: Stop[], order: number, pseudo: Stop): Stop[] {
  const shifted = stops.map(s => (s.stopOrder >= order ? { ...s, stopOrder: s.stopOrder + 1 } : s));
  return [...shifted, { ...pseudo, stopOrder: order }].sort((a, b) => a.stopOrder - b.stopOrder);
}

/** Client mirror of the backend insertion (ADR-028): window-ordered slot in the pending tail plus the
 *  conflict (overlap prev/next, or window before a completed stop). Returns the 1-based stopOrder. */
export function predictSlot(realStops: Stop[], startMin: number | null, endMin: number | null): { order: number; conflict: 'prev' | 'next' | 'past' | null } {
  let floorOrder = 1;
  for (const s of realStops) if (DONE.has(s.status)) floorOrder = s.stopOrder + 1;
  let order = realStops.length ? realStops[realStops.length - 1].stopOrder + 1 : 1;
  if (startMin != null) {
    for (const s of realStops) {
      if (s.stopOrder < floorOrder) continue;
      const sMin = toMin(s.startTimeWindow);
      if (sMin == null) continue;
      const eMin = toMin(s.endTimeWindow);
      if (sMin > startMin || (sMin === startMin && eMin != null && endMin != null && eMin > endMin)) { order = s.stopOrder; break; }
    }
  }
  if (order < floorOrder) order = floorOrder;
  const prev = [...realStops].reverse().find(s => s.stopOrder < order);
  const next = realStops.find(s => s.stopOrder >= order);
  if (floorOrder > 1 && endMin != null) {
    const lastDone = realStops.filter(s => DONE.has(s.status)).slice(-1)[0];
    const dref = toMin(lastDone?.endTimeWindow) ?? toMin(lastDone?.startTimeWindow);
    if (dref != null && endMin < dref) return { order, conflict: 'past' };
  }
  if (prev && startMin != null) { const pe = toMin(prev.endTimeWindow); if (pe != null && startMin < pe) return { order, conflict: 'prev' }; }
  if (next && endMin != null) { const ns = toMin(next.startTimeWindow); if (ns != null && endMin > ns) return { order, conflict: 'next' }; }
  return { order, conflict: null };
}

/** Conflict for placing a stop at `order` with the given window — unified for the auto-placed and the
 *  manually-moved slot. Returns the kind AND the neighbour time, so the warning can name it. */
export function conflictAt(stops: Stop[], order: number, startMin: number | null, endMin: number | null): { kind: 'prev' | 'next' | 'past' | null; time: string | null } {
  let floorOrder = 1;
  for (const s of stops) if (DONE.has(s.status)) floorOrder = s.stopOrder + 1;
  if (floorOrder > 1 && endMin != null) {
    const lastDone = stops.filter(s => DONE.has(s.status)).slice(-1)[0];
    const dref = toMin(lastDone?.endTimeWindow) ?? toMin(lastDone?.startTimeWindow);
    if (dref != null && endMin < dref) return { kind: 'past', time: null };
  }
  const prev = [...stops].reverse().find(s => s.stopOrder < order);
  const next = stops.find(s => s.stopOrder >= order);
  if (prev && startMin != null) { const pe = toMin(prev.endTimeWindow); if (pe != null && startMin < pe) return { kind: 'prev', time: hhmm(prev.endTimeWindow) }; }
  if (next && endMin != null) { const ns = toMin(next.startTimeWindow); if (ns != null && endMin > ns) return { kind: 'next', time: hhmm(next.startTimeWindow) }; }
  return { kind: null, time: null };
}

/** The driver's most relevant executable route today: prefer IN_PROGRESS, then VALIDATED, then any
 *  live draft — CLOSED/CANCELLED excluded. Only IN_PROGRESS/VALIDATED count as an "active route". */
export const loadDriverRoute = async (driverId: string): Promise<RouteData | null> => {
  const from = new Date().toISOString().slice(0, 10);
  const to = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  const [up, run] = await Promise.all([
    api.get(`/admin/routes/driver/${driverId}`, { params: { from, to } }).catch(() => ({ data: [] })),
    api.get('/admin/routes', { params: { driverId, status: 'IN_PROGRESS' } }).catch(() => ({ data: [] })),
  ]);
  const byId = new Map<string, RouteData>();
  [...(Array.isArray(up.data) ? up.data : []), ...(Array.isArray(run.data) ? run.data : [])].forEach((r: RouteData) => byId.set(r.id, r));
  const rank = (s: string) => (s === 'IN_PROGRESS' ? 0 : s === 'VALIDATED' ? 1 : 2);
  return Array.from(byId.values())
    .filter(r => r.status !== 'CLOSED' && r.status !== 'CANCELLED')
    .sort((a, b) => rank(a.status) - rank(b.status))[0] ?? null;
};
