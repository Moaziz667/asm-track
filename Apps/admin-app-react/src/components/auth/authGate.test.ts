import { describe, it, expect } from 'vitest';
import { protectedGate, publicGate } from './authGate';

describe('protectedGate', () => {
  it('waits while the session is still being restored', () => {
    expect(protectedGate(true, false)).toBe('wait');
  });

  it('sends an anonymous visitor to the login page', () => {
    expect(protectedGate(false, false)).toBe('redirect');
  });

  it('renders for an authenticated user', () => {
    expect(protectedGate(false, true)).toBe('render');
  });

  /*
    The case the whole file exists for.

    A silent renew sets isLoading while isAuthenticated stays true. Reading isLoading alone made the
    guard unmount the page on every token refresh; remounting refired every query, and one 401 among
    them refreshed again — an endless spinner built entirely out of refreshes that succeeded.
  */
  it('keeps rendering during a silent renew of a live session', () => {
    expect(protectedGate(true, true)).toBe('render');
  });
});

describe('publicGate', () => {
  it('waits while the session is still being restored', () => {
    expect(publicGate(true, false)).toBe('wait');
  });

  it('shows the login page to an anonymous visitor', () => {
    expect(publicGate(false, false)).toBe('render');
  });

  it('sends an authenticated user to the dashboard', () => {
    expect(publicGate(false, true)).toBe('redirect');
  });

  it('does not park an authenticated user on a spinner mid-renew', () => {
    expect(publicGate(true, true)).toBe('redirect');
  });
});
