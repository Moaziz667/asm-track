import { IconCircleCheck, IconCircleX, IconClock, IconLock, IconPlugConnected } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import type { ConnStatus, ConformanceReport } from '@/lib/api/erpIntegration';
import { blockingChecks } from '@/lib/api/erpIntegration';

/**
 * The last gate, and the daily health view afterwards.
 *
 * Activation is refused while a REQUIRED capability is missing, and the refusal names the exact
 * capabilities rather than saying "not ready". That is the point of certifying at all: the
 * integrator learns at configuration time, when they can act, instead of a driver's delivery
 * dead-lettering weeks later.
 */
export function StepActivation({
  status, report, lastConnectedAt, lastError, onRetest, testing, onGoToStep, copy,
}: {
  status: ConnStatus;
  report: ConformanceReport | null;
  lastConnectedAt?: string | null;
  lastError?: string | null;
  onRetest: () => void;
  testing: boolean;
  onGoToStep: (id: 'connection' | 'compatibility') => void;
  copy: Record<string, string>;
}) {
  const blocking = blockingChecks(report);
  const connected = status === 'CONNECTED';
  const active = connected && blocking.length === 0;

  return (
    <div className="flex flex-col gap-4">
      <section
        className="rounded-lg border border-[var(--border)] p-5"
        style={{
          background: active
            ? 'color-mix(in srgb, var(--success) 7%, transparent)'
            : 'color-mix(in srgb, var(--warning) 7%, transparent)',
        }}
      >
        <div className="flex items-start gap-3">
          <span className="shrink-0 mt-px" style={{ color: active ? 'var(--success)' : 'var(--warning)' }}>
            {active ? <IconCircleCheck size={20} /> : <IconLock size={20} />}
          </span>
          <div className="min-w-0 flex-1">
            <p
              className="text-sm font-semibold"
              style={{ color: active ? 'var(--success)' : 'var(--warning)' }}
            >
              {active ? copy.activeTitle : copy.blockedTitle}
            </p>
            <p className="mt-0.5 text-xs text-[var(--text-secondary)] max-w-[62ch] leading-relaxed">
              {active
                ? copy.activeBody
                : !connected
                  ? copy.blockedNoConnection
                  : copy.blockedByChecks.replace('{n}', String(blocking.length))}
            </p>

            {!active && !connected && (
              <Button size="sm" variant="outline" className="mt-3" onClick={() => onGoToStep('connection')}>
                {copy.goConnection}
              </Button>
            )}
            {!active && connected && blocking.length > 0 && (
              <>
                <ul className="mt-2.5 flex flex-col gap-1">
                  {blocking.map((c) => (
                    <li key={c.capability} className="font-mono text-2xs text-[var(--danger)] break-all">
                      {c.capability}
                    </li>
                  ))}
                </ul>
                <Button size="sm" variant="outline" className="mt-3" onClick={() => onGoToStep('compatibility')}>
                  {copy.goCompatibility}
                </Button>
              </>
            )}
          </div>
        </div>
      </section>

      <section className="rounded-lg border border-[var(--border)] overflow-hidden">
        <header className="px-4 py-2.5 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
          <h3 className="text-xs font-semibold text-[var(--text-primary)]">{copy.healthTitle}</h3>
        </header>
        <dl className="divide-y divide-[var(--border)]">
          <Row label={copy.rowStatus}>
            <span className="inline-flex items-center gap-1.5 text-base">
              {connected
                ? <IconCircleCheck size={14} className="text-[var(--success)]" />
                : <IconCircleX size={14} className="text-[var(--danger)]" />}
              <span className="text-[var(--text-primary)]">
                {connected ? copy.statusConnected : copy.statusNotConnected}
              </span>
            </span>
          </Row>
          <Row label={copy.rowVersion}>
            <span className="font-mono text-xs text-[var(--text-primary)]">
              {report?.detectedVersion ?? '—'}
            </span>
          </Row>
          <Row label={copy.rowVerdict}>
            <span className="font-mono text-xs text-[var(--text-primary)]">{report?.verdict ?? '—'}</span>
          </Row>
          <Row label={copy.rowLastConnected}>
            <span className="inline-flex items-center gap-1.5 text-base text-[var(--text-secondary)]">
              <IconClock size={13} className="text-[var(--text-muted)]" />
              {lastConnectedAt ? new Date(lastConnectedAt).toLocaleString() : '—'}
            </span>
          </Row>
          {lastError && (
            <Row label={copy.rowLastError}>
              <span className="text-base text-[var(--danger)] break-words">{lastError}</span>
            </Row>
          )}
        </dl>
        <div className="flex justify-end px-4 py-3 border-t border-[var(--border)]">
          <Button size="sm" variant="outline" onClick={onRetest} disabled={testing}>
            <IconPlugConnected size={15} /> {testing ? copy.testing : copy.retest}
          </Button>
        </div>
      </section>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(0,180px)_minmax(0,1fr)] items-baseline gap-3 px-4 py-2.5">
      <dt className="text-xs text-[var(--text-muted)]">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  );
}
