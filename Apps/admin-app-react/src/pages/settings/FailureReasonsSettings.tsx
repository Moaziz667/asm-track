import { useCallback, useEffect, useState, useMemo } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { useT } from '@/lib/LocaleContext';
import { IconPlus, IconPencil, IconBan, IconCheck } from '@tabler/icons-react';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings, ColumnDef } from '@/hooks/useColumnSettings';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { cn } from '@/lib/utils';

// Analytics categories the configurable reasons roll up to (mirrors backend FailureCode enum).
const CATEGORIES = ['CLIENT_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'DAMAGED', 'OTHER'] as const;
type Category = typeof CATEGORIES[number];

const CATEGORY_LABELS: Record<Category, string> = {
  CLIENT_ABSENT: 'Client absent',
  REFUSED: 'Refus',
  WRONG_ADDRESS: 'Adresse incorrecte',
  DAMAGED: 'Endommagé',
  OTHER: 'Autre',
};

interface FailureReason {
  id: string;
  code: string;
  label: string;
  category: Category;
  active: boolean;
  sortOrder: number;
}

interface FormState {
  id?: string;
  code: string;
  label: string;
  category: Category;
  sortOrder: number;
  active: boolean;
}

const EMPTY_FORM: FormState = { code: '', label: '', category: 'OTHER', sortOrder: 100, active: true };

const REASON_ROW_H = {
  compact: 'h-9',
  comfortable: 'h-12',
  spacious: 'h-15',
} as const;

export default function FailureReasonsSettings({ canManage }: { canManage: boolean }) {
  const t = useT();
  const REASON_COLUMNS = useMemo<ColumnDef[]>(() => [
    { id: 'label', label: t.failureReasonsSettings.tableLabel || "Motif", pinned: true },
    { id: 'code', label: t.failureReasonsSettings.tableCode || "Code" },
    { id: 'category', label: t.failureReasonsSettings.tableCategory || "Catégorie" },
    { id: 'status', label: t.failureReasonsSettings.tableStatus || "Statut" },
  ], [t]);

  const { density, setDensity } = useDensity('failure-reasons', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('failure-reasons', REASON_COLUMNS);
  const [reasons, setReasons] = useState<FailureReason[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);

  const fetchReasons = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/admin/failure-reasons');
      setReasons(Array.isArray(res.data) ? res.data : []);
    } catch {
      showErrorToast(null, t.failureReasonsSettings.toastLoadFailed);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void fetchReasons(); }, [fetchReasons]);

  const openCreate = () => { setForm(EMPTY_FORM); setModalOpen(true); };
  const openEdit = (r: FailureReason) => {
    setForm({ id: r.id, code: r.code, label: r.label, category: r.category, sortOrder: r.sortOrder, active: r.active });
    setModalOpen(true);
  };

  const submit = async () => {
    if (!form.label.trim()) { showErrorToast(null, t.failureReasonsSettings.toastLabelRequired); return; }
    setSubmitting(true);
    try {
      const payload = {
        code: form.code.trim() || undefined,
        label: form.label.trim(),
        category: form.category,
        sortOrder: form.sortOrder,
        active: form.active,
      };
      if (form.id) {
        await api.put(`/api/admin/failure-reasons/${form.id}`, payload);
        showSuccessToast(t.failureReasonsSettings.toastUpdated);
      } else {
        await api.post('/api/admin/failure-reasons', payload);
        showSuccessToast(t.failureReasonsSettings.toastCreated);
      }
      setModalOpen(false);
      await fetchReasons();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.failureReasonsSettings.toastSaveFailed);
    } finally {
      setSubmitting(false);
    }
  };

  const deactivate = async (r: FailureReason) => {
    try {
      await api.delete(`/api/admin/failure-reasons/${r.id}`);
      showSuccessToast(t.failureReasonsSettings.toastDeactivated);
      await fetchReasons();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.failureReasonsSettings.toastDeactivateFailed);
    }
  };

  const reactivate = async (r: FailureReason) => {
    try {
      await api.put(`/api/admin/failure-reasons/${r.id}`, {
        label: r.label, category: r.category, sortOrder: r.sortOrder, active: true,
      });
      showSuccessToast(t.failureReasonsSettings.toastReactivated);
      await fetchReasons();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.failureReasonsSettings.toastReactivateFailed);
    }
  };

  return (
    <div className="flex flex-col gap-4 animate-fade-in">
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-md font-bold text-[var(--text-primary)]">{t.failureReasonsSettings.title}</h2>
          <p className="text-xs text-[var(--text-muted)]">
            {t.failureReasonsSettings.subtitle}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <DisplaySettingsDropdown
            columns={orderedColumns}
            visibleIds={visibleIds}
            onToggle={toggleColumn}
            onReorder={moveColumn}
            onReset={resetColumns}
            density={density}
            onDensityChange={setDensity}
          />
          {canManage && (
            <Button size="sm" onClick={openCreate} className="gap-1.5">
              <IconPlus size={14} /> {t.failureReasonsSettings.addButton}
            </Button>
          )}
        </div>
      </div>

      <div className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
        <table className="w-full text-sm border-collapse">
          <thead>
            <tr className="text-2xs uppercase tracking-wider text-[var(--text-muted)] h-10 border-b border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
              {orderedColumns.map(col => {
                if (!visibleIds.has(col.id)) return null;
                return (
                  <th key={col.id} className="text-start font-bold px-4 align-middle">{col.label}</th>
                );
              })}
              <th className="px-4 align-middle" />
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border)]">
            {loading ? (
              <tr><td colSpan={visibleIds.size + 1} className="px-4 py-8 text-center text-[var(--text-muted)]">{t.failureReasonsSettings.loading}</td></tr>
            ) : reasons.length === 0 ? (
              <tr><td colSpan={visibleIds.size + 1} className="px-4 py-8 text-center text-[var(--text-muted)]">{t.failureReasonsSettings.empty}</td></tr>
            ) : reasons.map(r => (
              <tr key={r.id} className={cn("group hover:bg-[var(--hover-bg)] transition-all", REASON_ROW_H[density])}>
                {orderedColumns.map(col => {
                  if (!visibleIds.has(col.id)) return null;
                  if (col.id === 'label') return (
                    <td key="label" className="px-4 font-semibold text-[var(--text-primary)] align-middle">{r.label}</td>
                  );
                  if (col.id === 'code') return (
                    <td key="code" className="px-4 font-mono text-xs text-[var(--text-muted)] align-middle">{r.code}</td>
                  );
                  if (col.id === 'category') return (
                    <td key="category" className="px-4 align-middle">
                      <span className="text-2xs font-bold px-2 py-0.5 rounded-full" style={{ background: 'var(--hover-bg)', color: 'var(--text-secondary)' }}>
                        {CATEGORY_LABELS[r.category] ?? r.category}
                      </span>
                    </td>
                  );
                  if (col.id === 'status') return (
                    <td key="status" className="px-4 align-middle">
                      <span className={cn(
                        "text-2xs font-bold px-2 py-0.5 rounded-full inline-flex items-center gap-1",
                        r.active ? 'text-[#2D8A5E]' : 'text-[var(--text-muted)]'
                      )}
                            style={{ background: r.active ? 'rgba(76,175,130,0.10)' : 'var(--hover-bg)' }}>
                        {r.active ? <><IconCheck size={11} /> {t.failureReasonsSettings.active}</> : t.failureReasonsSettings.inactive}
                      </span>
                    </td>
                  );
                  return null;
                })}
                <td className="px-4 text-end align-middle">
                  {canManage && (
                    <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <button type="button" onClick={() => openEdit(r)}
                              className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]" title={t.failureReasonsSettings.editTooltip}>
                        <IconPencil size={13} />
                      </button>
                      {r.active ? (
                        <button type="button" onClick={() => deactivate(r)}
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[#A52B24] hover:bg-[var(--hover-bg)]" title={t.failureReasonsSettings.deactivateTooltip}>
                          <IconBan size={13} />
                        </button>
                      ) : (
                        <button type="button" onClick={() => reactivate(r)}
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[#2D8A5E] hover:bg-[var(--hover-bg)]" title={t.failureReasonsSettings.reactivateTooltip}>
                          <IconCheck size={13} />
                        </button>
                      )}
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <AppModal opened={modalOpen} onClose={() => setModalOpen(false)} title={form.id ? t.failureReasonsSettings.editTitle : t.failureReasonsSettings.createTitle} size="sm">
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-bold text-[var(--text-secondary)]">{t.failureReasonsSettings.formLabel}</label>
            <input value={form.label} onChange={e => setForm(f => ({ ...f, label: e.target.value }))}
                   className="h-9 px-3 rounded-md border border-[var(--border)] bg-[var(--surface)] text-base text-[var(--text-primary)]"
                   placeholder={t.failureReasonsSettings.formLabelPlaceholder} />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-bold text-[var(--text-secondary)]">{t.failureReasonsSettings.formCategory}</label>
            <select value={form.category} onChange={e => setForm(f => ({ ...f, category: e.target.value as Category }))}
                    className="h-9 px-3 rounded-md border border-[var(--border)] bg-[var(--surface)] text-base text-[var(--text-primary)]">
              {CATEGORIES.map(c => <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>)}
            </select>
          </div>
          {!form.id && (
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-bold text-[var(--text-secondary)]">{t.failureReasonsSettings.formCode}</label>
              <input value={form.code} onChange={e => setForm(f => ({ ...f, code: e.target.value }))}
                     className="h-9 px-3 rounded-md border border-[var(--border)] bg-[var(--surface)] text-base font-mono text-[var(--text-primary)]"
                     placeholder={t.failureReasonsSettings.formCodePlaceholder} />
            </div>
          )}
          <div className="flex items-center gap-4">
            <div className="flex flex-col gap-1.5 flex-1">
              <label className="text-xs font-bold text-[var(--text-secondary)]">{t.failureReasonsSettings.formOrder}</label>
              <input type="number" value={form.sortOrder} onChange={e => setForm(f => ({ ...f, sortOrder: Number(e.target.value) || 0 }))}
                     className="h-9 px-3 rounded-md border border-[var(--border)] bg-[var(--surface)] text-base text-[var(--text-primary)]" />
            </div>
            <label className="flex items-center gap-2 mt-5 cursor-pointer">
              <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                     className="w-4 h-4 accent-[var(--brand)]" />
              <span className="text-sm font-semibold text-[var(--text-secondary)]">{t.failureReasonsSettings.formActive}</span>
            </label>
          </div>
          <div className="flex items-center justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setModalOpen(false)} className="h-8 px-3 text-xs">{t.failureReasonsSettings.cancelButton}</Button>
            <Button size="sm" onClick={submit} disabled={submitting} className="px-4">
              {submitting ? t.failureReasonsSettings.savingButton : t.failureReasonsSettings.saveButton}
            </Button>
          </div>
        </div>
      </AppModal>
    </div>
  );
}
