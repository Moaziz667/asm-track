import { useCallback, useEffect } from 'react';
import { AxiosError } from 'axios';
import { useAuth } from 'react-oidc-context';
import { api } from '@/lib/api';
import { useAssistantHistory } from '@/lib/state/assistant-history';
import type { AnswerResponse } from './types';

// The assistant lives under /api/assistant (not the /api/v1 business surface), so we override the
// shared axios instance's baseURL per-call — this reuses its Bearer-token attach + 401 silent-refresh
// interceptors instead of standing up a second auth path.
const ASSISTANT_BASE = '/api/assistant';

function newId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Drives the answer feed: newest turn on top, each answered independently.
 *
 * The feed lives in a persisted store rather than in this hook. It used to be local `useState`, so
 * closing the panel or reloading the page threw the conversation away — and an answer here costs
 * seconds of model latency, sometimes half a minute. The server still owns the audit trail; this is
 * only the operator's own view of what he asked.
 */
export function useAssistant() {
  const auth = useAuth();
  const userId = auth.user?.profile?.sub ?? null;
  const turns = useAssistantHistory((s) => s.turns);
  const adopt = useAssistantHistory((s) => s.adopt);
  const clear = useAssistantHistory((s) => s.clear);

  // Bind the stored feed to whoever is signed in; a different operator starts from an empty panel.
  //
  // Only once a user is actually known. On a reload react-oidc-context restores the session
  // asynchronously, so `auth.user` is briefly null — adopting that null read as "another operator"
  // and wiped the history at the exact moment it was meant to be restored. Signing out is handled
  // where it happens (the two menu buttons and the 401 handler), not by watching for an absence.
  useEffect(() => {
    if (!userId) return;
    adopt(userId);
  }, [userId, adopt]);

  const ask = useCallback(async (question: string) => {
    const trimmed = question.trim();
    if (!trimmed) return;

    const id = newId();
    const askedAt = Date.now();
    // Read the actions off the store rather than closing over them: `ask` must stay stable, and the
    // update below lands after an await, when a subscribed copy could already be stale.
    const { push, update } = useAssistantHistory.getState();
    push({ id, question: trimmed, askedAt, status: 'loading' });

    try {
      const res = await api.post<AnswerResponse>('/query', { query: trimmed }, { baseURL: ASSISTANT_BASE });
      update(id, { status: 'done', answer: res.data, latencyMs: Date.now() - askedAt });
    } catch (err) {
      const status = (err as AxiosError)?.response?.status;
      const errorKind = status === 429 ? 'rate_limited'
        : status === 401 || status === 403 ? 'unauthorized'
        : 'network';
      update(id, { status: 'error', errorKind, latencyMs: Date.now() - askedAt });
    }
  }, []);

  return { turns, ask, clear };
}
