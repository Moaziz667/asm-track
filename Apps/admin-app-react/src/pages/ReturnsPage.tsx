import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';

import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { ExportCsvButton } from '@/components/layout/ExportCsvButton';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Select, SelectTrigger, SelectValue, SelectContent, SelectItem,
} from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/feedback/EmptyState';
import { AppModal } from '@/components/overlays/AppModal';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import {
  IconPlus, IconTrash, IconRotateClockwise, IconPackageExport, IconSearch,
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
  const [createOpen, setCreateOpen] = useState(false);
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
      showErrorToast(null, 'Échec du chargement des retours');
    } finally {
      setLoading(false);
    }
  }, [filter]);

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
      showSuccessToast(`Retour → ${statusLabel(target)}`);
      await fetchAll();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, 'Transition impossible');
    } finally {
      setBusyId(null);
    }
  };

  const confirmReason = async () => {
    if (!reasonModal) return;
    const note = reasonText.trim();
    if (!note) return; // ConfirmModal enforces this via reasonRequired, but guard anyway
    const { rma, target } = reasonModal;
    setReasonModal(null);
    await runTransition(rma, target, note);
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
      FILTERS.map((f) => ({ value: f, label: f === 'ALL' ? 'Tous' : statusLabel(f) }));
    return base.map((b) => ({
      value: b.value,
      label: b.label,
      count: b.value === filter ? visibleRows.length : undefined,
    }));
  }, [filter, visibleRows.length, t]);

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
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
            <Button size="sm" onClick={() => setCreateOpen(true)} className="h-7 gap-1.5 px-3 text-xs font-bold">
              <IconPlus size={14} /> {t.returnsPage?.newReturn ?? 'Nouveau retour'}
            </Button>
          </>
        }
      />

      <div className="flex flex-1 min-h-0 flex-col overflow-hidden" style={{ background: 'var(--surface)' }}>
        {/* Compact toolbar — label + mini KPIs */}
        <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
          <div className="flex items-center gap-8">
            <span className="text-base font-[600] text-[var(--text-primary)]">
              Retours <span className="font-mono text-[var(--brand)]">{filter === 'ALL' ? 'TOUS' : statusLabel(filter).toUpperCase()}</span>
            </span>
            <span className="text-xs font-[500] text-[var(--text-muted)]">
              {visibleRows.length} retour{visibleRows.length > 1 ? 's' : ''}
            </span>
          </div>
          <div className="flex items-center gap-5 text-xs font-[500]">
            <span className="text-[var(--text-muted)]">Total <b className="font-mono text-[var(--text-primary)] tabular-nums">{kpi?.total ?? 0}</b></span>
            <span className="text-[var(--text-muted)]">
              <span className="inline-block h-1.5 w-1.5 rounded-full align-middle mr-1" style={{ background: 'var(--warning)' }} />
              En cours <b className="font-mono text-[var(--text-primary)] tabular-nums">{kpi?.open ?? 0}</b>
            </span>
            <span className="text-[var(--text-muted)]">
              <span className="inline-block h-1.5 w-1.5 rounded-full align-middle mr-1" style={{ background: 'var(--success)' }} />
              Restockés <b className="font-mono text-[var(--text-primary)] tabular-nums">{kpi?.restocked ?? 0}</b>
            </span>
          </div>
        </div>

        {/* Table — styled to match the Deliveries data slab: status ribbon, sticky sunken
            header, compact mono refs. Kept semantic (<table>) for a11y/screen readers. */}
        <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
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
                    {Array.from({ length: 6 }).map((__, j) => (
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
                      hint={t.returnsPage?.emptyHint ?? 'Créez un retour depuis une livraison livrée.'}
                      action={{ label: t.returnsPage?.newReturn ?? 'Nouveau retour', onClick: () => setCreateOpen(true) }}
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
                      <span className="tabular-nums font-[600]">{r.totalUnits}</span> u.
                      <span className="text-[var(--text-soft)]"> · {r.items.length} lignes</span>
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

      <CreateReturnModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => { setCreateOpen(false); void fetchAll(); }}
      />

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

// ─── Create return modal ─────────────────────────────────────────────────────
function CreateReturnModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<any | null>(null);
  const [items, setItems] = useState<RmaItem[]>([]);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) { setSearch(''); setResults([]); setSelected(null); setItems([]); setReason(''); }
  }, [open]);

  const doSearch = async () => {
    setSearching(true);
    try {
      const res = await api.get('/api/admin/deliveries', { params: { q: search, status: 'DELIVERED', size: 10 } });
      setResults(Array.isArray(res.data?.content) ? res.data.content : []);
    } catch { setResults([]); }
    finally { setSearching(false); }
  };

  const selectDelivery = async (d: any) => {
    setSelected(d);
    try {
      const res = await api.get(`/api/admin/deliveries/${d.deliveryId}`);
      const detailItems = res.data?.items ?? [];
      setItems(detailItems.length > 0
        ? detailItems.map((it: any) => ({ sku: it.sku, name: it.name, quantity: it.quantity ?? 1, condition: 'RESELLABLE' as const }))
        : [{ name: '', quantity: 1, condition: 'RESELLABLE' }]);
    } catch {
      setItems([{ name: '', quantity: 1, condition: 'RESELLABLE' }]);
    }
  };

  const updateItem = (i: number, patch: Partial<RmaItem>) => setItems((prev) => prev.map((it, idx) => idx === i ? { ...it, ...patch } : it));
  const removeItem = (i: number) => setItems((prev) => prev.filter((_, idx) => idx !== i));
  const addItem = () => setItems((prev) => [...prev, { name: '', quantity: 1, condition: 'RESELLABLE' }]);

  const submit = async () => {
    if (!selected) { showErrorToast(null, 'Sélectionnez une livraison'); return; }
    const valid = items.filter((it) => (it.name || it.sku) && it.quantity > 0);
    if (valid.length === 0) { showErrorToast(null, 'Ajoutez au moins un article'); return; }
    setSubmitting(true);
    try {
      await api.post('/api/admin/returns', { deliveryId: selected.deliveryId, reason, items: valid });
      showSuccessToast('Retour créé');
      onCreated();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, 'Échec de la création');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AppModal opened={open} onClose={onClose} title="Nouveau retour (RMA)" subtitle="À créer sur une livraison déjà livrée." size="lg">
      <div className="flex flex-col gap-4">
        {!selected ? (
          <>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <IconSearch size={15} className="absolute start-3 top-1/2 -translate-y-1/2 text-[var(--text-soft)]" />
                <Input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && doSearch()}
                  placeholder="Rechercher une livraison livrée (client, réf, BL)…"
                  className="ps-9"
                />
              </div>
              <Button onClick={doSearch} disabled={searching}>{searching ? 'Recherche…' : 'Rechercher'}</Button>
            </div>
            <div className="flex max-h-[320px] flex-col gap-1.5 overflow-y-auto">
              {results.length === 0 ? (
                <p className="py-8 text-center text-sm text-[var(--text-soft)]">
                  {searching ? 'Recherche…' : 'Aucune livraison — lancez une recherche.'}
                </p>
              ) : results.map((d) => (
                <button
                  key={d.deliveryId}
                  onClick={() => selectDelivery(d)}
                  className="flex items-center justify-between rounded-lg border border-[var(--border)] p-3 text-left transition-colors hover:bg-[var(--hover-bg)]"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-[var(--text-primary)]">{d.clientName ?? d.orderRef}</p>
                    <p className="truncate text-xs text-[var(--text-muted)]">{d.blNumber ?? d.erpOrderId} · {d.dropoffCity}</p>
                  </div>
                  <IconArrowRight size={14} className="text-[var(--text-soft)]" />
                </button>
              ))}
            </div>
          </>
        ) : (
          <>
            <div className="flex items-center justify-between rounded-lg border border-[var(--border)] bg-[var(--app-bg)] p-3">
              <div className="min-w-0">
                <p className="truncate text-base font-bold text-[var(--text-primary)]">{selected.clientName ?? selected.orderRef}</p>
                <p className="truncate text-xs text-[var(--text-muted)]">{selected.blNumber ?? selected.erpOrderId}</p>
              </div>
              <Button variant="ghost" size="sm" onClick={() => { setSelected(null); setItems([]); }}>Changer</Button>
            </div>

            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between">
                <label className="text-xs font-bold text-[var(--text-secondary)]">Articles retournés</label>
                <Button variant="ghost" size="sm" onClick={addItem} className="h-7 gap-1 px-2 text-xs"><IconPlus size={12} /> Ajouter</Button>
              </div>
              {items.map((it, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Input
                    value={it.name ?? ''}
                    onChange={(e) => updateItem(i, { name: e.target.value })}
                    placeholder="Article"
                    className="h-9 flex-1"
                  />
                  <Input
                    type="number"
                    min={1}
                    value={it.quantity}
                    onChange={(e) => updateItem(i, { quantity: Number(e.target.value) || 0 })}
                    className="h-9 w-20"
                  />
                  <Select value={it.condition} onValueChange={(v) => updateItem(i, { condition: v as RmaItem['condition'] })}>
                    <SelectTrigger className="h-9 w-[150px]"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="RESELLABLE">Revendable</SelectItem>
                      <SelectItem value="DAMAGED">Endommagé</SelectItem>
                    </SelectContent>
                  </Select>
                  <Button variant="outline" size="icon" onClick={() => removeItem(i)} className="h-9 w-9 text-[var(--danger)]">
                    <IconTrash size={14} />
                  </Button>
                </div>
              ))}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-bold text-[var(--text-secondary)]">Motif du retour</label>
              <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} placeholder="Ex. produit défectueux, erreur de commande…" />
            </div>

            <div className="flex items-center justify-end gap-2 pt-1">
              <Button variant="outline" onClick={onClose}>Annuler</Button>
              <Button onClick={submit} disabled={submitting} className="gap-1.5">
                {submitting ? 'Création…' : (<><IconPackageExport size={15} /> Créer le retour</>)}
              </Button>
            </div>
          </>
        )}
      </div>
    </AppModal>
  );
}
