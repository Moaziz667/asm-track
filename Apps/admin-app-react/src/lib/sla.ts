export type ScheduleSignal = 'late' | 'soon' | 'ok' | 'none';
export type ExceptionSignal = 'critical' | 'warning' | 'ok';

/** Returns the urgency of an order relative to its ERP scheduled delivery date.
 *  'late' = scheduledAt is in the past (order should have been dispatched already)
 *  'soon' = within leadtimeMinutes of scheduledAt
 *  'ok'   = plenty of time remaining
 *  'none' = no scheduledAt */
export function getScheduleSignal(
  scheduledAt: string | undefined | null,
  leadtimeMinutes: number
): ScheduleSignal {
  if (!scheduledAt) return 'none';
  const diffMins = (effectiveDeadlineMs(scheduledAt) - Date.now()) / 60000;
  if (diffMins < 0) return 'late';
  if (diffMins <= leadtimeMinutes) return 'soon';
  return 'ok';
}

/** End-of-business-day hour used when the ERP promise is a bare date (no time). */
const BUSINESS_EOD_HOUR = 18;

/**
 * Effective deadline (ms) for a scheduled date. A date-only ERP promise (serialized at midnight)
 * means "by end of business day", not 00:00 — so a delivery scheduled *today* is not reported
 * "overdue" the moment the day begins. Mirrors the backend SlaEvaluator's EOD rule.
 */
function effectiveDeadlineMs(scheduledAt: string): number {
  const d = new Date(scheduledAt);
  if (/T00:00(:00)?(\.0+)?(Z|[+-]\d{2}:?\d{2})?$/.test(scheduledAt)) {
    d.setHours(BUSINESS_EOD_HOUR, 0, 0, 0);
  }
  return d.getTime();
}

export type DayBucket = 'overdue' | 'today' | 'future' | 'none';

/** The business operates on Tunisia time. "Today"/"tomorrow" are defined in this
 *  zone for every user, regardless of their browser's timezone, so the admin UI
 *  agrees with the backend SLA monitor (which also reasons in business time)
 *  rather than drifting with wherever the dispatcher happens to be sitting. */
export const BUSINESS_TIMEZONE = 'Africa/Tunis';

// Cached — getDayBucket runs per row, per render. 'en-CA' yields a sortable
// YYYY-MM-DD, which we use purely as an opaque calendar-day key.
const businessDayFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_TIMEZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** The calendar day (YYYY-MM-DD) of an instant, in the business timezone.
 *  Defaults to now. Use this anywhere "today" must mean the business day. */
export function getBusinessDayKey(date: Date = new Date()): string {
  return businessDayFormatter.format(date);
}

/** Calendar-day classification of a scheduled delivery, in business time.
 *  Mutually exclusive — a delivery falls in exactly one bucket. Unlike
 *  getScheduleSignal (a rolling window relative to *now*), this snaps to
 *  calendar days so list tabs don't slide with the wall clock.
 *  'overdue' = scheduledAt instant is already in the past (true in any zone)
 *  'today'   = same business calendar day as now, and still upcoming
 *  'future'  = a later business calendar day
 *  'none'    = no scheduledAt */
export function getDayBucket(scheduledAt: string | undefined | null): DayBucket {
  if (!scheduledAt) return 'none';
  const sched = new Date(scheduledAt);
  const now = new Date();
  // Past-its-effective-deadline (EOD for date-only promises) takes precedence over "today",
  // so an order scheduled *for today* is not shown overdue until the business day ends.
  if (effectiveDeadlineMs(scheduledAt) < now.getTime()) return 'overdue';
  return getBusinessDayKey(sched) === getBusinessDayKey(now) ? 'today' : 'future';
}

/** Returns how alarming an open exception is based on how long it has been open. */
export function getExceptionSignal(
  openedAt: string | undefined | null,
  limitMinutes: number
): ExceptionSignal {
  if (!openedAt || limitMinutes <= 0) return 'ok';
  const elapsed = (Date.now() - new Date(openedAt).getTime()) / 60000;
  if (elapsed >= limitMinutes * 2) return 'critical';
  if (elapsed >= limitMinutes) return 'warning';
  return 'ok';
}

/** How long ago a past ISO date was, as a short 3-lang string. */
export function formatElapsed(
  dateString: string | undefined | null,
  locale: string
): string {
  if (!dateString) return '—';
  const diff = Math.floor((Date.now() - new Date(dateString).getTime()) / 60000);
  if (diff < 1) {
    if (locale === 'ar') return 'الآن';
    if (locale === 'en') return 'just now';
    return 'à l\'instant';
  }
  if (diff < 60) {
    if (locale === 'ar') return `منذ ${diff} دق`;
    if (locale === 'en') return `${diff}m ago`;
    return `${diff} min`;
  }
  const h = Math.floor(diff / 60);
  const mm = String(diff % 60).padStart(2, '0');
  if (h < 24) {
    if (locale === 'ar') return `منذ ${h}س ${mm}د`;
    if (locale === 'en') return `${h}h ${mm}m ago`;
    return `${h}h${mm}`;
  }
  const d = Math.floor(h / 24);
  if (locale === 'ar') return `منذ ${d} يوم`;
  if (locale === 'en') return `${d}d ago`;
  return `${d}j`;
}

/** Humanize a raw duration given in minutes (e.g. 45141 → "31j" / "31d" / "31 يوم").
 *  Used for notification copy where the backend only ships elapsed minutes. */
export function humanizeMinutes(
  minutes: number | string | undefined | null,
  locale: string
): string {
  const m = Math.abs(Math.round(Number(minutes) || 0));
  if (m < 60) {
    if (locale === 'ar') return `${m} دق`;
    if (locale === 'en') return `${m}m`;
    return `${m} min`;
  }
  const h = Math.floor(m / 60);
  if (h < 24) {
    const mm = String(m % 60).padStart(2, '0');
    if (locale === 'ar') return `${h}س ${mm}د`;
    if (locale === 'en') return `${h}h ${mm}m`;
    return `${h}h${mm}`;
  }
  const d = Math.floor(h / 24);
  const hh = h % 24;
  if (locale === 'ar') return hh ? `${d} يوم ${hh}س` : `${d} يوم`;
  if (locale === 'en') return hh ? `${d}d ${hh}h` : `${d}d`;
  return hh ? `${d}j ${hh}h` : `${d}j`;
}

/** Countdown (or overdue distance) from now to a future scheduled date, as a short 3-lang string.
 *  Negative diff (past) returns an "overdue by X" string. */
export function formatCountdown(
  scheduledAt: string | undefined | null,
  locale: string
): string {
  if (!scheduledAt) return '—';
  const diffMins = Math.floor((new Date(scheduledAt).getTime() - Date.now()) / 60000);
  if (diffMins >= 0) {
    if (diffMins < 60) {
      if (locale === 'ar') return `خلال ${diffMins} دق`;
      if (locale === 'en') return `in ${diffMins}m`;
      return `dans ${diffMins} min`;
    }
    const h = Math.floor(diffMins / 60);
    if (h < 24) {
      if (locale === 'ar') return `خلال ${h}س`;
      if (locale === 'en') return `in ${h}h`;
      return `dans ${h}h`;
    }
    const d = Math.floor(h / 24);
    if (locale === 'ar') return `خلال ${d} يوم`;
    if (locale === 'en') return `in ${d}d`;
    return `dans ${d}j`;
  }
  const abs = Math.abs(diffMins);
  if (abs < 60) {
    if (locale === 'ar') return `منذ ${abs} دق`;
    if (locale === 'en') return `${abs}m overdue`;
    return `${abs} min de retard`;
  }
  const h = Math.floor(abs / 60);
  if (h < 24) {
    if (locale === 'ar') return `منذ ${h}س`;
    if (locale === 'en') return `${h}h overdue`;
    return `${h}h de retard`;
  }
  const d = Math.floor(h / 24);
  if (locale === 'ar') return `منذ ${d} يوم`;
  if (locale === 'en') return `${d}d overdue`;
  return `${d}j de retard`;
}
