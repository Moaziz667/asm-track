import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { sortQueue, sortByRoute, getWeekStart, getMonthStart, rowId } from './utils';

// Minimal row shape sortQueue/sortByRoute operate on.
const row = (o: {
  id?: string; routeId?: string; routeName?: string;
  severity?: string; slaHealth?: string; status?: string; worst?: string; createdAt?: string;
}) => ({
  id: o.id,
  routeId: o.routeId,
  routeName: o.routeName,
  alert: o.severity || o.slaHealth ? { severity: o.severity, slaHealth: o.slaHealth } : undefined,
  delivery: { status: o.status, createdAt: o.createdAt, slaHealth: o.slaHealth, slaWorstHealth: o.worst },
});

const ids = (rows: ReturnType<typeof row>[]) => rows.map(r => r.id);

describe('rowId', () => {
  it('prefers deliveryId then id', () => {
    expect(rowId({ deliveryId: 'd1', id: 'x' })).toBe('d1');
    expect(rowId({ id: 'x' })).toBe('x');
    expect(rowId({})).toBe('');
  });
});

describe('sortQueue', () => {
  it('sla mode floats breached/late to the top, then at-risk, then healthy', () => {
    const rows = [
      row({ id: 'ok', status: 'SCHEDULED', slaHealth: 'ON_TRACK' }),
      row({ id: 'breach', status: 'IN_TRANSIT', slaHealth: 'BREACHED' }),
      row({ id: 'risk', status: 'SCHEDULED', slaHealth: 'AT_RISK' }),
    ];
    expect(ids(sortQueue(rows, 'sla'))).toEqual(['breach', 'risk', 'ok']);
  });

  it('sla mode falls back to worst past health when live health is none', () => {
    const rows = [
      row({ id: 'clean', status: 'DELIVERED', slaHealth: 'MET' }),
      row({ id: 'wasLate', status: 'FAILED', worst: 'BREACHED' }),
    ];
    expect(ids(sortQueue(rows, 'sla'))[0]).toBe('wasLate');
  });

  it('severity mode orders CRITICAL > WARNING > rest', () => {
    const rows = [
      row({ id: 'info', severity: 'INFO' }),
      row({ id: 'crit', severity: 'CRITICAL' }),
      row({ id: 'warn', severity: 'WARNING' }),
    ];
    expect(ids(sortQueue(rows, 'severity'))).toEqual(['crit', 'warn', 'info']);
  });

  it('date mode is newest-first', () => {
    const rows = [
      row({ id: 'old', createdAt: '2026-06-01T10:00:00Z' }),
      row({ id: 'new', createdAt: '2026-06-07T10:00:00Z' }),
    ];
    expect(ids(sortQueue(rows, 'date'))).toEqual(['new', 'old']);
  });

  it('route mode puts assigned-to-route rows before unassigned', () => {
    const rows = [
      row({ id: 'noroute', status: 'SCHEDULED' }),
      row({ id: 'routed', routeId: 'r1', routeName: 'AA' }),
    ];
    expect(ids(sortQueue(rows, 'route'))).toEqual(['routed', 'noroute']);
  });

  it('does not mutate the input array', () => {
    const rows = [row({ id: 'a', severity: 'INFO' }), row({ id: 'b', severity: 'CRITICAL' })];
    const before = ids(rows);
    sortQueue(rows, 'severity');
    expect(ids(rows)).toEqual(before);
  });
});

describe('sortByRoute', () => {
  it('groups by routeName then applies the secondary score', () => {
    const rows = [
      { id: 'a2', routeName: 'A', score: 2 },
      { id: 'b1', routeName: 'B', score: 1 },
      { id: 'a1', routeName: 'A', score: 1 },
    ];
    const out = sortByRoute(rows, r => r.score).map(r => r.id);
    expect(out).toEqual(['a1', 'a2', 'b1']);
  });
});

describe('getWeekStart / getMonthStart', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('week start is the Monday of the current week', () => {
    vi.setSystemTime(new Date('2026-06-10T12:00:00Z')); // a Wednesday
    expect(getWeekStart()).toBe('2026-06-08'); // Monday
  });
  it('month start is the 1st of the current month', () => {
    vi.setSystemTime(new Date('2026-06-10T12:00:00Z'));
    expect(getMonthStart()).toBe('2026-06-01');
  });
});
