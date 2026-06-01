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
let refreshQueue: Array<(ok: boolean) => void> = [];

function flushQueue(ok: boolean) {
  refreshQueue.forEach((resolve) => resolve(ok));
  refreshQueue = [];
}

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
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;

    // Don't retry the refresh call itself to avoid infinite loops
    if (
      error?.response?.status === 401 &&
      !original._retry &&
      typeof window !== 'undefined' &&
      !original.url?.includes('/api/auth/admin/refresh') &&
      !original.url?.includes('/api/auth/admin/login')
    ) {
      if (isRefreshing) {
        // Queue callers while a refresh is already in flight
        return new Promise((resolve, reject) => {
          refreshQueue.push((ok) => {
            if (ok) resolve(api(original));
            else reject(error);
          });
        });
      }

      original._retry = true;
      isRefreshing = true;

      try {
        await api.post('/api/auth/admin/refresh');
        flushQueue(true);
        return api(original); // retry original request with new cookie
      } catch {
        flushQueue(false);
        safeStorage.removeItem('admin_role');
        safeStorage.removeItem('admin_name');
        safeStorage.removeItem('admin_user');
        window.location.href = '/login';
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

export default api;
