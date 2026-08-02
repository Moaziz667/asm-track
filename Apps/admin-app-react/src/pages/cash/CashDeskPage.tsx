import { useMemo, useState } from 'react';
import {
  IconCashBanknote, IconAlertTriangle, IconCheck, IconScale, IconWallet,
} from '@tabler/icons-react';

import { useT } from '@/lib/i18n/LocaleContext';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { formatAmount, formatDelta } from '@/lib/utils/money';
import { receiveRemittance, reconcileRemittance, type CashRemittance, type CashRemittanceStatus } from '@/lib/api/cash';
import { useRemittances, useCashCirculation, useRefreshCash } from '@/hooks/useCash';

import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { TablePagination } from '@/components/data-display/TablePagination';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/feedback/EmptyState';
import { AppModal } from '@/components/overlays/AppModal';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { Input } from '@/components/ui/input';

const CURRENCY = 'TND';

/** Only the two states that need a human; everything else is history. */
const FILTERS: (CashRemittanceStatus | 'ALL')[] = ['ALL', 'DECLARED', 'DISPUTED', 'RECONCILED'];

export default function CashDeskPage() {
  const t = useT();
  const c = t.cashPage;

  const [filter, setFilter] = useState<CashRemittanceStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);

  const remittances = useRemittances({
    status: filter === 'ALL' ? undefined : filter,
    page,
    size: pageSize,
  });
  const circulationQuery = useCashCirculation();
  const refreshCash = useRefreshCash();

  const rows = remittances.data?.content ?? [];
  const totalPages = Math.max(1, remittances.data?.totalPages ?? 1);
  const totalElements = remittances.data?.totalElements ?? 0;
  const circulation = circulationQuery.data?.amount ?? null;
  const loading = remittances.isPending;

  const [counting, setCounting] = useState<CashRemittance | null>(null);
  const [countedText, setCountedText] = useState('');
  const [countNote, setCountNote] = useState('');
  const [settling, setSettling] = useState<CashRemittance | null>(null);
  const [settleNote, setSettleNote] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  const quickFilters = useMemo(
    () => FILTERS.map(f => ({
      value: f,
      label: f === 'ALL' ? c.filterPending : (t.cashStatus as Record<string, string>)[f] ?? f,
      count: f === filter ? totalElements : undefined,
    })),
    [filter, totalElements, c.filterPending, t.cashStatus],
  );

  /**
   * The count is parsed from text, not bound to a number input's value.
   *
   * A depot counts in dinars and millimes and will type `3400,500` on a French keyboard; a bare
   * `<input type="number">` silently rejects the comma in several browsers, leaving the field
   * looking filled and the value empty. Accepting both separators is the difference between a
   * cashier finishing his evening and one wondering why the button does nothing.
   */
  const countedValue = useMemo(() => {
    const raw = countedText.trim().replace(/\s/g, '').replace(',', '.');
    if (!raw) return null;
    const n = Number(raw);
    return Number.isFinite(n) && n >= 0 ? n : null;
  }, [countedText]);

  /** Shown live while typing, so the operator sees the gap before committing to it. */
  const liveDelta = counting && countedValue !== null
    ? countedValue - (counting.expectedTotal ?? 0)
    : null;

  const submitCount = async () => {
    if (!counting || countedValue === null) return;
    setBusyId(counting.id);
    try {
      const updated = await receiveRemittance(counting.id, countedValue, countNote || undefined);
      showSuccessToast(updated.discrepancy === 0 ? c.countMatched : c.countDisputed);
      setCounting(null);
      refreshCash();
    } catch (err) {
      showErrorToast(err, c.countError);
    } finally {
      setBusyId(null);
    }
  };

  const submitSettle = async () => {
    if (!settling || !settleNote.trim()) return;
    setBusyId(settling.id);
    try {
      await reconcileRemittance(settling.id, settleNote.trim());
      showSuccessToast(c.settleDone);
      setSettling(null);
      refreshCash();
    } catch (err) {
      showErrorToast(err, c.settleError);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] overflow-visible lg:overflow-hidden bg-[var(--app-bg)] flex flex-col">
      <PageFilterBar
        onRefresh={() => refreshCash()}
        refreshing={loading}
        quickFilters={quickFilters}
        activeQuickFilter={filter}
        onQuickFilterChange={v => { setFilter(v as CashRemittanceStatus | 'ALL'); setPage(0); }}
      />

      <div className="flex flex-1 min-h-0 flex-col overflow-visible lg:overflow-hidden">
        {/*
          The headline figure.

          "Cash in circulation" is the one number no ERP can produce: it describes the state of the
          field between two accounting entries. It leads the page because it is the question a depot
          manager actually has at six in the evening.
        */}
        <div className="flex items-center justify-between gap-6 px-4 h-14 shrink-0 border-b border-[var(--border)]"
             style={{ background: 'var(--surface)' }}>
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
                 style={{ background: 'color-mix(in srgb, var(--brand) 12%, transparent)' }}>
              <IconWallet size={18} style={{ color: 'var(--brand)' }} />
            </div>
            <div className="min-w-0">
              <div className="text-xs font-medium text-[var(--text-muted)]">{c.circulationLabel}</div>
              <div className="flex items-baseline gap-1.5">
                <span className="text-xl font-[700] tabular-nums text-[var(--text-primary)]">
                  {circulation === null ? '—' : formatAmount(circulation)}
                </span>
                <span className="text-xs font-medium text-[var(--text-muted)]">{CURRENCY}</span>
              </div>
            </div>
          </div>
          <span className="text-xs text-[var(--text-muted)] text-end hidden sm:block max-w-[280px]">
            {c.circulationHint}
          </span>
        </div>

        <div className="flex-1 overflow-auto" style={{ scrollbarWidth: 'thin' }}>
          <div className="min-w-[900px] lg:min-w-0">
            <table className="w-full border-collapse">
              <thead className="sticky top-0 z-20 border-b border-[var(--border)]"
                     style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                <tr>
                  <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{c.colDriver}</th>
                  {/* Amounts right-aligned so decimal points line up down the column. */}
                  <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]">{c.colExpected}</th>
                  <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]">{c.colDeclared}</th>
                  <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]">{c.colCounted}</th>
                  <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]">{c.colDelta}</th>
                  <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{c.colStatus}</th>
                  <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]" />
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  Array.from({ length: 5 }).map((_, i) => (
                    <tr key={i} className="border-b border-[var(--border)]">
                      {Array.from({ length: 7 }).map((__, j) => (
                        <td key={j} className="px-6 py-3"><Skeleton className="h-4 w-full max-w-[110px]" /></td>
                      ))}
                    </tr>
                  ))
                ) : rows.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="py-0">
                      <EmptyState
                        icon={<IconCashBanknote size={26} />}
                        message={c.emptyMessage}
                        hint={c.emptyHint}
                      />
                    </td>
                  </tr>
                ) : (
                  rows.map(r => {
                    const disputed = r.status === 'DISPUTED';
                    const delta = r.discrepancy;
                    return (
                      <tr key={r.id} className="h-14 border-b border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors">
                        <td className="px-6 text-xs">
                          <span className="font-[600] text-[var(--text-primary)]">{r.driverName ?? '—'}</span>
                        </td>
                        <td className="px-6 text-end text-xs tabular-nums text-[var(--text-secondary)]">
                          {formatAmount(r.expectedTotal)}
                        </td>
                        <td className="px-6 text-end text-xs tabular-nums text-[var(--text-secondary)]">
                          {formatAmount(r.declaredTotal)}
                        </td>
                        <td className="px-6 text-end text-xs tabular-nums font-[600] text-[var(--text-primary)]">
                          {formatAmount(r.receivedTotal)}
                        </td>
                        {/*
                          The gap carries its sign as text, with colour only reinforcing it — colour
                          alone fails a colour-blind cashier, a printed sheet, and a screen reader.
                        */}
                        <td className="px-6 text-end text-xs tabular-nums font-[700]"
                            style={{
                              color: delta == null ? 'var(--text-soft)'
                                : delta === 0 ? 'var(--success)'
                                : 'var(--danger)',
                            }}>
                          <span className="inline-flex items-center gap-1 justify-end">
                            {delta != null && delta !== 0 && <IconAlertTriangle size={12} aria-hidden />}
                            {formatDelta(delta)}
                          </span>
                        </td>
                        <td className="px-6">
                          <StatusBadge
                            status={r.status}
                            label={(t.cashStatus as Record<string, string>)[r.status] ?? r.status}
                            size="sm"
                          />
                        </td>
                        <td className="px-6 text-end">
                          <div className="flex items-center justify-end gap-1.5">
                            {r.status === 'DECLARED' && (
                              <Button
                                size="sm"
                                disabled={busyId === r.id}
                                onClick={() => { setCounting(r); setCountedText(''); setCountNote(''); }}
                                className="h-7 gap-1 px-2 text-xs font-semibold"
                              >
                                <IconScale size={12} /> {c.actionCount}
                              </Button>
                            )}
                            {disputed && (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={busyId === r.id}
                                onClick={() => { setSettling(r); setSettleNote(''); }}
                                className="h-7 gap-1 px-2 text-xs font-semibold"
                                style={{ color: 'var(--danger)' }}
                              >
                                <IconCheck size={12} /> {c.actionSettle}
                              </Button>
                            )}
                            {!disputed && r.status !== 'DECLARED' && (
                              <span className="text-xs text-[var(--text-soft)]">—</span>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>

        <TablePagination
          page={page}
          totalPages={totalPages}
          totalElements={totalElements}
          size={pageSize}
          onPageChange={setPage}
          onSizeChange={s => { setPageSize(s); setPage(0); }}
          labels={{ results: c.countSuffix }}
        />
      </div>

      {/* Counting — the second pair of eyes. */}
      <AppModal
        open={counting !== null}
        onClose={() => setCounting(null)}
        title={c.countTitle}
        subtitle={counting?.driverName ?? undefined}
        size="sm"
        footer={
          <div className="flex items-center justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setCounting(null)}>
              {t.actions?.cancel ?? 'Annuler'}
            </Button>
            <Button
              size="sm"
              disabled={countedValue === null || busyId === counting?.id}
              onClick={() => void submitCount()}
            >
              {c.countConfirm}
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-3">
            <Figure label={c.colExpected} value={formatAmount(counting?.expectedTotal)} />
            <Figure label={c.colDeclared} value={formatAmount(counting?.declaredTotal)} />
          </div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="counted" className="text-xs font-medium text-[var(--text-muted)]">
              {c.countedLabel}
            </label>
            <div className="flex items-center gap-2">
              <Input
                id="counted"
                inputMode="decimal"
                autoFocus
                value={countedText}
                onChange={e => setCountedText(e.target.value)}
                placeholder="0,000"
                className="tabular-nums text-end font-[600]"
              />
              <span className="text-xs font-medium text-[var(--text-muted)]">{CURRENCY}</span>
            </div>
            <p className="text-xs text-[var(--text-muted)]">{c.countedHint}</p>
          </div>

          {/* The gap appears while typing: the operator commits to a number he has already seen. */}
          {liveDelta !== null && (
            <div
              className="rounded-lg px-3 py-2 flex items-center justify-between"
              style={{
                background: liveDelta === 0
                  ? 'color-mix(in srgb, var(--success) 10%, transparent)'
                  : 'color-mix(in srgb, var(--danger) 10%, transparent)',
              }}
            >
              <span className="text-xs font-medium" style={{ color: 'var(--text-secondary)' }}>
                {c.colDelta}
              </span>
              <span
                className="text-sm font-[700] tabular-nums"
                style={{ color: liveDelta === 0 ? 'var(--success)' : 'var(--danger)' }}
              >
                {formatDelta(liveDelta)} {CURRENCY}
              </span>
            </div>
          )}
        </div>
      </AppModal>

      {/* Settling a gap — the explanation is what an audit will have to go on. */}
      <ConfirmModal
        open={settling !== null}
        title={`${c.settleTitle} — ${settling?.driverName ?? ''}`}
        description={`${c.colDelta} : ${formatDelta(settling?.discrepancy)} ${CURRENCY}`}
        variant="danger"
        reasonLabel={c.settleNoteLabel}
        reasonPlaceholder={c.settleNotePlaceholder}
        reason={settleNote}
        onReasonChange={setSettleNote}
        reasonRequired
        confirmLabel={c.settleConfirm}
        cancelLabel={t.actions?.cancel ?? 'Annuler'}
        loading={busyId === settling?.id}
        onConfirm={() => void submitSettle()}
        onCancel={() => setSettling(null)}
      />
    </div>
  );
}

/** One read-only figure in the counting dialog. */
function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-[var(--border)] px-3 py-2">
      <div className="text-xs font-medium text-[var(--text-muted)]">{label}</div>
      <div className="text-sm font-[600] tabular-nums text-[var(--text-primary)]">{value}</div>
    </div>
  );
}
