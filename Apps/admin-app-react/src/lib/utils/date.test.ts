import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { formatDate, formatTime, formatDateTime, formatRelative, formatDateFile } from './date';

describe('formatDate / formatTime / formatDateTime', () => {
  it('returns "-" for null/undefined/invalid', () => {
    expect(formatDate(null)).toBe('-');
    expect(formatDate(undefined)).toBe('-');
    expect(formatDate('not-a-date')).toBe('-');
    expect(formatTime(null)).toBe('-');
    expect(formatDateTime('nope')).toBe('-');
  });
  it('formats a valid date as fr-FR DD/MM/YYYY and HH:MM (format, not TZ-exact)', () => {
    const d = '2026-06-07T12:30:00Z';
    expect(formatDate(d)).toMatch(/^\d{2}\/\d{2}\/\d{4}$/);
    expect(formatTime(d)).toMatch(/^\d{2}:\d{2}$/);
    expect(formatDateTime(d)).toMatch(/^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}$/);
  });
});

describe('formatDateFile', () => {
  it('returns the UTC calendar day of an instant', () => {
    expect(formatDateFile('2026-06-07T12:00:00Z')).toBe('2026-06-07');
  });
  it('returns sentinels for missing/invalid', () => {
    expect(formatDateFile(null)).toBe('unnamed');
    expect(formatDateFile('bad')).toBe('invalid-date');
  });
});

describe('formatRelative', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('buckets elapsed time into instant / min / h / j (French)', () => {
    vi.setSystemTime(new Date('2026-06-07T12:00:00Z'));
    expect(formatRelative('2026-06-07T11:59:30Z')).toBe("à l'instant");
    expect(formatRelative('2026-06-07T11:45:00Z')).toBe('il y a 15 min');
    expect(formatRelative('2026-06-07T09:00:00Z')).toBe('il y a 3 h');
    expect(formatRelative('2026-06-04T12:00:00Z')).toBe('il y a 3 j');
  });
  it('returns "-" for null/invalid', () => {
    expect(formatRelative(null)).toBe('-');
    expect(formatRelative('bad')).toBe('-');
  });
});
