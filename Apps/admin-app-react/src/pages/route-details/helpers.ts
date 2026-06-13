import { format } from 'date-fns';
import { getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { ProofOfDelivery, TimelineEvent } from '@/types';

export function fmt(v?: string | null) {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : format(d, 'dd/MM HH:mm');
}
export function fmtLong(v?: string | null) {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : format(d, 'dd MMM yyyy HH:mm');
}
export function fmtDuration(sec?: number | null): string {
  if (!sec) return '—';
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
export function fmtDist(m?: number | null): string {
  if (!m) return '—';
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${m} m`;
}
export function fmtTimeWindow(s?: string | null) {
  if (!s) return '—';
  return String(s).slice(0, 5);
}
export function normalizeTimeline(raw?: TimelineEvent[]): TimelineEvent[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .map((e) => ({ ...e, timestamp: e.timestamp ?? e.changedAt ?? '', actor: e.actor ?? e.changedBy }))
    .filter((e) => Boolean(e.timestamp))
    .sort((a, b) => +new Date(a.timestamp) - +new Date(b.timestamp));
}
export function cleanNote(note?: string, copy?: any): string | undefined {
  if (!note) return undefined;
  const t = note.trim();
  if (!t) return undefined;
  const half = Math.floor(t.length / 2);
  const s = t.length % 2 === 0 && t.slice(0, half) === t.slice(half) ? t.slice(0, half) : t;
  if (s.startsWith('ADMIN_ACTION:')) {
    const r = s.match(/ - (.*?) \|/)?.[1]?.trim();
    const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
    let msg: string = resolvedCopy.routeBuilderPage?.dispatchActionRecorded || resolvedCopy.routeDetailPage?.dispatchActionRecorded || 'Action de dispatch enregistrée.';
    if (s.includes('REPLAN')) msg = resolvedCopy.routeBuilderPage?.deliveryReplanned || resolvedCopy.routeDetailPage?.deliveryReplanned || 'Livraison remise en file de planification.';
    else if (s.includes('REASSIGN')) msg = resolvedCopy.routeBuilderPage?.deliveryReassigned || resolvedCopy.routeDetailPage?.deliveryReassigned || 'Livraison réaffectée à un autre chauffeur.';
    const reasonLabel = resolvedCopy.routeBuilderPage?.reasonLabel || resolvedCopy.routeDetailPage?.reason || 'Motif';
    return r ? `${msg} ${reasonLabel} ${r}` : msg;
  }
  return s;
}
export function normalizePod(raw: any): ProofOfDelivery | null {
  if (!raw || typeof raw !== 'object') return null;
  return {
    signatureBase64: raw.signatureBase64, photoBase64: raw.photoBase64,
    signatureUrl: raw.signatureUrl, photoUrl: raw.photoUrl,
    comment: raw.comment,
    timestamp: raw.timestamp ?? raw.collectedAt, collectedAt: raw.collectedAt,
    latitude: raw.latitude ?? raw.lat, longitude: raw.longitude ?? raw.lng,
    lat: raw.lat, lng: raw.lng,
  };
}
export function mediaSrc(url?: string, b64?: string): string | null {
  if (url) return url;
  if (b64) return b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`;
  return null;
}
