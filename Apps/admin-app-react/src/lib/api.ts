import axios from 'axios';
import { safeStorage } from '@/lib/storage';

let baseURL = import.meta.env.VITE_API_BASE_URL;

if (typeof window !== 'undefined') {
  // Always use relative path in the browser so Next.js rewrites proxy the request to the correct backend host
  baseURL = '';
} else if (!baseURL) {
  baseURL = 'http://localhost:80';
}

export const TOKEN_KEY = 'admin_jwt';

export const api = axios.create({ baseURL, withCredentials: true });

// withCredentials: true — browser sends the httpOnly access_token cookie automatically.
// On 401: silently attempt one token refresh, then retry the original request.
// Only redirect to /login if the refresh itself fails.
let isRefreshing = false;

api.interceptors.request.use((config) => {
  const method = config.method?.toUpperCase() ?? '';
  if (['POST', 'PUT', 'PATCH'].includes(method) && !config.headers['X-Idempotency-Key']) {
    const body = config.data ? JSON.stringify(config.data) : '';
    const raw = `${method}:${config.url}:${body}`;
    let hash = 0;
    for (let i = 0; i < raw.length; i++) {
      hash = (Math.imul(31, hash) + raw.charCodeAt(i)) | 0;
    }
    config.headers['X-Idempotency-Key'] = `${Math.abs(hash).toString(16)}-${Date.now()}`;
  }

  // Inject Keycloak JWT token
  const token = safeStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }

  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    // On 401 Unauthorized, Keycloak token has expired or is invalid.
    // Redirect to login to start a new OIDC flow.
    if (
      error?.response?.status === 401 &&
      typeof window !== 'undefined' &&
      !window.location.pathname.includes('/login')
    ) {
      safeStorage.removeItem('access_token');
      safeStorage.removeItem('admin_role');
      safeStorage.removeItem('role');
      safeStorage.removeItem('admin_name');
      safeStorage.removeItem('admin_user');
      window.location.href = '/login';
    }

    return Promise.reject(error);
  }
);

export default api;
