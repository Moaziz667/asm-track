import { Link } from 'react-router-dom';
import { IconCircleCheck, IconCircleDashed, IconRefresh } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { Rehearsal } from '@/lib/api/erpIntegration';

/**
 * Proof that the write path works, before anyone trusts it with a real delivery.
 *
 * <p>The compatibility probe stops at what can be checked without side effects — models, fields,
 * methods, access rights. Whether the ERP actually accepts a validated transfer is unknowable
 * without validating one, so this step asks the integrator to do exactly that, on a copy of the
 * customer's database, and then reports what really happened.
 *
 * <p>Nothing here is tickable. Every mark is derived from a delivery that reached the outcome and
 * synced back — a box someone ticks himself gets ticked without testing, which manufactures
 * confidence instead of establishing it. The evidence link is the point: it lets whoever reads the
 * screen check the claim rather than take it.
 */
export function StepRehearsal({
  rehearsal, loading, copy, onRefresh,
}: {
  rehearsal: Rehearsal | null;
  loading: boolean;
  copy: Record<string, string>;
  onRefresh: () => void;
}) {
  if (loading && !rehearsal) return <Skeleton className="h-[280px] w-full rounded-lg" />;

  const steps = rehearsal?.steps ?? [];
  const doneCount = steps.filter((s) => s.done).length;

  return (
    <div className="flex flex-col gap-5">
      <p className="text-xs text-[var(--text-secondary)] leading-relaxed max-w-[68ch]">
        {copy.intro}
      </p>

      {/* Deliberately loud, and deliberately not a blocker: pointing at a copy is the integrator's
          call to make, and the screen has no way to verify a URL is staging rather than production. */}
      <div
        className="flex items-start gap-2 rounded-lg px-3 py-2.5 text-xs"
        style={{
          background: 'color-mix(in srgb, var(--warning) 8%, transparent)',
          color: 'var(--warning)',
        }}
      >
        <span className="leading-relaxed max-w-[64ch]">{copy.stagingWarning}</span>
      </div>

      <section className="rounded-lg border border-[var(--border)] overflow-hidden">
        <header className="flex items-center justify-between px-4 py-2.5 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
          <div>
            <h3 className="text-xs font-semibold text-[var(--text-primary)]">{copy.sectionTitle}</h3>
            <p className="mt-0.5 text-2xs text-[var(--text-muted)]">
              {(copy.progress ?? '{done}/{total}')
                .replace('{done}', String(doneCount))
                .replace('{total}', String(steps.length))}
            </p>
          </div>
          <Button size="sm" variant="outline" onClick={onRefresh} disabled={loading}>
            <IconRefresh size={15} className={loading ? 'animate-spin' : undefined} />
            {copy.refresh}
          </Button>
        </header>

        <ul className="divide-y divide-[var(--border)]">
          {steps.map((s) => (
            <li key={s.scenario} className="flex items-start gap-3 px-4 py-3">
              {s.done
                ? <IconCircleCheck size={16} className="shrink-0 mt-px" style={{ color: 'var(--success)' }} />
                : <IconCircleDashed size={16} className="shrink-0 mt-px text-[var(--text-soft)]" />}

              <div className="flex-1 min-w-0">
                <p className={cn(
                  'text-xs font-medium',
                  s.done ? 'text-[var(--text-primary)]' : 'text-[var(--text-secondary)]',
                )}>
                  {copy[`scenario${s.scenario}`] ?? s.scenario}
                </p>
                <p className="mt-0.5 text-2xs text-[var(--text-muted)] leading-relaxed max-w-[62ch]">
                  {copy[`scenario${s.scenario}Hint`]}
                </p>
              </div>

              {/* The evidence, so the claim can be checked rather than believed. */}
              {s.done && s.evidence && (
                <Link
                  to={`/deliveries/${s.evidence}`}
                  className="shrink-0 text-2xs font-medium underline text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                >
                  {copy.seeEvidence}
                </Link>
              )}
            </li>
          ))}
        </ul>
      </section>

      {rehearsal?.since && (
        <p className="text-2xs text-[var(--text-muted)]">
          {(copy.since ?? '').replace('{date}', new Date(rehearsal.since).toLocaleString('fr-FR', {
            dateStyle: 'medium', timeStyle: 'short',
          }))}
        </p>
      )}
    </div>
  );
}
