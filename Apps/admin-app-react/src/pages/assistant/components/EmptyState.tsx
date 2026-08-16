import { IconArrowRight } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';

/** First-run state that teaches the surface: what it answers, and a few real prompts to try. */
export function EmptyState({ onPick }: { onPick: (q: string) => void }) {
  const t = useT();
  const examples = [t.assistant.example1, t.assistant.example2, t.assistant.example3, t.assistant.example4];

  return (
    <div className="flex h-full flex-col justify-center px-5 py-8">
      <h2 className="text-lg font-bold text-[var(--text-primary)]">{t.assistant.emptyTitle}</h2>
      <p className="mt-1.5 max-w-[42ch] text-base leading-[1.55] text-[var(--text-secondary)]">
        {t.assistant.emptyHint}
      </p>

      <div className="mt-5 space-y-1.5">
        {examples.map((ex) => (
          <button
            key={ex}
            type="button"
            onClick={() => onPick(ex)}
            className="group flex w-full items-center gap-2.5 rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 py-2.5 text-start transition-colors hover:border-[var(--border-strong)] hover:bg-[var(--hover-bg)] focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[var(--brand)]"
          >
            <span className="min-w-0 flex-1 truncate text-base text-[var(--text-secondary)] group-hover:text-[var(--text-primary)]">
              {ex}
            </span>
            <IconArrowRight
              size={15}
              className="shrink-0 text-[var(--text-faded)] transition-transform group-hover:translate-x-0.5 group-hover:text-[var(--brand)] rtl:rotate-180"
              stroke={1.8}
            />
          </button>
        ))}
      </div>
    </div>
  );
}
