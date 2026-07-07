import { useCallback, useEffect, useState, useMemo } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { getApiError } from '@/lib/errors';
import { tlabel } from '@/lib/i18n-dict';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { useT } from '@/lib/LocaleContext';
import { IconPencil, IconBan, IconCheck, IconChevronUp, IconChevronDown, IconSelector } from '@tabler/icons-react';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings, ColumnDef } from '@/hooks/useColumnSettings';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { AddButton } from '@/components/ui/AddButton';
import { TablePagination } from '@/components/data-display/TablePagination';
import { cn } from '@/lib/utils';

// Analytics categories (mirror backend FailureCode enum). Labels come from i18n (t.failureCodes).
const CATEGORIES = ['CLIENT_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'DAMAGED', 'MISSING', 'OTHER'] as const;
type Category = typeof CATEGORIES[number];

// Scope (mirror backend ReasonScope enum). Category drives the disposition; scope only says WHERE the
// reason is usable. DELIVERY = failure sheet, ITEM = per-line disposition, BOTH = both.
const SCOPES = ['DELIVERY', 'ITEM', 'BOTH'] as const;
type Scope = typeof SCOPES[number];

// Only these categories are per-item dispositions — so ITEM / BOTH scope is only valid for them.
const ITEM_CATEGORIES: readonly Category[] = ['REFUSED', 'DAMAGED', 'MISSING'];
const isItemCategory = (c: Category) => ITEM_CATEGORIES.includes(c);
const allowedScopes = (c: Category): Scope[] => (isItemCategory(c) ? ['DELIVERY', 'ITEM', 'BOTH'] : ['DELIVERY']);

interface FailureReason {
  id: string;
  code: string;
  label: string;
  category: Category;
  scope: Scope;
  active: boolean;
  sortOrder: number;
}

interface FormState {
  id?: string;
  label: string;
  category: Category;
  scope: Scope;
  sortOrder: number;
  active: boolean;
}

// Code is auto-generated server-side from the label (and deduped) — not an editable field.
const EMPTY_FORM: FormState = { label: '', category: 'OTHER', scope: 'DELIVERY', sortOrder: 100, active: true };

const REASON_ROW_H = {
  compact: 'h-9',
  comfortable: 'h-12',
  spacious: 'h-15',
} as const;

type SortKey = 'label' | 'code' | 'category' | 'order' | 'status';
type StatusFilter = 'all' | 'active' | 'inactive';

export default function FailureReasonsTable({ canManage }: { canManage: boolean }) {
  const t = useT();
  const s = t.failureReasonsSettings;
  const catLabel = (c: string) => (t.failureCodes as Record<string, string>)[c] ?? c;
  const scopeLabel = (sc: string) =>
    (t.failureScopes as Record<string, string>)?.[sc] ?? ({ DELIVERY: 'Livraison', ITEM: 'Article', BOTH: 'Les deux' } as Record<string, string>)[sc] ?? sc;

  const REASON_COLUMNS = useMemo<ColumnDef[]>(() => [
    { id: 'label', label: s.tableLabel || 'Motif', pinned: true },
    { id: 'code', label: s.tableCode || 'Code' },
    { id: 'category', label: s.tableCategory || 'Catégorie' },
    { id: 'scope', label: tlabel(s, 'tableScope') || 'Portée' },
    { id: 'order', label: s.formOrder || 'Ordre' },
    { id: 'status', label: s.tableStatus || 'Statut' },
  ], [t]);

  const { density, setDensity } = useDensity('failure-reasons', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('failure-reasons', REASON_COLUMNS);
  const [reasons, setReasons] = useState<FailureReason[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);
  // Field-level error for a code/label conflict (FAILURE_REASON_EXISTS) — shown under the label input.
  const [labelError, setLabelError] = useState<string | null>(null);

  // Toolbar state
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<'all' | Category>('all');
  const [scopeFilter, setScopeFilter] = useState<'all' | Scope>('all');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sort, setSort] = useState<{ key: SortKey; dir: 'asc' | 'desc' }>({ key: 'order', dir: 'asc' });
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);

  const fetchReasons = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/admin/failure-reasons');
      const rows: FailureReason[] = ((Array.isArray(res.data) ? res.data : []) as FailureReason[]).map((r) => ({
        ...r,
        scope: (r.scope ?? 'DELIVERY') as Scope,
      }));
      setReasons(rows);
    } catch {
      showErrorToast(null, s.toastLoadFailed);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void fetchReasons(); }, [fetchReasons]);

  const openCreate = () => { setForm(EMPTY_FORM); setLabelError(null); setModalOpen(true); };
  const openEdit = (r: FailureReason) => {
    setForm({ id: r.id, label: r.label, category: r.category, scope: r.scope, sortOrder: r.sortOrder, active: r.active });
    setLabelError(null);
    setModalOpen(true);
  };

  // Changing the category re-clamps the scope: a non-item category can only be DELIVERY.
  const setCategory = (category: Category) => setForm(f => ({
    ...f,
    category,
    scope: allowedScopes(category).includes(f.scope) ? f.scope : 'DELIVERY',
  }));

  const submit = async () => {
    if (!form.label.trim()) { setLabelError(s.toastLabelRequired); return; }
    setLabelError(null);
    setSubmitting(true);
    try {
      const payload = { label: form.label.trim(), category: form.category, scope: form.scope, sortOrder: form.sortOrder, active: form.active };
      if (form.id) {
        await api.put(`/api/admin/failure-reasons/${form.id}`, payload);
        showSuccessToast(s.toastUpdated);
      } else {
        await api.post('/api/admin/failure-reasons', payload);
        showSuccessToast(s.toastCreated);
      }
      setModalOpen(false);
      await fetchReasons();
    } catch (err) {
      // A duplicate code shows under the label field (the code is derived from it), not as a toast.
      if (getApiError(err).errorCode === 'FAILURE_REASON_EXISTS') {
        setLabelError(tlabel(s, 'formCodeExists') ?? 'Un motif avec ce code existe déjà');
      } else {
        showErrorToast(err, s.toastSaveFailed);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const deactivate = async (r: FailureReason) => {
    try {
      await api.delete(`/api/admin/failure-reasons/${r.id}`);
      showSuccessToast(s.toastDeactivated);
      await fetchReasons();
    } catch (err) {
      showErrorToast(err, s.toastDeactivateFailed);
    }
  };

  const reactivate = async (r: FailureReason) => {
    try {
      await api.put(`/api/admin/failure-reasons/${r.id}`, {
        label: r.label, category: r.category, scope: r.scope, sortOrder: r.sortOrder, active: true,
      });
      showSuccessToast(s.toastReactivated);
      await fetchReasons();
    } catch (err) {
      showErrorToast(err, s.toastReactivateFailed);
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
        api.put(`/api/admin/failure-reasons/${r.id}`, { label: r.label, category: r.category, scope: r.scope, active: r.active, sortOrder: neighbour.sortOrder }),
        api.put(`/api/admin/failure-reasons/${neighbour.id}`, { label: neighbour.label, category: neighbour.category, scope: neighbour.scope, active: neighbour.active, sortOrder: r.sortOrder }),
      ]);
      await fetchReasons();
    } catch (err) {
      showErrorToast(err, s.toastReorderFailed);
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
      if (scopeFilter !== 'all' && r.scope !== scopeFilter) return false;
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
  }, [reasons, search, categoryFilter, scopeFilter, statusFilter, sort, t]);

  // Client-side pagination over the filtered set (bounded reference catalog), same primitive as the
  // Drivers/Vehicles/Users tables.
  const totalPages = Math.max(1, Math.ceil(visible.length / pageSize));
  useEffect(() => { setPage(0); }, [search, categoryFilter, scopeFilter, statusFilter, pageSize]);
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = useMemo(
    () => visible.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [visible, safePage, pageSize],
  );

  const toggleSort = (key: SortKey) => setSort(st => st.key === key ? { key, dir: st.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' });
  const sortIcon = (key: SortKey) => sort.key !== key
    ? <IconSelector size={12} className="opacity-40" />
    : (sort.dir === 'asc' ? <IconChevronUp size={12} /> : <IconChevronDown size={12} />);
  const SORTABLE: Record<string, SortKey> = { label: 'label', code: 'code', category: 'category', order: 'order', status: 'status' };

  const scopeOptions = allowedScopes(form.category);

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
      <PageFilterBar
        search={search}
        onSearch={setSearch}
        searchPlaceholder={s.searchPlaceholder}
        attributes={[
          { key: 'category', label: s.tableCategory || 'Catégorie', options: CATEGORIES.map(c => ({ value: c, label: catLabel(c) })) },
          { key: 'scope', label: tlabel(s, 'tableScope') || 'Portée', options: SCOPES.map(sc => ({ value: sc, label: scopeLabel(sc) })) },
          { key: 'status', label: s.tableStatus || 'Statut', options: [
            { value: 'active', label: s.active },
            { value: 'inactive', label: s.inactive },
          ]},
        ]}
        activeFilters={{
          ...(categoryFilter !== 'all' && { category: categoryFilter }),
          ...(scopeFilter !== 'all' && { scope: scopeFilter }),
          ...(statusFilter !== 'all' && { status: statusFilter }),
        }}
        onFilterChange={(key, val) => {
          if (key === 'category') setCategoryFilter((val ?? 'all') as 'all' | Category);
          if (key === 'scope') setScopeFilter((val ?? 'all') as 'all' | Scope);
          if (key === 'status') setStatusFilter((val ?? 'all') as StatusFilter);
        }}
        onRefresh={fetchReasons}
        refreshing={loading}
        extraActions={canManage ? <AddButton label={s.addButton} onClick={openCreate} /> : undefined}
      />

      <div className="flex flex-1 min-h-0 overflow-hidden">
        <div className="flex flex-col flex-1 overflow-hidden min-w-0" style={{ background: 'var(--app-bg)' }}>
          {/* Toolbar */}
          <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
            <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
              {visible.length} {tlabel(s, 'countLabel') || 'motif'}{visible.length !== 1 ? 's' : ''}
            </span>
            <DisplaySettingsDropdown
              columns={orderedColumns}
              visibleIds={visibleIds}
              onToggle={toggleColumn}
              onReorder={moveColumn}
              onReset={resetColumns}
              density={density}
              onDensityChange={setDensity}
            />
          </div>

          <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm border-collapse min-w-[720px]">
                <thead className="sticky top-0 z-10">
                  <tr className="text-2xs uppercase tracking-wider text-[var(--text-muted)] h-10 border-b border-[var(--border)]" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
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
                    <tr><td colSpan={visibleIds.size + 1} className="px-4 py-8 text-center text-[var(--text-muted)]">{s.loading}</td></tr>
                  ) : visible.length === 0 ? (
                    <tr><td colSpan={visibleIds.size + 1} className="px-4 py-8 text-center text-[var(--text-muted)]">{s.empty}</td></tr>
                  ) : pageRows.map(r => (
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
                        if (col.id === 'scope') return (
                          <td key="scope" className="px-4 align-middle">
                            <span className="text-2xs font-medium px-2 py-0.5 rounded" style={{ background: 'var(--hover-bg)', color: 'var(--text-secondary)' }}>
                              {scopeLabel(r.scope)}
                            </span>
                          </td>
                        );
                        if (col.id === 'order') return (
                          <td key="order" className="px-4 align-middle">
                            <div className="flex items-center gap-1.5">
                              <span className="font-mono text-xs text-[var(--text-muted)] w-6">{r.sortOrder}</span>
                              {canManage && (
                                <span className="flex items-center opacity-0 group-hover:opacity-100 transition-opacity">
                                  <button type="button" disabled={!canMoveUp(r)} onClick={() => move(r, -1)}
                                          className="w-6 h-6 flex items-center justify-center rounded text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-30 disabled:cursor-not-allowed" title={s.moveUp}>
                                    <IconChevronUp size={14} />
                                  </button>
                                  <button type="button" disabled={!canMoveDown(r)} onClick={() => move(r, 1)}
                                          className="w-6 h-6 flex items-center justify-center rounded text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-30 disabled:cursor-not-allowed" title={s.moveDown}>
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
                              label={r.active ? s.active : s.inactive}
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
                                    className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]" title={s.editTooltip}>
                              <IconPencil size={13} />
                            </button>
                            {r.active ? (
                              <button type="button" onClick={() => deactivate(r)}
                                      className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--danger)] hover:bg-[var(--hover-bg)]" title={s.deactivateTooltip}>
                                <IconBan size={13} />
                              </button>
                            ) : (
                              <button type="button" onClick={() => reactivate(r)}
                                      className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--success)] hover:bg-[var(--hover-bg)]" title={s.reactivateTooltip}>
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
          </div>

          <TablePagination
            page={safePage}
            totalPages={totalPages}
            totalElements={visible.length}
            size={pageSize}
            onPageChange={setPage}
            onSizeChange={setPageSize}
          />
        </div>
      </div>

      <AppModal opened={modalOpen} onClose={() => setModalOpen(false)} title={form.id ? s.editTitle : s.createTitle} size="sm">
        <div className="flex flex-col gap-4">
          <FieldInput
            label={s.formLabel}
            value={form.label}
            onChange={e => { setForm(f => ({ ...f, label: e.target.value })); if (labelError) setLabelError(null); }}
            placeholder={s.formLabelPlaceholder}
            error={labelError ?? undefined}
          />
          <FieldSelect
            label={s.formCategory}
            value={form.category}
            onChange={e => setCategory(e.target.value as Category)}
            options={CATEGORIES.map(c => ({ value: c, label: catLabel(c) }))}
          />
          <FieldSelect
            label={tlabel(s, 'formScope') || 'Portée'}
            value={form.scope}
            onChange={e => setForm(f => ({ ...f, scope: e.target.value as Scope }))}
            options={scopeOptions.map(sc => ({ value: sc, label: scopeLabel(sc) }))}
          />
          {!isItemCategory(form.category) && (
            <span className="text-2xs text-[var(--text-muted)] -mt-2">
              {tlabel(s, 'formScopeItemHint') || 'La portée « Article » n’est disponible que pour les catégories Refusé, Endommagé ou Manquant.'}
            </span>
          )}
          <div className="flex items-end gap-4">
            <FieldInput
              wrapperClassName="flex-1"
              type="number"
              label={s.formOrder}
              value={form.sortOrder}
              onChange={e => setForm(f => ({ ...f, sortOrder: Number(e.target.value) || 0 }))}
            />
            <label className="flex items-center gap-2 h-9 cursor-pointer">
              <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                     className="w-4 h-4 accent-[var(--brand)]" />
              <span className="text-sm font-semibold text-[var(--text-secondary)]">{s.formActive}</span>
            </label>
          </div>
          <div className="flex items-center justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setModalOpen(false)} className="h-8 px-3 text-xs">{s.cancelButton}</Button>
            <Button size="sm" onClick={submit} disabled={submitting} className="px-4">
              {submitting ? s.savingButton : s.saveButton}
            </Button>
          </div>
        </div>
      </AppModal>
    </div>
  );
}
