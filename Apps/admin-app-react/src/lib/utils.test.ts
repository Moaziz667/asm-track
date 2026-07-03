import { describe, it, expect } from 'vitest';
import { resolveOrderRef, shortId, formatMoney, formatMinutes, createRouteColorMap, routeColorFromMap, ROUTE_PALETTE } from './utils';

describe('resolveOrderRef', () => {
  it('prefers orderRef, then erp fallbacks, in order', () => {
    expect(resolveOrderRef({ orderRef: 'WH/1', erpOrderId: 'E1' })).toBe('WH/1');
    expect(resolveOrderRef({ erpOrderId: 'E1', erpExternalRef: 'X1' })).toBe('E1');
    expect(resolveOrderRef({ erpExternalRef: 'X1', erpId: 'I1' })).toBe('X1');
    expect(resolveOrderRef({ erpId: 'I1' })).toBe('I1');
  });
  it('returns em-dash for null/empty', () => {
    expect(resolveOrderRef(null)).toBe('—');
    expect(resolveOrderRef(undefined)).toBe('—');
    expect(resolveOrderRef({})).toBe('—');
  });
});

describe('shortId', () => {
  it('strips dashes, takes first 8, uppercases', () => {
    expect(shortId('27ad9cee-4a0b-4ead-8bc3-0ec1e35b0170')).toBe('27AD9CEE');
    expect(shortId('abcdef12')).toBe('ABCDEF12');
  });
  it('handles short ids and null', () => {
    expect(shortId('ab-cd')).toBe('ABCD');
    expect(shortId(null)).toBe('—');
    expect(shortId(undefined)).toBe('—');
  });
});

describe('formatMoney', () => {
  it('uses 3 decimals for TND/DT, 2 otherwise', () => {
    expect(formatMoney(10, 'TND')).toBe('10,000 TND');
    expect(formatMoney(10, 'DT')).toBe('10,000 DT');
    expect(formatMoney(10, 'EUR')).toBe('10,00 EUR');
    expect(formatMoney(10, 'USD')).toBe('10,00 USD');
  });
  it('parses numeric strings', () => {
    expect(formatMoney('5.5', 'EUR')).toBe('5,50 EUR');
  });
  it('returns em-dash for null/empty/NaN', () => {
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney('')).toBe('—');
    expect(formatMoney('abc')).toBe('—');
  });
});

describe('formatMinutes', () => {
  it('formats hours+minutes, minutes only, and sign', () => {
    expect(formatMinutes(333)).toBe('5h 33m');
    expect(formatMinutes(33)).toBe('33m');
    expect(formatMinutes(-12)).toBe('-12m');
    expect(formatMinutes(-90)).toBe('-1h 30m');
  });
  it('defaults null/undefined to 0m', () => {
    expect(formatMinutes(null)).toBe('0m');
    expect(formatMinutes(undefined)).toBe('0m');
  });
});

describe('createRouteColorMap', () => {
  it('assigns distinct palette colours by position', () => {
    const routes = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    const map = createRouteColorMap(routes);
    expect(map.get('a')).toBe(ROUTE_PALETTE[0]);
    expect(map.get('b')).toBe(ROUTE_PALETTE[1]);
    expect(map.get('c')).toBe(ROUTE_PALETTE[2]);
  });
  it('wraps around the palette for more routes than colours', () => {
    const routes = Array.from({ length: 12 }, (_, i) => ({ id: `r${i}` }));
    const map = createRouteColorMap(routes);
    expect(map.get('r0')).toBe(ROUTE_PALETTE[0]);
    expect(map.get('r10')).toBe(ROUTE_PALETTE[0]);
    expect(map.get('r11')).toBe(ROUTE_PALETTE[1]);
  });
  it('returns an empty map for an empty array', () => {
    expect(createRouteColorMap([]).size).toBe(0);
  });
});

describe('routeColorFromMap', () => {
  it('returns the palette colour when the route is in the map', () => {
    const map = createRouteColorMap([{ id: 'x' }]);
    expect(routeColorFromMap(map, 'x')).toBe(ROUTE_PALETTE[0]);
  });
  it('returns fallback grey for an unknown route', () => {
    expect(routeColorFromMap(new Map(), 'unknown')).toBe('#71717A');
  });
  it('returns fallback grey for null/undefined id', () => {
    expect(routeColorFromMap(new Map(), null)).toBe('#71717A');
    expect(routeColorFromMap(new Map())).toBe('#71717A');
  });
});
