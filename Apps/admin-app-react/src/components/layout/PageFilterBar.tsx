import React, { useRef, useState, useEffect, useLayoutEffect } from 'react';
import { createPortal } from 'react-dom';
import { IconSearch, IconChevronDown, IconChevronLeft, IconX } from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { cn } from '@/lib/utils';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';
import { useT } from '@/lib/i18n/LocaleContext';

// ── Public types ──────────────────────────────────────────────────────────────

export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterAttribute {
  key: string;
  label: string;
  options?: FilterOption[];
  type?: 'select' | 'date';
  /** When true, the value list is multi-select: clicking toggles a value and the menu stays open.
   *  The active value for this key is then a string[] (the consumer toggles it on each change). */
  multi?: boolean;
}

/** Active value for a filter key — a single value (single-select) or a list (multi-select). */
export type ActiveFilterValue = string | string[];

/** Read helper: normalize a single/array active value to an array. */
function asArray(v: ActiveFilterValue | undefined): string[] {
  return v == null ? [] : Array.isArray(v) ? v : [v];
}

export interface PageFilterBarProps {
  /** Controlled search string */
  search?: string;
  onSearch?: (v: string) => void;
  searchPlaceholder?: string;
  /** Attribute definitions for the 2-step dropdown */
  attributes?: FilterAttribute[];
  /** Currently active filter values: { attrKey: selectedValue | selectedValues[] } */
  activeFilters?: Record<string, ActiveFilterValue>;
  onFilterChange?: (key: string, value: string | null) => void;
  onRefresh?: () => void;
  refreshing?: boolean;
  /** Quick-filter pills rendered below the toolbar (e.g. all/today/failed) */
  quickFilters?: { value: string; label: string; count?: number }[];
  activeQuickFilter?: string;
  onQuickFilterChange?: (v: string) => void;
  /** Slot for extra actions injected between Clear and Refresh */
  extraActions?: React.ReactNode;
  className?: string;
}

// ── Internal: 2-step attribute dropdown ──────────────────────────────────────

interface DropdownProps {
  anchorRef: React.RefObject<HTMLElement | null>;
  open: boolean;
  onClose: () => void;
  attributes: FilterAttribute[];
  activeFilters: Record<string, ActiveFilterValue>;
  onFilterChange: (key: string, value: string | null) => void;
}

function FilterDropdown({ anchorRef, open, onClose, attributes, activeFilters, onFilterChange }: DropdownProps) {
  const t = useT();
  const ref = useRef<HTMLDivElement>(null);
  const [step, setStep] = useState<'attrs' | string>('attrs');
  const [pos, setPos] = useState({ top: 0, left: 0, width: 240 });
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  // useLayoutEffect so the dropdown is positioned BEFORE the browser paints — a plain useEffect
  // paints it once at {0,0} (top-left, shadow included) then repositions → the brief shadow flash.
  useLayoutEffect(() => {
    if (!open || !anchorRef.current) {
      if (!open) setStep('attrs');
      return;
    }

    const updatePosition = () => {
      if (anchorRef.current) {
        const r = anchorRef.current.getBoundingClientRect();
        setPos({ top: r.bottom + 4, left: r.left, width: Math.max(240, r.width) });
      }
    };

    updatePosition();

    // Listen to resize and scroll to keep dropdown aligned
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);

    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [open, anchorRef]);

  useEffect(() => {
    if (!open) return;
    const handle = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node) &&
          anchorRef.current && !anchorRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener('mousedown', handle);
    return () => document.removeEventListener('mousedown', handle);
  }, [open, onClose, anchorRef]);

  if (!open || !mounted) return null;

  const activeAttr = step !== 'attrs' ? attributes.find(a => a.key === step) : null;

  return createPortal(
    <div
      ref={ref}
      className="fixed z-[9999] rounded-[var(--radius)] overflow-hidden"
      style={{
        top: pos.top,
        left: pos.left,
        width: pos.width,
        background: 'var(--surface)',
        boxShadow: 'var(--shadow-dropdown)',
        border: '1px solid var(--border-strong)',
      }}
    >
      {/* Step 1 — attribute list */}
      {step === 'attrs' && (
        <div className="py-1">
          <div className="px-3 py-1.5 text-2xs font-semibold tracking-wider uppercase" style={{ color: 'var(--text-muted)' }}>
            {t.common?.filtrerPar ?? 'Filtrer par'}
          </div>
          {attributes.map(attr => {
            const hasValue = asArray(activeFilters[attr.key]).length > 0;
            return (
              <button
                key={attr.key}
                type="button"
                className="w-full flex items-center justify-between gap-2 px-3 py-2 text-sm hover:bg-[var(--hover-bg)] transition-colors"
                style={{ color: hasValue ? 'var(--brand)' : 'var(--text-primary)' }}
                onClick={() => {
                  if (attr.type === 'date') {
                    // date fields don't have a step-2 list — handled inline
                    setStep(attr.key);
                  } else {
                    setStep(attr.key);
                  }
                }}
              >
                <span>{attr.label}</span>
                <div className="flex items-center gap-1.5 shrink-0">
                  {hasValue && <div style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand)' }} />}
                  <IconChevronDown size={12} style={{ color: 'var(--text-muted)' }} />
                </div>
              </button>
            );
          })}
        </div>
      )}

      {/* Step 2 — value list or date input */}
      {step !== 'attrs' && activeAttr && (
        <div>
          <div className="flex items-center gap-2 px-3 py-2 border-b" style={{ borderColor: 'var(--border)' }}>
            <button
              type="button"
              className="flex items-center gap-1 text-xs font-medium hover:opacity-70 transition-opacity"
              style={{ color: 'var(--text-muted)' }}
              onClick={() => setStep('attrs')}
            >
              <IconChevronLeft size={12} />
              Retour
            </button>
            <span className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{activeAttr.label}</span>
          </div>

          {activeAttr.type === 'date' ? (
            <div className="p-3">
              <DatePickerPopover
                value={asArray(activeFilters[activeAttr.key])[0] ?? null}
                onChange={val => { onFilterChange(activeAttr.key, val); }}
                placeholder="Choisir une date"
                className="w-full !h-8 bg-[var(--app-bg)] text-[var(--text-primary)] border-[var(--border)] rounded text-sm"
              />
              {activeFilters[activeAttr.key] && (
                <button
                  type="button"
                  className="mt-2 w-full text-xs font-medium text-center hover:opacity-70"
                  style={{ color: 'var(--brand)' }}
                  onClick={() => { onFilterChange(activeAttr.key, null); setStep('attrs'); }}
                >
                  {t.common?.effacer ?? 'Effacer'}
                </button>
              )}
            </div>
          ) : (
            <div className="py-1 max-h-56 overflow-y-auto">
              {(activeAttr.options ?? []).map(opt => {
                const isActive = asArray(activeFilters[activeAttr.key]).includes(opt.value);
                return (
                  <button
                    key={opt.value}
                    type="button"
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-sm hover:bg-[var(--hover-bg)] transition-colors"
                    style={{ color: 'var(--text-primary)' }}
                    onClick={() => {
                      // Multi: toggle the clicked value and keep the menu open to pick more.
                      // Single: replace (or clear) and close, as before.
                      onFilterChange(activeAttr.key, isActive && !activeAttr.multi ? null : opt.value);
                      if (!activeAttr.multi) onClose();
                    }}
                  >
                    <div
                      className="w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors"
                      style={{
                        borderColor: isActive ? 'var(--brand-blue)' : 'var(--border-strong)',
                        background: isActive ? 'var(--brand-blue)' : 'transparent',
                      }}
                    >
                      {isActive && (
                        <svg width="8" height="6" viewBox="0 0 8 6" fill="none">
                          <path d="M1 3l2 2 4-4" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      )}
                    </div>
                    <span className={isActive ? 'font-[500]' : ''}>{opt.label}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>,
    document.body
  );
}

// ── Internal: active filter token ─────────────────────────────────────────────

function FilterToken({ attrLabel, valueLabel, onRemove }: { attrLabel: string; valueLabel: string; onRemove: () => void }) {
  return (
    <span
      className="inline-flex items-center gap-1 text-xs font-[500] px-2 py-0.5 rounded border shrink-0"
      style={{ color: 'var(--brand-blue)', borderColor: 'var(--brand-blue)', background: 'var(--brand-blue-soft)' }}
    >
      <span style={{ color: 'var(--text-soft)' }}>{attrLabel}:</span>
      {valueLabel}
      <button type="button" className="ml-0.5 hover:opacity-70 transition-opacity" onClick={onRemove}>
        <IconX size={10} />
      </button>
    </span>
  );
}

// ── Main export ───────────────────────────────────────────────────────────────

export function PageFilterBar({
  search = '',
  onSearch,
  searchPlaceholder,
  attributes,
  activeFilters = {},
  onFilterChange,
  onRefresh,
  refreshing = false,
  quickFilters,
  activeQuickFilter,
  onQuickFilterChange,
  extraActions,
  className,
}: PageFilterBarProps) {
  const t = useT();
  const resolvedSearchPlaceholder = searchPlaceholder ?? t.common?.rechercher ?? 'Rechercher…';
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const filterBtnRef = useRef<HTMLButtonElement | null>(null);

  // One token per selected value (multi-select keys expand to several tokens).
  const activeTokens = attributes
    ? attributes.flatMap(a =>
        asArray(activeFilters[a.key]).map(raw => ({
          key: a.key,
          value: raw,
          multi: !!a.multi,
          attrLabel: a.label,
          valueLabel: a.options?.find(o => o.value === raw)?.label ?? raw,
        })))
    : [];

  const hasActive = activeTokens.length > 0;
  const activeCount = attributes
    ? attributes.filter(a => asArray(activeFilters[a.key]).length > 0).length
    : 0;

  const handleClearAll = () => {
    // Clear each active key once (null = clear the whole value, single or array).
    Array.from(new Set(activeTokens.map(t => t.key))).forEach(key => onFilterChange?.(key, null));
    onSearch?.('');
  };

  return (
    <div
      className={cn('shrink-0', className)}
      style={{
        background: 'var(--surface)',
        boxShadow: 'var(--shadow-sm)',
        fontFamily: "'Clear Sans',system-ui,sans-serif",
      }}
    >
      {/* Toolbar row */}
      <div className="flex flex-wrap md:flex-nowrap items-center gap-2 px-4 py-2 md:py-0 min-h-[44px] md:h-11">
        {/* Search */}
        {onSearch !== undefined && (
          <div className="relative flex items-center flex-1 max-w-sm">
            <IconSearch size={13} className="absolute left-2.5 pointer-events-none" style={{ color: 'var(--text-muted)' }} />
            <input
              type="text"
              value={search}
              onChange={e => onSearch(e.target.value)}
              placeholder=            {resolvedSearchPlaceholder}
              className="w-full h-8 pl-7 pr-3 rounded-full text-sm border outline-none focus:ring-2 focus:ring-[var(--brand-blue)] focus:ring-offset-0 transition"
              style={{
                background: 'var(--app-bg)',
                borderColor: 'var(--border)',
                color: 'var(--text-primary)',
              }}
            />
          </div>
        )}

        {/* Filter by attribute button */}
        {attributes && attributes.length > 0 && (
          <button
            ref={filterBtnRef}
            type="button"
            onClick={() => setDropdownOpen(o => !o)}
            className="flex items-center gap-1.5 h-8 px-3 rounded-full text-sm font-[500] border transition-colors hover:bg-[var(--hover-bg)] shrink-0"
            style={{
              borderColor: dropdownOpen ? 'var(--brand-blue)' : 'var(--border)',
              background: dropdownOpen ? 'var(--brand-blue-soft)' : 'var(--app-bg)',
              color: dropdownOpen ? 'var(--brand-blue)' : 'var(--text-muted)',
            }}
          >
            {t.common?.filtrer ?? 'Filtrer'}
            {activeCount > 0 && (
              <span
                className="text-2xs font-bold w-4 h-4 rounded-full flex items-center justify-center"
                style={{ background: 'var(--brand-blue)', color: '#fff' }}
              >
                {activeCount}
              </span>
            )}
            <IconChevronDown size={11} style={{ opacity: 0.6 }} />
          </button>
        )}

        {/* Separator */}
        {(hasActive || onSearch) && (
          <div className="w-px h-5 shrink-0" style={{ background: 'var(--border)' }} />
        )}

        {/* Clear all (when filters active) */}
        {hasActive && (
          <button
            type="button"
            onClick={handleClearAll}
            className="text-xs font-[500] shrink-0 hover:opacity-70 transition-opacity"
            style={{ color: 'var(--text-muted)' }}
          >
            {t.common?.effacerTout ?? 'Effacer tout'}
          </button>
        )}

        {extraActions}

        {/* Refresh */}
        {onRefresh && (
          <div className="ml-auto shrink-0">
            <RefreshButton onClick={onRefresh} refreshing={refreshing} />
          </div>
        )}
      </div>

      {/* Active filter tokens row */}
      {hasActive && (
        <div className="flex items-center flex-wrap gap-1.5 px-4 pb-2.5">
          {activeTokens.map(t => (
            <FilterToken
              key={`${t.key}-${t.value}`}
              attrLabel={t.attrLabel}
              valueLabel={t.valueLabel}
              onRemove={() => onFilterChange?.(t.key, t.multi ? t.value : null)}
            />
          ))}
        </div>
      )}

      {/* Quick-filter pills row */}
      {quickFilters && quickFilters.length > 0 && (
        <div className="flex items-center gap-1.5 px-4 pb-2.5 overflow-x-auto flex-nowrap" style={{ scrollbarWidth: 'none', WebkitOverflowScrolling: 'touch' }}>
          {quickFilters.map(qf => {
            const active = activeQuickFilter === qf.value;
            return (
              <button
                key={qf.value}
                type="button"
                onClick={() => onQuickFilterChange?.(qf.value)}
                className="inline-flex items-center gap-1.5 h-6 px-2.5 rounded-full text-xs font-[500] transition-colors border"
                style={{
                  borderColor: active ? 'var(--brand-blue)' : 'var(--border)',
                  background: active ? 'var(--brand-blue-soft)' : 'transparent',
                  color: active ? 'var(--brand-blue)' : 'var(--text-muted)',
                }}
              >
                {qf.label}
                {qf.count !== undefined && (
                  <span
                    className="text-2xs font-bold px-1 rounded-full"
                    style={{ background: active ? 'var(--brand-blue)' : 'var(--hover-bg)', color: active ? '#fff' : 'var(--text-muted)' }}
                  >
                    {qf.count}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}

      <FilterDropdown
        anchorRef={filterBtnRef}
        open={dropdownOpen}
        onClose={() => setDropdownOpen(false)}
        attributes={attributes ?? []}
        activeFilters={activeFilters}
        onFilterChange={(key, val) => {
          onFilterChange?.(key, val);
        }}
      />
    </div>
  );
}
