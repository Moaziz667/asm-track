
import { createContext, useContext, ReactNode } from 'react';
import type { Locale } from './i18n';
import { useLocaleStore } from './i18n';
import { FR_COPY, EN_COPY, AR_COPY } from './ux-copy';

type DeepStringify<T> = {
  [K in keyof T]: T[K] extends Record<string, any>
    ? DeepStringify<T[K]>
    : string;
};

export type TranslationSchema = DeepStringify<typeof EN_COPY>;
export type CopyDict = TranslationSchema;

interface LocaleContextType {
  locale: Locale;
  copy: CopyDict;
}

const LocaleContext = createContext<LocaleContextType | undefined>(undefined);

export function LocaleProvider({ children }: { children: ReactNode }) {
  const locale = useLocaleStore((s) => s.locale);

  const copy = locale === 'ar' ? AR_COPY : locale === 'en' ? EN_COPY : FR_COPY;

  return (
    <LocaleContext.Provider value={{ locale, copy }}>
      {children}
    </LocaleContext.Provider>
  );
}

export function useLocaleContext() {
  const ctx = useContext(LocaleContext);
  if (!ctx) {
    throw new Error('useLocaleContext must be used inside <LocaleProvider>');
  }
  return ctx;
}

export function useT(): TranslationSchema {
  return useLocaleContext().copy;
}

export function getCopy(locale: Locale) {
  return locale === 'ar' ? AR_COPY : locale === 'en' ? EN_COPY : FR_COPY;
}

