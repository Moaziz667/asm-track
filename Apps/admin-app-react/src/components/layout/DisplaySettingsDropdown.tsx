import React, { useRef, useState, useEffect } from 'react';
import { IconLayoutGrid, IconX } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';

export type Density = 'compact' | 'comfortable' | 'spacious';

export interface ColumnConfig {
  key: string;
  label: string;
  visible: boolean;
}

export interface DisplaySettingsConfig {
  /** localStorage key for persisting density */
  storageKey: string;
  density: Density;
  onDensityChange: (d: Density) => void;
  columns?: ColumnConfig[];
  onColumnToggle?: (key: string) => void;
}

const DENSITIES: { value: Density; rowH: string }[] = [
  { value: 'compact',     rowH: '32px' },
  { value: 'comfortable', rowH: '44px' },
  { value: 'spacious',    rowH: '56px' },
];

export function DisplaySettingsDropdown({ config }: { config: DisplaySettingsConfig }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const anchorRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [pos, setPos] = useState({ top: 0, left: 0 });

  useEffect(() => {
    if (open && anchorRef.current) {
      const r = anchorRef.current.getBoundingClientRect();
      setPos({ top: r.bottom + 4, left: r.right - 220 });
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handle = (e: MouseEvent) => {
      if (
        panelRef.current && !panelRef.current.contains(e.target as Node) &&
        anchorRef.current && !anchorRef.current.contains(e.target as Node)
      ) setOpen(false);
    };
    document.addEventListener('mousedown', handle);
    return () => document.removeEventListener('mousedown', handle);
  }, [open]);

  return (
    <>
      <button
        ref={anchorRef}
        type="button"
        onClick={() => setOpen(o => !o)}
        title={t.displaySettings.title}
        className="flex items-center gap-1.5 h-8 px-2.5 rounded-full text-sm font-[500] border transition-colors hover:bg-[var(--hover-bg)] shrink-0"
        style={{
          borderColor: open ? 'var(--brand-blue)' : 'var(--border)',
          background: open ? 'var(--brand-blue-soft)' : 'var(--app-bg)',
          color: open ? 'var(--brand-blue)' : 'var(--text-muted)',
        }}
      >
        <IconLayoutGrid size={13} />
        <span className="hidden sm:inline">{t.displaySettings.display}</span>
      </button>

      {open && (
        <div
          ref={panelRef}
          className="fixed z-[200] rounded-[var(--radius)] overflow-hidden"
          style={{
            top: pos.top,
            left: pos.left,
            width: 220,
            background: 'var(--surface)',
            boxShadow: 'var(--shadow-card-hover)',
            border: '1px solid var(--border)',
          }}
        >
          <div className="flex items-center justify-between px-3 py-2 border-b" style={{ borderColor: 'var(--border)' }}>
            <span className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{t.displaySettings.title}</span>
            <button type="button" onClick={() => setOpen(false)} className="hover:opacity-70 transition-opacity">
              <IconX size={12} style={{ color: 'var(--text-muted)' }} />
            </button>
          </div>

          {/* Density */}
          <div className="p-3">
            <p className="text-2xs font-semibold uppercase tracking-wider mb-2" style={{ color: 'var(--text-muted)' }}>{t.displaySettings.density}</p>
            <div className="flex flex-col gap-1">
              {DENSITIES.map(d => (
                <button
                  key={d.value}
                  type="button"
                  onClick={() => { config.onDensityChange(d.value); }}
                  className={cn(
                    'flex items-center gap-2.5 px-2.5 py-1.5 rounded text-sm transition-colors',
                    config.density === d.value
                      ? 'bg-[var(--brand-blue-soft)] text-[var(--brand-blue)] font-[500]'
                      : 'hover:bg-[var(--hover-bg)] text-[var(--text-muted)]'
                  )}
                >
                  <div
                    className="w-5 flex flex-col justify-center gap-px shrink-0"
                    style={{ height: 14 }}
                  >
                    {Array.from({ length: d.value === 'compact' ? 4 : d.value === 'comfortable' ? 3 : 2 }).map((_, i) => (
                      <div key={i} className="w-full rounded-sm" style={{ height: d.value === 'spacious' ? 4 : 3, background: 'currentColor', opacity: 0.5 }} />
                    ))}
                  </div>
                  {d.value === 'compact' ? t.displaySettings.compact : d.value === 'comfortable' ? t.displaySettings.comfortable : t.displaySettings.spacious}
                </button>
              ))}
            </div>
          </div>

          {/* Columns */}
          {config.columns && config.columns.length > 0 && (
            <div className="p-3 border-t" style={{ borderColor: 'var(--border)' }}>
              <p className="text-2xs font-semibold uppercase tracking-wider mb-2" style={{ color: 'var(--text-muted)' }}>{t.displaySettings.visibleColumns}</p>
              <div className="flex flex-col gap-1">
                {config.columns.map(col => (
                  <button
                    key={col.key}
                    type="button"
                    onClick={() => config.onColumnToggle?.(col.key)}
                    className="flex items-center gap-2.5 px-2.5 py-1.5 rounded text-sm transition-colors hover:bg-[var(--hover-bg)]"
                    style={{ color: 'var(--text-primary)' }}
                  >
                    <div
                      className="w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors"
                      style={{
                        borderColor: col.visible ? 'var(--brand-blue)' : 'var(--border-strong)',
                        background: col.visible ? 'var(--brand-blue)' : 'transparent',
                      }}
                    >
                      {col.visible && (
                        <svg width="8" height="6" viewBox="0 0 8 6" fill="none">
                          <path d="M1 3l2 2 4-4" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      )}
                    </div>
                    {col.label}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </>
  );
}

/**
 * Hook to manage density state with localStorage persistence.
 * Usage: const { density, setDensity } = useDensity('my-page-key')
 */
export function useDensity(storageKey: string, defaultDensity: Density = 'comfortable') {
  const [density, setDensityState] = useState<Density>(() => {
    try {
      const stored = localStorage.getItem(`display-density:${storageKey}`);
      if (stored === 'compact' || stored === 'comfortable' || stored === 'spacious') return stored;
    } catch { /* ignore */ }
    return defaultDensity;
  });

  const setDensity = (d: Density) => {
    setDensityState(d);
    try { localStorage.setItem(`display-density:${storageKey}`, d); } catch { /* ignore */ }
  };

  return { density, setDensity };
}
