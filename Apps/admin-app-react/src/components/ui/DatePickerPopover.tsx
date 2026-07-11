
import { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { createPortal } from 'react-dom';
import { format, parseISO } from 'date-fns';
import { fr } from 'date-fns/locale';
import { IconCalendar } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import { Calendar } from '@/components/ui/calendar';

interface DatePickerPopoverProps {
  value: string | null;
  onChange: (iso: string | null) => void;
  placeholder?: string;
  className?: string;
  /** Earliest selectable day — days strictly before this are disabled (e.g. no past dates). */
  minDate?: Date;
}

export function DatePickerPopover({ value, onChange, placeholder, className, minDate }: DatePickerPopoverProps) {
  const t = useT();
  const selected = value ? parseISO(value) : undefined;
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState({ top: 0, right: 0 });
  const [mounted, setMounted] = useState(false);
  const btnRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setMounted(true); }, []);

  // Position the portal panel below the trigger button — useLayoutEffect so the position is set
  // BEFORE the browser paints. With a plain useEffect the panel paints once at {0,0} (top-right,
  // shadow and all) then jumps into place → the brief shadow flash on open.
  useLayoutEffect(() => {
    if (!open || !btnRef.current) return;
    const rect = btnRef.current.getBoundingClientRect();
    setPos({
      top: rect.bottom + window.scrollY + 6,
      right: window.innerWidth - rect.right,
    });
  }, [open]);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (
        panelRef.current && !panelRef.current.contains(e.target as Node) &&
        btnRef.current && !btnRef.current.contains(e.target as Node)
      ) setOpen(false);
    };
    if (open) document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        onClick={() => setOpen(o => !o)}
        className={cn(
          'h-8 px-2.5 flex items-center gap-1.5 rounded border text-xs font-medium transition-all',
          'bg-[var(--surface-2)] border-[var(--border)] text-[var(--text-strong)]',
          'hover:border-[var(--brand)] focus:outline-none',
          open && 'border-[var(--brand)]',
          className,
        )}
      >
        <IconCalendar size={13} className="text-[var(--text-soft)] shrink-0" />
        <span className={cn('font-mono text-xs', !selected && 'text-[var(--text-soft)]')}>
          {selected ? format(selected, 'd MMM yyyy', { locale: fr }) : (placeholder ?? t.placeholders?.date ?? 'Pick a date')}
        </span>
      </button>

      {mounted && open && createPortal(
        <div
          ref={panelRef}
          className="fixed z-[9000] bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-xl overflow-hidden"
          style={{
            top: pos.top,
            right: pos.right,
            boxShadow: '0 8px 24px rgba(0,0,0,0.14)',
          }}
        >
          <Calendar
            mode="single"
            selected={selected}
            onSelect={(day) => {
              onChange(day ? format(day, 'yyyy-MM-dd') : null);
              setOpen(false);
            }}
            disabled={minDate ? { before: minDate } : undefined}
            locale={fr}
            className="[--cell-size:28px] text-xs"
          />
          {selected && (
            <div className="border-t border-[var(--border)] px-3 py-1.5">
              <button
                type="button"
                onClick={() => { onChange(null); setOpen(false); }}
                className="text-xs font-semibold text-[var(--text-muted)] hover:text-[var(--danger)] transition-colors"
              >
                {t.common?.effacer ?? 'Clear'}
              </button>
            </div>
          )}
        </div>,
        document.body
      )}
    </>
  );
}

