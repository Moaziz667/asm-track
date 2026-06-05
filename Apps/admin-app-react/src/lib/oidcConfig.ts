import { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore, User } from 'oidc-client-ts';
import { safeStorage } from './storage';
import { jwtDecode } from 'jwt-decode';

// Environment-driven so dev / staging / prod use the same build.
const AUTHORITY =
  import.meta.env.VITE_OIDC_AUTHORITY || 'http://localhost:8089/realms/asm';
const CLIENT_ID = import.meta.env.VITE_OIDC_CLIENT_ID || 'admin-web';
const APP_ORIGIN = window.location.origin;

// redirect_uri is PINNED via env (falls back to current origin). This keeps it
// deterministic — it can never accidentally point at Keycloak's own origin.
const REDIRECT_URI =
  import.meta.env.VITE_OIDC_REDIRECT_URI || APP_ORIGIN + '/callback';
const POST_LOGOUT_URI =
  import.meta.env.VITE_OIDC_POST_LOGOUT_URI || APP_ORIGIN + '/login';

/**
 * Mirror the OIDC access token + decoded identity into localStorage so the
 * axios layer (lib/api.ts) and legacy guards can read it. Called both on the
 * initial sign-in callback AND on every silent renew, so the stored token is
 * never stale.
 */
export function syncSession(user: User | null | undefined): void {
  if (!user?.access_token) return;

  safeStorage.setItem('access_token', user.access_token);

  try {
    const decoded: any = jwtDecode(user.access_token);
    const roles: string[] = decoded.realm_access?.roles ?? [];

    let role = 'ADMIN';
    if (roles.includes('ADMIN')) role = 'ADMIN';
    else if (roles.includes('DISPATCHER')) role = 'DISPATCHER';
    else if (roles.includes('MANAGER')) role = 'MANAGER';

    safeStorage.setItem(
      'admin_name',
      decoded.name || decoded.preferred_username || decoded.email || 'Admin'
    );
    safeStorage.setItem('role', role);
  } catch (e) {
    console.error('Failed to parse JWT', e);
  }
}

function clearSession(): void {
  safeStorage.removeItem('access_token');
  safeStorage.removeItem('admin_user');
  safeStorage.removeItem('admin_name');
  safeStorage.removeItem('role');
}

export const oidcConfig: AuthProviderProps = {
  authority: AUTHORITY,
  client_id: CLIENT_ID,
  redirect_uri: REDIRECT_URI,
  post_logout_redirect_uri: POST_LOGOUT_URI,
  response_type: 'code',
  scope: 'openid profile email',

  // Persist the OIDC session in localStorage (default is sessionStorage, which
  // is lost across tabs / some redirect flows and causes phantom logouts).
  userStore: new WebStorageStateStore({ store: window.localStorage }),

  // Keep the access token fresh transparently.
  automaticSilentRenew: true,

  // Session-status iframe checks don't work reliably against Keycloak on
  // http://localhost (third-party cookie / X-Frame issues); disable to avoid
  // spurious logouts in dev.
  monitorSession: false,

  onSigninCallback: (user) => {
    syncSession(user);
    // Strip ?code & ?state from the URL without a full reload.
    window.history.replaceState({}, document.title, window.location.pathname);
  },

  onRemoveUser: () => {
    clearSession();
  },
};
