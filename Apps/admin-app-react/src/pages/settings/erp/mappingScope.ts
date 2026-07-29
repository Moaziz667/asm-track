import type { ErpField, FieldScope } from '@/lib/api/erpIntegration';

/**
 * What a mapping row may read from, and how a chosen field becomes a stored path.
 *
 * <p>Kept apart from the components so it can be tested without a DOM, and because it is the half of
 * the picker that has to agree with the backend: every rule here mirrors `OdooFieldMappingResolver`,
 * and a disagreement does not fail loudly — it writes a mapping that resolves to a silent blank.
 */

/** Documents in scope for a per-order value, most commonly used first. */
export const HEADER_MODELS = ['stock.picking', 'sale.order', 'res.partner'];

/**
 * Documents in scope for a per-article value.
 *
 * <p>The header models stay reachable — an article row can legitimately name its order — but they
 * come after the line models, which are what the row is actually about.
 */
export const LINE_MODELS = ['stock.move', 'product.product', 'sale.order.line', ...HEADER_MODELS];

/**
 * The document a bare path hangs off.
 *
 * <p>This is the rule worth getting right: the resolver reads a line's bare path against the stock
 * move, so a line row that offered picking-relative paths would quietly produce mappings that read
 * nothing at all.
 */
export const PRIMARY_MODEL: Record<FieldScope, string> = {
  HEADER: 'stock.picking',
  LINE: 'stock.move',
};

/** Drop what this ERP did not report, so the picker never offers an empty group. */
export function modelsFor(scope: FieldScope, availableFields: Record<string, ErpField[]>) {
  const wanted = scope === 'LINE' ? LINE_MODELS : HEADER_MODELS;
  return wanted.filter((m) => (availableFields[m]?.length ?? 0) > 0);
}

/** One selectable ERP field, with the path the resolver will later parse. */
export interface Option {
  model: string;
  name: string;
  label: string;
  custom: boolean;
  path: string;
}

/**
 * Flatten the catalogue into what the list renders.
 *
 * <p>A field on the primary model is stored bare, anything else is qualified with its model — the
 * two shapes the resolver accepts.
 */
export function buildOptions(
  models: string[],
  availableFields: Record<string, ErpField[]>,
  primaryModel: string,
): Option[] {
  const out: Option[] = [];
  for (const model of models) {
    for (const f of availableFields[model] ?? []) {
      out.push({
        model,
        name: f.name,
        label: f.label,
        custom: f.custom,
        path: model === primaryModel ? f.name : `${model}:${f.name}`,
      });
    }
  }
  return out;
}

/** Enough rows to scroll through, few enough that a large Odoo does not stutter. */
export const MAX_SHOWN = 60;

/**
 * Filter and order, best first — but grouped, so each model's header appears once.
 *
 * <p>Scored rather than merely filtered: an integrator typing "name" wants `name` before
 * `partner_invoice_id.name`, and a prefix hit is the strongest evidence of intent. The results are
 * then regrouped by model, because a flat relevance list interleaves models and would repeat a
 * header every few rows — the grouping is what tells the integrator which document they are reading
 * from, which is the one thing a bare field name does not say.
 */
export function rank(options: Option[], query: string): Option[] {
  const q = query.trim().toLowerCase();

  const scoreOf = (o: Option): number => {
    if (!q) return o.custom ? 0 : 1;   // nothing typed: the customer's own fields lead
    const name = o.name.toLowerCase();
    const label = o.label.toLowerCase();
    let score = -1;
    if (name === q) score = 0;
    else if (name.startsWith(q)) score = 1;
    else if (label.startsWith(q)) score = 2;
    else if (name.includes(q)) score = 3;
    else if (label.includes(q)) score = 4;
    // Weakest signal, and last on purpose: "partner" should show the partner's fields rather than
    // nothing, but only once no field has answered to the word itself.
    else if (o.model.toLowerCase().includes(q)) score = 5;
    return score < 0 ? -1 : score - (o.custom ? 0.5 : 0);
  };

  // Group in first-seen order, which is the scope order the caller chose.
  const groups = new Map<string, { o: Option; score: number }[]>();
  for (const o of options) {
    const score = scoreOf(o);
    if (score < 0) continue;
    const bucket = groups.get(o.model);
    if (bucket) bucket.push({ o, score });
    else groups.set(o.model, [{ o, score }]);
  }

  // A typed query re-orders the groups by their best hit; an untouched list keeps scope order.
  const ordered = [...groups.values()];
  if (q) {
    ordered.sort((a, b) => Math.min(...a.map((x) => x.score)) - Math.min(...b.map((x) => x.score)));
  }
  return ordered.flatMap((bucket) =>
    [...bucket].sort((a, b) => a.score - b.score).map((x) => x.o));
}
