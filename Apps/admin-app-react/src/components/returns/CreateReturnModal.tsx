import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { useT } from '@/lib/LocaleContext';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import {
  IconSearch, IconArrowRight, IconPackageExport,
  IconRecycle, IconTrashX, IconMinus, IconPlus,
} from '@tabler/icons-react';
import { ConditionPill, conditionColors, type Condition } from '@/components/data-display/ConditionPill';

interface DeliveryLite {
  deliveryId: string; clientName?: string; orderRef?: string;
  blNumber?: string; erpOrderId?: string; dropoffCity?: string;
}

/** A return line bound to a delivered item — qty can never exceed what was delivered. */
interface ReturnLine {
  include: boolean;
  sku?: string;
  name: string;
  maxQty: number;
  quantity: number;
  condition: Condition;
  reason?: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
  /** When set, skip the picker and scope the return straight to this delivery. */
  prefillDeliveryId?: string | null;
}

/**
 * Enterprise return-creation flow:
 *  - opened contextually from a delivery (prefillDeliveryId) → no search at all, or
 *  - standalone → a LIVE picker that shows recent delivered orders immediately and filters
 *    as you type (no manual "Search" button).
 * Items are a checklist of the delivery's ACTUAL delivered lines — you can only return what was
 * delivered, qty is bounded by the delivered quantity, and the condition spells out its effect
 * (Revendable → remis en stock · Endommagé → mis au rebut).
 */
export function CreateReturnModal({ open, onClose, onCreated, prefillDeliveryId }: Props) {
  const t = useT();
  const m = t.returnsPage?.createModal;
  const mx = m as Record<string, string> | undefined; // new keys via fallback, no copy-file churn

  const [selected, setSelected] = useState<DeliveryLite | null>(null);
  const [lines, setLines] = useState<ReturnLine[]>([]);
  const [loadingLines, setLoadingLines] = useState(false);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Picker state (standalone mode)
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<DeliveryLite[]>([]);
  const [searching, setSearching] = useState(false);
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null);

  const loadLines = useCallback(async (d: DeliveryLite) => {
    setSelected(d);
    setLoadingLines(true);
    try {
      const res = await api.get(`/api/admin/deliveries/${d.deliveryId}`);
      const data = res.data ?? {};
      // Enrich the header — in prefill mode we only had the id; fill client/BL from the detail.
      setSelected((prev) => ({
        deliveryId: d.deliveryId,
        clientName: d.clientName ?? data.clientName ?? prev?.clientName,
        blNumber: d.blNumber ?? data.blNumber ?? prev?.blNumber,
        erpOrderId: d.erpOrderId ?? data.erpOrderId ?? prev?.erpOrderId,
        orderRef: d.orderRef ?? prev?.orderRef,
        dropoffCity: d.dropoffCity ?? data.dropoffCity ?? prev?.dropoffCity,
      }));
      const items: any[] = data.items ?? [];
      // returnable = what was actually delivered (quantityDone), falling back to ordered qty
      setLines(items.map((it) => {
        const max = Math.max(it.quantityDone ?? it.quantity ?? 1, 1);
        return { include: true, sku: it.sku, name: it.name ?? it.sku ?? '—', maxQty: max, quantity: max, condition: 'RESELLABLE' as Condition };
      }));
    } catch {
      setLines([]);
    } finally {
      setLoadingLines(false);
    }
  }, []);

  // Reset whenever the modal opens. Prefill mode (from a delivery) scopes straight to that order;
  // standalone mode is search-first — the operator looks up the specific order, nothing is preloaded.
  useEffect(() => {
    if (!open) return;
    setSearch(''); setResults([]); setReason(''); setSelected(null); setLines([]);
    if (prefillDeliveryId) void loadLines({ deliveryId: prefillDeliveryId });
  }, [open, prefillDeliveryId, loadLines]);

  // Live, debounced search — only once the operator types. Returnable = DELIVERED or
  // PARTIALLY_DELIVERED, and the endpoint takes a single status, so we query both and merge.
  useEffect(() => {
    if (!open || prefillDeliveryId || selected) return;
    const term = search.trim();
    if (!term) { setResults([]); return; }
    if (debounce.current) clearTimeout(debounce.current);
    debounce.current = setTimeout(() => {
      setSearching(true);
      Promise.all([
        api.get('/api/admin/deliveries', { params: { status: 'DELIVERED', size: 6, q: term } }),
        api.get('/api/admin/deliveries', { params: { status: 'PARTIALLY_DELIVERED', size: 6, q: term } }),
      ])
        .then(([a, b]) => setResults([...(a.data?.content ?? []), ...(b.data?.content ?? [])].slice(0, 12)))
        .catch(() => setResults([]))
        .finally(() => setSearching(false));
    }, 280);
    return () => { if (debounce.current) clearTimeout(debounce.current); };
  }, [search, open, prefillDeliveryId, selected]);

  const patchLine = (i: number, patch: Partial<ReturnLine>) =>
    setLines((prev) => prev.map((l, idx) => (idx === i ? { ...l, ...patch } : l)));

  const chosen = lines.filter((l) => l.include && l.quantity > 0);
  const totalUnits = chosen.reduce((s, l) => s + l.quantity, 0);

  const submit = async () => {
    if (!selected) return;
    if (chosen.length === 0) { showErrorToast(null, m?.errNoItems ?? 'Sélectionnez au moins un article'); return; }
    setSubmitting(true);
    try {
      await api.post('/api/admin/returns', {
        deliveryId: selected.deliveryId,
        reason,
        items: chosen.map((l) => ({ sku: l.sku, name: l.name, quantity: l.quantity, condition: l.condition, reason: l.reason })),
      });
      showSuccessToast(m?.created ?? 'Retour créé');
      onCreated();
    } catch (err) {
      showErrorToast(err, m?.createError ?? 'Échec de la création');
    } finally {
      setSubmitting(false);
    }
  };

  const showPicker = !selected && !prefillDeliveryId;

  return (
    <AppModal
      opened={open}
      onClose={onClose}
      title={m?.title ?? 'Nouveau retour (RMA)'}
      subtitle={m?.subtitle ?? 'À créer sur une livraison déjà livrée.'}
      size="lg"
    >
      <div className="flex flex-col gap-4">
        {showPicker ? (
          /* ── Step 1 · live delivery picker (no Search button) ── */
          <>
            <div className="relative">
              <IconSearch size={15} className="absolute start-3 top-1/2 -translate-y-1/2 text-[var(--text-soft)]" />
              <Input
                autoFocus
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={m?.searchPlaceholder ?? 'Rechercher une livraison livrée (client, réf, BL)…'}
                className="ps-9"
              />
            </div>

            {!search.trim() ? (
              <p className="py-10 text-center text-sm text-[var(--text-soft)]">
                {mx?.searchPrompt ?? 'Recherchez la livraison concernée par le retour (client, BL, réf ERP)…'}
              </p>
            ) : (
              <div className="flex max-h-[340px] flex-col gap-1.5 overflow-y-auto">
                {searching ? (
                  Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-[58px] w-full rounded-lg" />)
                ) : results.length === 0 ? (
                  <p className="py-8 text-center text-sm text-[var(--text-soft)]">{m?.noResults ?? 'Aucune livraison livrée trouvée.'}</p>
                ) : results.map((d) => (
                  <button
                    key={d.deliveryId}
                    onClick={() => void loadLines(d)}
                    className="flex items-center justify-between rounded-lg border border-[var(--border)] p-3 text-left transition-colors hover:bg-[var(--hover-bg)] hover:border-[var(--border-strong)]"
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-bold text-[var(--text-primary)]">{d.clientName ?? d.orderRef ?? '—'}</p>
                      <p className="truncate font-mono text-xs text-[var(--text-muted)]">{d.blNumber ?? d.erpOrderId ?? '—'}{d.dropoffCity ? ` · ${d.dropoffCity}` : ''}</p>
                    </div>
                    <IconArrowRight size={15} className="shrink-0 text-[var(--text-soft)]" />
                  </button>
                ))}
              </div>
            )}
          </>
        ) : (
          /* ── Step 2 · return lines (checklist of delivered items) ── */
          <>
            {/* Selected delivery header */}
            <div className="flex items-center justify-between rounded-lg border border-[var(--border)] bg-[var(--app-bg)] p-3">
              <div className="min-w-0">
                <p className="truncate text-base font-bold text-[var(--text-primary)]">{selected?.clientName ?? selected?.orderRef ?? (m?.title ?? 'Retour')}</p>
                <p className="truncate font-mono text-xs text-[var(--text-muted)]">{selected?.blNumber ?? selected?.erpOrderId ?? selected?.deliveryId}</p>
              </div>
              {!prefillDeliveryId && (
                <Button variant="ghost" size="sm" onClick={() => { setSelected(null); setLines([]); }}>{m?.changeBtn ?? 'Changer'}</Button>
              )}
            </div>

            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-[var(--text-secondary)]">{m?.itemsLabel ?? 'Articles retournés'}</label>
              <span className="text-2xs text-[var(--text-soft)]">{mx?.onlyDelivered ?? 'Seuls les articles livrés sont retournables.'}</span>
            </div>

            <div className="flex max-h-[300px] flex-col gap-1.5 overflow-y-auto">
              {loadingLines ? (
                Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-12 w-full rounded-lg" />)
              ) : lines.length === 0 ? (
                <p className="py-6 text-center text-sm text-[var(--text-soft)]">{mx?.noLines ?? 'Aucun article livré sur cette livraison.'}</p>
              ) : lines.map((l, i) => (
                <div
                  key={i}
                  className={`flex items-center gap-3 rounded-lg border p-2.5 transition-colors ${l.include ? 'border-[var(--border-strong)] bg-[var(--surface)]' : 'border-[var(--border)] bg-[var(--app-bg)] opacity-60'}`}
                >
                  <input
                    type="checkbox"
                    checked={l.include}
                    onChange={(e) => patchLine(i, { include: e.target.checked })}
                    className="size-4 shrink-0 accent-[var(--brand)]"
                  />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-[var(--text-primary)]">{l.name}</p>
                    <p className="font-mono text-2xs text-[var(--text-soft)]">{l.sku ?? '—'} · {mx?.deliveredQty ?? 'livré'} ×{l.maxQty}</p>
                  </div>

                  {/* qty stepper bounded by delivered qty */}
                  <div className="flex items-center gap-1">
                    <Button variant="outline" size="icon" className="size-7" disabled={!l.include || l.quantity <= 1}
                      onClick={() => patchLine(i, { quantity: Math.max(1, l.quantity - 1) })}><IconMinus size={13} /></Button>
                    <span className="w-7 text-center text-sm font-bold tabular-nums">{l.quantity}</span>
                    <Button variant="outline" size="icon" className="size-7" disabled={!l.include || l.quantity >= l.maxQty}
                      onClick={() => patchLine(i, { quantity: Math.min(l.maxQty, l.quantity + 1) })}><IconPlus size={13} /></Button>
                  </div>

                  {/* condition toggle with explicit consequence */}
                  <div className="flex overflow-hidden rounded-md border border-[var(--border)]">
                    {(['RESELLABLE', 'DAMAGED'] as Condition[]).map((c) => {
                      const active = l.condition === c;
                      const isDamaged = c === 'DAMAGED';
                      const cc = conditionColors(c);
                      return (
                        <button
                          key={c}
                          disabled={!l.include}
                          onClick={() => patchLine(i, { condition: c })}
                          title={isDamaged ? (mx?.hintDamaged ?? 'Mis au rebut, non restocké') : (mx?.hintResellable ?? 'Remis en stock')}
                          className="flex items-center gap-1 px-2 py-1.5 text-2xs font-bold transition-colors"
                          style={{
                            background: active ? cc.bg : 'transparent',
                            color: active ? cc.text : 'var(--text-soft)',
                          }}
                        >
                          {isDamaged ? <IconTrashX size={12} /> : <IconRecycle size={12} />}
                          {isDamaged ? (m?.conditionDamaged ?? 'Endommagé') : (m?.conditionResellable ?? 'Revendable')}
                        </button>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>

            {/* condition legend */}
            <div className="flex items-center gap-2 text-2xs text-[var(--text-soft)]">
              <ConditionPill condition="RESELLABLE" label={mx?.hintResellable ?? 'Revendable → remis en stock'} />
              <span>·</span>
              <ConditionPill condition="DAMAGED" label={mx?.hintDamaged ?? 'Endommagé → mis au rebut'} />
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-bold text-[var(--text-secondary)]">{m?.reasonLabel ?? 'Motif du retour'}</label>
              <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} placeholder={m?.reasonPlaceholder ?? 'Ex. produit défectueux, erreur de commande…'} />
            </div>

            <div className="flex items-center justify-between border-t border-[var(--border)] pt-3">
              <span className="text-xs text-[var(--text-muted)]">
                <b className="tabular-nums text-[var(--text-primary)]">{totalUnits}</b> {mx?.unitsWord ?? 'unité(s)'} · {chosen.length} {mx?.linesWord ?? 'ligne(s)'}
              </span>
              <div className="flex items-center gap-2">
                <Button variant="outline" onClick={onClose}>{t.actions?.cancel ?? 'Annuler'}</Button>
                <Button onClick={() => void submit()} disabled={submitting || chosen.length === 0} className="gap-1.5">
                  {submitting ? (m?.creatingBtn ?? 'Création…') : (<><IconPackageExport size={15} /> {m?.createBtn ?? 'Créer le retour'}</>)}
                </Button>
              </div>
            </div>
          </>
        )}
      </div>
    </AppModal>
  );
}
