import { i18nBuilder } from "keycloakify/login/i18n";

const { useI18n, ofTypeI18n } = i18nBuilder.build();

export { useI18n, ofTypeI18n };
export type I18n = typeof ofTypeI18n;
