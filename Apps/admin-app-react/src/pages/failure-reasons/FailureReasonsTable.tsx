import { useCallback, useEffect, useState, useMemo } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { useT } from '@/lib/LocaleContext';
import { IconPlus, IconPencil, IconBan, IconCheck, IconSearch, IconChevronUp, IconChevronDown, IconSelector } from '@tabler/icons-react';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings, ColumnDef } from '@/hooks/useColumnSettings';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { cn } from '@/lib/utils';

// Analytics categories (mirror backend FailureCode enum). Labels come from i18n (t.failureCodes).
const CATEGORIES = ['CLIENT_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'DAMAGED', 'MISSING', 'OTHER'] as const;
type Category = typeof CATEGORIES[number];

// Applicability contexts (mirror backend FailureContext enum). Labels from i18n (t.failureContexts).
const CONTEXTS = ['FAILURE', 'ITEM_REFUSED', 'ITEM_DAMAGED', 'ITEM_MISSING'] as const;
type Context = typeof CONTEXTS[number];

interface FailureReason {
  id: string;
  code: string;
  label: string;
  category: Category;
  appliesTo: Context[];
  active: boolean;
  sortOrder: number;
}

interface FormState {
  id?: string;
  label: string;
  category: Category;
  appliesTo: Context[];
  sortOrder: number;
  active: boolean;
}

// Code is auto-generated server-side from the label (and deduped) — not an editable field.
const EMPTY_FORM: FormState = { label: '', category: 'OTHER', appliesTo: ['FAILURE'], sortOrder: 100, active: true };

const REASON_ROW_H = {
  compact: 'h-9',
  comfortable: 'h-12',
  spacious: 'h-15',
} as const;

type SortKey = 'label' | 'code' | 'category' | 'order' | 'status';
type StatusFilter = 'all' | 'active' | 'inactive';

export default function FailureReasonsTable({ canManage }: { canManage: boolean }) {
  const t = useT();
  const catLabel = (c: string) => (t.failureCodes as Record<string, string>)[c] ?? c;
  const ctxLabel = (c: string) => (t.failureContexts as Record<string, string>)?.[c] ?? c;

  const REASON_COLUMNS = useMemo<ColumnDef[]>(() => [
    { id: 'label', label: t.failureReasonsSettings.tableLabel || 'Motif', pinned: true },
    { id: 'code', label: t.failureReasonsSettings.tableCode || 'Code' },
    { id: 'category', label: t.failureReasonsSettings.tableCategory || 'Catégorie' },
    { id: 'appliesTo', label: t.failureReasonsSettings.tableAppliesTo || "S'applique à" },
    { id: 'order', label: t.failureReasonsSettings.formOrder || 'Ordre' },
    { id: 'status', label: t.failureReasonsSettings.tableStatus || 'Statut' },
  ], [t]);

  const { density, setDensity } = useDensity('failure-reasons', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('failure-reasons', REASON_COLUMNS);
  const [reasons, setReasons] = useState<FailureReason[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);

  // Toolbar state
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<'all' | Category>('all');
  const [contextFilter, setContextFilter] = useState<'all' | Context>('all');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sort, setSort] = useState<{ key: SortKey; dir: 'asc' | 'desc' }>({ key: 'order', dir: 'asc' });

  const fetchReasons = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/admin/failure-reasons');
      const rows: FailureReason[] = (Array.isArray(res.data) ? res.data : []).map((r: any) => ({
        ...r,
        appliesTo: Array.isArray(r.appliesTo) ? r.appliesTo : (r.appliesTo ? [...r.appliesTo] : []),
      }));
      setReasons(rows);
    } catch {
      showErrorToast(null, t.failureReasonsSettings.toastLoadFailed);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void fetchReasons(); }, [fetchReasons]);

  const openCreate = () => { setForm(EMPTY_FORM); setModalOpen(true); };
  const openEdit = (r: FailureReason) => {
    setForm({ id: r.id, label: r.label, category: r.category, appliesTo: [...r.appliesTo], sortOrder: r.sortOrder, active: r.active });
    setModalOpen(true);
  };

  const toggleFormContext = (ctx: Context) => setForm(f => ({
    ...f,
    appliesTo: f.appliesTo.includes(ctx) ? f.appliesTo.filter(c => c !== ctx) : [...f.appliesTo, ctx],
  }));

  const submit = async () => {
    if (!form.label.trim()) { showErrorToast(null, t.failureReasonsSettings.toastLabelRequired); return; }
    if (form.appliesTo.length === 0) { showErrorToast(null, t.failureReasonsSettings.formAppliesToRequired); return; }
    setSubmitting(true);
    try {
      const payload = {
        label: form.label.trim(),
        category: form.category,
        appliesTo: form.appliesTo,
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
        label: r.label, category: r.category, appliesTo: r.appliesTo, sortOrder: r.sortOrder, active: true,
      });
      showSuccessToast(t.failureReasonsSettings.toastReactivated);
      await fetchReasons();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.failureReasonsSettings.toastReactivateFailed);
    }
  };

  // Inline reorder: swap sortOrder with the adjacent motif in the global sortOrder ordering.
  const move = async (r: FailureReason, dir: -1 | 1) => {
    const ordered = [...reasons].sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label));
    const idx = ordered.findIndex(x => x.id === r.id);
    const neighbour = ordered[idx + dir];
    if (!neighbour) return;
    try {
      await Promise.all([
        api.put(`/api/admin/failure-reasons/${r.id}`, { label: r.label, category: r.category, appliesTo: r.appliesTo, active: r.active, sortOrder: neighbour.sortOrder }),
        api.put(`/api/admin/failure-reasons/${neighbour.id}`, { label: neighbour.label, category: neighbour.category, appliesTo: neighbour.appliesTo, active: neighbour.active, sortOrder: r.sortOrder }),
      ]);
      await fetchReasons();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.failureReasonsSettings.toastReorderFailed);
    }
  };

  const canMoveUp = useCallback((r: FailureReason) => {
    const ordered = [...reasons].sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label));
    return ordered.findIndex(x => x.id === r.id) > 0;
  }, [reasons]);
  const canMoveDown = useCallback((r: FailureReason) => {
    const ordered = [...reasons].sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label));
    const idx = ordered.findIndex(x => x.id === r.id);
    return idx >= 0 && idx < ordered.length - 1;
  }, [reasons]);

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    const filtered = reasons.filter(r => {
      if (q && !(r.label.toLowerCase().includes(q) || r.code.toLowerCase().includes(q))) return false;
      if (categoryFilter !== 'all' && r.category !== categoryFilter) return false;
      if (contextFilter !== 'all' && !r.appliesTo.includes(contextFilter)) return false;
      if (statusFilter === 'active' && !r.active) return false;
      if (statusFilter === 'inactive' && r.active) return false;
      return true;
    });
    const dir = sort.dir === 'asc' ? 1 : -1;
    return filtered.sort((a, b) => {
      switch (sort.key) {
        case 'label': return dir * a.label.localeCompare(b.label);
        case 'code': return dir * a.code.localeCompare(b.code);
        case 'category': return dir * catLabel(a.category).localeCompare(catLabel(b.category));
        case 'status': return dir * (Number(a.active) - Number(b.active));
        case 'order':
        default: return dir * ((a.sortOrder - b.sortOrder) || a.label.localeCompare(b.label));
      }
    });
  }, [reasons, search, categoryFilter, contextFilter, statusFilter, sort, t]);

  const toggleSort = (key: SortKey) => setSort(s => s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' });
  const sortIcon = (key: SortKey) => sort.key !== key
    ? <IconSelector size={12} className="opacity-40" />
    : (sort.dir === 'asc' ? <IconChevronUp size={12} /> : <IconChevronDown size={12} />);
  const SORTABLE: Record<string, SortKey> = { label: 'label', code: 'code', category: 'category', order: 'order', status: 'status' };

  return (
    <div className="flex flex-col gap-4 animate-fade-in">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-md font-bold text-[var(--text-primary)]">{t.failureReasonsSettings.title}</h2>
          <p className="text-xs text-[var(--text-muted)]">{t.failureReasonsSettings.subtitle}</p>
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

      {/* Toolbar: search + filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="relative flex-1 min-w-[200px]">
          <IconSearch size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder={t.failureReasonsSettings.searchPlaceholder}
            className="w-full h-9 ps-9 pe-3 text-sm rounded-md bg-[var(--surface)] border border-[var(--border)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:border-[var(--brand)]"
          />
        </div>
        <FieldSelect
          value={categoryFilter}
          onChange={e => setCategoryFilter(e.target.value as 'all' | Category)}
          options={[{ value: 'all', label: t.failureReasonsSettings.filterAllCategories }, ...CATEGORIES.map(c => ({ value: c, label: catLabel(c) }))]}
        />
        <FieldSelect
          value={contextFilter}
          onChange={e => setContextFilter(e.target.value as 'all' | Context)}
          options={[{ value: 'all', label: t.failureReasonsSettings.filterAllContexts }, ...CONTEXTS.map(c => ({ value: c, label: ctxLabel(c) }))]}
        />
        <FieldSelect
          value={statusFilter}
          onChange={e => setStatusFilter(e.target.value as StatusFilter)}
          options={[
            { value: 'all', label: t.failureReasonsSettings.filterAllStatuses },
            { value: 'active', label: t.failureReasonsSettings.active },
            { value: 'inactive', label: t.failureReasonsSettings.inactive },
          ]}
        />
      </div>

      <div className="rounded-lg overflow-x-auto" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
        <table className="w-full text-sm border-collapse min-w-[720px]">
          <thead>
            <tr className="text-2xs uppercase tracking-wider text-[var(--text-muted)] h-10 border-b border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
              {orderedColumns.map(col => {
                if (!visibleIds.has(col.id)) return null;
                const sk = SORTABLE[col.id];
                return (
                  <th key={col.id} className="text-start font-bold px-4 align-middle">
                    {sk ? (
                      <button type="button" onClick={() => toggleSort(sk)} className="inline-flex items-center gap-1 hover:text-[var(--text-primary)]">
                        {col.label} {sortIcon(sk)}
                      </button>
                    ) : col.label}
                  </th>
                );
              })}
              <th className="px-4 align-middle" />
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border)]">
            {loading ? (
              <tr><td colSpan={visibleIds.size + 1} className="px-4 py-8 text-center text-[var(--text-muted)]">{t.failureReasonsSettings.loading}</td></tr>
            ) : visible.length === 0 ? (
              <tr><td colSpan={visibleIds.size + 1} className="px-4 py-8 text-center text-[var(--text-muted)]">{t.failureReasonsSettings.empty}</td></tr>
            ) : visible.map(r => (
              <tr key={r.id} className={cn('group hover:bg-[var(--hover-bg)] transition-all', REASON_ROW_H[density])}>
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
                      <StatusBadge status={r.category} label={catLabel(r.category)} size="sm" />
                    </td>
                  );
                  if (col.id === 'appliesTo') return (
                    <td key="appliesTo" className="px-4 align-middle">
                      <div className="flex flex-wrap gap-1">
                        {r.appliesTo.map(c => (
                          <StatusBadge key={c} status={c} label={ctxLabel(c)} size="sm" />
                        ))}
                      </div>
                    </td>
                  );
                  if (col.id === 'order') return (
                    <td key="order" className="px-4 align-middle">
                      <div className="flex items-center gap-1.5">
                        <span className="font-mono text-xs text-[var(--text-muted)] w-6">{r.sortOrder}</span>
                        {canManage && (
                          <span className="flex items-center opacity-0 group-hover:opacity-100 transition-opacity">
                            <button type="button" disabled={!canMoveUp(r)} onClick={() => move(r, -1)}
                                    className="w-6 h-6 flex items-center justify-center rounded text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-30 disabled:cursor-not-allowed" title={t.failureReasonsSettings.moveUp}>
                              <IconChevronUp size={14} />
                            </button>
                            <button type="button" disabled={!canMoveDown(r)} onClick={() => move(r, 1)}
                                    className="w-6 h-6 flex items-center justify-center rounded text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-30 disabled:cursor-not-allowed" title={t.failureReasonsSettings.moveDown}>
                              <IconChevronDown size={14} />
                            </button>
                          </span>
                        )}
                      </div>
                    </td>
                  );
                  if (col.id === 'status') return (
                    <td key="status" className="px-4 align-middle">
                      <StatusBadge
                        status={r.active ? 'ACTIVE' : 'INACTIVE'}
                        label={r.active ? t.failureReasonsSettings.active : t.failureReasonsSettings.inactive}
                        size="sm"
                      />
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
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--danger)] hover:bg-[var(--hover-bg)]" title={t.failureReasonsSettings.deactivateTooltip}>
                          <IconBan size={13} />
                        </button>
                      ) : (
                        <button type="button" onClick={() => reactivate(r)}
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--success)] hover:bg-[var(--hover-bg)]" title={t.failureReasonsSettings.reactivateTooltip}>
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
          <FieldInput
            label={t.failureReasonsSettings.formLabel}
            value={form.label}
            onChange={e => setForm(f => ({ ...f, label: e.target.value }))}
            placeholder={t.failureReasonsSettings.formLabelPlaceholder}
          />
          <FieldSelect
            label={t.failureReasonsSettings.formCategory}
            value={form.category}
            onChange={e => setForm(f => ({ ...f, category: e.target.value as Category }))}
            options={CATEGORIES.map(c => ({ value: c, label: catLabel(c) }))}
          />
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-semibold text-[var(--text-secondary)]">{t.failureReasonsSettings.formAppliesTo}</span>
            <span className="text-2xs text-[var(--text-muted)] -mt-1">{t.failureReasonsSettings.formAppliesToHint}</span>
            <div className="flex flex-col gap-1.5 mt-1">
              {CONTEXTS.map(ctx => (
                <label key={ctx} className="flex items-center gap-2 cursor-pointer text-sm">
                  <input type="checkbox" checked={form.appliesTo.includes(ctx)} onChange={() => toggleFormContext(ctx)}
                         className="w-4 h-4 accent-[var(--brand)]" />
                  <span className="text-[var(--text-secondary)]">{ctxLabel(ctx)}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="flex items-end gap-4">
            <FieldInput
              wrapperClassName="flex-1"
              type="number"
              label={t.failureReasonsSettings.formOrder}
              value={form.sortOrder}
              onChange={e => setForm(f => ({ ...f, sortOrder: Number(e.target.value) || 0 }))}
            />
            <label className="flex items-center gap-2 h-9 cursor-pointer">
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
