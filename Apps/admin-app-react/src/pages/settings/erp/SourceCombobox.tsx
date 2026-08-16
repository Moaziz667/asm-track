import { useEffect, useMemo, useRef, useState } from 'react';
import { IconSearch, IconCheck, IconSelector, IconX } from '@tabler/icons-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';
import { MAX_SHOWN, buildOptions, compatibilityOf, rank } from './mappingScope';
import type { CanonicalType, Compatibility, ErpField, SourceType, TypeMatrix } from '@/lib/api/erpIntegration';

/**
 * Picks one ERP field out of the few hundred a live Odoo exposes.
 *
 * <p>This replaced a native `<select>`. The `<select>` was the right instinct — familiar, unclippable,
 * type-ahead for free — but it only holds up while the list is browsable, and six models at ~100
 * fields each is not browsable: type-ahead there matches the option's *leading* characters, so an
 * integrator hunting `x_client_nom` by typing "client" lands nowhere. The control has to search the
 * way the user thinks about the field, which means substring matching over both the technical name
 * and the human label.
 *
 * <p>Two things do the real work of making the list small. Scope: a line row only offers the models a
 * line can actually read, so half the catalogue never appears. And ranking: the customer's own `x_*`
 * fields sort first, because they are the ones no probe could have guessed and the reason a human is
 * on this screen at all.
 */
export function SourceCombobox({
  value, availableFields, models, primaryModel, disabled, placeholder, copy, onChange,
  targetType, typeMatrix,
}: {
  value: string;
  availableFields: Record<string, ErpField[]>;
  /** Models this row may read from, most relevant first. */
  models: string[];
  /**
   * The document a bare path is relative to — must match the resolver, or the mapping reads nothing.
   * Undefined until the scopes have loaded, which disables the control: without it there is no way
   * to know whether a chosen field is stored bare or qualified, and guessing writes a broken path.
   */
  primaryModel: string | undefined;
  disabled: boolean;
  placeholder: string;
  copy: Record<string, string>;
  onChange: (path: string) => void;
  /** What the canonical field expects. Undefined for a customer-defined extra, which accepts anything. */
  targetType?: CanonicalType;
  /** Server-derived compatibility. Undefined while loading — the picker then blocks nothing. */
  typeMatrix?: TypeMatrix;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  /** Every selectable field, flattened once, with the path the resolver will later parse. */
  const options = useMemo(
    () => buildOptions(models, availableFields, primaryModel),
    [availableFields, models, primaryModel]);

  const matches = useMemo(() => rank(options, query), [options, query]);

  /**
   * The verdict per row, so an incompatible field is visibly out of reach before it is clicked.
   * Computed here rather than in `buildOptions` because it depends on the row being filled, and the
   * same catalogue serves every row of the screen.
   */
  const verdictOf = (o: { sourceType: SourceType }) =>
    compatibilityOf(targetType, o.sourceType, typeMatrix);
  const shown = matches.slice(0, MAX_SHOWN);
  const hidden = matches.length - shown.length;

  const selected = options.find((o) => o.path === value);

  /** A fresh query invalidates the highlight — keep it on the best match, not a stale row. */
  const search = (next: string) => {
    setQuery(next);
    setActive(0);
  };

  /** Each opening starts from a clean list, so a previous hunt does not hide the current value. */
  const toggle = (next: boolean) => {
    setOpen(next);
    if (!next) search('');
  };

  // Follow the highlight when the keyboard drives it past the fold.
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' });
  }, [active, query]);

  const commit = (path: string, verdict: Compatibility) => {
    // Refused here as well as on the server: clicking a greyed row should do nothing, not submit and
    // bounce. The server check remains the one that counts.
    if (verdict === 'UNSUPPORTED') return;
    onChange(path);
    toggle(false);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (shown.length === 0) return;
      const step = e.key === 'ArrowDown' ? 1 : -1;
      setActive((i) => (i + step + shown.length) % shown.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (shown[active]) commit(shown[active].path, verdictOf(shown[active]));
    } else if (e.key === 'Escape') {
      toggle(false);
    }
  };

  return (
    <Popover open={open} onOpenChange={toggle}>
      <PopoverTrigger
        disabled={disabled || !primaryModel}
        className={cn(
          'flex w-full h-8 items-center gap-1.5 rounded-lg px-2 text-left',
          'bg-[var(--surface)] border border-[var(--border-strong)]',
          'transition-colors duration-150',
          'hover:border-[var(--brand)] focus:border-[var(--brand)]',
          'focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] focus-visible:ring-offset-1',
          'disabled:opacity-60 disabled:cursor-not-allowed',
        )}
      >
        <span className={cn(
          'flex-1 min-w-0 truncate font-mono text-xs',
          value ? 'text-[var(--text-primary)]' : 'text-[var(--text-soft)]',
        )}>
          {value || placeholder}
        </span>
        {value && !disabled && (
          // Clearing is the second most common action here; making it a trip through the list would
          // be a step backwards from the `<select>`, which had "default" as its first option.
          <span
            role="button"
            tabIndex={-1}
            aria-label={copy.resetToDefault}
            title={copy.resetToDefault}
            onPointerDown={(e) => { e.preventDefault(); e.stopPropagation(); commit(''); }}
            className="grid place-items-center h-4 w-4 shrink-0 rounded text-[var(--text-muted)] hover:text-[var(--text-primary)]"
          >
            <IconX size={12} />
          </span>
        )}
        <IconSelector size={13} className="shrink-0 text-[var(--text-muted)]" />
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className="w-[min(30rem,calc(100vw-2rem))] gap-0 p-0 overflow-hidden"
      >
        <div className="flex items-center gap-2 border-b border-[var(--border)] px-2.5 py-2">
          <IconSearch size={14} className="shrink-0 text-[var(--text-muted)]" />
          <input
            autoFocus
            value={query}
            onChange={(e) => search(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={copy.searchFields}
            aria-label={copy.searchFields}
            className={cn(
              'w-full bg-transparent text-xs text-[var(--text-primary)]',
              'placeholder:text-[var(--text-soft)] focus:outline-none',
            )}
          />
        </div>

        <div ref={listRef} role="listbox" className="max-h-72 overflow-y-auto py-1">
          <button
            type="button"
            role="option"
            aria-selected={!value}
            onClick={() => commit('')}
            className={cn(
              'flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-xs',
              'text-[var(--text-secondary)] hover:bg-[var(--hover-bg)]',
            )}
          >
            <IconCheck size={13} className={cn('shrink-0', value && 'invisible')} />
            {copy.usingDefault}
          </button>

          {shown.map((o, i) => {
            const verdict = verdictOf(o);
            const first = i === 0 || shown[i - 1].model !== o.model;
            return (
              <div key={o.path}>
                {first && (
                  <div className={cn(
                    'px-2.5 pt-2 pb-1 font-mono text-2xs text-[var(--text-muted)]',
                    i > 0 && 'mt-1 border-t border-[var(--border)]',
                  )}>
                    {o.model}
                    {o.model === primaryModel && (
                      <span className="ml-1.5 font-sans not-italic text-[var(--text-soft)]">
                        {copy.primaryModelHint}
                      </span>
                    )}
                  </div>
                )}
                <button
                  type="button"
                  role="option"
                  aria-selected={o.path === value}
                  data-active={i === active}
                  onClick={() => commit(o.path, verdict)}
                  onPointerMove={() => setActive(i)}
                  disabled={verdict === 'UNSUPPORTED'}
                  title={verdict === 'UNSUPPORTED'
                    ? (copy.typeIncompatible ?? '').replace('{type}', o.erpType)
                    : verdict === 'LOSSY' ? copy.typeLossyShort : undefined}
                  className={cn(
                    'flex w-full items-center gap-2 px-2.5 py-1.5 text-left',
                    i === active && verdict !== 'UNSUPPORTED' && 'bg-[var(--hover-bg)]',
                    verdict === 'UNSUPPORTED' && 'cursor-not-allowed opacity-40',
                  )}
                >
                  <IconCheck size={13} className={cn(
                    'shrink-0 text-[var(--brand)]', o.path !== value && 'invisible',
                  )} />
                  <span className="flex-1 min-w-0">
                    <span className="flex items-center gap-1.5">
                      {o.custom && <span className="text-2xs text-[var(--brand)]">★</span>}
                      <span className="truncate font-mono text-xs text-[var(--text-primary)]">
                        {o.name}
                      </span>
                    </span>
                    <span className="block truncate text-2xs text-[var(--text-muted)]">
                      {o.label}
                      {/* The ERP's own type name: the integrator recognises it from his own screen,
                          where the normalised one would mean nothing to him. */}
                      <span className="ml-1.5 font-mono text-[var(--text-soft)]">· {o.erpType}</span>
                      {verdict === 'LOSSY' && (
                        <span className="ml-1.5 text-[var(--warning)]">· {copy.typeLossyBadge}</span>
                      )}
                    </span>
                  </span>
                </button>
              </div>
            );
          })}

          {matches.length === 0 && (
            <p className="px-2.5 py-6 text-center text-xs text-[var(--text-muted)]">
              {copy.noFieldMatch}
            </p>
          )}

          {hidden > 0 && (
            // The cap keeps the popover responsive on a large Odoo. Saying so — with the count — is
            // what stops it reading as "the field I want isn't there".
            <p className="mt-1 border-t border-[var(--border)] px-2.5 py-2 text-2xs text-[var(--text-muted)]">
              {(copy.moreFields ?? '+{n}').replace('{n}', String(hidden))}
            </p>
          )}
        </div>

        {selected && (
          <div className="border-t border-[var(--border)] bg-[var(--surface-sunken)] px-2.5 py-1.5">
            <span className="font-mono text-2xs text-[var(--text-muted)]">{selected.label}</span>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}
