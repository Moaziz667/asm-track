import { useState } from 'react';
import {
  IconCircleCheck, IconAlertTriangle, IconHelpCircle, IconRefresh, IconCircleX,
} from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import type { CapabilityCheck, ConformanceReport, Verdict } from '@/lib/api/erpIntegration';

/**
 * The sentence under a capability row, in the reader's language.
 *
 * Two parts, either of which may be absent: why the status is what it is (keyed by the probe, since
 * "absent — and that is expected here" cannot be inferred from a green row), then what the capability
 * is for (keyed by its name, which the row already carries).
 *
 * Falls back to the probe's English `detail` when neither is translated — that is what a provider
 * whose probe is not keyed yet still shows, and it is better than a blank line.
 */
function useCheckText() {
  const t = useT();
  const text = (t.erpSetup as unknown as {
    conformanceText?: { notes?: Record<string, string>; reasons?: Record<string, string> };
  }).conformanceText;

  return (check: CapabilityCheck): string | null => {
    const note = text?.notes?.[check.capability];
    const template = check.reasonKey ? text?.reasons?.[check.reasonKey] : undefined;
    const reason = template
      ? template.replace(/\{(\w+)\}/g, (m, k) => String(check.reasonParams?.[k] ?? m))
      : undefined;

    const parts = [reason, note].filter(Boolean);
    if (parts.length > 0) return parts.join(' ');
    return check.detail || null;
  };
}

/**
 * The certification report, with the fix attached to the failing row.
 *
 * A report that only states "CREATE_RETURN is missing" leaves the integrator with a ticket to file.
 * The same row carrying an input — "what is it called in this customer's ERP?" — turns a blocked
 * onboarding into a thirty-second edit. That is the whole reason this screen exists rather than a
 * status banner.
 *
 * Only REQUIRED + MISSING blocks. RECOMMENDED gaps are shown but never bar the way: the adapter has a
 * working path for each, and refusing them would turn away ERP versions that work fine.
 */
export function StepCompatibility({
  report,
  loading,
  onRun,
  onOverride,
  canManage,
  copy,
}: {
  report: ConformanceReport | null;
  loading: boolean;
  onRun: () => void;
  onOverride: (capability: string, odooName: string) => Promise<void>;
  canManage: boolean;
  copy: Record<string, string>;
}) {
  if (loading && !report) return <ReportProgress copy={copy} />;

  if (!report) {
    return (
      <div className="rounded-lg border border-dashed border-[var(--border)] p-8 text-center">
        <p className="text-sm text-[var(--text-secondary)]">{copy.noReport}</p>
        <p className="mt-1 text-xs text-[var(--text-muted)]">{copy.noReportHint}</p>
        <Button size="sm" variant="outline" className="mt-4" onClick={onRun}>
          <IconRefresh size={15} /> {copy.runCheck}
        </Button>
      </div>
    );
  }

  const blocking = report.checks.filter((c) => c.severity === 'REQUIRED' && c.status === 'MISSING');
  const degraded = report.checks.filter((c) => c.severity === 'RECOMMENDED' && c.status !== 'OK');
  const ok = report.checks.filter((c) => c.status === 'OK');
  const unknown = report.checks.filter((c) => c.status === 'UNKNOWN' && c.severity === 'REQUIRED');

  return (
    <div className="flex flex-col gap-4">
      <VerdictBanner
        verdict={report.verdict}
        version={report.detectedVersion}
        blocking={blocking.length}
        copy={copy}
        onRun={onRun}
        loading={loading}
      />

      {blocking.length > 0 && (
        <CheckGroup title={copy.groupBlocking} tone="danger" count={blocking.length}>
          {blocking.map((c) => (
            <BlockingRow key={c.capability} check={c} onOverride={onOverride} canManage={canManage} copy={copy} />
          ))}
        </CheckGroup>
      )}

      {degraded.length > 0 && (
        <CheckGroup title={copy.groupDegraded} tone="warning" count={degraded.length}>
          {degraded.map((c) => <PlainRow key={c.capability} check={c} />)}
        </CheckGroup>
      )}

      {unknown.length > 0 && (
        <CheckGroup title={copy.groupUnknown} tone="muted" count={unknown.length}>
          {unknown.map((c) => <PlainRow key={c.capability} check={c} />)}
        </CheckGroup>
      )}

      {ok.length > 0 && (
        <CheckGroup title={copy.groupOk} tone="success" count={ok.length} collapsible>
          {ok.map((c) => <PlainRow key={c.capability} check={c} />)}
        </CheckGroup>
      )}
    </div>
  );
}

// ── Verdict ───────────────────────────────────────────────────────────────────────────────────────

function VerdictBanner({
  verdict, version, blocking, copy, onRun, loading,
}: {
  verdict: Verdict; version: string; blocking: number;
  copy: Record<string, string>; onRun: () => void; loading: boolean;
}) {
  const tone =
    verdict === 'GO' ? { fg: 'var(--success)', icon: <IconCircleCheck size={18} />, label: copy.verdictGo }
    : verdict === 'DEGRADED' ? { fg: 'var(--warning)', icon: <IconAlertTriangle size={18} />, label: copy.verdictDegraded }
    : { fg: 'var(--danger)', icon: <IconCircleX size={18} />, label: copy.verdictNoGo };

  return (
    <div
      className="flex items-start gap-3 rounded-lg border border-[var(--border)] p-4"
      style={{ background: `color-mix(in srgb, ${tone.fg} 7%, transparent)` }}
    >
      <span className="shrink-0 mt-px" style={{ color: tone.fg }}>{tone.icon}</span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold" style={{ color: tone.fg }}>{tone.label}</p>
        <p className="mt-0.5 text-xs text-[var(--text-secondary)]">
          {verdict === 'NO_GO'
            ? copy.verdictNoGoHint.replace('{n}', String(blocking))
            : verdict === 'DEGRADED' ? copy.verdictDegradedHint : copy.verdictGoHint}
        </p>
        <p className="mt-1.5 font-mono text-2xs text-[var(--text-muted)]">{version}</p>
      </div>
      <Button size="sm" variant="outline" onClick={onRun} disabled={loading} className="shrink-0">
        <IconRefresh size={15} className={loading ? 'animate-spin' : undefined} />
        {loading ? copy.checking : copy.recheck}
      </Button>
    </div>
  );
}

// ── Groups ────────────────────────────────────────────────────────────────────────────────────────

function CheckGroup({
  title, tone, count, collapsible = false, children,
}: {
  title: string; tone: 'danger' | 'warning' | 'success' | 'muted';
  count: number; collapsible?: boolean; children: React.ReactNode;
}) {
  const [open, setOpen] = useState(!collapsible);
  const color =
    tone === 'danger' ? 'var(--danger)' : tone === 'warning' ? 'var(--warning)'
    : tone === 'success' ? 'var(--success)' : 'var(--text-muted)';

  return (
    <section className="rounded-lg border border-[var(--border)] overflow-hidden">
      <button
        type="button"
        onClick={() => collapsible && setOpen((v) => !v)}
        disabled={!collapsible}
        aria-expanded={collapsible ? open : undefined}
        className={cn(
          'flex w-full items-center gap-2 px-4 py-2.5 text-left',
          'border-b border-[var(--border)] bg-[var(--surface-sunken)]',
          collapsible && 'hover:bg-[var(--hover-bg)] transition-colors duration-150',
          !collapsible && 'cursor-default',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] focus-visible:ring-inset',
        )}
      >
        <span className="h-1.5 w-1.5 rounded-full shrink-0" style={{ background: color }} aria-hidden />
        <span className="text-xs font-semibold text-[var(--text-primary)]">{title}</span>
        <span className="font-mono text-2xs text-[var(--text-muted)] tabular-nums">{count}</span>
        {collapsible && (
          <span className="ml-auto text-2xs text-[var(--text-muted)]">{open ? '−' : '+'}</span>
        )}
      </button>
      {open && <ul className="divide-y divide-[var(--border)]">{children}</ul>}
    </section>
  );
}

// ── Rows ──────────────────────────────────────────────────────────────────────────────────────────

/** A blocking row carries its own fix: name the capability as this customer's ERP calls it. */
function BlockingRow({
  check, onOverride, canManage, copy,
}: {
  check: CapabilityCheck;
  onOverride: (capability: string, odooName: string) => Promise<void>;
  canManage: boolean;
  copy: Record<string, string>;
}) {
  const [value, setValue] = useState('');
  const [saving, setSaving] = useState(false);
  const checkText = useCheckText();

  // The report names the model.method it looked for; the override only needs the method name.
  const capability = check.capability.includes('.')
    ? check.capability.slice(check.capability.lastIndexOf('.') + 1)
    : check.capability;

  const submit = async () => {
    if (!value.trim() || saving) return;
    setSaving(true);
    try {
      await onOverride(check.capability, value.trim());
      setValue('');
    } finally {
      setSaving(false);
    }
  };

  return (
    <li className="px-4 py-3">
      <div className="flex items-start gap-2.5">
        <IconCircleX size={15} className="mt-px shrink-0 text-[var(--danger)]" />
        <div className="min-w-0 flex-1">
          <p className="font-mono text-xs text-[var(--text-primary)] break-all">{check.capability}</p>
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">{checkText(check)}</p>
        </div>
      </div>
      {canManage && (
        <div className="mt-2.5 flex items-end gap-2 pl-[25px]">
          <FieldInput
            wrapperClassName="flex-1 min-w-0"
            label={copy.overrideLabel}
            placeholder={capability}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') submit(); }}
          />
          <Button size="sm" onClick={submit} disabled={!value.trim() || saving} className="mb-px">
            {saving ? copy.saving : copy.applyOverride}
          </Button>
        </div>
      )}
    </li>
  );
}

function PlainRow({ check }: { check: CapabilityCheck }) {
  const checkText = useCheckText();
  const detail = checkText(check);

  const icon =
    check.status === 'OK' ? <IconCircleCheck size={15} className="text-[var(--success)]" />
    : check.status === 'UNKNOWN' ? <IconHelpCircle size={15} className="text-[var(--text-muted)]" />
    : <IconAlertTriangle size={15} className="text-[var(--warning)]" />;

  return (
    <li className="flex items-start gap-2.5 px-4 py-2.5">
      <span className="mt-px shrink-0">{icon}</span>
      <div className="min-w-0 flex-1">
        <p className="font-mono text-xs text-[var(--text-primary)] break-all">{check.capability}</p>
        {/* Shown on passing rows too. The probe's most informative sentences sit on them — "absent
            from Odoo 19 onward, expected on this version, the adapter's fallback covers it" is why
            that row is green rather than a warning, and hiding it left a capability the ERP does not
            actually have looking indistinguishable from one it does. This group is collapsed by
            default, so anyone reading these lines opened them on purpose. */}
        {detail && (
          <p className="mt-0.5 text-xs text-[var(--text-muted)]">{detail}</p>
        )}
      </div>
    </li>
  );
}

/**
 * The wait while the probe runs — several seconds against a real Odoo.
 *
 * <p>Deliberately not a progress bar. The probe is one request; the client has no idea how far
 * along it is, so any percentage or step-by-step tick would be invented, and a progress indicator
 * that does not track progress is a lie told at the exact moment the user is deciding whether the
 * product is trustworthy. What can be said honestly is *what* is being verified and *why* it is not
 * instant — every check is a round trip to the customer's own server.
 *
 * <p>The four groups mirror the probe's own structure and are named at category level, not per
 * check: a hardcoded list of models would silently drift from the backend the first time one is
 * added.
 */
function ReportProgress({ copy }: { copy: Record<string, string> }) {
  const groups = [copy.probeModels, copy.probeAccess, copy.probeMethods, copy.probeVersion];
  return (
    <div className="flex flex-col gap-4">
      <section className="rounded-lg border border-[var(--border)] overflow-hidden">
        <header className="flex items-center gap-2 px-4 py-2.5 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
          <IconRefresh size={14} className="shrink-0 animate-spin text-[var(--text-muted)]" />
          <h3 className="text-xs font-semibold text-[var(--text-primary)]">{copy.probeTitle}</h3>
        </header>
        <div className="px-4 py-3">
          <p className="text-2xs text-[var(--text-muted)] leading-relaxed max-w-[62ch]">
            {copy.probeWhy}
          </p>
          <ul className="mt-3 flex flex-col gap-1.5">
            {groups.map((label) => (
              <li key={label} className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
                <span
                  className="h-1.5 w-1.5 shrink-0 rounded-full animate-pulse"
                  style={{ background: 'var(--brand)' }}
                />
                {label}
              </li>
            ))}
          </ul>
        </div>
      </section>

      {/* The shape of the answer, so the panel does not jump when it lands. */}
      <Skeleton className="h-[140px] w-full rounded-lg" />
      <Skeleton className="h-[96px] w-full rounded-lg" />
    </div>
  );
}
