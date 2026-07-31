import { create } from 'zustand';
import { safeStorage } from '@/lib/storage';

export type Locale = 'fr' | 'en' | 'ar';

interface LocaleState {
  locale: Locale;
  setLocale: (locale: Locale) => void;
}

// Helper to get locale from cookie (works on server and client)
export function getLocaleFromCookie(): Locale {
  try {
    if (typeof document === 'undefined') return 'fr';
    const cookies = document.cookie.split('; ');
    const localeCookie = cookies.find(c => c.startsWith('asm-locale='));
    if (localeCookie) {
      const value = localeCookie.split('=')[1];
      if (['fr', 'en', 'ar'].includes(value)) return value as Locale;
    }
  } catch {
    // Cookies can be unreadable (private mode, sandboxed iframe) — fall back to the default.
  }
  return 'fr';
}

// Helper to set locale cookie
function setLocaleCookie(locale: Locale) {
  if (typeof document !== 'undefined') {
    document.cookie = `asm-locale=${locale}; path=/; max-age=31536000; SameSite=Strict`;
  }
}

function getInitialLocale(): Locale {
  if (typeof document === 'undefined') return 'fr';
  // Read from data-locale set by the blocking script in <head> — always correct, no async
  const fromAttr = document.documentElement.getAttribute('data-locale');
  if (fromAttr && (['fr', 'en', 'ar'] as const).includes(fromAttr as Locale)) return fromAttr as Locale;
  // Fallback chain if script didn't run
  return getLocaleFromCookie() || (safeStorage.getItem('asm-locale') as Locale) || 'fr';
}

export const useLocaleStore = create<LocaleState>((set) => ({
  locale: getInitialLocale(),
  setLocale: (locale) => {
    if (typeof window !== 'undefined') {
      setLocaleCookie(locale);
      safeStorage.setItem('asm-locale', locale);
      document.documentElement.setAttribute('data-locale', locale);
      document.documentElement.setAttribute('lang', locale);
      document.documentElement.setAttribute('dir', locale === 'ar' ? 'rtl' : 'ltr');
    }
    set({ locale });
  },
}));
