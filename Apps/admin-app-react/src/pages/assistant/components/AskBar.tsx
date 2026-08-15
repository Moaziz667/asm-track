import { useLayoutEffect, useRef, useState, KeyboardEvent } from 'react';
import { IconArrowUp } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';

/**
 * The ask input: an auto-growing single-field composer. Enter sends, Shift+Enter adds a line — the
 * convention operators already expect from every modern composer, so it disappears into the task.
 */
export function AskBar({ onSubmit, busy }: { onSubmit: (q: string) => void; busy: boolean }) {
  const t = useT();
  const [value, setValue] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);

  // Grow with content up to a ceiling, then scroll — never a jumping panel.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [value]);

  const submit = () => {
    const q = value.trim();
    if (!q) return;
    onSubmit(q);
    setValue('');
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  const canSend = value.trim().length > 0;

  return (
    <div className="flex items-end gap-2 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] p-1.5 pl-3 shadow-[var(--shadow-xs)] transition-colors focus-within:border-[var(--brand)] focus-within:ring-3 focus-within:ring-[var(--brand-soft)]">
      <textarea
        ref={ref}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKeyDown}
        autoFocus
        rows={1}
        placeholder={t.assistant.placeholder}
        aria-label={t.assistant.placeholder}
        className="min-h-[24px] flex-1 resize-none self-center bg-transparent py-1 text-[13.5px] leading-[1.5] text-[var(--text-primary)] placeholder:text-[var(--text-soft)] outline-none"
      />
      <button
        type="button"
        onClick={submit}
        disabled={!canSend || busy}
        aria-label={t.assistant.send}
        className="grid size-8 shrink-0 place-items-center rounded-lg bg-[var(--brand)] text-white transition-all hover:bg-[var(--brand-hover)] disabled:opacity-40 disabled:cursor-not-allowed active:translate-y-px focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--brand)]"
      >
        <IconArrowUp size={17} stroke={2.2} />
      </button>
    </div>
  );
}
