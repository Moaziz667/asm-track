/**
 * Typed boundary for runtime lookups into i18n label maps.
 *
 * Copy objects (see ux-copy / en-copy / ar-copy) are strongly typed, but many
 * labels are looked up by a *runtime* key — a delivery status, an event key, a
 * failure code — that TypeScript can't prove is a member of the typed dict.
 * The historical workaround was `(dict as any)[key]`, scattering unsafe casts.
 *
 * These helpers isolate that single unavoidable cast in one reviewed place and
 * hand back a typed `string | undefined`, so call sites stay `any`-free.
 */

/**
 * Params passed to a copy message template (`(p) => \`…${p.clientName}…\``).
 * The notification/event payload is delivered as string key/values, so templates
 * only ever interpolate strings.
 */
export type MsgParams = Record<string, string>;

/** Look up `key` in a label dict, returning the string label or undefined. */
export function tlabel(dict: unknown, key: string | null | undefined): string | undefined {
  if (dict == null || key == null) return undefined;
  const v = (dict as Record<string, unknown>)[key];
  return typeof v === 'string' ? v : undefined;
}

/** Like {@link tlabel} but falls back to the key itself (the common “label ?? code” pattern). */
export function tlabelOr(dict: unknown, key: string | null | undefined): string {
  return tlabel(dict, key) ?? (key ?? '');
}

/**
 * Look up `key` in a dict of *arbitrary* values (not just strings) — e.g. copy
 * entries shaped `{ title, message }`. Returns the typed value or undefined.
 * Same single-cast boundary as {@link tlabel}, for object-valued maps.
 */
export function dget<V = unknown>(dict: unknown, key: string | null | undefined): V | undefined {
  if (dict == null || key == null) return undefined;
  return (dict as Record<string, V>)[key];
}

/**
 * Fill a counted copy template: `{count}` becomes the number, every `{plural}` the plural mark.
 *
 * Call sites used to chain `.replace('{count}', …).replace('{plural}', …)`, and a string pattern
 * replaces only the FIRST match — so French copy that agrees twice ("commande{plural}
 * sélectionnée{plural}") shipped the raw token on screen. Replacing globally, in one place, is the
 * only way that class of bug stops coming back.
 *
 * Arabic doesn't pluralise by suffix: its templates pass an empty mark and simply drop the token.
 */
export function tcount(template: string | undefined, count: number, mark = 's'): string {
  if (!template) return String(count);
  return template
    .replace(/\{count\}/g, String(count))
    .replace(/\{plural\}/g, count > 1 ? mark : '');
}
