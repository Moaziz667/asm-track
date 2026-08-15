import { describe, it, expect, beforeEach } from 'vitest';
import { useAssistantHistory } from './assistant-history';
import type { Turn } from '@/pages/assistant/types';

const turn = (id: string, status: Turn['status'] = 'done'): Turn => ({
  id,
  question: `q-${id}`,
  askedAt: Date.now(),
  status,
});

describe('assistant history', () => {
  beforeEach(() => {
    useAssistantHistory.setState({ ownerId: null, turns: [] });
  });

  it('keeps the newest turn on top', () => {
    const { push } = useAssistantHistory.getState();
    push(turn('a'));
    push(turn('b'));
    expect(useAssistantHistory.getState().turns.map((t) => t.id)).toEqual(['b', 'a']);
  });

  it('caps the feed so localStorage cannot be filled until writes fail', () => {
    const { push } = useAssistantHistory.getState();
    for (let i = 0; i < 40; i++) push(turn(`t${i}`));
    const { turns } = useAssistantHistory.getState();
    expect(turns).toHaveLength(30);
    expect(turns[0].id).toBe('t39'); // newest kept
    expect(turns.at(-1)!.id).toBe('t10'); // oldest dropped
  });

  it('updates a turn in place when its answer arrives', () => {
    const { push, update } = useAssistantHistory.getState();
    push(turn('a', 'loading'));
    update('a', { status: 'done', latencyMs: 1200 });
    const t = useAssistantHistory.getState().turns[0];
    expect(t.status).toBe('done');
    expect(t.latencyMs).toBe(1200);
  });

  // The privacy rule: these answers quote customers, addresses and amounts, and a dispatch desk is
  // a shared workstation.
  it('wipes the feed when a different operator signs in', () => {
    const { adopt, push } = useAssistantHistory.getState();
    adopt('user-1');
    push(turn('a'));
    useAssistantHistory.getState().adopt('user-2');
    expect(useAssistantHistory.getState().turns).toEqual([]);
    expect(useAssistantHistory.getState().ownerId).toBe('user-2');
  });

  it('keeps the feed when the same operator reloads', () => {
    const { adopt, push } = useAssistantHistory.getState();
    adopt('user-1');
    push(turn('a'));
    useAssistantHistory.getState().adopt('user-1');
    expect(useAssistantHistory.getState().turns.map((t) => t.id)).toEqual(['a']);
  });

  // The regression that made the whole feature look broken: on a reload the OIDC session is restored
  // asynchronously, so the hook briefly sees no user. Treating that gap as "another operator" wiped
  // the history at the exact moment it was meant to come back.
  it('does not treat an unknown user as a different operator', () => {
    const { adopt, push } = useAssistantHistory.getState();
    adopt('user-1');
    push(turn('a'));
    // What the hook must NOT do while auth is still loading.
    expect(useAssistantHistory.getState().turns).toHaveLength(1);
    useAssistantHistory.getState().adopt('user-1');
    expect(useAssistantHistory.getState().turns).toHaveLength(1);
  });

  it('clears on demand', () => {
    const { push, clear } = useAssistantHistory.getState();
    push(turn('a'));
    clear();
    expect(useAssistantHistory.getState().turns).toEqual([]);
  });
});
