
import { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { createPortal } from 'react-dom';
import { format, parseISO } from 'date-fns';
import { fr } from 'date-fns/locale';
import { ar } from 'date-fns/locale';
import { enUS } from 'date-fns/locale';
import { IconCalendar } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { Calendar } from '@/components/ui/calendar';

const DATE_LOCALES = { fr, ar, en: enUS };

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
  const { locale } = useLocaleStore();
  const selected = value ? parseISO(value) : undefined;
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; left?: number; right?: number }>({ top: 0 });
  const [mounted, setMounted] = useState(false);
  const btnRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setMounted(true); }, []);

  useLayoutEffect(() => {
    if (!open || !btnRef.current) return;
    const rect = btnRef.current.getBoundingClientRect();
    const isRtl = locale === 'ar';
    setPos({
      top: rect.bottom + window.scrollY + 6,
      left: isRtl ? rect.left + window.scrollX : undefined,
      right: isRtl ? undefined : window.innerWidth - rect.right,
    });
  }, [open, locale]);

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
          {selected ? format(selected, 'd MMM yyyy', { locale: DATE_LOCALES[locale] }) : (placeholder ?? t.placeholders?.date ?? 'Pick a date')}
        </span>
      </button>

      {mounted && open && createPortal(
        <div
          ref={panelRef}
          className="fixed z-[9000] bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-xl overflow-hidden"
          style={{
            top: pos.top,
            left: pos.left,
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
            locale={DATE_LOCALES[locale]}
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
