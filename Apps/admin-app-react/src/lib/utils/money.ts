/**
 * Money formatting for the cash screens.
 *
 * Three rules, all of them about being able to compare two figures at a glance:
 *
 * 1. **Always three decimals.** The dinar is a millime currency, and a column where one row reads
 *    `3400` and the next `3400,5` cannot be scanned — the eye re-parses every line.
 * 2. **Tabular figures, right-aligned.** Callers pair this with `tabular-nums`; with proportional
 *    digits a `1` is narrower than an `8`, so decimal points drift and the column stops lining up.
 * 3. **The currency code sits after the amount**, never merged into it, so a long amount stays
 *    readable and the number alone can be selected and copied.
 *
 * Grouping and decimal marks come from `toLocaleString('fr-TN')` rather than being patched
 * afterwards: the separator it emits is a non-breaking space whose exact codepoint has changed
 * between ICU versions, and rewriting it here would only re-introduce an invisible character into
 * the source for no visible gain.
 */

/** `3400.5` → `3 400,500`. No currency: the caller places it. */
export function formatAmount(value: number | string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return '—';
  return n.toLocaleString('fr-TN', {
    minimumFractionDigits: 3,
    maximumFractionDigits: 3,
  });
}

/**
 * A difference, with its sign always written out.
 *
 * The sign is not decoration. Colour alone cannot carry "money is missing": it fails for the ~8% of
 * men with a colour vision deficiency, it disappears on a printed handover sheet, and a screen
 * reader announces nothing at all. So the glyph leads and the colour reinforces it.
 */
export function formatDelta(value: number | string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return '—';
  if (n === 0) return formatAmount(0);
  return `${n > 0 ? '+' : '−'}${formatAmount(Math.abs(n))}`;
}

function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}
