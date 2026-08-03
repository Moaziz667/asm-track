// Pure, framework-free derivations for the System Health page — kept here so the
// non-obvious logic (staleness, problem counting, age bucketing) is unit-testable
// in a node environment without a DOM.

export interface SHCircuitBreaker {
  name: string;
  state: string;
  reachable?: boolean;
  failureRate?: number;
  bufferedCalls?: number;
}
export interface SHPayload {
  generatedAt?: string;
  dlq?: Record<string, number>;
  circuitBreakers?: SHCircuitBreaker[];
  db?: { reachable: boolean };
  erpSync?: { failed: number; inProgress: number };
}

export const STALE_AFTER_MS = 60_000;

export type DescriptionKey =
  | 'erp' | 'erpImport' | 'driver' | 'routes' | 'auth' | 'generic'
  | 'dlqErpCommand' | 'dlqErpResult' | 'dlqAudit' | 'dlqDriverLocation' | 'dlqGeneric';

/**
 * Maps a raw circuit-breaker or dead-letter-queue identifier (often a mangled
 * ClassName+method string) to a stable key for a plain-language description.
 */
export function describeKey(name: string, isDlq: boolean): DescriptionKey {
  const n = name.toLowerCase();
  if (isDlq) {
    if (n.includes('erp') && n.includes('result')) return 'dlqErpResult';
    if (n.includes('erp') && n.includes('command')) return 'dlqErpCommand';
    if (n.includes('audit')) return 'dlqAudit';
    if (n.includes('location')) return 'dlqDriverLocation';
    return 'dlqGeneric';
  }
  if (n.includes('pendingorder') || n.includes('import') || n.includes('preview')) return 'erpImport';
  if (n.includes('erp') || n.includes('adapter')) return 'erp';
  if (n.includes('driver')) return 'driver';
  if (n.includes('route') || n.includes('osrm') || n.includes('geocode')) return 'routes';
  if (n.includes('auth') || n.includes('keycloak')) return 'auth';
  return 'generic';
}

/** A service is "down" if its breaker tripped OR its reachability probe failed. */
export function isDown(b: SHCircuitBreaker): boolean {
  return b.state === 'OPEN' || b.state === 'FORCED_OPEN' || b.reachable === false;
}

export function isRecovering(b: SHCircuitBreaker): boolean {
  return b.state === 'HALF_OPEN' && b.reachable !== false;
}

export interface ServiceGroup {
  label: string;
  state: string;          // worst state among members
  reachableDown: boolean; // any member's probe failed
  failureRate: number;    // max among members with buffered calls, else -1
  bufferedCalls: number;
}

const severity = (state: string, down: boolean): number =>
  down || state === 'OPEN' || state === 'FORCED_OPEN' ? 3 : state === 'HALF_OPEN' ? 2 : 1;

/**
 * Collapses circuit breakers that resolve to the same friendly service name into one entry —
 * several Feign clients (e.g. two ERP clients) otherwise render as duplicate cards. The group
 * takes the worst state, down if any member is unreachable, and the highest active error rate.
 */
export function groupServices(
  breakers: SHCircuitBreaker[],
  labelOf: (name: string) => string,
): ServiceGroup[] {
  const order: string[] = [];
  const byLabel = new Map<string, ServiceGroup>();
  for (const cb of breakers) {
    const label = labelOf(cb.name);
    const down = cb.reachable === false;
    const rate = (cb.bufferedCalls ?? 0) > 0 ? (cb.failureRate ?? -1) : -1;
    const g = byLabel.get(label);
    if (!g) {
      byLabel.set(label, { label, state: cb.state, reachableDown: down, failureRate: rate, bufferedCalls: cb.bufferedCalls ?? 0 });
      order.push(label);
    } else {
      if (severity(cb.state, down) > severity(g.state, g.reachableDown)) g.state = cb.state;
      g.reachableDown = g.reachableDown || down;
      if (rate > g.failureRate) g.failureRate = rate;
      g.bufferedCalls += cb.bufferedCalls ?? 0;
    }
  }
  return order.map(l => byLabel.get(l)!);
}

export interface HealthSummary {
  serviceCount: number;
  downCount: number;
  recoveringCount: number;
  okServices: number;
  totalStuck: number;
  stuckQueueCount: number;
  erpFailed: number;
  erpInProgress: number;
  problems: number;
  allGood: boolean;
}

/**
 * Derives the headline counts. Pass `labelOf` so services are counted by their grouped (friendly)
 * identity — otherwise the summary disagrees with the grid (e.g. two ERP breakers counted as two).
 */
export function deriveHealthSummary(data: SHPayload | null, labelOf?: (name: string) => string): HealthSummary {
  const breakers = data?.circuitBreakers ?? [];
  const groups = labelOf
    ? groupServices(breakers, labelOf)
    : breakers.map(b => ({ label: b.name, state: b.state, reachableDown: b.reachable === false, failureRate: -1, bufferedCalls: 0 }));
  const dlqEntries = Object.entries(data?.dlq ?? {});
  const stuckQueues = dlqEntries.filter(([, d]) => Number(d) > 0);
  const totalStuck = stuckQueues.reduce((s, [, d]) => s + Number(d), 0);
  const downCount = groups.filter(isGroupDown).length;
  const recoveringCount = groups.filter(isGroupRecovering).length;
  const dbReachable = data?.db?.reachable ?? true;
  const erpFailed = data?.erpSync?.failed ?? 0;
  const erpInProgress = data?.erpSync?.inProgress ?? 0;

  const problems = downCount + stuckQueues.length + erpFailed + (dbReachable ? 0 : 1);
  const allGood = problems === 0 && recoveringCount === 0;

  return {
    serviceCount: groups.length,
    downCount,
    recoveringCount,
    okServices: groups.length - downCount - recoveringCount,
    totalStuck,
    stuckQueueCount: stuckQueues.length,
    erpFailed,
    erpInProgress,
    problems,
    allGood,
  };
}

function isGroupDown(g: ServiceGroup): boolean {
  return g.reachableDown || g.state === 'OPEN' || g.state === 'FORCED_OPEN';
}
function isGroupRecovering(g: ServiceGroup): boolean {
  return g.state === 'HALF_OPEN' && !g.reachableDown;
}

/** True when the snapshot is older than the staleness window or the client is disconnected. */
export function computeStale(opts: {
  connected: boolean;
  generatedAt?: string;
  lastUpdated: number | null;
  now: number;
}): boolean {
  if (!opts.connected) return true;
  const ts = opts.generatedAt ? Date.parse(opts.generatedAt) : opts.lastUpdated;
  if (ts == null || Number.isNaN(ts)) return false;
  return opts.now - ts > STALE_AFTER_MS;
}

export type AgeParts =
  | { kind: 'none' }
  | { kind: 'minutes'; n: number }
  | { kind: 'hours'; h: number; m: number }
  | { kind: 'days'; d: number; h: number };

/** Buckets an elapsed time since `iso` into minutes / hours / days for display. */
export function ageParts(iso: string | null | undefined, now: number): AgeParts {
  if (!iso) return { kind: 'none' };
  const parsed = Date.parse(iso);
  if (Number.isNaN(parsed)) return { kind: 'none' };
  const mins = Math.max(0, Math.round((now - parsed) / 60000));
  if (mins < 60) return { kind: 'minutes', n: mins };
  const hours = Math.floor(mins / 60);
  if (hours < 24) return { kind: 'hours', h: hours, m: mins % 60 };
  const days = Math.floor(hours / 24);
  return { kind: 'days', d: days, h: hours % 24 };
}

/** Past this, a daily backup has plainly missed its slot — one skipped night is already a miss. */
export const BACKUP_STALE_AFTER_MS = 36 * 60 * 60 * 1000;

export interface BackupStatus {
  status?: string; finishedAt?: string; detail?: string; offsite?: boolean;
}

export type BackupState = 'unknown' | 'failed' | 'stale' | 'fresh';

/**
 * Judges the backup state file.
 *
 * The failure worth catching is the quiet one: a job that stopped running weeks ago leaves a
 * state file still saying "ok". So freshness is decided here rather than trusted from the file —
 * an old success is reported as stale, never as fresh. A missing file is 'unknown', which is not
 * the same as healthy: it is what an operator sees before the schedule has ever been installed.
 */
export function backupState(b: BackupStatus | undefined, now: number): BackupState {
  if (!b?.status || b.status === 'unknown') return 'unknown';
  if (b.status !== 'ok') return 'failed';
  if (!b.finishedAt) return 'stale';
  const at = Date.parse(b.finishedAt);
  if (Number.isNaN(at)) return 'stale';
  return now - at > BACKUP_STALE_AFTER_MS ? 'stale' : 'fresh';
}
