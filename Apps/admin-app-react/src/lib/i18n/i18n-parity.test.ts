import { describe, it, expect } from 'vitest';
import { FR_COPY } from './ux-copy';
import { EN_COPY } from './en-copy';
import { AR_COPY } from './ar-copy';

/**
 * Guards against the classic i18n rot: someone adds a French key but forgets EN/AR, and the UI silently
 * renders `undefined` (or the raw key) for those users. FR is the source of truth (the base locale);
 * every FR leaf key must exist in EN and AR.
 *
 * We compare the SHAPE (the set of dotted key paths), not the values — translations differ by design.
 */

/** Flattens a nested copy object into the set of its leaf key paths ("a.b.c"). */
function leafKeyPaths(obj: unknown, prefix = ''): string[] {
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
    // Leaf (string, function used for interpolation, etc.) — record the path that led here.
    return prefix ? [prefix] : [];
  }
  return Object.entries(obj as Record<string, unknown>).flatMap(([key, value]) =>
    leafKeyPaths(value, prefix ? `${prefix}.${key}` : key),
  );
}

/** Keys present in `base` but missing from `other`. */
function missingKeys(base: string[], other: Set<string>): string[] {
  return base.filter((k) => !other.has(k));
}

describe('i18n key parity (FR is the base locale)', () => {
  const frKeys = leafKeyPaths(FR_COPY);
  const enKeys = new Set(leafKeyPaths(EN_COPY));
  const arKeys = new Set(leafKeyPaths(AR_COPY));

  it('EN has every FR key', () => {
    const missing = missingKeys(frKeys, enKeys);
    expect(missing, `EN is missing ${missing.length} key(s):\n${missing.slice(0, 30).join('\n')}`).toEqual([]);
  });

  it('AR has every FR key', () => {
    const missing = missingKeys(frKeys, arKeys);
    expect(missing, `AR is missing ${missing.length} key(s):\n${missing.slice(0, 30).join('\n')}`).toEqual([]);
  });
});
