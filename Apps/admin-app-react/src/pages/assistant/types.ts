// Mirrors AssistantService AnswerResponse (Phase 4/5). Kept minimal and explicit so the UI can
// distinguish a grounded answer, a refusal ("no evidence"), and a degraded one ("service down")
// without ever presenting a fabricated answer as fact.

export interface Citation {
  marker: number;
  chunkId: string;
  path: string;
  section: string | null;
  authority: string; // AUTHORITATIVE | SECONDARY | LOW
}

export type AssistantRoute = 'RAG' | 'LIVE_API' | 'DETERMINISTIC';

export interface AnswerResponse {
  answer: string;
  route: AssistantRoute | string;
  grounded: boolean;
  refused: boolean;
  degraded: boolean;
  citations: Citation[];
  liveSources: string[];
}

/** One entry in the in-session answer feed (client-side only; server keeps the audit trail). */
export interface Turn {
  id: string;
  question: string;
  askedAt: number;
  status: 'loading' | 'done' | 'error';
  latencyMs?: number;
  answer?: AnswerResponse;
  /** Set on transport/HTTP failure (e.g. 429 rate-limit, network). */
  errorKind?: 'rate_limited' | 'unauthorized' | 'network';
}
