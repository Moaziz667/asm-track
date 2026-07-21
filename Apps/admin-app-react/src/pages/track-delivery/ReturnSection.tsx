import { useCallback, useEffect, useRef, useState } from 'react';
import { useT } from '@/lib/i18n/LocaleContext';

// Client-facing self-service return, rendered on the public tracking page. Plain inline styles (no design
// tokens) to match TrackDeliveryPage — this surface is outside the admin design system.

type Condition = 'RESELLABLE' | 'DAMAGED';

interface ReturnableItem {
  sku: string;
  name?: string;
  deliveredQty?: number;
  returnableQty: number;
  unitPrice?: number;
}

interface ReturnableResponse {
  deliveryStatus: string;
  returnable: boolean;
  hasOpenReturn: boolean;
  openReturnId?: string;
  openReturnStatus?: string;
  items: ReturnableItem[];
}

interface Props {
  deliveryId: string;
  returnStatus?: string;
  returnResolutionNote?: string;
  onChanged?: () => void;
}

const MAX_PHOTOS = 5;

export default function ReturnSection({ deliveryId, returnStatus, returnResolutionNote, onChanged }: Props) {
  const t = useT();
  const tp = t.trackingPage as Record<string, unknown>;
  const tr = (k: string, fallback: string) => (tp[k] as string) ?? fallback;

  const [panel, setPanel] = useState<ReturnableResponse | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [qty, setQty] = useState<Record<string, number>>({});
  const [condition, setCondition] = useState<Condition>('RESELLABLE');
  const [reason, setReason] = useState('');
  const [photoUrls, setPhotoUrls] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const loadPanel = useCallback(async () => {
    try {
      const res = await fetch(`/api/v1/public/track/${deliveryId}/return`);
      if (!res.ok) { setPanel(null); return; }
      setPanel(await res.json());
    } catch { setPanel(null); }
  }, [deliveryId]);

  useEffect(() => { void loadPanel(); }, [loadPanel, returnStatus]);

  const toggleItem = (sku: string, max: number) =>
    setQty(prev => {
      const next = { ...prev };
      if (next[sku]) delete next[sku];
      else next[sku] = max;
      return next;
    });

  const setItemQty = (sku: string, value: number, max: number) =>
    setQty(prev => ({ ...prev, [sku]: Math.max(1, Math.min(max, value || 1)) }));

  const onPickPhotos = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const room = MAX_PHOTOS - photoUrls.length;
    if (room <= 0) return;
    const form = new FormData();
    Array.from(files).slice(0, room).forEach(f => form.append('files', f));
    setUploading(true);
    setErrorMsg(null);
    try {
      const res = await fetch(`/api/v1/public/track/${deliveryId}/return/photos`, { method: 'POST', body: form });
      if (!res.ok) { setErrorMsg(tr('returnPhotoError', 'Échec du téléversement de la photo.')); return; }
      const urls: string[] = await res.json();
      setPhotoUrls(prev => [...prev, ...urls].slice(0, MAX_PHOTOS));
    } catch { setErrorMsg(tr('returnPhotoError', 'Échec du téléversement de la photo.')); }
    finally { setUploading(false); if (fileRef.current) fileRef.current.value = ''; }
  };

  const submit = async () => {
    const selected = panel?.items.filter(i => qty[i.sku]) ?? [];
    if (selected.length === 0) { setErrorMsg(tr('returnPickItem', 'Sélectionnez au moins un article.')); return; }
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const body = {
        items: selected.map(i => ({
          sku: i.sku, name: i.name, quantity: qty[i.sku], unitPrice: i.unitPrice, condition, reason: reason.trim() || undefined,
        })),
        photoUrls,
      };
      const res = await fetch(`/api/v1/public/track/${deliveryId}/return`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      if (res.status === 429) { setErrorMsg(tr('rateLimitExceeded', 'Trop de demandes. Réessayez demain.')); return; }
      if (!res.ok) { setErrorMsg(tr('returnSubmitError', 'Échec de la demande de retour.')); return; }
      // Reset + refresh both this panel and the parent tracking banner.
      setFormOpen(false); setQty({}); setReason(''); setPhotoUrls([]); setCondition('RESELLABLE');
      await loadPanel();
      onChanged?.();
    } catch { setErrorMsg(tr('returnSubmitError', 'Échec de la demande de retour.')); }
    finally { setSubmitting(false); }
  };

  const cancel = async () => {
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const res = await fetch(`/api/v1/public/track/${deliveryId}/return/cancel`, { method: 'POST' });
      if (res.status === 429) { setErrorMsg(tr('rateLimitExceeded', 'Trop de demandes. Réessayez demain.')); return; }
      if (!res.ok) { setErrorMsg(tr('returnCancelError', 'Échec de l’annulation.')); return; }
      await loadPanel();
      onChanged?.();
    } catch { setErrorMsg(tr('returnCancelError', 'Échec de l’annulation.')); }
    finally { setSubmitting(false); }
  };

  if (!panel) return null;

  const card: React.CSSProperties = {
    background: '#fff', border: '1px solid #e2e8f0', borderRadius: 14, padding: 16, marginTop: 12,
  };
  const title: React.CSSProperties = {
    fontSize: 10, fontWeight: 700, color: '#b45309', textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 8,
  };
  const primaryBtn: React.CSSProperties = {
    width: '100%', height: 44, borderRadius: 10, border: 'none', background: '#0f172a', color: '#fff',
    fontSize: 14, fontWeight: 700, cursor: 'pointer',
  };
  const ghostBtn: React.CSSProperties = {
    height: 40, padding: '0 14px', borderRadius: 10, border: '1px solid #e2e8f0', background: '#fff',
    color: '#475569', fontSize: 13, fontWeight: 600, cursor: 'pointer',
  };

  // ── Open return: show status (+ cancel when still cancellable) ──────────────
  if (panel.hasOpenReturn) {
    const status = panel.openReturnStatus ?? returnStatus ?? 'REQUESTED';
    const label = (t.trackingPage.returnStatusLabels as Record<string, string>)?.[status] ?? status;
    return (
      <div style={card}>
        <div style={title}>{tr('returnSectionTitle', 'Retour')}</div>
        <div style={{ fontSize: 14, fontWeight: 700, color: '#0f172a' }}>{label}</div>
        {status === 'REJECTED' && returnResolutionNote && (
          <div style={{ marginTop: 6, fontSize: 12, color: '#b91c1c' }}>{returnResolutionNote}</div>
        )}
        {status === 'REQUESTED' && (
          <button style={{ ...ghostBtn, marginTop: 12 }} onClick={cancel} disabled={submitting}>
            {submitting ? '…' : tr('returnCancelBtn', 'Annuler la demande')}
          </button>
        )}
        {errorMsg && <div style={{ marginTop: 8, fontSize: 12, color: '#b91c1c' }}>{errorMsg}</div>}
      </div>
    );
  }

  // ── No open return: offer the form when the delivery is returnable ───────────
  if (!panel.returnable || panel.items.length === 0) return null;

  if (!formOpen) {
    return (
      <div style={card}>
        <div style={title}>{tr('returnSectionTitle', 'Retour')}</div>
        <button style={primaryBtn} onClick={() => setFormOpen(true)}>
          {tr('requestReturnBtn', 'Demander un retour')}
        </button>
      </div>
    );
  }

  const canReach = photoUrls.length < MAX_PHOTOS;

  return (
    <div style={card}>
      <div style={title}>{tr('returnFormTitle', 'Formulaire de retour')}</div>

      {/* Items */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {panel.items.map(it => {
          const active = !!qty[it.sku];
          return (
            <div key={it.sku} style={{
              border: `1px solid ${active ? '#0f172a' : '#e2e8f0'}`, borderRadius: 10, padding: 10,
              display: 'flex', alignItems: 'center', gap: 10,
            }}>
              <input type="checkbox" checked={active} onChange={() => toggleItem(it.sku, it.returnableQty)} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {it.name || it.sku}
                </div>
                <div style={{ fontSize: 11, color: '#94a3b8' }}>
                  {tr('returnMax', 'Max')}: {it.returnableQty}
                </div>
              </div>
              {active && (
                <input
                  type="number" min={1} max={it.returnableQty} value={qty[it.sku]}
                  onChange={e => setItemQty(it.sku, parseInt(e.target.value, 10), it.returnableQty)}
                  style={{ width: 56, height: 34, borderRadius: 8, border: '1px solid #e2e8f0', textAlign: 'center', fontSize: 13 }}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Condition */}
      <div style={{ marginTop: 14, fontSize: 12, fontWeight: 600, color: '#475569' }}>{tr('returnFormCondition', 'État de l’article')}</div>
      <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
        {(['RESELLABLE', 'DAMAGED'] as Condition[]).map(c => (
          <button key={c} onClick={() => setCondition(c)} style={{
            flex: 1, height: 38, borderRadius: 10, cursor: 'pointer', fontSize: 13, fontWeight: 600,
            border: `1px solid ${condition === c ? '#0f172a' : '#e2e8f0'}`,
            background: condition === c ? '#0f172a' : '#fff', color: condition === c ? '#fff' : '#475569',
          }}>
            {c === 'RESELLABLE' ? tr('conditionResellable', 'Revendable') : tr('conditionDamaged', 'Endommagé')}
          </button>
        ))}
      </div>

      {/* Reason */}
      <div style={{ marginTop: 14, fontSize: 12, fontWeight: 600, color: '#475569' }}>{tr('returnFormReason', 'Raison du retour')}</div>
      <textarea
        value={reason} onChange={e => setReason(e.target.value)} rows={3} maxLength={500}
        style={{ width: '100%', marginTop: 6, borderRadius: 10, border: '1px solid #e2e8f0', padding: 10, fontSize: 13, resize: 'none', boxSizing: 'border-box' }}
      />

      {/* Photos */}
      <div style={{ marginTop: 14, fontSize: 12, fontWeight: 600, color: '#475569' }}>{tr('returnFormPhotos', 'Photos (optionnel)')}</div>
      <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap', alignItems: 'center' }}>
        {photoUrls.map((u, i) => (
          <img key={i} src={u} alt="" style={{ width: 48, height: 48, borderRadius: 8, objectFit: 'cover', border: '1px solid #e2e8f0' }} />
        ))}
        {canReach && (
          <button style={{ ...ghostBtn, height: 48, width: 48, padding: 0, fontSize: 22 }}
                  onClick={() => fileRef.current?.click()} disabled={uploading}>
            {uploading ? '…' : '+'}
          </button>
        )}
        <input ref={fileRef} type="file" accept="image/jpeg,image/png" multiple hidden
               onChange={e => onPickPhotos(e.target.files)} />
      </div>

      {errorMsg && <div style={{ marginTop: 10, fontSize: 12, color: '#b91c1c' }}>{errorMsg}</div>}

      {/* Actions */}
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button style={{ ...ghostBtn, flex: 1 }} onClick={() => setFormOpen(false)} disabled={submitting}>
          {tr('returnFormCancel', 'Annuler')}
        </button>
        <button style={{ ...primaryBtn, flex: 2, width: 'auto' }} onClick={submit} disabled={submitting || uploading}>
          {submitting ? '…' : tr('returnFormSubmit', 'Envoyer la demande')}
        </button>
      </div>
    </div>
  );
}
