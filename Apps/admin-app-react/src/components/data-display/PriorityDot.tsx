import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

/**
 * A single dot marking an urgent order.
 *
 * <p>Deliberately just a dot. Priority is an indication a dispatcher glances at while scanning for
 * something else, not a field they read — a labelled chip earns its width on the one screen showing
 * one delivery, and costs it on every row of a list of two hundred. The label lives in the tooltip,
 * which is where someone looks once the dot has already caught them.
 *
 * <p>Renders nothing for a normal priority. A list where every row carries a marker has no marker:
 * the whole value here is that the eye lands only on the exceptions.
 */
export function PriorityDot({ priority, label }: { priority?: string | null; label: string }) {
  if (priority !== 'HIGH') return null;
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span
            aria-label={label}
            className="inline-block h-1.5 w-1.5 shrink-0 rounded-full align-middle cursor-default"
            style={{ background: 'var(--danger)' }}
          />
        }
      />
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
