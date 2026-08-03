/**
 * What a route guard should do, given the OIDC session state.
 *
 * <p>Extracted from the guards themselves because the rule is easy to state, easy to get wrong, and
 * impossible to test through a component in this project (the unit suite runs in plain Node, with no
 * DOM). The bug it exists to prevent left the dashboard spinning forever and looked like a hung
 * network call.
 *
 * <h2>Why isLoading is not enough on its own</h2>
 * In react-oidc-context, {@code isLoading} covers two unrelated situations:
 * <ul>
 *   <li>the session has not been restored yet — the initial page load, where we genuinely do not
 *       know who the user is and must wait;</li>
 *   <li>a navigator is running right now, which includes every silent renew of a session that is
 *       already valid and already authenticated.</li>
 * </ul>
 *
 * <p>A guard that waits on the second case unmounts the page each time a token is refreshed. When it
 * comes back, every query on the page refires; if one of them 401s, it refreshes again. The result
 * is a permanent spinner produced by refreshes that were all *succeeding* — the failure mode gives
 * no error to find, because nothing failed.
 *
 * <p>So: once there is a session, there is nothing to wait for.
 */
export type AuthGate = 'wait' | 'redirect' | 'render';

export function protectedGate(isLoading: boolean, isAuthenticated: boolean): AuthGate {
  if (isLoading && !isAuthenticated) return 'wait';
  return isAuthenticated ? 'render' : 'redirect';
}

/** The mirror image, for routes only anonymous users should see (login). */
export function publicGate(isLoading: boolean, isAuthenticated: boolean): AuthGate {
  if (isLoading && !isAuthenticated) return 'wait';
  return isAuthenticated ? 'redirect' : 'render';
}
