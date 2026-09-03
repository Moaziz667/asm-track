import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { useT } from '@/lib/i18n/LocaleContext';
import { AppLoader } from '@/components/AppLoader';
import { showErrorToast } from '@/lib/ui/toast-service';
import { isAbortError } from '@/lib/utils/errors';

// The assistant lives under /api/assistant, not the /api/v1 business surface — same per-call baseURL
// override as useAssistant, so this reuses the shared client's token attach and 401 refresh.
const ASSISTANT_BASE = '/api/assistant';

type Row = {
  id: string;
  userId: string | null;
  question: string;
  route: string | null;
  refused: boolean;
  latencyMs: number | null;
  citations: string | null;
  createdAt: string;
};

type Page = { rows: Row[]; total: number; page: number; size: number };

const PAGE_SIZE = 50;

/** How many sources the answer leaned on — the payload is the raw JSONB the server stored. */
function citationCount(raw: string | null): number {
  if (!raw) return 0;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
}

/** One colour per route, so the mix of sources is readable at a glance down the column. */
function routeColor(route: string | null, refused: boolean): string {
  if (refused) return 'var(--danger)';
  switch (route) {
    case 'RAG':           return 'var(--info)';
    case 'LIVE_API':      return 'var(--success)';
    case 'DETERMINISTIC': return 'var(--warning)';
    default:              return 'var(--text-muted)';
  }
}

/**
 * The assistant's own trail: what was asked, which route answered, how many sources grounded it,
 * whether it refused.
 *
 * Deliberately a separate table from the platform audit feed rather than rows merged into it. The
 * two answer different questions — "who changed what" against "why did the assistant say that" —
 * and share no column beyond a timestamp.
 */
export default function AssistantAuditTab() {
  const t = useT();
  const [rows, setRows] = useState<Row[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  // Starts true: the first render is already a load, and turning the flag on inside the effect would
  // be a setState in an effect body — a cascading render the linter rightly refuses. The handlers
  // below raise it again when the operator asks for another page, where it is an event, not an effect.
  const [loading, setLoading] = useState(true);
  const [refusedOnly, setRefusedOnly] = useState(false);

  const fetchRows = useCallback(async (p: number, onlyRefused: boolean) => {
    try {
      const params: Record<string, string | number | boolean> = { page: p, size: PAGE_SIZE };
      if (onlyRefused) params.refused = true;
      const res = await api.get<Page>('/audit', { params, baseURL: ASSISTANT_BASE });
      setRows(res.data.rows);
      setTotal(res.data.total);
    } catch (e) {
      if (!isAbortError(e)) showErrorToast(e, 'errorDataLoadFailed');
    } finally {
      setLoading(false);
    }
  }, []);

  // Loading data on mount is a setState reached from an effect, which this rule refuses on principle
  // — and which every data page here does, since the project fetches with useEffect rather than a
  // query library. Suppressed rather than counted: the warning cap exists to stop that pattern
  // spreading unnoticed, and a named exception says more than a cap raised by one.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchRows(page, refusedOnly);
  }, [fetchRows, page, refusedOnly]);

  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div
        className="flex items-center gap-3 px-4 py-2 border-b shrink-0"
        style={{ borderColor: 'var(--border)' }}
      >
        <label className="flex items-center gap-1.5 text-xs font-semibold cursor-pointer"
               style={{ color: 'var(--text-muted)' }}>
          <input
            type="checkbox"
            checked={refusedOnly}
            onChange={e => { setLoading(true); setPage(0); setRefusedOnly(e.target.checked); }}
          />
          {t.auditLogsPage.assistantRefusedOnly}
        </label>
        <span className="ml-auto text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
          {total} {t.auditLogsPage.assistantInteractions}
        </span>
      </div>

      <div className="flex-1 overflow-auto">
        {loading ? (
          <AppLoader centered height="200px" size="sm" label={t.auditLogsPage.loadingLogs} />
        ) : rows.length === 0 ? (
          <div className="flex items-center justify-center h-64">
            <p className="text-xs font-bold" style={{ color: 'var(--text-muted)' }}>
              {t.auditLogsPage.noLogs}
            </p>
          </div>
        ) : (
          <table className="w-full text-xs">
            <thead>
              <tr style={{ color: 'var(--text-soft)' }}>
                <th className="text-start font-[700] px-4 py-2">{t.auditLogsPage.assistantQuestion}</th>
                <th className="text-start font-[700] px-3 py-2">{t.auditLogsPage.assistantRoute}</th>
                <th className="text-end font-[700] px-3 py-2">{t.auditLogsPage.assistantSources}</th>
                <th className="text-end font-[700] px-3 py-2">{t.auditLogsPage.assistantLatency}</th>
                <th className="text-start font-[700] px-4 py-2">{t.auditLogsPage.assistantWhen}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(r => {
                const color = routeColor(r.route, r.refused);
                return (
                  <tr key={r.id} style={{ borderTop: '1px solid var(--border)' }}>
                    <td className="px-4 py-2 max-w-[38rem]" style={{ color: 'var(--text-primary)' }}>
                      <span className="line-clamp-2">{r.question}</span>
                    </td>
                    <td className="px-3 py-2">
                      <span className="font-[700] text-2xs" style={{ color }}>
                        {r.refused ? t.auditLogsPage.assistantRefused : (r.route ?? '—')}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-end tabular-nums" style={{ color: 'var(--text-muted)' }}>
                      {citationCount(r.citations) || '—'}
                    </td>
                    <td className="px-3 py-2 text-end tabular-nums" style={{ color: 'var(--text-muted)' }}>
                      {r.latencyMs != null ? `${r.latencyMs} ms` : '—'}
                    </td>
                    <td className="px-4 py-2 whitespace-nowrap" style={{ color: 'var(--text-muted)' }}>
                      {new Date(r.createdAt).toLocaleString()}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {!loading && lastPage > 0 && (
        <div
          className="p-3 border-t flex items-center justify-center gap-3 shrink-0"
          style={{ borderColor: 'var(--border)' }}
        >
          <button
            className="text-xs font-semibold disabled:opacity-40"
            style={{ color: 'var(--text-muted)' }}
            aria-label={t.auditLogsPage.assistantPrev}
            disabled={page === 0}
            onClick={() => { setLoading(true); setPage(p => Math.max(0, p - 1)); }}
          >
            ‹
          </button>
          <span className="text-xs font-semibold tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {page + 1} / {lastPage + 1}
          </span>
          <button
            className="text-xs font-semibold disabled:opacity-40"
            style={{ color: 'var(--text-muted)' }}
            aria-label={t.auditLogsPage.assistantNext}
            disabled={page >= lastPage}
            onClick={() => { setLoading(true); setPage(p => Math.min(lastPage, p + 1)); }}
          >
            ›
          </button>
        </div>
      )}
    </div>
  );
}
