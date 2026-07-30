import { IconCheck, IconLock, IconAlertTriangle } from '@tabler/icons-react';
import { cn } from '@/lib/utils';

export type StepId = 'connection' | 'compatibility' | 'mapping' | 'preview' | 'rehearsal' | 'activation';
export type StepState = 'done' | 'current' | 'available' | 'locked' | 'attention';

export interface StepDescriptor {
  id: StepId;
  label: string;
  hint: string;
  state: StepState;
  /** Why the step is locked — shown so the integrator knows what to do, not just that they can't. */
  lockReason?: string;
}

/**
 * The spine of the integration flow.
 *
 * Linear on a first run — a step unlocks when its prerequisite is satisfied — but every step already
 * reached stays clickable afterwards. That matters because this surface is two things at once: an
 * onboarding an integrator walks once, and the place they come back to six months later to remap a
 * single field. A pure wizard serves the first and punishes the second.
 *
 * A locked step still says why it is locked. "Testez la connexion d'abord" is actionable; a greyed
 * row that just refuses the click is not.
 */
export function StepRail({
  steps,
  onSelect,
}: {
  steps: StepDescriptor[];
  onSelect: (id: StepId) => void;
}) {
  return (
    <nav aria-label="Étapes de l'intégration" className="w-full lg:w-[248px] lg:shrink-0">
      <ol className="flex lg:flex-col gap-1 overflow-x-auto lg:overflow-visible pb-1 lg:pb-0">
        {steps.map((step, i) => {
          const locked = step.state === 'locked';
          const current = step.state === 'current';
          return (
            <li key={step.id} className="min-w-[164px] lg:min-w-0">
              <button
                type="button"
                onClick={() => !locked && onSelect(step.id)}
                disabled={locked}
                aria-current={current ? 'step' : undefined}
                title={locked ? step.lockReason : undefined}
                className={cn(
                  'group w-full flex items-start gap-2.5 rounded-lg px-2.5 py-2 text-left',
                  'transition-colors duration-150',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] focus-visible:ring-offset-2',
                  current && 'bg-[var(--brand-soft)]',
                  !current && !locked && 'hover:bg-[var(--hover-bg)]',
                  locked && 'cursor-not-allowed opacity-55',
                )}
              >
                <StepMarker index={i + 1} state={step.state} />
                <span className="flex flex-col min-w-0 pt-px">
                  <span
                    className={cn(
                      'text-base leading-snug truncate',
                      current ? 'font-semibold text-[var(--brand)]' : 'font-medium text-[var(--text-primary)]',
                    )}
                  >
                    {step.label}
                  </span>
                  <span className="text-2xs leading-snug text-[var(--text-muted)] truncate">
                    {locked ? step.lockReason ?? step.hint : step.hint}
                  </span>
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * The step's own indicator. Status is never colour-only: done carries a check, attention a warning
 * triangle, locked a padlock, so the state survives greyscale and colour-blindness.
 */
function StepMarker({ index, state }: { index: number; state: StepState }) {
  const base =
    'shrink-0 grid place-items-center h-[22px] w-[22px] rounded-full text-2xs font-semibold tabular-nums transition-colors duration-150';

  if (state === 'done') {
    return (
      <span
        className={cn(base, 'text-[var(--success)]')}
        style={{ background: 'color-mix(in srgb, var(--success) 14%, transparent)' }}
      >
        <IconCheck size={13} stroke={2.5} />
      </span>
    );
  }
  if (state === 'attention') {
    return (
      <span
        className={cn(base, 'text-[var(--warning)]')}
        style={{ background: 'color-mix(in srgb, var(--warning) 16%, transparent)' }}
      >
        <IconAlertTriangle size={13} />
      </span>
    );
  }
  if (state === 'locked') {
    return (
      <span className={cn(base, 'bg-[var(--hover-bg)] text-[var(--text-soft)]')}>
        <IconLock size={12} />
      </span>
    );
  }
  if (state === 'current') {
    return (
      <span className={cn(base, 'bg-[var(--brand)] text-white')}>
        {index}
      </span>
    );
  }
  return (
    <span className={cn(base, 'border border-[var(--border-strong)] text-[var(--text-muted)]')}>
      {index}
    </span>
  );
}
