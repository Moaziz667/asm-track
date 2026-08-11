export function formatDate(value?: string | Date | null): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleDateString('fr-FR');
}

export function formatTime(value?: string | Date | null): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

export function formatDateTime(value?: string | Date | null): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return `${formatDate(date)} ${formatTime(date)}`;
}

/**
 * "il y a 2 h" — in the language the screen is being read in.
 *
 * <p>These were French literals, so an English or Arabic page carried a French date column.
 * {@link Intl.RelativeTimeFormat} knows all three, with their plurals and their right-to-left forms,
 * which a hand-written table of suffixes would have to relearn for every language added.
 *
 * <p>`locale` defaults to French so existing callers keep their behaviour; a screen that knows which
 * language it is in passes its own.
 *
 * <p>Short style on purpose: this fills a column in dense tables, where "il y a 15 minutes" wraps
 * and "il y a 15 min" does not.
 */

/**
 * Under a minute has no {@link Intl.RelativeTimeFormat} equivalent worth showing: the API offers
 * "cette minute-ci" (a calendar minute, not an elapsed one) and "maintenant" (a point in time, not
 * a recency). Both read as noise in a "last seen" column, so the three languages the product ships
 * carry their own phrase and anything else falls back to the API.
 */
const JUST_NOW: Record<string, string> = {
  fr: "à l'instant",
  en: 'just now',
  ar: 'الآن',
};

export function formatRelative(value?: string | Date | null, locale: string = 'fr'): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';

  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'always', style: 'short' });
  const diffMin = Math.floor((Date.now() - date.getTime()) / 60000);
  // Negative, because these are minutes already elapsed: RelativeTimeFormat reads the past as a
  // negative offset, and the positive value would date every row into the future.
  if (diffMin < 1) return JUST_NOW[locale.split('-')[0]] ?? rtf.format(0, 'second');
  if (diffMin < 60) return rtf.format(-diffMin, 'minute');
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return rtf.format(-diffH, 'hour');
  return rtf.format(-Math.floor(diffH / 24), 'day');
}

export function formatDateFile(value?: string | Date | null): string {
  if (!value) return 'unnamed';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'invalid-date';
  return date.toISOString().split('T')[0];
}

export function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const base64String = reader.result as string;
      resolve(base64String.split(',')[1]);
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}
