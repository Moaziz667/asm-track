import { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore, User, UserManager, UserManagerSettings } from 'oidc-client-ts';
import { safeStorage } from '@/lib/storage';
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

// Drive Keycloak's language from the app's chosen locale (asm-locale cookie) so the login page —
// and the KEYCLOAK_LOCALE cookie it sets, which the account console then inherits — match the app.
function appLocale(): string {
  if (typeof document === 'undefined') return 'fr';
  const m = document.cookie.split('; ').find((c) => c.startsWith('asm-locale='));
  return m ? m.split('=')[1] : 'fr';
}

// Pass the app's dark mode preference to the Keycloak login theme so it renders
// in the same mode. Default = dark (matches the admin app default).
function appDarkMode(): string {
  if (typeof window === 'undefined') return 'true';
  return safeStorage.getItem('admin-color-scheme') !== 'light' ? 'true' : 'false';
}

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
    const decoded = jwtDecode<{
      realm_access?: { roles?: string[] };
      name?: string; preferred_username?: string; email?: string;
    }>(user.access_token);
    const roles: string[] = decoded.realm_access?.roles ?? [];

    let role = 'ADMIN';
    if (roles.includes('ADMIN')) role = 'ADMIN';
    else if (roles.includes('DISPATCHER')) role = 'DISPATCHER';
    else if (roles.includes('MANAGER')) role = 'MANAGER';

    // Fine-grained permissions ride in the token as composite-role members (perm:*), exactly like the
    // backend reads them. The UI gates nav + actions on these — same source of truth as the gateway and
    // services (Keycloak role composites), so the UI never drifts from what the backend actually allows.
    const perms = roles.filter((r) => r.startsWith('perm:'));

    safeStorage.setItem(
      'admin_name',
      decoded.name || decoded.preferred_username || decoded.email || 'Admin'
    );
    if (decoded.email) safeStorage.setItem('admin_email', decoded.email);
    safeStorage.setItem('role', role);
    safeStorage.setItem('perms', JSON.stringify(perms));
    // Notify same-tab listeners (the storage event only fires cross-tab) so nav/actions re-gate live.
    if (typeof window !== 'undefined') window.dispatchEvent(new Event('perms-changed'));
  } catch (e) {
    console.error('Failed to parse JWT', e);
  }
}

function clearSession(): void {
  safeStorage.removeItem('access_token');
  safeStorage.removeItem('admin_user');
  safeStorage.removeItem('admin_name');
  safeStorage.removeItem('admin_email');
  safeStorage.removeItem('role');
  safeStorage.removeItem('perms');
}

const settings: UserManagerSettings & { useRefreshToken?: boolean } = {
  authority: AUTHORITY,
  client_id: CLIENT_ID,
  redirect_uri: REDIRECT_URI,
  post_logout_redirect_uri: POST_LOGOUT_URI,
  response_type: 'code',
  scope: 'openid profile email organization',

  // Render the Keycloak login in the app's language (FR/EN/AR), seed KEYCLOAK_LOCALE,
  // and pass the dark mode preference so the login page matches the app theme.
  extraQueryParams: { ui_locales: appLocale(), dark: appDarkMode() },

  // Persist the OIDC session in localStorage (default is sessionStorage, which
  // is lost across tabs / some redirect flows and causes phantom logouts).
  userStore: new WebStorageStateStore({ store: window.localStorage }),

  // Keep the access token fresh transparently. Use the REFRESH TOKEN grant for silent renew (not the
  // default iframe/prompt=none flow, which needs 3rd-party cookies and is broken on http://localhost
  // — same reason monitorSession is off). Without this, signinSilent / automaticSilentRenew fail and
  // a KC-side profile change (e.g. name in "Mon compte") only shows after a full re-login.
  automaticSilentRenew: true,
  useRefreshToken: true,

  // Session-status iframe checks don't work reliably against Keycloak on
  // http://localhost (third-party cookie / X-Frame issues); disable to avoid
  // spurious logouts in dev.
  monitorSession: false,
};

/**
 * The one UserManager, owned here rather than built inside AuthProvider.
 *
 * <p>Owning it is what lets a token refresh happen without touching React state. The
 * {@code signinSilent} handed out by {@code useAuth()} is a wrapper: it dispatches NAVIGATOR_INIT
 * first, which sets {@code isLoading: true} on a session that is perfectly valid and merely being
 * renewed. Any guard reading {@code isLoading} then unmounts the page mid-session — see
 * ProtectedRoute, which did exactly that. Calling this instance directly renews the token and
 * announces it through the userLoaded event, which is all anyone actually needs.
 */
export const userManager = new UserManager(settings);

export const oidcConfig: AuthProviderProps = {
  userManager,

  onSigninCallback: (user) => {
    syncSession(user);
    // Strip ?code & ?state from the URL without a full reload.
    window.history.replaceState({}, document.title, window.location.pathname);
  },

  onRemoveUser: () => {
    clearSession();
  },
};
