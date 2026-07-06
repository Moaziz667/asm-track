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
