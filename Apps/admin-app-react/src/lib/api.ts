import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { safeStorage } from '@/lib/storage';

let baseURL = import.meta.env.VITE_API_BASE_URL;

if (typeof window !== 'undefined') {
  // In the browser we always use a relative path so the API gateway / Vite dev
  // proxy forwards the request to the correct backend host.
  baseURL = '';
} else if (!baseURL) {
  baseURL = 'http://localhost:80';
}

export const TOKEN_KEY = 'admin_jwt';

export const api = axios.create({ baseURL, withCredentials: true });

// ── Token refresh wiring ────────────────────────────────────────────────────
// The OIDC layer (oidc-client-ts, see lib/oidcConfig.ts) owns acquisition and
// silent renewal of the Keycloak access token (public client + PKCE), mirroring
// the freshest token into localStorage via AuthSync. The token therefore lives
// in browser storage by design of the SPA flow — protect it with a strict CSP
// at the gateway rather than assuming an httpOnly cookie. If we ever move to a
// BFF/cookie model, this interceptor is the single place to change.
//
// On 401 we attempt ONE silent renew (single-flight, shared across concurrent
// requests) and retry the original request before giving up and redirecting.

type Refresher = () => Promise<unknown>;
let tokenRefresher: Refresher | null = null;
let logoutHandler: (() => void) | null = null;
let refreshInFlight: Promise<boolean> | null = null;

/** Wired once from Providers so this layer can trigger oidc signinSilent. */
export function registerTokenRefresher(fn: Refresher | null) {
  tokenRefresher = fn;
}
export function registerLogoutHandler(fn: (() => void) | null) {
  logoutHandler = fn;
}

async function runRefresh(): Promise<boolean> {
  if (!tokenRefresher) return false;
  if (!refreshInFlight) {
    refreshInFlight = tokenRefresher()
      .then(() => true)
      .catch(() => false)
      .finally(() => { refreshInFlight = null; });
  }
  return refreshInFlight;
}

function hardLogout() {
  safeStorage.removeItem('access_token');
  safeStorage.removeItem('admin_role');
  safeStorage.removeItem('role');
  safeStorage.removeItem('admin_name');
  safeStorage.removeItem('admin_user');
  // Clear the OIDC session so route guards don't treat the user as
  // authenticated and bounce them straight back (401 redirect loop).
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const key = localStorage.key(i);
      if (key && key.startsWith('oidc.')) localStorage.removeItem(key);
    }
  } catch { /* private mode — safeStorage fallback, nothing to purge */ }
  if (logoutHandler) {
    try { logoutHandler(); } catch { /* fall through to redirect */ }
  }
  if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
    window.location.href = '/login';
  }
}

api.interceptors.request.use((config) => {
  const method = config.method?.toUpperCase() ?? '';
  // Stable idempotency key per logical write. It MUST survive transport retries
  // (axios / react-query) so the backend dedup recognises the duplicate, so we
  // generate it once per request config and never fold in a timestamp.
  if (['POST', 'PUT', 'PATCH'].includes(method) && !config.headers['X-Idempotency-Key']) {
    config.headers['X-Idempotency-Key'] =
      (globalThis.crypto?.randomUUID?.() ??
        `${Math.random().toString(16).slice(2)}${Math.random().toString(16).slice(2)}`);
  }

  const token = safeStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }

  return config;
});

api.interceptors.response.use(
  (response) => {
    // Spring Boot 3.3 serializes a Page as { content, page: { size, number, totalElements, totalPages } }
    // (PagedModel / VIA_DTO). The app reads these fields top-level, so flatten them here once for every
    // paginated endpoint — otherwise pagination metadata is undefined and tables lose their controls.
    const d = response.data as Record<string, unknown> | undefined;
    if (d && Array.isArray((d as { content?: unknown }).content)
        && (d as { totalPages?: unknown }).totalPages === undefined) {
      const meta = (d as { page?: Record<string, unknown> }).page;
      if (meta && typeof meta === 'object') {
        d.totalPages = meta.totalPages;
        d.totalElements = meta.totalElements;
        d.number = meta.number;
        d.size = meta.size;
      }
    }
    return response;
  },
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined;
    const status = error.response?.status;
    const onLogin = typeof window !== 'undefined' && window.location.pathname.includes('/login');

    if (status === 401 && original && !original._retried && !onLogin) {
      original._retried = true;
      const refreshed = await runRefresh();
      if (refreshed) {
        const token = safeStorage.getItem('access_token');
        if (token) original.headers.Authorization = `Bearer ${token}`;
        return api(original);
      }
      // Refresh failed / unavailable — token is truly dead.
      hardLogout();
    }

    return Promise.reject(error);
  }
);

export default api;
