import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Turn } from '@/pages/assistant/types';

/** Older turns are dropped past this. localStorage holds ~5 MB and answers are long — an unbounded
 *  feed eventually fails to write, silently losing the whole history rather than the oldest of it. */
const MAX_TURNS = 30;

type AssistantHistoryState = {
  /** Keycloak `sub` of the operator these turns belong to. */
  ownerId: string | null;
  turns: Turn[];
  /** Attach the history to a user, wiping it when that user changes. */
  adopt: (userId: string | null) => void;
  push: (turn: Turn) => void;
  update: (id: string, patch: Partial<Turn>) => void;
  clear: () => void;
};

/**
 * The assistant's answer feed, surviving a panel close, a navigation and a page reload.
 *
 * It used to live in the panel's own `useState`, so every reload wiped it — you asked a question,
 * refreshed, and your answer was gone. Answers here are worth minutes of a model's latency; losing
 * them on F5 is not something an operator forgives.
 *
 * Scoped to one operator and cleared when the user changes: these answers quote real customers,
 * amounts and addresses, and this is a workstation several people may sign in to.
 */
export const useAssistantHistory = create<AssistantHistoryState>()(
  persist(
    (set, get) => ({
      ownerId: null,
      turns: [],

      adopt: (userId) => {
        if (get().ownerId === userId) return;
        // Different operator (or a first sign-in): start clean rather than hand over the previous
        // one's questions and the tenant data in their answers.
        set({ ownerId: userId, turns: [] });
      },

      push: (turn) => set((s) => ({ turns: [turn, ...s.turns].slice(0, MAX_TURNS) })),

      update: (id, patch) =>
        set((s) => ({ turns: s.turns.map((t) => (t.id === id ? { ...t, ...patch } : t)) })),

      clear: () => set({ turns: [] }),
    }),
    {
      name: 'asm.assistant.history',
      partialize: (s) => ({ ownerId: s.ownerId, turns: s.turns }),
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        // A question that was still in flight when the tab closed has no answer coming: its request
        // died with the page. Restoring it as `loading` would spin forever.
        state.turns = state.turns.map((t) =>
          t.status === 'loading' ? { ...t, status: 'error', errorKind: 'network' } : t);
      },
    },
  ),
);
