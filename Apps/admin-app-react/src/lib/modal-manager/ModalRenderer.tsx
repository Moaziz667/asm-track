

import React, { useEffect, useRef, useCallback } from 'react';
import { useModalStore } from './useModal';
import { IconAlertTriangle as AlertTriangle, IconUserCheck as UserCheck, IconX as X } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';

// ── Rendu central de toutes les modales.
// Monter une seule fois dans app/layout.tsx.
//
// Gère :
// - Piège de focus (Tab / Shift+Tab reste à l'intérieur)
// - Retour du focus à l'élément déclencheur à la fermeture
// - Fermeture par Échap, clic sur backdrop
// - z-index garanti à 200 (au-dessus de tout)
//
export function ModalRenderer() {
  const t = useT();
  const { config, loading, reason, close, setLoading, setReason } = useModalStore();
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<Element | null>(null);

  // Capture le focus déclencheur à l'ouverture
  useEffect(() => {
    if (config) {
      triggerRef.current = document.activeElement;
    }
  }, [config]);

  // Piège de focus & fermeture par Échap
  useEffect(() => {
    if (!config) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        close();
        return;
      }
      if (e.key === 'Tab' && containerRef.current) {
        const focusable = containerRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (!first) return;
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    // Mise au point initiale
    setTimeout(() => {
      const first = containerRef.current?.querySelector<HTMLElement>('input, button:not([disabled])');
      first?.focus();
    }, 50);

    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [config, close]);

  // Retour du focus à la fermeture
  useEffect(() => {
    if (!config && triggerRef.current instanceof HTMLElement) {
      triggerRef.current.focus();
      triggerRef.current = null;
    }
  }, [config]);

  if (!config) return null;

  return (
    <div
      aria-modal="true"
      role="dialog"
      aria-label={config.type === 'CONFIRM' ? config.title : t.reassignCommandOverlay.modalTitle}
      onClick={close}
      className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 backdrop-blur-sm"
    >
      <div
        ref={containerRef}
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md mx-4 bg-white rounded-2xl shadow-2xl ring-1 ring-black/5 animate-in fade-in zoom-in-95 duration-150"
      >
        {config.type === 'CONFIRM' && <ConfirmContent config={config} loading={loading} reason={reason} setReason={setReason} setLoading={setLoading} close={close} />}
        {config.type === 'REASSIGN' && <ReassignContent config={config} loading={loading} setLoading={setLoading} close={close} />}
      </div>
    </div>
  );
}

// ── Contenu Confirm ────────────────────────────────────────────────────

function ConfirmContent({
  config, loading, reason, setReason, setLoading, close,
}: {
  config: import('./types').ConfirmModalConfig;
  loading: boolean;
  reason: string;
  setReason: (v: string) => void;
  setLoading: (v: boolean) => void;
  close: () => void;
}) {
  const isDanger = config.variant === 'danger';
  const t = useT();

  const handleConfirm = useCallback(async () => {
    setLoading(true);
    try {
      await config.onConfirm(reason || undefined);
    } finally {
      setLoading(false);
      close();
    }
  }, [config, reason, setLoading, close]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !loading) handleConfirm();
  }, [loading, handleConfirm]);

  return (
    <div className="p-6" onKeyDown={handleKeyDown}>
      {/* En-tête */}
      <div className="flex items-start gap-3 mb-4">
        {isDanger && (
          <div className="shrink-0 flex items-center justify-center w-9 h-9 rounded-xl bg-red-50 border border-red-100">
            <AlertTriangle size={16} className="text-red-600" />
          </div>
        )}
        <div className="flex-1 min-w-0">
          <h2 className={`text-[15px] font-bold leading-snug ${isDanger ? 'text-red-700' : 'text-slate-900'}`}>
            {config.title}
          </h2>
          {config.body && (
            <p className="mt-1 text-sm text-slate-500 leading-relaxed">{config.body}</p>
          )}
        </div>
        <button
          onClick={close}
          aria-label={t.actions.close}
          className="shrink-0 flex items-center justify-center w-7 h-7 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
        >
          <X size={14} />
        </button>
      </div>

      {/* Champ raison optionnel */}
      {config.reasonLabel && (
        <div className="mb-4">
          <label className="block text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-1.5">
            {config.reasonLabel}
          </label>
          <input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={config.reasonPlaceholder ?? t.placeholders.reason}
            className="w-full h-9 px-3 text-sm text-slate-800 bg-white border border-slate-200 rounded-lg outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400 transition-colors"
          />
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center justify-between gap-3 mt-5">
        <p className="text-[10px] text-slate-400 font-mono">{t.actions.cancel} · {t.actions.confirm}</p>
        <div className="flex gap-2">
          <button
            onClick={close}
            disabled={loading}
            className="h-8 px-4 text-sm font-semibold text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
          >
            {config.cancelLabel ?? t.actions.cancel}
          </button>
          <button
            onClick={handleConfirm}
            disabled={loading}
            className={`h-8 px-4 text-sm font-semibold text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
              isDanger
                ? 'bg-red-600 hover:bg-red-700'
                : 'bg-blue-600 hover:bg-blue-700'
            }`}
          >
            {loading ? '…' : (config.confirmLabel ?? t.actions.confirm)}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Contenu Reassign ───────────────────────────────────────────────────

function ReassignContent({
  config, loading, setLoading, close,
}: {
  config: import('./types').ReassignModalConfig;
  loading: boolean;
  setLoading: (v: boolean) => void;
  close: () => void;
}) {
  const available = config.drivers.filter((d) => d.id !== config.currentDriverId);
  const [selectedId, setSelectedId] = React.useState(available[0]?.id ?? '');
  const [search, setSearch] = React.useState('');

  const filtered = available.filter((d) =>
    !search || (d.name ?? d.id).toLowerCase().includes(search.toLowerCase())
  );

  const handleConfirm = useCallback(async () => {
    if (!selectedId) return;
    setLoading(true);
    try {
      await config.onConfirm(selectedId);
    } finally {
      setLoading(false);
      close();
    }
  }, [selectedId, config, setLoading, close]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && selectedId && !loading) { e.preventDefault(); handleConfirm(); }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [selectedId, loading, handleConfirm]);

  return (
    <div className="p-6">
      {/* En-tête */}
      <div className="flex items-start justify-between gap-2 mb-4">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <div className="flex items-center justify-center w-7 h-7 rounded-lg bg-blue-50 border border-blue-100">
              <UserCheck size={14} className="text-blue-600" />
            </div>
            <h2 className="text-[15px] font-bold text-slate-900">
              {t.reassignCommandOverlay.title} {(config.entityLabel ?? t.reassignDrawer.assignTitle).toLowerCase()}
            </h2>
          </div>
          {config.entityName && (
            <p className="text-sm text-slate-500">{config.entityName}</p>
          )}
        </div>
        <button
          onClick={close}
          aria-label={t.actions.close}
          className="shrink-0 flex items-center justify-center w-7 h-7 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
        >
          <X size={14} />
        </button>
      </div>

      {/* Recherche chauffeur */}
      <div className="relative mb-3">
        <svg className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t.reassignCommandOverlay.searchDriver}
          className="w-full h-9 pl-9 pr-3 text-sm text-slate-800 bg-slate-50 border border-slate-200 rounded-lg outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400 transition-colors"
        />
      </div>

      {/* Liste chauffeurs */}
      <div className="border border-slate-200 rounded-xl overflow-hidden mb-4 max-h-56 overflow-y-auto">
        {filtered.length === 0 ? (
          <div className="py-8 text-center text-sm text-slate-400">
            {t.reassignCommandOverlay.noResults}
          </div>
        ) : (
          filtered.map((driver, idx) => {
            const isSelected = driver.id === selectedId;
            return (
              <button
                key={driver.id}
                onClick={() => setSelectedId(driver.id)}
                className={`w-full text-left flex items-center gap-3 px-4 py-3 transition-colors ${
                  idx < filtered.length - 1 ? 'border-b border-slate-100' : ''
                } ${isSelected ? 'bg-blue-50 border-l-2 border-l-blue-500' : 'hover:bg-slate-50'}`}
              >
                {/* Avatar */}
                <div className={`shrink-0 flex items-center justify-center w-8 h-8 rounded-lg text-[10px] font-black ${
                  isSelected ? 'bg-blue-600 text-white' : 'bg-slate-200 text-slate-600'
                }`}>
                  {(driver.name ?? driver.id).substring(0, 2).toUpperCase()}
                </div>
                <div className="flex-1 min-w-0">
                  <div className={`text-sm font-semibold truncate ${isSelected ? 'text-blue-700' : 'text-slate-800'}`}>
                    {driver.name ?? driver.id}
                  </div>
                  {driver.phone && (
                    <div className="text-xs text-slate-400 truncate">{driver.phone}</div>
                  )}
                </div>
                {/* Badge disponibilité */}
                {driver.isAvailable !== undefined && (
                  <span className={`shrink-0 text-[10px] font-bold px-2 py-0.5 rounded-full ${
                    driver.isAvailable
                      ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                      : 'bg-amber-50 text-amber-700 border border-amber-200'
                  }`}>
                    {driver.isAvailable ? t.availability.driverAvailable : t.availability.driverBusy}
                  </span>
                )}
                {isSelected && (
                  <div className="shrink-0 w-4 h-4 rounded-full bg-blue-600 flex items-center justify-center">
                    <svg width="8" height="8" viewBox="0 0 12 12" fill="none"><path d="M2 6l3 3 5-5" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  </div>
                )}
              </button>
            );
          })
        )}
      </div>

      {/* Actions */}
      <div className="flex gap-2">
        <button
          onClick={close}
          disabled={loading}
          className="flex-1 h-9 text-sm font-semibold text-slate-700 bg-white border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors disabled:opacity-50"
        >
          {t.reassignCommandOverlay.cancelBtn}
        </button>
        <button
          onClick={handleConfirm}
          disabled={!selectedId || loading}
          className="flex-1 h-9 text-sm font-semibold text-white bg-blue-600 rounded-xl hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {loading ? '…' : t.reassignCommandOverlay.confirmBtn}
        </button>
      </div>
    </div>
  );
}

