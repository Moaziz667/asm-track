import { useState, useCallback } from 'react';
import { AxiosError } from 'axios';
import { api } from '@/lib/api';
import type { AnswerResponse, Turn } from './types';

// The assistant lives under /api/assistant (not the /api/v1 business surface), so we override the
// shared axios instance's baseURL per-call — this reuses its Bearer-token attach + 401 silent-refresh
// interceptors instead of standing up a second auth path.
const ASSISTANT_BASE = '/api/assistant';

function newId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Drives the in-session answer feed: newest turn on top, each answered independently. Keeps only
 * client state — the server owns the audit trail. No global query cache: answers are one-shot and
 * shouldn't be re-served stale from cache.
 */
export function useAssistant() {
  const [turns, setTurns] = useState<Turn[]>([]);

  const ask = useCallback(async (question: string) => {
    const trimmed = question.trim();
    if (!trimmed) return;

    const id = newId();
    const askedAt = Date.now();
    setTurns((prev) => [{ id, question: trimmed, askedAt, status: 'loading' }, ...prev]);

    try {
      const res = await api.post<AnswerResponse>('/query', { query: trimmed }, { baseURL: ASSISTANT_BASE });
      const latencyMs = Date.now() - askedAt;
      setTurns((prev) => prev.map((t) =>
        t.id === id ? { ...t, status: 'done', answer: res.data, latencyMs } : t));
    } catch (err) {
      const status = (err as AxiosError)?.response?.status;
      const errorKind = status === 429 ? 'rate_limited'
        : status === 401 || status === 403 ? 'unauthorized'
        : 'network';
      setTurns((prev) => prev.map((t) =>
        t.id === id ? { ...t, status: 'error', errorKind, latencyMs: Date.now() - askedAt } : t));
    }
  }, []);

  const clear = useCallback(() => setTurns([]), []);

  return { turns, ask, clear };
}
