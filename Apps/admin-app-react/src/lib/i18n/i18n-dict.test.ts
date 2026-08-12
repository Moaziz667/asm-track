import { describe, it, expect } from 'vitest';
import { tcount } from './i18n-dict';
import { FR_COPY } from './ux-copy';
import { EN_COPY } from './en-copy';
import { AR_COPY } from './ar-copy';

describe('tcount', () => {
  // The bug that prompted this: French copy agrees twice, and `.replace('{plural}', …)` with a
  // string pattern only replaces the first match, so "4 commandes sélectionnée{plural}" shipped.
  it('replaces every {plural}, not just the first', () => {
    expect(tcount('commande{plural} sélectionnée{plural}', 4)).toBe('commandes sélectionnées');
  });

  it('drops the mark at one', () => {
    expect(tcount('commande{plural} sélectionnée{plural}', 1)).toBe('commande sélectionnée');
  });

  it('replaces every {count}', () => {
    expect(tcount('{count} arrêt{plural} sur {count}', 3)).toBe('3 arrêts sur 3');
  });

  it('leaves Arabic unsuffixed', () => {
    expect(tcount('{count} رحلة{plural}', 5, AR_COPY.pluralMark)).toBe('5 رحلة');
  });

  it('falls back to the bare number when the template is missing', () => {
    expect(tcount(undefined, 7)).toBe('7');
  });

  it('agrees the real selection labels in all three languages', () => {
    expect(tcount(FR_COPY.importPage.selectedMessage, 4, FR_COPY.pluralMark))
      .toBe('commandes sélectionnées');
    expect(tcount(EN_COPY.importPage.selectedMessage, 4, EN_COPY.pluralMark))
      .toBe('orders selected');
    expect(tcount(AR_COPY.importPage.selectedMessage, 4, AR_COPY.pluralMark))
      .toBe('طلب محدد');
  });
});
