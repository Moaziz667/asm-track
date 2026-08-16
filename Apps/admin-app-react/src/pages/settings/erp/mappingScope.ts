import type { Compatibility, CanonicalType, SourceType, TypeMatrix, ErpField, FieldScope } from '@/lib/api/erpIntegration';

/**
 * What a mapping row may read from, and how a chosen field becomes a stored path.
 *
 * <p>Kept apart from the components so it can be tested without a DOM. The scope itself is no longer
 * declared here: it comes from the backend, because it is the one rule the picker and the resolver
 * must agree on and the one whose disagreement is silent — a path built against the wrong document
 * resolves to nothing and reads as an empty ERP rather than a bad mapping. Hardcoding Odoo's models
 * here also meant an ERPNext tenant would have been offered `stock.picking`.
 */

/** Where a scope's mappings may read from, as served by the backend. */
export interface ScopeInfo {
  /** The document a path with no `Model:` prefix is relative to. */
  primary: string;
  /** Every document in scope including the primary, most relevant first. */
  addressable: string[];
}

export type MappingScopes = Partial<Record<FieldScope, ScopeInfo>>;

/**
 * Drop what this ERP did not report, so the picker never offers an empty group.
 *
 * <p>Returns nothing until the scopes have loaded: an empty picker for a moment is honest, whereas
 * guessing a default would be the hardcoding this call exists to remove.
 */
export function modelsFor(
  scope: FieldScope,
  availableFields: Record<string, ErpField[]>,
  scopes: MappingScopes,
) {
  const wanted = scopes[scope]?.addressable ?? [];
  return wanted.filter((m) => (availableFields[m]?.length ?? 0) > 0);
}

/** The document a bare path hangs off, or `undefined` while the scopes are still loading. */
export function primaryModelFor(scope: FieldScope, scopes: MappingScopes) {
  return scopes[scope]?.primary;
}

/** One selectable ERP field, with the path the resolver will later parse. */
export interface Option {
  model: string;
  name: string;
  label: string;
  custom: boolean;
  path: string;
  /** The ERP's own type name, shown so the integrator sees what he is choosing. */
  erpType: string;
  /** Normalised type, what compatibility is judged on. */
  sourceType: SourceType;
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
  primaryModel: string | undefined,
): Option[] {
  if (!primaryModel) return [];
  const out: Option[] = [];
  for (const model of models) {
    for (const f of availableFields[model] ?? []) {
      out.push({
        model,
        name: f.name,
        label: f.label,
        custom: f.custom,
        path: model === primaryModel ? f.name : `${model}:${f.name}`,
        erpType: f.type,
        // Older payloads predate the normalised type; treating them as UNKNOWN would grey out the
        // whole catalogue, so they fall through to TEXT and the server stays the authority.
        sourceType: f.sourceType ?? 'TEXT',
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

/**
 * How well one ERP field could fill one canonical field.
 *
 * <p>Reads the matrix the server derived from its registered converters rather than re-stating the
 * rules here. A second copy would eventually disagree with the save, and the integrator would be told
 * yes by a dropdown and no by a dialog.
 *
 * <p>Absent matrix — still loading, or an old backend — yields `SAFE`, so the picker never blocks on
 * information it does not have. The save re-checks regardless.
 */
export function compatibilityOf(
  target: CanonicalType | undefined,
  source: SourceType,
  matrix: TypeMatrix | undefined,
): Compatibility {
  if (!target || !matrix) return 'SAFE';
  const row = matrix[target];
  if (!row) return 'SAFE';
  return row[source] ?? 'UNSUPPORTED';
}
