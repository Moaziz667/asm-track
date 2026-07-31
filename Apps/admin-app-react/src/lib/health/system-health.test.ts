import { describe, it, expect } from 'vitest';
import { deriveHealthSummary, computeStale, ageParts, backupState, isDown, isRecovering, groupServices, describeKey, STALE_AFTER_MS } from './system-health';

describe('isDown / isRecovering', () => {
  it('treats a failed reachability probe as down even when the breaker is CLOSED', () => {
    expect(isDown({ name: 'erp', state: 'CLOSED', reachable: false })).toBe(true);
  });
  it('treats OPEN / FORCED_OPEN as down', () => {
    expect(isDown({ name: 'a', state: 'OPEN' })).toBe(true);
    expect(isDown({ name: 'b', state: 'FORCED_OPEN' })).toBe(true);
  });
  it('does not count an unreachable service as recovering', () => {
    expect(isRecovering({ name: 'a', state: 'HALF_OPEN', reachable: false })).toBe(false);
    expect(isRecovering({ name: 'b', state: 'HALF_OPEN', reachable: true })).toBe(true);
  });
});

describe('deriveHealthSummary', () => {
  it('reports all-good for a clean payload', () => {
    const s = deriveHealthSummary({
      circuitBreakers: [{ name: 'driver', state: 'CLOSED', reachable: true }],
      db: { reachable: true },
      dlq: { 'erp.sync.command.dlq': 0 },
      erpSync: { failed: 0, inProgress: 0 },
    });
    expect(s.allGood).toBe(true);
    expect(s.problems).toBe(0);
    expect(s.okServices).toBe(1);
  });

  it('counts ERP failures, stuck queues, down services and DB outage as problems', () => {
    const s = deriveHealthSummary({
      circuitBreakers: [
        { name: 'driver', state: 'OPEN' },
        { name: 'erp', state: 'CLOSED', reachable: false },
      ],
      db: { reachable: false },
      dlq: { 'audit.log.dlq': 3 },
      erpSync: { failed: 2, inProgress: 1 },
    });
    expect(s.downCount).toBe(2);            // OPEN + unreachable
    expect(s.stuckQueueCount).toBe(1);
    expect(s.totalStuck).toBe(3);
    expect(s.erpFailed).toBe(2);
    // 2 down + 1 stuck queue + 2 erp failed + 1 db = 6
    expect(s.problems).toBe(6);
    expect(s.allGood).toBe(false);
  });

  it('is not all-good while a service is recovering, even with zero problems', () => {
    const s = deriveHealthSummary({
      circuitBreakers: [{ name: 'driver', state: 'HALF_OPEN', reachable: true }],
      db: { reachable: true },
      dlq: {},
      erpSync: { failed: 0, inProgress: 0 },
    });
    expect(s.problems).toBe(0);
    expect(s.recoveringCount).toBe(1);
    expect(s.allGood).toBe(false);
  });

  it('defaults missing db/erpSync to healthy', () => {
    const s = deriveHealthSummary({ circuitBreakers: [], dlq: {} });
    expect(s.allGood).toBe(true);
  });
});

describe('groupServices', () => {
  // Two ERP Feign clients collapse to one "ERP Sync" card.
  const label = (n: string) => (n.toLowerCase().includes('erp') ? 'ERP Sync' : n);

  it('collapses breakers that share a friendly name into a single group', () => {
    const groups = groupServices([
      { name: 'erpAdapterClient', state: 'CLOSED', reachable: false, failureRate: 10, bufferedCalls: 10 },
      { name: 'erpLookupClient', state: 'CLOSED', reachable: false, failureRate: 0, bufferedCalls: 5 },
    ], label);
    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('ERP Sync');
  });

  it('takes the worst state and highest active error rate across members', () => {
    const groups = groupServices([
      { name: 'erpA', state: 'CLOSED', reachable: true, failureRate: 0, bufferedCalls: 8 },
      { name: 'erpB', state: 'OPEN', reachable: true, failureRate: 60, bufferedCalls: 8 },
    ], label);
    expect(groups[0].state).toBe('OPEN');
    expect(groups[0].failureRate).toBe(60);
    expect(groups[0].bufferedCalls).toBe(16);
  });

  it('marks the group down if any member is unreachable', () => {
    const groups = groupServices([
      { name: 'erpA', state: 'CLOSED', reachable: true, failureRate: 0, bufferedCalls: 1 },
      { name: 'erpB', state: 'CLOSED', reachable: false, failureRate: 0, bufferedCalls: 1 },
    ], label);
    expect(groups[0].reachableDown).toBe(true);
  });

  it('keeps distinct services separate and preserves first-seen order', () => {
    const groups = groupServices([
      { name: 'driver', state: 'CLOSED' },
      { name: 'erpA', state: 'CLOSED' },
      { name: 'erpB', state: 'CLOSED' },
    ], label);
    expect(groups.map(g => g.label)).toEqual(['driver', 'ERP Sync']);
  });
});

describe('describeKey', () => {
  it('classifies the mangled ERP Feign breaker names', () => {
    expect(describeKey('ErpAdapterFeignClientgetPendingOrdersStringint', false)).toBe('erpImport');
    expect(describeKey('ErpAdapterFeignClientgetPendingOrderPreviewStringString', false)).toBe('erpImport');
    expect(describeKey('ErpAdapterFeignClientsyncStock', false)).toBe('erp');
  });
  it('classifies the driver client and other services', () => {
    expect(describeKey('DriverInternalClientgetAvailableDrivers', false)).toBe('driver');
    expect(describeKey('osrmRouteClient', false)).toBe('routes');
    expect(describeKey('keycloakAuth', false)).toBe('auth');
    expect(describeKey('somethingElse', false)).toBe('generic');
  });
  it('classifies the dead-letter queues', () => {
    expect(describeKey('erp.sync.command.dlq', true)).toBe('dlqErpCommand');
    expect(describeKey('erp.sync.result.dlq', true)).toBe('dlqErpResult');
    expect(describeKey('audit.log.dlq', true)).toBe('dlqAudit');
    expect(describeKey('driver.location.update.dlq', true)).toBe('dlqDriverLocation');
    expect(describeKey('mystery.dlq', true)).toBe('dlqGeneric');
  });
});

describe('computeStale', () => {
  const now = 1_000_000_000;
  it('is stale when disconnected regardless of timestamp', () => {
    expect(computeStale({ connected: false, lastUpdated: now, now })).toBe(true);
  });
  it('is fresh when the snapshot is recent', () => {
    expect(computeStale({ connected: true, generatedAt: new Date(now - 5_000).toISOString(), lastUpdated: now, now })).toBe(false);
  });
  it('is stale when the snapshot is older than the window', () => {
    const old = new Date(now - STALE_AFTER_MS - 1).toISOString();
    expect(computeStale({ connected: true, generatedAt: old, lastUpdated: now, now })).toBe(true);
  });
  it('falls back to lastUpdated when generatedAt is absent', () => {
    expect(computeStale({ connected: true, lastUpdated: now - STALE_AFTER_MS - 1, now })).toBe(true);
  });
  it('is not stale when there is no timestamp yet', () => {
    expect(computeStale({ connected: true, lastUpdated: null, now })).toBe(false);
  });
});

describe('ageParts', () => {
  const now = Date.parse('2026-06-08T12:00:00Z');
  it('returns none for null', () => {
    expect(ageParts(null, now)).toEqual({ kind: 'none' });
  });
  it('buckets minutes', () => {
    expect(ageParts('2026-06-08T11:30:00Z', now)).toEqual({ kind: 'minutes', n: 30 });
  });
  it('buckets hours with remaining minutes', () => {
    expect(ageParts('2026-06-08T09:25:00Z', now)).toEqual({ kind: 'hours', h: 2, m: 35 });
  });
  it('buckets days with remaining hours', () => {
    expect(ageParts('2026-06-05T06:00:00Z', now)).toEqual({ kind: 'days', d: 3, h: 6 });
  });
});

describe('backupState', () => {
  const now = Date.parse('2026-07-31T12:00:00Z');
  const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString();

  it('reports unknown when the state file is missing — not healthy', () => {
    // What an operator sees before the schedule has ever been installed. Rendering this as
    // "ok" would hide exactly the situation the line exists to reveal.
    expect(backupState(undefined, now)).toBe('unknown');
    expect(backupState({ status: 'unknown' }, now)).toBe('unknown');
  });

  it('reports a failed run', () => {
    expect(backupState({ status: 'failed', finishedAt: hoursAgo(1) }, now)).toBe('failed');
  });

  it('accepts a recent success', () => {
    expect(backupState({ status: 'ok', finishedAt: hoursAgo(10) }, now)).toBe('fresh');
    expect(backupState({ status: 'ok', finishedAt: hoursAgo(35) }, now)).toBe('fresh');
  });

  it('calls an old success stale — the cron that stopped weeks ago still says ok', () => {
    expect(backupState({ status: 'ok', finishedAt: hoursAgo(37) }, now)).toBe('stale');
    expect(backupState({ status: 'ok', finishedAt: hoursAgo(24 * 30) }, now)).toBe('stale');
  });

  it('does not trust a success with no or unreadable date', () => {
    expect(backupState({ status: 'ok' }, now)).toBe('stale');
    expect(backupState({ status: 'ok', finishedAt: 'not a date' }, now)).toBe('stale');
  });
});
