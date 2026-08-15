import type { AnswerResponse } from '../types';

/** What produced an answer — the provenance the operator needs to trust or discount it. */
export type ProvenanceKind = 'documentation' | 'live' | 'sla' | 'refused' | 'degraded';

/** Derive provenance from a settled answer (shared by the badge + its aria summary). */
export function provenanceOf(a: Pick<AnswerResponse, 'route' | 'refused' | 'degraded'>): ProvenanceKind {
  if (a.degraded) return 'degraded';
  if (a.refused) return 'refused';
  if (a.route === 'LIVE_API') return 'live';
  if (a.route === 'DETERMINISTIC') return 'sla';
  return 'documentation';
}
