import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { IconPlus, IconTrash, IconRotateClockwise, IconPackageExport } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';

type RmaStatus = 'REQUESTED' | 'APPROVED' | 'RECEIVED' | 'RESTOCKED' | 'REJECTED' | 'CANCELLED';

interface RmaItem { id?: string; sku?: string; name?: string; quantity: number; condition?: 'RESELLABLE' | 'DAMAGED'; reason?: string; }
interface Rma {
  id: string; deliveryId: string; erpOrderId?: string; blNumber?: string; clientName?: string;
  status: RmaStatus; reason?: string; resolutionNote?: string; items: RmaItem[]; totalUnits: number;
  createdBy?: string; createdAt?: string;
}

// Colors per status; labels come from the locale (t.statusLabels) for i18n.
const STATUS_COLOR: Record<RmaStatus, string> = {
  REQUESTED: '#C4881A',
  APPROVED: '#5E6AD2',
  RECEIVED: '#2594B8',
  RESTOCKED: '#4CAF82',
  REJECTED: '#C7372F',
  CANCELLED: '#8A8F98',
};

// Allowed next transitions from each status.
const NEXT: Record<RmaStatus, RmaStatus[]> = {
  REQUESTED: ['APPROVED', 'REJECTED', 'CANCELLED'],
  APPROVED: ['RECEIVED', 'REJECTED', 'CANCELLED'],
  RECEIVED: ['RESTOCKED', 'CANCELLED'],
  RESTOCKED: [], REJECTED: [], CANCELLED: [],
};

const FILTERS: (RmaStatus | 'ALL')[] = ['ALL', 'REQUESTED', 'APPROVED', 'RECEIVED', 'RESTOCKED', 'REJECTED'];

export default function ReturnsPage() {
  const t = useT();
  const statusLabel = (s: RmaStatus) => (t.statusLabels as Record<string, string>)[s] ?? s;
  const [rmas, setRmas] = useState<Rma[]>([]);
  const [kpi, setKpi] = useState<{ total: number; open: number; restocked: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState<RmaStatus | 'ALL'>('ALL');
  const [createOpen, setCreateOpen] = useState(false);

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

  const transition = async (r: Rma, target: RmaStatus) => {
    try {
      await api.post(`/api/admin/returns/${r.id}/transition`, null, { params: { target } });
      showSuccessToast(`Retour → ${statusLabel(target)}`);
      await fetchAll();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, 'Transition impossible');
    }
  };

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-4 flex items-center justify-between max-w-[1800px] mx-auto">
          <div className="flex items-center gap-3">
            <IconPackageExport size={18} className="text-[var(--brand)]" />
            <h1 className="text-[15px] font-bold text-[var(--text-primary)]">Retours (RMA)</h1>
          </div>
          <Button size="sm" onClick={() => setCreateOpen(true)} className="h-8 px-3 text-[11px] font-bold flex items-center gap-1.5">
            <IconPlus size={14} /> Nouveau retour
          </Button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-[1800px] mx-auto p-6 flex flex-col gap-5">
          {/* KPI */}
          <div className="grid grid-cols-3 gap-4">
            {[
              { label: 'Total retours', value: kpi?.total ?? 0 },
              { label: 'En cours', value: kpi?.open ?? 0 },
              { label: 'Restockés', value: kpi?.restocked ?? 0 },
            ].map(k => (
              <div key={k.label} className="card p-4 border border-[var(--border)] rounded-[12px] bg-[var(--surface)]">
                <p className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">{k.label}</p>
                <p className="text-[28px] font-black text-[var(--text-primary)] tabular-nums mt-1">{k.value}</p>
              </div>
            ))}
          </div>

          {/* Filter tabs */}
          <div className="flex items-center gap-1.5">
            {FILTERS.map(f => (
              <button key={f} onClick={() => setFilter(f)}
                      className={cn('px-3 h-7 rounded-full text-[11px] font-bold transition-colors',
                        filter === f ? 'bg-background text-foreground border border-border shadow-2xs' : 'text-muted-foreground hover:text-foreground')}>
                {f === 'ALL' ? 'Tous' : statusLabel(f)}
              </button>
            ))}
          </div>

          {/* Table */}
          <div className="rounded-lg overflow-hidden border border-[var(--border)] bg-[var(--surface)]">
            <table className="w-full text-[12px]">
              <thead>
                <tr className="text-[10px] uppercase tracking-wider text-[var(--text-muted)]" style={{ background: 'var(--app-bg)' }}>
                  <th className="text-start font-bold px-4 py-2.5">Client</th>
                  <th className="text-start font-bold px-4 py-2.5">BL / Réf ERP</th>
                  <th className="text-start font-bold px-4 py-2.5">Articles</th>
                  <th className="text-start font-bold px-4 py-2.5">Statut</th>
                  <th className="text-start font-bold px-4 py-2.5">Motif</th>
                  <th className="px-4 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={6} className="px-4 py-10 text-center text-[var(--text-muted)]">Chargement…</td></tr>
                ) : rmas.length === 0 ? (
                  <tr><td colSpan={6} className="px-4 py-10 text-center text-[var(--text-muted)]">Aucun retour</td></tr>
                ) : rmas.map(r => {
                  const stColor = STATUS_COLOR[r.status];
                  return (
                    <tr key={r.id} className="border-t border-[var(--border)] group">
                      <td className="px-4 py-2.5 font-semibold text-[var(--text-primary)]">{r.clientName ?? '—'}</td>
                      <td className="px-4 py-2.5 font-mono text-[11px] text-[var(--text-muted)]">{r.blNumber ?? r.erpOrderId ?? '—'}</td>
                      <td className="px-4 py-2.5 text-[var(--text-secondary)]">{r.totalUnits} u. ({r.items.length})</td>
                      <td className="px-4 py-2.5">
                        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full" style={{ background: `${stColor}1a`, color: stColor }}>{statusLabel(r.status)}</span>
                      </td>
                      <td className="px-4 py-2.5 text-[var(--text-muted)] max-w-[220px] truncate">{r.reason ?? '—'}</td>
                      <td className="px-4 py-2.5 text-end">
                        <div className="flex items-center justify-end gap-1.5">
                          {NEXT[r.status].map(target => (
                            <button key={target} onClick={() => transition(r, target)}
                                    className="text-[10px] font-bold px-2 py-1 rounded border border-[var(--border)] hover:bg-[var(--hover-bg)]"
                                    style={{ color: STATUS_COLOR[target] }}>
                              {target === 'RESTOCKED' && <IconRotateClockwise size={11} className="inline mr-0.5" />}
                              {statusLabel(target)}
                            </button>
                          ))}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <CreateReturnModal open={createOpen} onClose={() => setCreateOpen(false)} onCreated={() => { setCreateOpen(false); void fetchAll(); }} />
    </div>
  );
}

// ── Create return modal ──────────────────────────────────────────────────────

function CreateReturnModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [selected, setSelected] = useState<any | null>(null);
  const [items, setItems] = useState<RmaItem[]>([]);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) { setSearch(''); setResults([]); setSelected(null); setItems([]); setReason(''); }
  }, [open]);

  const doSearch = async () => {
    try {
      const res = await api.get('/api/admin/deliveries', { params: { q: search, status: 'DELIVERED', size: 10 } });
      setResults(Array.isArray(res.data?.content) ? res.data.content : []);
    } catch { setResults([]); }
  };

  const selectDelivery = async (d: any) => {
    setSelected(d);
    // Try to prefill items from the delivery detail.
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

  const updateItem = (i: number, patch: Partial<RmaItem>) => setItems(prev => prev.map((it, idx) => idx === i ? { ...it, ...patch } : it));
  const removeItem = (i: number) => setItems(prev => prev.filter((_, idx) => idx !== i));
  const addItem = () => setItems(prev => [...prev, { name: '', quantity: 1, condition: 'RESELLABLE' }]);

  const submit = async () => {
    if (!selected) { showErrorToast(null, 'Sélectionnez une livraison'); return; }
    const valid = items.filter(it => (it.name || it.sku) && it.quantity > 0);
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
    <AppModal opened={open} onClose={onClose} title="Nouveau retour (RMA)" size="lg">
      <div className="flex flex-col gap-4">
        {!selected ? (
          <>
            <div className="flex gap-2">
              <input value={search} onChange={e => setSearch(e.target.value)} onKeyDown={e => e.key === 'Enter' && doSearch()}
                     placeholder="Rechercher une livraison livrée (client, réf, BL)…"
                     className="flex-1 h-9 px-3 rounded-md border border-[var(--border)] bg-[var(--surface)] text-[13px]" />
              <Button size="sm" onClick={doSearch} className="h-9 px-4 text-[12px]">Rechercher</Button>
            </div>
            <div className="flex flex-col gap-1.5 max-h-[300px] overflow-y-auto">
              {results.map(d => (
                <button key={d.deliveryId} onClick={() => selectDelivery(d)}
                        className="flex items-center justify-between p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left">
                  <div className="min-w-0">
                    <p className="text-[12px] font-bold text-[var(--text-primary)] truncate">{d.clientName ?? d.orderRef}</p>
                    <p className="text-[11px] text-[var(--text-muted)] truncate">{d.blNumber ?? d.erpOrderId} · {d.dropoffCity}</p>
                  </div>
                </button>
              ))}
            </div>
          </>
        ) : (
          <>
            <div className="flex items-center justify-between p-3 rounded-lg bg-[var(--app-bg)] border border-[var(--border)]">
              <div>
                <p className="text-[13px] font-bold text-[var(--text-primary)]">{selected.clientName ?? selected.orderRef}</p>
                <p className="text-[11px] text-[var(--text-muted)]">{selected.blNumber ?? selected.erpOrderId}</p>
              </div>
              <button onClick={() => { setSelected(null); setItems([]); }} className="text-[11px] font-bold text-[var(--brand)]">Changer</button>
            </div>

            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between">
                <label className="text-[11px] font-bold text-[var(--text-secondary)]">Articles retournés</label>
                <button onClick={addItem} className="text-[11px] font-bold text-[var(--brand)] flex items-center gap-1"><IconPlus size={12} /> Ajouter</button>
              </div>
              {items.map((it, i) => (
                <div key={i} className="flex items-center gap-2">
                  <input value={it.name ?? ''} onChange={e => updateItem(i, { name: e.target.value })} placeholder="Article"
                         className="flex-1 h-8 px-2 rounded-md border border-[var(--border)] bg-[var(--surface)] text-[12px]" />
                  <input type="number" value={it.quantity} onChange={e => updateItem(i, { quantity: Number(e.target.value) || 0 })}
                         className="w-16 h-8 px-2 rounded-md border border-[var(--border)] bg-[var(--surface)] text-[12px]" />
                  <select value={it.condition} onChange={e => updateItem(i, { condition: e.target.value as RmaItem['condition'] })}
                          className="h-8 px-2 rounded-md border border-[var(--border)] bg-[var(--surface)] text-[12px]">
                    <option value="RESELLABLE">Revendable</option>
                    <option value="DAMAGED">Endommagé</option>
                  </select>
                  <button onClick={() => removeItem(i)} className="w-8 h-8 flex items-center justify-center rounded border border-[var(--border)] text-[#A52B24]"><IconTrash size={13} /></button>
                </div>
              ))}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-[11px] font-bold text-[var(--text-secondary)]">Motif du retour</label>
              <textarea value={reason} onChange={e => setReason(e.target.value)} rows={2}
                        className="px-3 py-2 rounded-md border border-[var(--border)] bg-[var(--surface)] text-[13px]" />
            </div>

            <div className="flex items-center justify-end gap-2 pt-2">
              <Button variant="outline" size="sm" onClick={onClose} className="h-8 px-3 text-[11px]">Annuler</Button>
              <Button size="sm" onClick={submit} disabled={submitting} className="h-8 px-4 text-[11px] font-bold">
                {submitting ? 'Création…' : 'Créer le retour'}
              </Button>
            </div>
          </>
        )}
      </div>
    </AppModal>
  );
}
