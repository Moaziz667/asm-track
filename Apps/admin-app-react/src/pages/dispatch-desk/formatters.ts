import type { OpsException } from './types';
import { getCopy, type CopyDict } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { formatElapsed as slaFormatElapsed, formatCountdown } from '@/lib/sla';

/**
 * SLA-derived motifs are no longer shown as their own chip — lateness is conveyed by the unified
 * SlaHealthBadge (single source of truth). This identifies them so rows can render the badge instead.
 */
export function isSlaMotif(motif?: string): boolean {
  const key = (motif ?? '').toUpperCase().trim();
  return key.startsWith('SLA_') || key === 'SCHEDULED_MONITORING';
}

export function formatMotif(motif?: string, copy?: CopyDict): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const key = (motif ?? '').toUpperCase().trim();
  // SLA-derived motifs → no chip (the SlaHealthBadge carries lateness). Keep genuine exceptions below.
  if (isSlaMotif(key)) return '';
  if (key === 'CLIENT_ABSENT')  return resolvedCopy.dispatchDeskPage.motifClientAbsent;
  if (key === 'REFUSED')        return resolvedCopy.dispatchDeskPage.motifRefused;
  if (key === 'WRONG_ADDRESS')  return resolvedCopy.dispatchDeskPage.motifWrongAddress;
  if (key === 'DAMAGED')        return resolvedCopy.dispatchDeskPage.motifDamaged;
  if (key === 'MISSING')        return resolvedCopy.dispatchDeskPage.motifMissing;
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

export function formatElapsed(dateString?: string, _copy?: CopyDict): string {
  const locale = useLocaleStore.getState().locale || 'fr';
  return slaFormatElapsed(dateString, locale);
}

export function formatComment(row: OpsException, copy?: CopyDict): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const c = resolvedCopy.dispatchDeskPage || {};
  const comment = row.comment ?? '';
  const key = (row.motif ?? '').toUpperCase().trim();
  // SLA motifs use scheduledAt (ERP delivery promise), not createdAt (import time)
  const scheduleRef = row.scheduledAt || row.createdAt;
  const t = formatElapsed(scheduleRef, resolvedCopy);
  if (key === 'SLA_UNSCHEDULED_LATE')  return comment || (c.commentSlaUnscheduledLate || 'Late since {time}').replace('{time}', t);
  if (key === 'SLA_UNSCHEDULED_TODAY') return comment || c.commentSlaUnscheduledToday || 'Scheduled today';
  if (key === 'SLA_UNSCHEDULED')       return (c.commentSlaUnscheduled || 'Waiting since {time}').replace('{time}', t);
  if (key.includes('SLA_SCHEDULED'))   return (c.commentSlaScheduled || 'Scheduled since {time}').replace('{time}', t);
  if (key.includes('SLA_PICKUP'))      return (c.commentSlaPickup || 'Pickup pending since {time}').replace('{time}', t);
  if (key.includes('SLA_IN_TRANSIT'))  return (c.commentSlaInTransit || 'In transit since {time}').replace('{time}', t);
  if (key === 'SCHEDULED_MONITORING')  return c.commentScheduledMonitoring || 'Monitoring scheduled route';
  if (key === 'CLIENT_ABSENT')  return `${c.commentClientAbsent ? c.commentClientAbsent.replace('{time}', t) : `Absent (${t})`}${comment ? ` · ${comment}` : ''}`;
  if (key === 'REFUSED')        return `${c.commentRefused ? c.commentRefused.replace('{time}', t) : `Refused (${t})`}${comment ? ` · ${comment}` : ''}`;
  if (key === 'WRONG_ADDRESS')  return `${c.commentWrongAddress ? c.commentWrongAddress.replace('{time}', t) : `Incorrect address (${t})`}${comment ? ` · ${comment}` : ''}`;
  if (key === 'DAMAGED')        return `${c.commentDamaged ? c.commentDamaged.replace('{time}', t) : `Damaged (${t})`}${comment ? ` · ${comment}` : ''}`;
  if (key === 'OTHER')          return comment ? `${comment} · ${t}` : (c.commentOther || 'Incident ({time})').replace('{time}', t);
  if (key.includes('PARTIAL'))  return `${c.commentPartial ? c.commentPartial.replace('{time}', t) : `Partial (${t})`}${comment ? ` · ${comment}` : ''}`;
  if (key === 'FAILED' || key.includes('FAILED'))
    return `${c.commentFailed ? c.commentFailed.replace('{time}', t) : `Failed (${t})`}${comment && !comment.toLowerCase().includes('sla') ? ` · ${comment}` : ''}`;
  if (key.includes('CANCELLED')) return (c.commentCancelled || 'Cancelled ({time})').replace('{time}', t);
  return comment || (c.commentDefault || 'Event ({time})').replace('{time}', t);
}

export function formatSuggestion(row: OpsException, copy?: CopyDict): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const key = (row.motif ?? '').toUpperCase().trim();
  if (key === 'SLA_UNSCHEDULED_LATE') return resolvedCopy.dispatchDeskPage.suggestionSlaUnscheduledLate;
  if (key === 'SLA_UNSCHEDULED_TODAY') return resolvedCopy.dispatchDeskPage.suggestionSlaUnscheduledToday;
  if (key.includes('SLA_UNSCHEDULED')) return resolvedCopy.dispatchDeskPage.suggestionSlaUnscheduled;
  if (key === 'SCHEDULED_MONITORING')  return resolvedCopy.dispatchDeskPage.suggestionScheduledMonitoring;
  if (key === 'WRONG_ADDRESS')         return resolvedCopy.dispatchDeskPage.suggestionWrongAddress;
  if (key === 'OTHER')                 return resolvedCopy.dispatchDeskPage.suggestionOther;
  return '';
}

/** One flowing sentence describing the situation. For SLA motifs uses scheduledAt (delivery promise),
 *  not createdAt (import time), so "overdue by X" reflects reality, not time-since-import. */
export function formatNarrative(row: OpsException, copy?: CopyDict): string {
  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const locale = useLocaleStore.getState().locale || 'fr';
  const c = resolvedCopy.dispatchDeskPage || {};
  const key = (row.motif ?? '').toUpperCase().trim();
  // For SLA motifs: time relative to scheduledAt (ERP promise), fallback to createdAt
  const slaTime = formatCountdown(row.scheduledAt || row.createdAt, locale);
  const elapsed = formatElapsed(row.createdAt, resolvedCopy);
  if (key === 'SLA_UNSCHEDULED_LATE')  return (c.narrativeUnscheduledLate || 'Order is overdue by {time} — plan urgently').replace('{time}', slaTime);
  if (key === 'SLA_UNSCHEDULED_TODAY') return c.narrativeUnscheduledToday || 'Scheduled for today — assign now';
  // SLA_WAITING (real-time) is the monitoring twin of SLA_UNSCHEDULED, and raw
  // UNSCHEDULED is what the analytics endpoint emits for a not-yet-late order.
  // All three mean the same thing to a dispatcher: no route yet. With a date we
  // show the countdown to the promise; without one, how long it has been waiting.
  if (key === 'SLA_UNSCHEDULED' || key === 'SLA_WAITING' || key === 'UNSCHEDULED')
    return row.scheduledAt
      ? (c.narrativeUnscheduled || 'No route yet · due {time}.').replace('{time}', slaTime)
      : (c.narrativeUnscheduledNoDate || 'No route yet · waiting · {time}.').replace('{time}', elapsed);
  if (key.includes('SLA_SCHEDULED'))   return (c.narrativeScheduled || 'Scheduled (approaching deadline: {time})').replace('{time}', slaTime);
  if (key.includes('SLA_PICKUP'))      return (c.narrativePickup || 'Not picked up yet (waiting since {time})').replace('{time}', elapsed);
  if (key.includes('SLA_IN_TRANSIT'))  return (c.narrativeInTransit || 'In transit since {time}').replace('{time}', elapsed);
  // Real-time monitoring motifs (SlaMonitoringService) — semantic twins of the
  // batch analytics motifs above, so they reuse the same schedule-anchored copy.
  // (SLA_WAITING is handled alongside SLA_UNSCHEDULED above.)
  if (key === 'SLA_ASSIGNMENT') return (c.narrativeScheduled || 'Scheduled (approaching deadline: {time})').replace('{time}', slaTime);
  if (key === 'SLA_TRANSIT')    return (c.narrativeInTransit || 'In transit since {time}').replace('{time}', elapsed);
  if (key === 'SCHEDULED_MONITORING')  return c.narrativeScheduledMonitoring || 'Proceeding normally';
  if (key === 'CLIENT_ABSENT')         return (c.narrativeClientAbsent || 'Client absent ({time})').replace('{time}', elapsed);
  if (key === 'REFUSED')               return (c.narrativeRefused || 'Refused ({time})').replace('{time}', elapsed);
  if (key === 'WRONG_ADDRESS')         return c.narrativeWrongAddress || 'Incorrect address';
  if (key === 'DAMAGED')               return c.narrativeDamaged || 'Damaged';
  if (key.includes('PARTIAL'))         return c.narrativePartial || 'Partial delivery';
  if (key === 'FAILED' || key.includes('FAILED')) return (c.narrativeFailed || 'Failed ({time})').replace('{time}', elapsed);
  if (key.includes('CANCELLED'))       return c.narrativeCancelled || 'Cancelled';
  if (key === 'OTHER')                 return row.comment || (c.narrativeOther || 'Incident ({time})').replace('{time}', elapsed);
  // Fallback: raw status codes (SCHEDULED, PICKED_UP, IN_TRANSIT, …) and anything
  // unrecognized resolve to their calm localized label rather than an alarmist
  // "an event was reported" sentence, which read as noise on benign states.
  return formatMotif(key, resolvedCopy);
}

export function needsClientContact(motif?: string): boolean {
  const k = (motif ?? '').toUpperCase().trim();
  return k === 'CLIENT_ABSENT' || k === 'REFUSED' || k === 'WRONG_ADDRESS';
}

export function needsDriverContact(motif?: string): boolean {
  const k = (motif ?? '').toUpperCase().trim();
  return k === 'DAMAGED' || k === 'OTHER' || k === 'CLIENT_ABSENT' || k === 'REFUSED';
}

export function formatShortDate(dateStr?: string): string {
  if (!dateStr) return '—';
  try {
    const locale = useLocaleStore.getState().locale || 'fr';
    return new Date(dateStr).toLocaleString(locale === 'ar' ? 'ar-EG' : locale === 'en' ? 'en-US' : 'fr-FR', {
      day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
    });
  } catch { return dateStr.slice(0, 16); }
}
