
import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { format, parseISO } from 'date-fns';
import { fr } from 'date-fns/locale';
import { IconCalendar } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { Calendar } from '@/components/ui/calendar';

interface DatePickerPopoverProps {
  value: string | null;
  onChange: (iso: string | null) => void;
  placeholder?: string;
}

export function DatePickerPopover({ value, onChange, placeholder = 'Choisir une date' }: DatePickerPopoverProps) {
  const selected = value ? parseISO(value) : undefined;
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState({ top: 0, right: 0 });
  const [mounted, setMounted] = useState(false);
  const btnRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setMounted(true); }, []);

  // Position the portal panel below the trigger button
  useEffect(() => {
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
        )}
      >
        <IconCalendar size={13} className="text-[var(--text-soft)] shrink-0" />
        <span className={cn('font-mono text-xs', !selected && 'text-[var(--text-soft)]')}>
          {selected ? format(selected, 'd MMM yyyy', { locale: fr }) : placeholder}
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
                Effacer la date
              </button>
            </div>
          )}
        </div>,
        document.body
      )}
    </>
  );
}

