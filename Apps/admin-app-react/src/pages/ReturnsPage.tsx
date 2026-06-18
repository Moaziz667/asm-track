import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';

import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { ExportCsvButton } from '@/components/layout/ExportCsvButton';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/feedback/EmptyState';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import {
  IconRotateClockwise, IconPackageExport,
  IconArrowRight, IconBan, IconCircleCheck, IconArchive,
} from '@tabler/icons-react';

// ─── Domain ────────────────────────────────────────────────────────────────
type RmaStatus = 'REQUESTED' | 'APPROVED' | 'RECEIVED' | 'RESTOCKED' | 'REJECTED' | 'CANCELLED';

interface RmaItem { id?: string; sku?: string; name?: string; quantity: number; condition?: 'RESELLABLE' | 'DAMAGED'; reason?: string; }
interface Rma {
  id: string; deliveryId: string; erpOrderId?: string; blNumber?: string; clientName?: string;
  status: RmaStatus; reason?: string; resolutionNote?: string; items: RmaItem[]; totalUnits: number;
  erpSyncStatus?: string; createdBy?: string; createdAt?: string;
}

/** Status visual tokens (tone classes from the design system). */
const STATUS_TOKENS: Record<RmaStatus, { dot: string; text: string; bg: string }> = {
  REQUESTED: { dot: 'var(--warning)', text: 'var(--warning)', bg: 'color-mix(in srgb, var(--warning) 12%, transparent)' },
  APPROVED:  { dot: 'var(--brand)',   text: 'var(--brand)',   bg: 'color-mix(in srgb, var(--brand) 12%, transparent)' },
  RECEIVED:  { dot: 'var(--info)',    text: 'var(--info)',    bg: 'color-mix(in srgb, var(--info) 12%, transparent)' },
  RESTOCKED: { dot: 'var(--success)', text: 'var(--success)', bg: 'color-mix(in srgb, var(--success) 12%, transparent)' },
  REJECTED:  { dot: 'var(--danger)',  text: 'var(--danger)',  bg: 'color-mix(in srgb, var(--danger) 12%, transparent)' },
  CANCELLED: { dot: 'var(--text-soft)', text: 'var(--text-muted)', bg: 'color-mix(in srgb, var(--text-soft) 14%, transparent)' },
};

/** Allowed next transitions from each status. */
const NEXT: Record<RmaStatus, RmaStatus[]> = {
  REQUESTED: ['APPROVED', 'REJECTED', 'CANCELLED'],
  APPROVED:  ['RECEIVED', 'REJECTED', 'CANCELLED'],
  RECEIVED:  ['RESTOCKED', 'CANCELLED'],
  RESTOCKED: [], REJECTED: [], CANCELLED: [],
};

const TRANSITION_ICON: Partial<Record<RmaStatus, typeof IconArrowRight>> = {
  RESTOCKED: IconRotateClockwise,
  REJECTED: IconBan,
  CANCELLED: IconBan,
  APPROVED: IconCircleCheck,
  RECEIVED: IconArchive,
};

const FILTERS: (RmaStatus | 'ALL')[] = ['ALL', 'REQUESTED', 'APPROVED', 'RECEIVED', 'RESTOCKED', 'REJECTED'];

// ─── Page ────────────────────────────────────────────────────────────────────
export default function ReturnsPage() {
  const t = useT();
  const statusLabel = (s: RmaStatus) => (t.statusLabels as Record<string, string>)[s] ?? s;

  const [rmas, setRmas] = useState<Rma[]>([]);
  const [kpi, setKpi] = useState<{ total: number; open: number; restocked: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState<RmaStatus | 'ALL'>('ALL');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  // Reason modal for reject/cancel transitions (replaces window.prompt).
  const [reasonModal, setReasonModal] = useState<{ rma: Rma; target: RmaStatus } | null>(null);
  const [reasonText, setReasonText] = useState('');

  const fetchAll = useCallback(async () => {
    setLoading(true);
    try {
      const [listRes, kpiRes] = await Promise.all([
        api.get('/api/admin/returns', { params: filter === 'ALL' ? {} : { status: filter } }),
        api.get('/api/admin/returns/kpi').catch(() => ({ data: null })),
      ]);
      setRmas(Array.isArray(listRes.data) ? listRes.data : []);
      if (kpiRes.data) setKpi({ total: kpiRes.data.total, open: kpiRes.data.open, restocked: kpiRes.data.restocked });
    } catch {
      showErrorToast(null, t.returnsPage?.loadError ?? 'Échec du chargement des retours');
    } finally {
      setLoading(false);
    }
  }, [filter, t]);

  useEffect(() => { void fetchAll(); }, [fetchAll]);

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
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.returnsPage?.transitionError ?? 'Transition impossible');
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
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.returnsPage?.transitionError ?? 'Transition impossible');
    } finally {
      setBusyId(null);
    }
  };

  // Client-side search over the loaded rows (server already filters by status).
  const visibleRows = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rmas;
    return rmas.filter((r) =>
      (r.clientName ?? '').toLowerCase().includes(q) ||
      (r.blNumber ?? '').toLowerCase().includes(q) ||
      (r.erpOrderId ?? '').toLowerCase().includes(q),
    );
  }, [rmas, query]);

  // Quick-filter pills (statuses) with live counts for the active list.
  const quickFilters = useMemo(() => {
    const base: { value: RmaStatus | 'ALL'; label: string }[] =
      FILTERS.map((f) => ({ value: f, label: f === 'ALL' ? (t.returnsPage?.allFilter ?? 'Tous') : statusLabel(f) }));
    return base.map((b) => ({
      value: b.value,
      label: b.label,
      count: b.value === filter ? visibleRows.length : undefined,
    }));
  }, [filter, visibleRows.length, t]);

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
        onQuickFilterChange={(v) => setFilter(v as RmaStatus | 'ALL')}
        extraActions={
          <>
            <ExportCsvButton
              baseName="retours"
              rows={visibleRows}
              columns={[
                { header: 'Client', accessor: r => r.clientName },
                { header: 'BL', accessor: r => r.blNumber },
                { header: 'Réf ERP', accessor: r => r.erpOrderId },
                { header: 'Statut', accessor: r => statusLabel(r.status) },
                { header: 'Unités', accessor: r => r.totalUnits },
                { header: 'Motif', accessor: r => r.reason },
                { header: 'Créé le', accessor: r => r.createdAt },
              ]}
            />
          </>
        }
      />

      <div className="flex flex-1 min-h-0 flex-col overflow-visible lg:overflow-hidden" style={{ background: 'var(--surface)' }}>
        {/* Compact toolbar — label + mini KPIs */}
        <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
          <div className="flex items-center gap-8">
            <span className="text-base font-[600] text-[var(--text-primary)]">
              {t.returnsPage?.title ?? 'Retours'} <span className="font-mono text-[var(--brand)]">{filter === 'ALL' ? (t.returnsPage?.allUpper ?? 'TOUS') : statusLabel(filter).toUpperCase()}</span>
            </span>
            <span className="text-xs font-[500] text-[var(--text-muted)]">
              {visibleRows.length} {t.returnsPage?.countSuffix ?? 'retour(s)'}
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
          </div>
        </div>

        {/* Table — styled to match the Deliveries data slab: status ribbon, sticky sunken
            header, compact mono refs. Kept semantic (<table>) for a11y/screen readers. */}
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
                visibleRows.map((r) => (
                  <tr key={r.id} className="h-14 border-b border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors group">
                    {/* Status ribbon (same idiom as Deliveries) */}
                    <td className="p-0">
                      <div className="w-[3px] h-10 rounded-r-[2px]" style={{ backgroundColor: STATUS_TOKENS[r.status]?.dot ?? 'var(--border)' }} />
                    </td>
                    <td className="px-6 text-xs font-[600] text-[var(--text-primary)]">{r.clientName ?? '—'}</td>
                    <td className="px-6 font-mono text-xs text-[var(--text-muted)]">{r.blNumber ?? r.erpOrderId ?? '—'}</td>
                    <td className="px-6 text-xs text-[var(--text-secondary)]">
                      <span className="tabular-nums font-[600]">{r.totalUnits}</span> {t.returnsPage?.unitsSuffix ?? 'u.'}
                      <span className="text-[var(--text-soft)]"> · {r.items.length} {t.returnsPage?.linesSuffix ?? 'lignes'}</span>
                    </td>
                    <td className="px-6"><StatusBadge status={r.status} label={statusLabel(r.status)} size="sm" /></td>
                    <td className="px-6">
                      {r.erpSyncStatus
                        ? <StatusBadge status={r.erpSyncStatus} label={(t.returnsPage?.syncLabels as any)?.[r.erpSyncStatus] ?? r.erpSyncStatus} size="sm" />
                        : <span className="text-xs text-[var(--text-soft)]">—</span>}
                    </td>
                    <td className="px-6 max-w-[220px] truncate text-xs text-[var(--text-muted)]" title={r.reason ?? ''}>{r.reason ?? '—'}</td>
                    <td className="px-6 text-end">
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
                ))
              )}
            </tbody>
          </table>
          </div>
        </div>
      </div>

      {/* Reason modal for reject/cancel — replaces window.prompt with an inline-validated textarea. */}
      <ConfirmModal
        open={reasonModal !== null}
        title={reasonModal ? `${statusLabel(reasonModal.target)} — ${reasonModal.rma.clientName ?? reasonModal.rma.blNumber ?? ''}` : ''}
        description={t.returnsPage?.reasonRequiredDesc ?? 'Un motif est obligatoire pour cette action.'}
        variant="danger"
        reasonLabel={t.returnsPage?.reasonLabel ?? 'Motif'}
        reasonPlaceholder={t.returnsPage?.reasonPlaceholder ?? 'Expliquez la raison…'}
        reason={reasonText}
        onReasonChange={setReasonText}
        reasonRequired
        confirmLabel={reasonModal ? statusLabel(reasonModal.target) : ''}
        cancelLabel={t.actions?.cancel ?? 'Annuler'}
        loading={busyId === reasonModal?.rma.id}
        onConfirm={() => void confirmReason()}
        onCancel={() => setReasonModal(null)}
      />
    </div>
  );
}
