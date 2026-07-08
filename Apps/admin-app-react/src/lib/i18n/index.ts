export type { Locale } from './i18n';
export { getLocaleFromCookie, useLocaleStore } from './i18n';
export type { MsgParams } from './i18n-dict';
export { tlabel, tlabelOr, dget } from './i18n-dict';
export type { TranslationSchema, CopyDict } from './LocaleContext';
export { LocaleProvider, useLocaleContext, useT, getCopy } from './LocaleContext';
export { FR_COPY, notifHelpers } from './ux-copy';
export { EN_COPY } from './en-copy';
export { AR_COPY } from './ar-copy';
