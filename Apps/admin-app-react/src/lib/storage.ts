// Centralized fallback-safe localStorage utility to prevent crashes in private modes or incognito environments.

const fallbackStore: Record<string, string> = {};

export const safeStorage = {
  getItem(key: string): string | null {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        return localStorage.getItem(key);
      }
    } catch (e) {
      console.warn(`[safeStorage] Failed to read key "${key}" from localStorage. Falling back to memory storage.`, e);
    }
    return fallbackStore[key] || null;
  },

  setItem(key: string, value: string): void {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        localStorage.setItem(key, value);
        return;
      }
    } catch (e) {
      console.warn(`[safeStorage] Failed to write key "${key}" to localStorage. Falling back to memory storage.`, e);
    }
    fallbackStore[key] = value;
  },

  removeItem(key: string): void {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        localStorage.removeItem(key);
        return;
      }
    } catch (e) {
      console.warn(`[safeStorage] Failed to remove key "${key}" from localStorage. Falling back to memory storage.`, e);
    }
    delete fallbackStore[key];
  },

  clear(): void {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        localStorage.clear();
        return;
      }
    } catch (e) {
      console.warn('[safeStorage] Failed to clear localStorage.', e);
    }
    for (const key in fallbackStore) {
      delete fallbackStore[key];
    }
  }
};
