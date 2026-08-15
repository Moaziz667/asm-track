import { IconBrain } from '@tabler/icons-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useT } from '@/lib/i18n/LocaleContext';
import { useAssistantPanel } from '@/lib/state/assistant-panel';

/**
 * Top-bar entry point for the assistant — present on every page. A quiet icon button that matches the
 * other header actions (AlertBell, language, theme); the panel does the talking.
 */
export function AssistantTrigger() {
  const t = useT();
  const { toggle, open } = useAssistantPanel();

  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <button
            type="button"
            onClick={toggle}
            aria-label={t.assistant.title}
            aria-pressed={open}
            className="grid size-8 place-items-center rounded-lg text-[var(--text-secondary)] transition-colors hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)] aria-pressed:bg-[var(--brand-soft)] aria-pressed:text-[var(--brand)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--brand)]"
          />
        }
      >
        <IconBrain size={19} stroke={1.6} />
      </TooltipTrigger>
      <TooltipContent>{t.assistant.title}</TooltipContent>
    </Tooltip>
  );
}
