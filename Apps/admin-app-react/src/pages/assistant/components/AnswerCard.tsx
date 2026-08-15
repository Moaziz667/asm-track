import { Fragment, useState } from 'react';
import { IconAlertTriangle, IconClockPause, IconLock, IconWifiOff } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import type { Turn, Citation } from '../types';
import { RouteBadge } from './RouteBadge';
import { provenanceOf } from './provenance';

/** Split answer prose into text + clickable [n] citation markers that jump to their source. */
function renderWithCitations(text: string, markers: Set<number>, onJump: (n: number) => void) {
  const parts = text.split(/(\[\d+\])/g);
  return parts.map((part, i) => {
    const m = /^\[(\d+)\]$/.exec(part);
    if (m && markers.has(Number(m[1]))) {
      const n = Number(m[1]);
      return (
        <button
          key={i}
          type="button"
          onClick={() => onJump(n)}
          className="mx-0.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-[5px] px-1 align-[2px] text-[11px] font-bold text-[var(--brand)] bg-[var(--brand-soft)] transition-colors hover:bg-[var(--brand)] hover:text-white focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[var(--brand)]"
          aria-label={`Source ${n}`}
        >
          {n}
        </button>
      );
    }
    return <Fragment key={i}>{part}</Fragment>;
  });
}

function ErrorState({ kind }: { kind: NonNullable<Turn['errorKind']> }) {
  const t = useT();
  const map = {
    rate_limited: { Icon: IconClockPause, msg: t.assistant.error.rateLimited },
    unauthorized: { Icon: IconLock, msg: t.assistant.error.unauthorized },
    network: { Icon: IconWifiOff, msg: t.assistant.error.network },
  } as const;
  const { Icon, msg } = map[kind];
  return (
    <div className="flex items-start gap-2 text-[13px] text-[var(--text-secondary)]">
      <Icon size={16} className="mt-px shrink-0 text-[var(--text-soft)]" stroke={1.7} />
      <span>{msg}</span>
    </div>
  );
}

function LoadingState() {
  const t = useT();
  return (
    <div aria-live="polite" aria-busy="true">
      <span className="sr-only">{t.assistant.thinking}</span>
      <div className="space-y-2">
        <div className="skeleton h-3 w-[92%]" />
        <div className="skeleton h-3 w-[78%]" />
        <div className="skeleton h-3 w-[60%]" />
      </div>
    </div>
  );
}

function Sources({ turnId, citations }: { turnId: string; citations: Citation[] }) {
  const t = useT();
  if (citations.length === 0) return null;
  return (
    <div className="mt-3.5 border-t border-[var(--border)] pt-3">
      <div className="mb-2 text-[11px] font-bold uppercase tracking-[0.04em] text-[var(--text-soft)]">
        {t.assistant.sources}
      </div>
      <ul className="space-y-1.5">
        {citations.map((c) => (
          <li
            key={c.marker}
            id={`src-${turnId}-${c.marker}`}
            className="flex items-start gap-2 rounded-md px-1.5 py-1 -mx-1.5 transition-colors [&.asm-cite-flash]:bg-[var(--brand-soft)]"
          >
            <span className="mt-px inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-[5px] bg-[var(--hover-bg)] px-1 text-[11px] font-bold text-[var(--text-secondary)]">
              {c.marker}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[12px] text-[var(--text-secondary)]" title={`${c.path}${c.section ? ' › ' + c.section : ''}`}>
                <span className="font-mono text-[var(--text-primary)]">{c.path}</span>
                {c.section ? <span className="text-[var(--text-soft)]"> › {c.section}</span> : null}
              </span>
            </span>
            <span className="mt-px shrink-0 rounded bg-[var(--surface-sunken)] px-1.5 py-0.5 text-[10px] font-semibold text-[var(--text-muted)] border border-[var(--border)]">
              {t.assistant.authority[c.authority as keyof typeof t.assistant.authority] ?? c.authority}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function AnswerCard({ turn }: { turn: Turn }) {
  const t = useT();
  const [, force] = useState(0);

  const jumpToSource = (n: number) => {
    const el = document.getElementById(`src-${turn.id}-${n}`);
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    el.classList.add('asm-cite-flash');
    window.setTimeout(() => el.classList.remove('asm-cite-flash'), 1200);
    force((x) => x + 1);
  };

  const a = turn.answer;
  const markers = new Set((a?.citations ?? []).map((c) => c.marker));

  return (
    <article className="px-4 py-3.5">
      {/* Question */}
      <div className="mb-2.5 flex items-baseline gap-2">
        <span className="shrink-0 text-[11px] font-bold uppercase tracking-[0.04em] text-[var(--text-soft)]">
          {t.assistant.you}
        </span>
        <p className="min-w-0 text-[13px] font-semibold text-[var(--text-primary)] [text-wrap:pretty]">
          {turn.question}
        </p>
      </div>

      {turn.status === 'loading' && <LoadingState />}
      {turn.status === 'error' && turn.errorKind && <ErrorState kind={turn.errorKind} />}

      {turn.status === 'done' && a && (
        <>
          <div className="mb-2">
            <RouteBadge kind={provenanceOf(a)} />
          </div>
          <div className="max-w-[68ch] whitespace-pre-wrap text-[13.5px] leading-[1.62] text-[var(--text-primary)] [text-wrap:pretty]">
            {renderWithCitations(a.answer, markers, jumpToSource)}
          </div>

          {/* Not on a refusal: those citations are what retrieval surfaced and the answer judged
              insufficient — listing them under "Sources" would suggest they back an answer. */}
          {!a.refused && <Sources turnId={turn.id} citations={a.citations} />}

          {a.liveSources.length > 0 && (
            <div className="mt-3 border-t border-[var(--border)] pt-2.5 text-[12px] text-[var(--text-muted)]">
              <IconAlertTriangle size={13} className="mr-1 inline align-[-1px] text-[var(--text-soft)]" stroke={1.7} />
              {t.assistant.liveNote}: <span className="font-medium text-[var(--text-secondary)]">{a.liveSources.join(', ')}</span>
            </div>
          )}

          {turn.latencyMs != null && (
            <div className="mt-2 font-mono text-[11px] text-[var(--text-soft)]">
              {a.route} · {turn.latencyMs} ms
            </div>
          )}
        </>
      )}
    </article>
  );
}
