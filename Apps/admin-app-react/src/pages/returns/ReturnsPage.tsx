import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { cn, formatMoney } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { useIsMobile } from '@/hooks/use-mobile';

import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { ExportCsvButton } from '@/components/layout/ExportCsvButton';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/feedback/EmptyState';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { RmaDetailDrawer } from '@/components/returns/RmaDetailDrawer';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import {
  IconRotateClockwise, IconPackageExport,
  IconArrowRight, IconBan, IconCircleCheck, IconArchive, IconReload,
} from '@tabler/icons-react';

// ─── Domain ────────────────────────────────────────────────────────────────
export type RmaStatus = 'REQUESTED' | 'APPROVED' | 'RECEIVED' | 'RESTOCKED' | 'REJECTED' | 'CANCELLED';

export interface RmaItem { id?: string; sku?: string; name?: string; quantity: number; unitPrice?: number; condition?: 'RESELLABLE' | 'DAMAGED'; reason?: string; }
export interface Rma {
  id: string; deliveryId: string; erpOrderId?: string; blNumber?: string; clientName?: string;
  status: RmaStatus; reason?: string; resolutionNote?: string; items: RmaItem[]; totalUnits: number;
  erpSyncStatus?: string; erpSyncError?: string; createdBy?: string; createdAt?: string;
  receivedAt?: string; restockedAt?: string;
  trackingNumber?: string; shippingCarrier?: string; shippedAt?: string;
}

/** Status visual tokens (tone classes from the design system). */
export const STATUS_TOKENS: Record<RmaStatus, { dot: string; text: string; bg: string }> = {
  REQUESTED: { dot: 'var(--warning)', text: 'var(--warning)', bg: 'color-mix(in srgb, var(--warning) 12%, transparent)' },
  APPROVED:  { dot: 'var(--brand)',   text: 'var(--brand)',   bg: 'color-mix(in srgb, var(--brand) 12%, transparent)' },
  RECEIVED:  { dot: 'var(--info)',    text: 'var(--info)',    bg: 'color-mix(in srgb, var(--info) 12%, transparent)' },
  RESTOCKED: { dot: 'var(--success)', text: 'var(--success)', bg: 'color-mix(in srgb, var(--success) 12%, transparent)' },
  REJECTED:  { dot: 'var(--danger)',  text: 'var(--danger)',  bg: 'color-mix(in srgb, var(--danger) 12%, transparent)' },
  CANCELLED: { dot: 'var(--text-soft)', text: 'var(--text-muted)', bg: 'color-mix(in srgb, var(--text-soft) 14%, transparent)' },
};

/** Allowed next transitions from each status. */
export const NEXT: Record<RmaStatus, RmaStatus[]> = {
  REQUESTED: ['APPROVED', 'REJECTED', 'CANCELLED'],
  APPROVED:  ['RECEIVED', 'REJECTED', 'CANCELLED'],
  RECEIVED:  ['RESTOCKED', 'CANCELLED'],
  RESTOCKED: [], REJECTED: [], CANCELLED: [],
};

export const TRANSITION_ICON: Partial<Record<RmaStatus, typeof IconArrowRight>> = {
  RESTOCKED: IconRotateClockwise,
  REJECTED: IconBan,
  CANCELLED: IconBan,
  APPROVED: IconCircleCheck,
  RECEIVED: IconArchive,
};

const FILTERS: (RmaStatus | 'ALL')[] = ['ALL', 'REQUESTED', 'APPROVED', 'RECEIVED', 'RESTOCKED', 'REJECTED'];

const PAGE_SIZE = 25;

// ─── Page ────────────────────────────────────────────────────────────────────
export default function ReturnsPage() {
  const t = useT();
  const isMobile = useIsMobile();
  const statusLabel = (s: RmaStatus) => (t.statusLabels as Record<string, string>)[s] ?? s;

  const [rmas, setRmas] = useState<Rma[]>([]);
  const [kpi, setKpi] = useState<{ total: number; open: number; restocked: number; totalValue?: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState<RmaStatus | 'ALL'>('ALL');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  // Reason modal for reject/cancel transitions (replaces window.prompt).
  const [reasonModal, setReasonModal] = useState<{ rma: Rma; target: RmaStatus } | null>(null);
  const [reasonText, setReasonText] = useState('');
  // Detail drawer — opens on row click; shows items, value, timeline, sync triage.
  const [selected, setSelected] = useState<Rma | null>(null);

  const fetchAll = useCallback(async () => {
    setLoading(true);
    try {
      const params: Record<string, string | number> = { page, size: PAGE_SIZE };
      if (filter !== 'ALL') params.status = filter;
      if (debouncedQuery.trim()) params.q = debouncedQuery.trim();
      if (dateFrom) params.dateFrom = dateFrom;
      if (dateTo) params.dateTo = dateTo;
      const [listRes, kpiRes] = await Promise.all([
        api.get('/api/admin/returns', { params }),
        api.get('/api/admin/returns/kpi').catch(() => ({ data: null })),
      ]);
      // Endpoint is paginated → response is a Spring Page { content, totalPages, totalElements }.
      const data = listRes.data ?? {};
      setRmas(Array.isArray(data.content) ? data.content : (Array.isArray(data) ? data : []));
      setTotalPages(Math.max(1, Number(data.totalPages ?? 1)));
      setTotalElements(Number(data.totalElements ?? (Array.isArray(data.content) ? data.content.length : 0)));
      if (kpiRes.data) setKpi({ total: kpiRes.data.total, open: kpiRes.data.open, restocked: kpiRes.data.restocked, totalValue: Number(kpiRes.data.totalValue) || 0 });
    } catch {
      showErrorToast(null, t.returnsPage?.loadError ?? 'Échec du chargement des retours');
    } finally {
      setLoading(false);
    }
  }, [filter, debouncedQuery, dateFrom, dateTo, page, t]);

  useEffect(() => { void fetchAll(); }, [fetchAll]);

  // Real-time: refresh when any return changes status (admin action elsewhere or a public client
  // create/cancel). Broadcast on the admin.deliveries topic as `return.status_changed`.
  useRealtimeEvent(['return.status_changed'], () => { void fetchAll(); });

  // Keep the open drawer in sync with the freshly-fetched list (status/sync update after an action).
  useEffect(() => {
    if (!selected) return;
    const fresh = rmas.find((r) => r.id === selected.id);
    if (fresh && fresh !== selected) setSelected(fresh);
  }, [rmas]); // eslint-disable-line react-hooks/exhaustive-deps

  // Debounce the search box → server `q`; reset to the first page on a new search or filter.
  useEffect(() => {
    const id = setTimeout(() => { setDebouncedQuery(query); setPage(0); }, 300);
    return () => clearTimeout(id);
  }, [query]);

  // Entry point from the row actions. Reject/cancel need a reason → open the modal; other
  // transitions run immediately.
  const transition = (r: Rma, target: RmaStatus) => {
    if (target === 'REJECTED' || target === 'CANCELLED') {
      setReasonText('');
      setReasonModal({ rma: r, target });
      return;
    }
    void runTransition(r, target);
  };

  const runTransition = async (r: Rma, target: RmaStatus, note?: string) => {
    setBusyId(r.id);
    try {
      await api.post(`/api/admin/returns/${r.id}/transition`, null, {
        params: { target, ...(note ? { note } : {}) },
      });
      showSuccessToast(`${t.returnsPage?.transitionDone ?? 'Retour'} → ${statusLabel(target)}`);
      await fetchAll();
    } catch (err) {
      showErrorToast(err, t.returnsPage?.transitionError ?? 'Transition impossible');
    } finally {
      setBusyId(null);
    }
  };

  // Save inbound return-shipment tracking (carrier + tracking number) from the drawer.
  const saveShipping = async (r: Rma, data: { trackingNumber: string; shippingCarrier: string }) => {
    try {
      await api.patch(`/api/admin/returns/${r.id}/shipping`, data);
      showSuccessToast(t.returnsPage?.shippingSaved ?? 'Expédition enregistrée');
      await fetchAll();
    } catch (err) {
      showErrorToast(err, t.returnsPage?.shippingError ?? 'Enregistrement impossible');
    }
  };

  // Manual re-run of the ERP reverse-move for a return whose sync failed.
  const resync = async (r: Rma) => {
    setBusyId(r.id);
    try {
      await api.post(`/api/admin/returns/${r.id}/resync`);
      showSuccessToast(t.returnsPage?.resyncDone ?? 'Resynchronisation lancée');
      await fetchAll();
    } catch (err) {
      showErrorToast(err, t.returnsPage?.resyncError ?? 'Resynchronisation impossible');
    } finally {
      setBusyId(null);
    }
  };

  const confirmReason = async () => {
    if (!reasonModal) return;
    const note = reasonText.trim();
    if (!note) return; // ConfirmModal enforces this via reasonRequired, but guard anyway
    const { rma, target } = reasonModal;
    // Keep the modal open while the request runs so its spinner (loading prop) shows;
    // close on success, leave open on error so the user can retry.
    setBusyId(rma.id);
    try {
      await api.post(`/api/admin/returns/${rma.id}/transition`, null, { params: { target, note } });
      showSuccessToast(`${t.returnsPage?.transitionDone ?? 'Retour'} → ${statusLabel(target)}`);
      setReasonModal(null);
      await fetchAll();
    } catch (err) {
      showErrorToast(err, t.returnsPage?.transitionError ?? 'Transition impossible');
    } finally {
      setBusyId(null);
    }
  };

  // Rows come pre-filtered + paginated from the server (status + q applied server-side).
  const visibleRows = rmas;

  // Quick-filter pills (statuses); count reflects the full server-side result for the active filter.
  const quickFilters = useMemo(() => {
    const base: { value: RmaStatus | 'ALL'; label: string }[] =
      FILTERS.map((f) => ({ value: f, label: f === 'ALL' ? (t.returnsPage?.allFilter ?? 'Tous') : statusLabel(f) }));
    return base.map((b) => ({
      value: b.value,
      label: b.label,
      count: b.value === filter ? totalElements : undefined,
    }));
  }, [filter, totalElements, t]);

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] overflow-visible lg:overflow-hidden bg-[var(--app-bg)] flex flex-col">
      <PageFilterBar
        search={query}
        onSearch={setQuery}
        searchPlaceholder={t.returnsPage?.searchPlaceholder ?? 'Rechercher un retour (client, BL, réf ERP)…'}
        onRefresh={() => void fetchAll()}
        refreshing={loading}
        quickFilters={quickFilters}
        activeQuickFilter={filter}
        onQuickFilterChange={(v) => { setFilter(v as RmaStatus | 'ALL'); setPage(0); }}
        extraActions={
          <>
            <div className="flex items-center gap-1.5 shrink-0">
              <DatePickerPopover value={dateFrom || null} onChange={v => { setDateFrom(v ?? ''); setPage(0); }} placeholder={t.dispatchDeskPage?.dateFrom ?? 'De'} />
              <span className="text-xs text-[var(--text-muted)]">→</span>
              <DatePickerPopover value={dateTo || null} onChange={v => { setDateTo(v ?? ''); setPage(0); }} placeholder={t.dispatchDeskPage?.dateTo ?? 'À'} />
            </div>
            <ExportCsvButton
              baseName="retours"
              rows={visibleRows}
              columns={[
                { header: t.common?.client ?? 'Client', accessor: r => r.clientName },
                { header: 'BL', accessor: r => r.blNumber },
                { header: t.common?.reference ?? 'ERP Ref', accessor: r => r.erpOrderId },
                { header: t.common?.statut ?? 'Status', accessor: r => statusLabel(r.status) },
                { header: 'Units', accessor: r => r.totalUnits },
                { header: t.common?.motif ?? 'Reason', accessor: r => r.reason },
                { header: 'Created', accessor: r => r.createdAt },
              ]}
            />
          </>
        }
      />

      <div className="flex flex-1 min-h-0 flex-col overflow-visible lg:overflow-hidden" style={{ background: 'var(--app-bg)' }}>
        {/* Compact toolbar — label + mini KPIs */}
        <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
          <div className="flex items-center gap-8">
            <span className="text-base font-[600] text-[var(--text-primary)]">
              {t.returnsPage?.title ?? 'Retours'} <span className="font-mono text-[var(--brand)]">{filter === 'ALL' ? (t.returnsPage?.allUpper ?? 'TOUS') : statusLabel(filter).toUpperCase()}</span>
            </span>
            <span className="text-xs font-[500] text-[var(--text-muted)]">
              {totalElements} {t.returnsPage?.countSuffix ?? 'retour(s)'}
            </span>
          </div>
          <div className="flex items-center gap-5 text-xs font-[500]">
            <span className="text-[var(--text-muted)]">{t.returnsPage?.kpiTotal ?? 'Total'} <b className="font-mono text-[var(--text-primary)] tabular-nums">{kpi?.total ?? 0}</b></span>
            <span className="text-[var(--text-muted)]">
              <span className="inline-block h-1.5 w-1.5 rounded-full align-middle mr-1" style={{ background: 'var(--warning)' }} />
              {t.returnsPage?.kpiOpen ?? 'En cours'} <b className="font-mono text-[var(--text-primary)] tabular-nums">{kpi?.open ?? 0}</b>
            </span>
            <span className="text-[var(--text-muted)]">
              <span className="inline-block h-1.5 w-1.5 rounded-full align-middle mr-1" style={{ background: 'var(--success)' }} />
              {t.returnsPage?.kpiRestocked ?? 'Restockés'} <b className="font-mono text-[var(--text-primary)] tabular-nums">{kpi?.restocked ?? 0}</b>
            </span>
            <span className="text-[var(--text-muted)]">
              {t.returnsPage?.kpiValue ?? 'Valeur'} <b className="font-mono text-[var(--text-primary)] tabular-nums">{formatMoney(kpi?.totalValue ?? 0)}</b>
            </span>
          </div>
        </div>

        {/* Mobile: tap-to-open cards (the drawer holds all transitions). Desktop: table. */}
        {isMobile ? (
          <div className="flex-1 overflow-auto flex flex-col gap-2 p-3">
            {loading ? (
              Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-[84px] w-full rounded-lg" />)
            ) : visibleRows.length === 0 ? (
              <EmptyState
                icon={<IconPackageExport size={26} />}
                message={t.returnsPage?.emptyMessage ?? 'Aucun retour'}
                hint={t.returnsPage?.emptyHint ?? 'Créez un retour depuis le détail d’une livraison livrée.'}
              />
            ) : (
              visibleRows.map((r) => {
                const itemTeaser = r.items.map((it) => it.name ?? it.sku).filter(Boolean).slice(0, 2).join(', ');
                return (
                  <button
                    key={r.id}
                    type="button"
                    onClick={() => setSelected(r)}
                    className="text-start rounded-lg border border-[var(--border)] p-3 flex flex-col gap-2 hover:bg-[var(--hover-bg)] transition-colors"
                    style={{ background: 'var(--surface)', borderInlineStartWidth: 3, borderInlineStartColor: STATUS_TOKENS[r.status]?.dot ?? 'var(--border)' }}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-[600] text-[var(--text-primary)] truncate">{r.clientName ?? '—'}</span>
                      <StatusBadge status={r.status} label={statusLabel(r.status)} size="sm" />
                    </div>
                    <div className="flex items-center justify-between gap-2 text-xs text-[var(--text-muted)]">
                      <span className="font-mono truncate">{r.blNumber ?? r.erpOrderId ?? '—'}</span>
                      <span className="tabular-nums shrink-0">{r.totalUnits} {t.returnsPage?.unitsSuffix ?? 'u.'} · {r.items.length} {t.returnsPage?.linesSuffix ?? 'lignes'}</span>
                    </div>
                    {itemTeaser && <span className="text-xs text-[var(--text-secondary)] truncate">{itemTeaser}</span>}
                    {r.erpSyncStatus && (
                      <div><StatusBadge status={r.erpSyncStatus} label={tlabel(t.returnsPage?.syncLabels, r.erpSyncStatus) ?? r.erpSyncStatus} size="sm" /></div>
                    )}
                  </button>
                );
              })
            )}
          </div>
        ) : (
        <div className="flex-1 overflow-auto" style={{ scrollbarWidth: 'thin' }}>
          <div className="min-w-[900px] lg:min-w-0">
          <table className="w-full border-collapse">
            <thead className="sticky top-0 z-20 border-b border-[var(--border)]" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
              <tr>
                <th className="w-2 px-0" />
                <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colClient ?? 'Client'}</th>
                <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colBl ?? 'BL / Réf ERP'}</th>
                <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colItems ?? 'Articles'}</th>
                <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colStatus ?? 'Statut'}</th>
                <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colSync ?? 'Sync ERP'}</th>
                <th className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colReason ?? 'Motif'}</th>
                <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]">{t.returnsPage?.colActions ?? ''}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-[var(--border)]">
                    <td className="p-0" />
                    {Array.from({ length: 7 }).map((__, j) => (
                      <td key={j} className="px-6 py-3"><Skeleton className="h-4 w-full max-w-[120px]" /></td>
                    ))}
                  </tr>
                ))
              ) : visibleRows.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-0">
                    <EmptyState
                      icon={<IconPackageExport size={26} />}
                      message={t.returnsPage?.emptyMessage ?? 'Aucun retour'}
                      hint={t.returnsPage?.emptyHint ?? 'Créez un retour depuis le détail d’une livraison livrée.'}
                    />
                  </td>
                </tr>
              ) : (
                visibleRows.map((r) => {
                  const itemTeaser = r.items.map((it) => it.name ?? it.sku).filter(Boolean).slice(0, 2).join(', ');
                  const moreItems = r.items.length - Math.min(r.items.length, 2);
                  return (
                  <tr
                    key={r.id}
                    onClick={() => setSelected(r)}
                    className="h-14 border-b border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors group cursor-pointer"
                  >
                    {/* Status ribbon (same idiom as Deliveries) */}
                    <td className="p-0">
                      <div className="w-[3px] h-10 rounded-r-[2px]" style={{ backgroundColor: STATUS_TOKENS[r.status]?.dot ?? 'var(--border)' }} />
                    </td>
                    <td className="px-6 text-xs font-[600] text-[var(--text-primary)]">{r.clientName ?? '—'}</td>
                    <td className="px-6 font-mono text-xs text-[var(--text-muted)]">{r.blNumber ?? r.erpOrderId ?? '—'}</td>
                    <td className="px-6 text-xs">
                      <div className="flex flex-col gap-0.5 min-w-0 max-w-[240px]">
                        <span className="truncate text-[var(--text-secondary)]">
                          {itemTeaser || '—'}{moreItems > 0 ? <span className="text-[var(--text-soft)]"> +{moreItems}</span> : null}
                        </span>
                        <span className="text-2xs text-[var(--text-soft)]">
                          <span className="tabular-nums font-[600] text-[var(--text-muted)]">{r.totalUnits}</span> {t.returnsPage?.unitsSuffix ?? 'u.'} · {r.items.length} {t.returnsPage?.linesSuffix ?? 'lignes'}
                        </span>
                      </div>
                    </td>
                    <td className="px-6"><StatusBadge status={r.status} label={statusLabel(r.status)} size="sm" /></td>
                    <td className="px-6" onClick={(e) => e.stopPropagation()}>
                      {r.erpSyncStatus ? (
                        <div className="flex items-center gap-1.5">
                          <span title={r.erpSyncStatus === 'SYNC_FAILED' && r.erpSyncError ? `${t.returnsPage?.syncErrorLabel ?? 'Erreur'} : ${r.erpSyncError}` : undefined}>
                            <StatusBadge status={r.erpSyncStatus} label={tlabel(t.returnsPage?.syncLabels, r.erpSyncStatus) ?? r.erpSyncStatus} size="sm" />
                          </span>
                          {r.erpSyncStatus === 'SYNC_FAILED' && (
                            <Button
                              variant="outline"
                              size="icon"
                              disabled={busyId === r.id}
                              onClick={() => void resync(r)}
                              title={t.returnsPage?.resync ?? 'Resynchroniser'}
                              className="h-6 w-6"
                              style={{ color: 'var(--brand)' }}
                            >
                              <IconReload size={12} />
                            </Button>
                          )}
                        </div>
                      ) : <span className="text-xs text-[var(--text-soft)]">—</span>}
                    </td>
                    <td className="px-6 max-w-[220px] truncate text-xs text-[var(--text-muted)]" title={r.reason ?? ''}>{r.reason ?? '—'}</td>
                    <td className="px-6 text-end" onClick={(e) => e.stopPropagation()}>
                      <div className="flex items-center justify-end gap-1.5">
                        {NEXT[r.status].length === 0 ? (
                          <span className="text-xs text-[var(--text-soft)]">—</span>
                        ) : (
                          NEXT[r.status].map((target) => {
                            const Icon = TRANSITION_ICON[target] ?? IconArrowRight;
                            const tk = STATUS_TOKENS[target];
                            return (
                              <Button
                                key={target}
                                variant="outline"
                                size="sm"
                                disabled={busyId === r.id}
                                onClick={() => transition(r, target)}
                                className="h-7 gap-1 px-2 text-xs font-semibold"
                                style={{ color: tk.text }}
                              >
                                <Icon size={12} /> {statusLabel(target)}
                              </Button>
                            );
                          })
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
        )}

        {/* Pagination footer — server-side paged (mirrors the Deliveries pager). */}
        {(totalPages > 1 || page > 0) && (
          <div className="flex items-center justify-between px-4 py-2.5 border-t border-[var(--border)] shrink-0" style={{ background: 'var(--surface)' }}>
            <span className="text-xs text-[var(--text-muted)]">
              {(t.deliveriesPage?.pageLabel ?? 'Page')} {page + 1} / {totalPages}
            </span>
            <div className="flex items-center gap-1.5">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} className="h-7 px-3 text-xs font-[700]">
                {t.deliveriesPage?.prevButton ?? 'Précédent'}
              </Button>
              <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)} className="h-7 px-3 text-xs font-[700]">
                {t.deliveriesPage?.nextButton ?? 'Suivant'}
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Reason modal for reject/cancel — replaces window.prompt with an inline-validated textarea. */}
      <ConfirmModal
        open={reasonModal !== null}
        title={reasonModal ? `${statusLabel(reasonModal.target)} — ${reasonModal.rma.clientName ?? reasonModal.rma.blNumber ?? ''}` : ''}
        description={t.returnsPage?.reasonRequiredDesc ?? 'A reason is required for this action.'}
        variant="danger"
        reasonLabel={t.returnsPage?.reasonLabel ?? 'Reason'}
        reasonPlaceholder={t.returnsPage?.reasonPlaceholder ?? 'Explain the reason…'}
        reason={reasonText}
        onReasonChange={setReasonText}
        reasonRequired
        confirmLabel={reasonModal ? statusLabel(reasonModal.target) : ''}
        cancelLabel={t.actions?.cancel ?? 'Cancel'}
        loading={busyId === reasonModal?.rma.id}
        onConfirm={() => void confirmReason()}
        onCancel={() => setReasonModal(null)}
      />

      {/* Detail drawer — items, value, ERP-sync triage, and lifecycle timeline. */}
      <RmaDetailDrawer
        rma={selected}
        open={selected !== null}
        onClose={() => setSelected(null)}
        statusLabel={statusLabel}
        busyId={busyId}
        onTransition={transition}
        onResync={(r) => void resync(r)}
        onSaveShipping={saveShipping}
        t={t}
      />
    </div>
  );
}
