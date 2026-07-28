import { useMemo, useState } from 'react';
import { IconRotate, IconPlus, IconTrash, IconSparkles } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type {
  CanonicalFieldInfo, ErpField, FieldMapping, UpsertMappingInput,
} from '@/lib/api/erpIntegration';

/**
 * Where, in this customer's ERP, each piece of business data lives.
 *
 * Deliberately separate from the capability report next door. Both are "mappings", but a wrong
 * capability breaks stock while a wrong field shows a wrong label — putting them in one screen would
 * let someone relabelling a customer reference corrupt quantities. Two screens, two risks.
 *
 * The dropdown lists the customer's own `x_*` fields first: they are the ones no probe could have
 * guessed, and the reason a human is doing this at all. Everything left untouched shows "défaut" and
 * runs the code that ran before mapping existed.
 */
export function StepMapping({
  canonicalFields, mappings, availableFields, loading, canManage, copy,
  onUpsert, onReset, onDeleteCustom,
}: {
  canonicalFields: CanonicalFieldInfo[];
  mappings: FieldMapping[];
  availableFields: Record<string, ErpField[]>;
  loading: boolean;
  canManage: boolean;
  copy: Record<string, string>;
  onUpsert: (input: UpsertMappingInput) => Promise<void>;
  onReset: (canonicalField: string) => Promise<void>;
  onDeleteCustom: (id: number) => Promise<void>;
}) {
  const byField = useMemo(() => {
    const m = new Map<string, FieldMapping>();
    mappings.forEach((x) => { if (x.canonicalField) m.set(x.canonicalField, x); });
    return m;
  }, [mappings]);

  const extras = useMemo(() => mappings.filter((m) => !m.canonicalField), [mappings]);

  const header = canonicalFields.filter((f) => f.scope === 'HEADER');
  const lines = canonicalFields.filter((f) => f.scope === 'LINE');

  if (loading && canonicalFields.length === 0) return <MappingSkeleton />;

  return (
    <div className="flex flex-col gap-5">
      <p className="text-xs text-[var(--text-secondary)] leading-relaxed max-w-[68ch]">
        {copy.intro}
      </p>

      <MappingSection
        title={copy.sectionHeader}
        subtitle={copy.sectionHeaderHint}
        fields={header}
        byField={byField}
        availableFields={availableFields}
        canManage={canManage}
        copy={copy}
        onUpsert={onUpsert}
        onReset={onReset}
      />

      <MappingSection
        title={copy.sectionLines}
        subtitle={copy.sectionLinesHint}
        fields={lines}
        byField={byField}
        availableFields={availableFields}
        canManage={canManage}
        copy={copy}
        onUpsert={onUpsert}
        onReset={onReset}
      />

      <ExtraFields
        extras={extras}
        availableFields={availableFields}
        canManage={canManage}
        copy={copy}
        onUpsert={onUpsert}
        onDelete={onDeleteCustom}
      />
    </div>
  );
}

// ── One section (header / lines) ──────────────────────────────────────────────────────────────────

function MappingSection({
  title, subtitle, fields, byField, availableFields, canManage, copy, onUpsert, onReset,
}: {
  title: string; subtitle: string;
  fields: CanonicalFieldInfo[];
  byField: Map<string, FieldMapping>;
  availableFields: Record<string, ErpField[]>;
  canManage: boolean;
  copy: Record<string, string>;
  onUpsert: (input: UpsertMappingInput) => Promise<void>;
  onReset: (canonicalField: string) => Promise<void>;
}) {
  if (fields.length === 0) return null;
  return (
    <section className="rounded-lg border border-[var(--border)] overflow-hidden">
      <header className="px-4 py-2.5 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
        <h3 className="text-xs font-semibold text-[var(--text-primary)]">{title}</h3>
        <p className="mt-0.5 text-2xs text-[var(--text-muted)]">{subtitle}</p>
      </header>
      <ul className="divide-y divide-[var(--border)]">
        {fields.map((f) => (
          <MappingRow
            key={f.field}
            field={f}
            mapping={byField.get(f.field)}
            availableFields={availableFields}
            canManage={canManage}
            copy={copy}
            onUpsert={onUpsert}
            onReset={onReset}
          />
        ))}
      </ul>
    </section>
  );
}

// ── One mappable field ────────────────────────────────────────────────────────────────────────────

function MappingRow({
  field, mapping, availableFields, canManage, copy, onUpsert, onReset,
}: {
  field: CanonicalFieldInfo;
  mapping?: FieldMapping;
  availableFields: Record<string, ErpField[]>;
  canManage: boolean;
  copy: Record<string, string>;
  onUpsert: (input: UpsertMappingInput) => Promise<void>;
  onReset: (canonicalField: string) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const mapped = Boolean(mapping);

  const change = async (path: string) => {
    setBusy(true);
    try {
      if (!path) await onReset(field.field);
      else await onUpsert({ canonicalField: field.field, sourcePath: path });
    } finally { setBusy(false); }
  };

  return (
    <li className="grid grid-cols-1 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.35fr)_auto] items-center gap-2 px-4 py-2.5">
      <div className="min-w-0">
        <p className="text-base text-[var(--text-primary)] truncate">
          {humanize(field.field)}
        </p>
        <p className="font-mono text-2xs text-[var(--text-soft)] truncate">{field.field}</p>
      </div>

      <SourcePicker
        value={mapping?.sourcePath ?? ''}
        availableFields={availableFields}
        disabled={!canManage || busy}
        placeholder={copy.usingDefault}
        onChange={change}
      />

      <div className="flex items-center justify-end gap-1.5 min-w-[72px]">
        {mapped ? (
          <>
            <span
              className="text-2xs font-medium px-1.5 py-0.5 rounded"
              style={{ color: 'var(--brand)', background: 'var(--brand-soft)' }}
            >
              {copy.overridden}
            </span>
            {canManage && (
              <button
                type="button"
                onClick={() => change('')}
                disabled={busy}
                title={copy.resetToDefault}
                aria-label={`${copy.resetToDefault} — ${humanize(field.field)}`}
                className={cn(
                  'grid place-items-center h-6 w-6 rounded text-[var(--text-muted)]',
                  'hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)] transition-colors duration-150',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)]',
                )}
              >
                <IconRotate size={14} />
              </button>
            )}
          </>
        ) : (
          <span className="text-2xs text-[var(--text-soft)]">{copy.defaultTag}</span>
        )}
      </div>
    </li>
  );
}

// ── The picker ────────────────────────────────────────────────────────────────────────────────────

/**
 * A native `<select>` on purpose. It is searchable by typing on every platform, it never gets clipped
 * by an overflow container, and it is the control users already know — reinventing it for flavour is
 * exactly the product-register trap. Custom `x_*` fields are grouped first because they are what the
 * integrator came here for.
 */
function SourcePicker({
  value, availableFields, disabled, placeholder, onChange,
}: {
  value: string;
  availableFields: Record<string, ErpField[]>;
  disabled: boolean;
  placeholder: string;
  onChange: (path: string) => void;
}) {
  const models = Object.keys(availableFields);

  // A stored path may point at a field the ERP no longer exposes; keep it selectable so the
  // integrator sees what is configured instead of the row silently resetting itself.
  const known = models.some((m) =>
    availableFields[m].some((f) => f.name === value || `${m}:${f.name}` === value));

  return (
    <select
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      className={cn(
        'w-full h-8 rounded-lg px-2 text-xs font-mono',
        'bg-[var(--surface)] border border-[var(--border-strong)] text-[var(--text-primary)]',
        'transition-colors duration-150',
        'hover:border-[var(--brand)] focus:border-[var(--brand)]',
        'focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] focus-visible:ring-offset-1',
        'disabled:opacity-60 disabled:cursor-not-allowed',
      )}
    >
      <option value="">{placeholder}</option>
      {value && !known && <option value={value}>{value}</option>}
      {models.map((model) => {
        const fields = availableFields[model] ?? [];
        const custom = fields.filter((f) => f.custom);
        const standard = fields.filter((f) => !f.custom);
        const prefix = model === 'stock.picking' ? '' : `${model}:`;
        return (
          <optgroup key={model} label={model}>
            {custom.map((f) => (
              <option key={`${model}.${f.name}`} value={`${prefix}${f.name}`}>
                ★ {f.name} — {f.label}
              </option>
            ))}
            {standard.map((f) => (
              <option key={`${model}.${f.name}`} value={`${prefix}${f.name}`}>
                {f.name} — {f.label}
              </option>
            ))}
          </optgroup>
        );
      })}
    </select>
  );
}

// ── Customer-defined extras ───────────────────────────────────────────────────────────────────────

/**
 * Fields ASM has no concept of. They land in the order's `custom_fields` bag and are display-only —
 * so the screen says so, rather than letting someone map a priority here and wonder why nothing
 * sorts by it.
 */
function ExtraFields({
  extras, availableFields, canManage, copy, onUpsert, onDelete,
}: {
  extras: FieldMapping[];
  availableFields: Record<string, ErpField[]>;
  canManage: boolean;
  copy: Record<string, string>;
  onUpsert: (input: UpsertMappingInput) => Promise<void>;
  onDelete: (id: number) => Promise<void>;
}) {
  const [label, setLabel] = useState('');
  const [path, setPath] = useState('');
  const [busy, setBusy] = useState(false);

  const add = async () => {
    if (!label.trim() || !path || busy) return;
    setBusy(true);
    try {
      await onUpsert({ customKey: label.trim(), sourcePath: path });
      setLabel(''); setPath('');
    } finally { setBusy(false); }
  };

  return (
    <section className="rounded-lg border border-[var(--border)] overflow-hidden">
      <header className="px-4 py-2.5 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
        <h3 className="flex items-center gap-1.5 text-xs font-semibold text-[var(--text-primary)]">
          <IconSparkles size={13} className="text-[var(--text-muted)]" />
          {copy.extrasTitle}
        </h3>
        <p className="mt-0.5 text-2xs text-[var(--text-muted)]">{copy.extrasHint}</p>
      </header>

      {extras.length > 0 && (
        <ul className="divide-y divide-[var(--border)]">
          {extras.map((x) => (
            <li key={x.id} className="flex items-center gap-2 px-4 py-2.5">
              <span className="text-base text-[var(--text-primary)] truncate flex-1 min-w-0">
                {x.customKey}
              </span>
              <span className="font-mono text-2xs text-[var(--text-muted)] truncate flex-1 min-w-0">
                {x.sourcePath}
              </span>
              {canManage && (
                <button
                  type="button"
                  onClick={() => onDelete(x.id)}
                  title={copy.removeExtra}
                  aria-label={`${copy.removeExtra} — ${x.customKey}`}
                  className={cn(
                    'grid place-items-center h-6 w-6 rounded text-[var(--text-muted)] shrink-0',
                    'hover:bg-[var(--hover-bg)] hover:text-[var(--danger)] transition-colors duration-150',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)]',
                  )}
                >
                  <IconTrash size={14} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <div className="grid grid-cols-1 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.35fr)_auto] items-end gap-2 px-4 py-3">
          <FieldInput
            label={copy.extraLabel}
            placeholder={copy.extraLabelPlaceholder}
            value={label}
            onChange={(e) => setLabel(e.target.value)}
          />
          <div className="flex flex-col gap-1">
            <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
              {copy.extraSource}
            </span>
            <SourcePicker
              value={path}
              availableFields={availableFields}
              disabled={busy}
              placeholder={copy.chooseField}
              onChange={setPath}
            />
          </div>
          <Button size="sm" variant="outline" onClick={add} disabled={!label.trim() || !path || busy}>
            <IconPlus size={15} /> {copy.addExtra}
          </Button>
        </div>
      )}

      {extras.length === 0 && !canManage && (
        <p className="px-4 py-6 text-center text-xs text-[var(--text-muted)]">{copy.extrasEmpty}</p>
      )}
    </section>
  );
}

// ── Helpers ───────────────────────────────────────────────────────────────────────────────────────

/** CUSTOMER_NAME → "Customer name". The canonical key stays visible underneath for support. */
function humanize(field: string) {
  const s = field.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function MappingSkeleton() {
  return (
    <div className="flex flex-col gap-4">
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-[260px] w-full rounded-lg" />
      <Skeleton className="h-[140px] w-full rounded-lg" />
    </div>
  );
}
