import { IconBrain, IconEraser } from '@tabler/icons-react';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter } from '@/components/ui/sheet';
import { useT } from '@/lib/i18n/LocaleContext';
import { useAssistantPanel } from '@/lib/state/assistant-panel';
import { useAssistant } from './useAssistant';
import { AskBar } from './components/AskBar';
import { EmptyState } from './components/EmptyState';
import { AnswerCard } from './components/AnswerCard';

/**
 * The global assistant: a right-side panel summoned from the top bar on any page. It answers from the
 * indexed documentation (with citations), from live business APIs for the current state of a specific
 * delivery/return/route/driver, and from the SLA engine — and says so, per answer. Independent-turn
 * feed, newest on top; the server keeps the audit trail.
 */
export function AssistantPanel() {
  const t = useT();
  const { open, setOpen } = useAssistantPanel();
  const { turns, ask, clear } = useAssistant();
  const busy = turns[0]?.status === 'loading';

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="w-full gap-0 p-0 sm:w-[440px] sm:max-w-[92vw]"
        aria-describedby={undefined}
      >
        <SheetHeader className="shrink-0 gap-1 border-b border-[var(--border)] pr-12">
          <div className="flex items-center gap-2">
            <span className="grid size-6 place-items-center rounded-md bg-[var(--brand-soft)] text-[var(--brand)]">
              <IconBrain size={15} stroke={1.8} />
            </span>
            <SheetTitle className="text-lg font-bold text-[var(--text-primary)]">
              {t.assistant.title}
            </SheetTitle>
            {turns.length > 0 && (
              <button
                type="button"
                onClick={clear}
                className="ms-auto inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs font-semibold text-[var(--text-muted)] transition-colors hover:bg-[var(--hover-bg)] hover:text-[var(--text-secondary)] focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[var(--brand)]"
              >
                <IconEraser size={13} stroke={1.8} /> {t.assistant.clear}
              </button>
            )}
          </div>
          <SheetDescription className="text-sm leading-snug text-[var(--text-muted)]">
            {t.assistant.subtitle}
          </SheetDescription>
        </SheetHeader>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {turns.length === 0 ? (
            <EmptyState onPick={ask} />
          ) : (
            <ul className="divide-y divide-[var(--border)]">
              {turns.map((turn) => (
                <li key={turn.id}>
                  <AnswerCard turn={turn} />
                </li>
              ))}
            </ul>
          )}
        </div>

        <SheetFooter className="shrink-0 gap-1.5 border-t border-[var(--border)] bg-[var(--surface-sunken)]">
          <AskBar onSubmit={ask} busy={busy} />
          <p className="px-0.5 text-xs leading-snug text-[var(--text-soft)]">{t.assistant.disclaimer}</p>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
