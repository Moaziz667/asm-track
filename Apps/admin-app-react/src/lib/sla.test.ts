import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  getDayBucket, getBusinessDayKey,
  getScheduleSignal, getExceptionSignal, formatElapsed, humanizeMinutes, formatCountdown,
} from './sla';

// The business runs on Tunisia time (UTC+1, no DST). These helpers express
// instants as Tunis wall-clock and convert to a FIXED UTC instant, so every
// assertion is independent of the machine/CI timezone — only getDayBucket's
// own Africa/Tunis interpretation decides the bucket. (mo is 1-based.)
const tunis = (y: number, mo: number, d: number, h = 0, mi = 0) =>
  new Date(Date.UTC(y, mo - 1, d, h - 1, mi, 0)).toISOString();

const setNow = (y: number, mo: number, d: number, h = 0, mi = 0) =>
  vi.setSystemTime(new Date(Date.UTC(y, mo - 1, d, h - 1, mi, 0)));

describe('getBusinessDayKey', () => {
  it('resolves an instant to its Tunis calendar day, not UTC', () => {
    // 23:30Z is already the next day in Tunis (UTC+1); 22:30Z is not.
    expect(getBusinessDayKey(new Date('2026-06-06T23:30:00Z'))).toBe('2026-06-07');
    expect(getBusinessDayKey(new Date('2026-06-06T22:30:00Z'))).toBe('2026-06-06');
  });
});

describe('getDayBucket', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  describe('no schedule', () => {
    it('returns "none" for null / undefined / empty string', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(null)).toBe('none');
      expect(getDayBucket(undefined)).toBe('none');
      expect(getDayBucket('')).toBe('none');
    });
  });

  describe('overdue — instant already in the past', () => {
    it('a past calendar day is overdue', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 6, 1, 9, 0))).toBe('overdue');
    });

    // The core business rule: due-today-but-already-late counts as overdue,
    // NOT today. Keeps the Deliveries tabs mutually exclusive and matches the
    // backend's SLA_UNSCHEDULED_LATE precedence.
    it('earlier today is overdue, taking precedence over "today"', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 6, 7, 10, 0))).toBe('overdue');
    });

    it('one minute ago is overdue', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 6, 7, 13, 59))).toBe('overdue');
    });
  });

  describe('today — same business day, still upcoming', () => {
    it('later today is today', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 6, 7, 16, 0))).toBe('today');
    });

    it('the last minute of today (23:59) is still today', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 6, 7, 23, 59))).toBe('today');
    });
  });

  describe('future — a later business day', () => {
    it('tomorrow morning is future, not today', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 6, 8, 8, 0))).toBe('future');
    });

    it('next month is future', () => {
      setNow(2026, 6, 7, 14, 0);
      expect(getDayBucket(tunis(2026, 7, 1, 0, 0))).toBe('future');
    });
  });

  describe('day boundaries (counted in Tunis)', () => {
    it('splits 23:59 today vs 00:01 tomorrow across the calendar line', () => {
      setNow(2026, 6, 7, 23, 30);
      expect(getDayBucket(tunis(2026, 6, 7, 23, 59))).toBe('today');
      expect(getDayBucket(tunis(2026, 6, 8, 0, 1))).toBe('future');
    });

    it('handles the year boundary (Dec 31 vs Jan 1)', () => {
      setNow(2026, 12, 31, 22, 0);
      expect(getDayBucket(tunis(2026, 12, 31, 23, 0))).toBe('today');
      expect(getDayBucket(tunis(2027, 1, 1, 1, 0))).toBe('future');
    });
  });

  describe('business-timezone authority (Tunis, not UTC or browser)', () => {
    // At Tunis 00:30 (== 23:30 UTC the previous day) an order later the same
    // Tunis day is "today" even though UTC is still "yesterday". This is the
    // whole point of pinning the zone: the bucket follows Tunisia, not the
    // dispatcher's browser or UTC.
    it('rolls the day over at Tunis midnight, not UTC midnight', () => {
      setNow(2026, 6, 7, 0, 30);
      expect(getDayBucket(tunis(2026, 6, 7, 9, 0))).toBe('today');
      expect(getDayBucket(tunis(2026, 6, 6, 23, 0))).toBe('overdue'); // earlier instant
      expect(getDayBucket(tunis(2026, 6, 8, 9, 0))).toBe('future');
    });
  });
});

describe('getScheduleSignal', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('returns "none" without a date', () => {
    expect(getScheduleSignal(null, 120)).toBe('none');
    expect(getScheduleSignal(undefined, 120)).toBe('none');
  });
  it('late when the deadline is past, soon within the lead window, ok beyond it', () => {
    vi.setSystemTime(new Date('2026-06-07T12:00:00Z'));
    expect(getScheduleSignal('2026-06-07T11:00:00Z', 120)).toBe('late');  // 60m ago
    expect(getScheduleSignal('2026-06-07T13:00:00Z', 120)).toBe('soon');  // in 60m, ≤120
    expect(getScheduleSignal('2026-06-07T15:00:00Z', 120)).toBe('ok');    // in 180m
  });
  it('treats a date-only promise as end-of-business-day (18:00)', () => {
    vi.setSystemTime(new Date('2026-06-07T12:00:00Z'));
    // midnight-stamped → deadline shifts to 18:00 local, comfortably in the future
    expect(getScheduleSignal('2026-06-07T00:00:00Z', 120)).not.toBe('late');
  });
});

describe('getExceptionSignal', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('is ok with no date or non-positive limit', () => {
    expect(getExceptionSignal(null, 15)).toBe('ok');
    expect(getExceptionSignal('2026-06-07T11:00:00Z', 0)).toBe('ok');
  });
  it('warns past the limit and goes critical past 2×', () => {
    vi.setSystemTime(new Date('2026-06-07T12:00:00Z'));
    expect(getExceptionSignal('2026-06-07T11:55:00Z', 15)).toBe('ok');       // 5m
    expect(getExceptionSignal('2026-06-07T11:40:00Z', 15)).toBe('warning');  // 20m ≥ 15
    expect(getExceptionSignal('2026-06-07T11:25:00Z', 15)).toBe('critical'); // 35m ≥ 30
  });
});

describe('formatElapsed', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('renders min / h / j per locale', () => {
    vi.setSystemTime(new Date('2026-06-07T12:00:00Z'));
    expect(formatElapsed('2026-06-07T11:45:00Z', 'fr')).toBe('15 min');
    expect(formatElapsed('2026-06-07T11:45:00Z', 'en')).toBe('15m ago');
    expect(formatElapsed('2026-06-07T09:05:00Z', 'fr')).toBe('2h55');
    expect(formatElapsed('2026-06-04T12:00:00Z', 'en')).toBe('3d ago');
  });
  it('returns em-dash for missing', () => {
    expect(formatElapsed(null, 'fr')).toBe('—');
  });
});

describe('humanizeMinutes', () => {
  it('formats minutes / hours / days per locale', () => {
    expect(humanizeMinutes(45, 'fr')).toBe('45 min');
    expect(humanizeMinutes(45, 'en')).toBe('45m');
    expect(humanizeMinutes(125, 'en')).toBe('2h 05m');
    expect(humanizeMinutes(45141, 'fr')).toMatch(/^31j/); // ~31 days
  });
  it('coerces nullish/string to 0', () => {
    expect(humanizeMinutes(null, 'en')).toBe('0m');
    expect(humanizeMinutes('90', 'en')).toBe('1h 30m');
  });
});

describe('formatCountdown', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('counts down to a future deadline and reports overdue distance for a past one', () => {
    vi.setSystemTime(new Date('2026-06-07T12:00:00Z'));
    expect(formatCountdown('2026-06-07T12:30:00Z', 'en')).toBe('in 30m');
    expect(formatCountdown('2026-06-07T15:00:00Z', 'fr')).toBe('dans 3h');
    expect(formatCountdown('2026-06-07T11:30:00Z', 'en')).toBe('30m overdue');
    expect(formatCountdown('2026-06-07T09:00:00Z', 'fr')).toBe('3h de retard');
  });
  it('returns em-dash for missing', () => {
    expect(formatCountdown(undefined, 'fr')).toBe('—');
  });
});
