export type { SHCircuitBreaker, SHPayload, DescriptionKey, ServiceGroup, HealthSummary, AgeParts } from './system-health';
export { STALE_AFTER_MS, describeKey, isDown, isRecovering, groupServices, deriveHealthSummary, computeStale, ageParts } from './system-health';
