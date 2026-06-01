import type { OpsException } from './types';
import { getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';

export function formatMotif(motif?: string, copy?: any): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const key = (motif ?? '').toUpperCase().trim();
  if (key === 'SLA_UNSCHEDULED' || key.includes('SLA_UNSCHEDULED')) return resolvedCopy.dispatchDeskPage.motifSlaUnscheduled;
  if (key === 'SLA_SCHEDULED'   || key.includes('SLA_SCHEDULED'))   return resolvedCopy.dispatchDeskPage.motifSlaScheduled;
  if (key === 'SLA_PICKUP'      || key.includes('SLA_PICKUP'))      return resolvedCopy.dispatchDeskPage.motifSlaPickup;
  if (key === 'SLA_IN_TRANSIT'  || key.includes('SLA_IN_TRANSIT'))  return resolvedCopy.dispatchDeskPage.motifSlaInTransit;
  if (key === 'SCHEDULED_MONITORING')                                return resolvedCopy.dispatchDeskPage.motifScheduledMonitoring;
  if (key === 'CLIENT_ABSENT')  return resolvedCopy.dispatchDeskPage.motifClientAbsent;
  if (key === 'REFUSED')        return resolvedCopy.dispatchDeskPage.motifRefused;
  if (key === 'WRONG_ADDRESS')  return resolvedCopy.dispatchDeskPage.motifWrongAddress;
  if (key === 'DAMAGED')        return resolvedCopy.dispatchDeskPage.motifDamaged;
  if (key === 'OTHER')          return resolvedCopy.dispatchDeskPage.motifOther;
  if (key === 'PARTIAL_DELIVERY' || key.includes('PARTIAL')) return resolvedCopy.dispatchDeskPage.motifPartialDelivery;
  if (key === 'FAILED')         return resolvedCopy.dispatchDeskPage.motifFailed;
  if (key === 'CANCELLED' || key.includes('CANCELLED'))      return resolvedCopy.dispatchDeskPage.motifCancelled;
  // Fallback: raw delivery status codes
  if (key === 'UNSCHEDULED')    return resolvedCopy.dispatchDeskPage.motifUnscheduled;
  if (key === 'SCHEDULED')      return resolvedCopy.dispatchDeskPage.motifScheduled;
  if (key === 'PICKED_UP')      return resolvedCopy.dispatchDeskPage.motifPickedUp;
  if (key === 'IN_TRANSIT')     return resolvedCopy.dispatchDeskPage.motifInTransit;
  if (key === 'DELIVERED')      return resolvedCopy.dispatchDeskPage.motifDelivered;
  if (key === 'PARTIALLY_DELIVERED') return resolvedCopy.dispatchDeskPage.motifPartiallyDelivered;
  return motif ?? resolvedCopy.dispatchDeskPage.motifUnknown;
}

export function formatElapsed(dateString?: string, copy?: any): string {
  if (!dateString) return '—';
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const diff = Math.floor((Date.now() - new Date(dateString).getTime()) / 60000);
  if (diff < 1)  return resolvedCopy.dispatchDeskPage.timeJustNow;
  if (diff < 60) return resolvedCopy.dispatchDeskPage.timeMinutes.replace('{diff}', String(diff));
  const h = Math.floor(diff / 60);
  if (h < 24)    return resolvedCopy.dispatchDeskPage.timeHours.replace('{h}', String(h)).replace('{mm}', String(diff % 60).padStart(2, '0'));
  return resolvedCopy.dispatchDeskPage.timeDays.replace('{d}', String(Math.floor(h / 24)));
}

export function formatComment(row: OpsException, copy?: any): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const comment = row.comment ?? '';
  const key = (row.motif ?? '').toUpperCase().trim();
  const t = formatElapsed(row.createdAt, resolvedCopy);
  if (key.includes('SLA_UNSCHEDULED')) return resolvedCopy.dispatchDeskPage.commentSlaUnscheduled.replace('{time}', t);
  if (key.includes('SLA_SCHEDULED'))   return resolvedCopy.dispatchDeskPage.commentSlaScheduled.replace('{time}', t);
  if (key.includes('SLA_PICKUP'))      return resolvedCopy.dispatchDeskPage.commentSlaPickup.replace('{time}', t);
  if (key.includes('SLA_IN_TRANSIT'))  return resolvedCopy.dispatchDeskPage.commentSlaInTransit.replace('{time}', t);
  if (key === 'SCHEDULED_MONITORING')  return resolvedCopy.dispatchDeskPage.commentScheduledMonitoring;
  if (key === 'CLIENT_ABSENT')  return `${resolvedCopy.dispatchDeskPage.commentClientAbsent.replace('{time}', t)}${comment ? ` · ${comment}` : ''}`;
  if (key === 'REFUSED')        return `${resolvedCopy.dispatchDeskPage.commentRefused.replace('{time}', t)}${comment ? ` · ${comment}` : ''}`;
  if (key === 'WRONG_ADDRESS')  return `${resolvedCopy.dispatchDeskPage.commentWrongAddress.replace('{time}', t)}${comment ? ` · ${comment}` : ''}`;
  if (key === 'DAMAGED')        return `${resolvedCopy.dispatchDeskPage.commentDamaged.replace('{time}', t)}${comment ? ` · ${comment}` : ''}`;
  if (key === 'OTHER')          return comment ? `${comment} · ${t}` : resolvedCopy.dispatchDeskPage.commentOther.replace('{time}', t);
  if (key.includes('PARTIAL'))  return `${resolvedCopy.dispatchDeskPage.commentPartial.replace('{time}', t)}${comment ? ` · ${comment}` : ''}`;
  if (key === 'FAILED' || key.includes('FAILED'))
    return `${resolvedCopy.dispatchDeskPage.commentFailed.replace('{time}', t)}${comment && !comment.toLowerCase().includes('sla') ? ` · ${comment}` : ''}`;
  if (key.includes('CANCELLED')) return resolvedCopy.dispatchDeskPage.commentCancelled.replace('{time}', t);
  return comment || resolvedCopy.dispatchDeskPage.commentDefault.replace('{time}', t);
}

export function formatSuggestion(row: OpsException, copy?: any): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const key = (row.motif ?? '').toUpperCase().trim();
  if (key.includes('SLA_UNSCHEDULED')) return resolvedCopy.dispatchDeskPage.suggestionSlaUnscheduled;
  if (key === 'SCHEDULED_MONITORING')  return resolvedCopy.dispatchDeskPage.suggestionScheduledMonitoring;
  if (key === 'WRONG_ADDRESS')         return resolvedCopy.dispatchDeskPage.suggestionWrongAddress;
  if (key === 'OTHER')                 return resolvedCopy.dispatchDeskPage.suggestionOther;
  return '';
}

export function needsClientContact(motif?: string): boolean {
  const k = (motif ?? '').toUpperCase().trim();
  return k === 'CLIENT_ABSENT' || k === 'REFUSED' || k === 'WRONG_ADDRESS';
}

export function needsDriverContact(motif?: string): boolean {
  const k = (motif ?? '').toUpperCase().trim();
  return k === 'DAMAGED' || k === 'OTHER' || k === 'CLIENT_ABSENT' || k === 'REFUSED';
}

export function needsReturnToDepot(motif?: string): boolean {
  return (motif ?? '').toUpperCase().trim() === 'DAMAGED';
}

export function formatShortDate(dateStr?: string): string {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleString('fr-FR', {
      day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
    });
  } catch { return dateStr.slice(0, 16); }
}
