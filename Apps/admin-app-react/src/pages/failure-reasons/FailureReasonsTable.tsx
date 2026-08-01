import { useCallback, useEffect, useState, useMemo } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { getApiError } from '@/lib/utils/errors';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { useT } from '@/lib/i18n/LocaleContext';
import { IconPencil, IconBan, IconCheck, IconChevronUp, IconChevronDown, IconSelector, IconGripVertical } from '@tabler/icons-react';
import {
  DndContext, closestCenter, PointerSensor, KeyboardSensor, useSensor, useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext, verticalListSortingStrategy, arrayMove, useSortable,
  sortableKeyboardCoordinates,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useColumnSettings, ColumnDef } from '@/hooks/useColumnSettings';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { AddButton } from '@/components/ui/AddButton';
import { TablePagination } from '@/components/data-display/TablePagination';

/**
 * A table row that can be dragged by its handle.
 *
 * The handle is the only drag surface: the row itself stays clickable, and a stray press while
 * reading does not start moving things. It appears on hover so an unfiltered list does not grow a
 * column of grips, and it is hidden entirely when reordering is not available — a control that
 * cannot do anything is worse than no control.
 */
function SortableRow({ id, disabled, children }: {
  id: string; disabled: boolean; children: React.ReactNode;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id, disabled });
  return (
    <tr
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.55 : 1 }}
      className="h-14 border-b border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors group relative"
    >
      {children}
      <td className="w-8 pe-2 align-middle">
        {!disabled && (
          <button
            type="button"
            className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity cursor-grab active:cursor-grabbing text-[var(--text-soft)] hover:text-[var(--text-secondary)]"
            {...attributes}
            {...listeners}
            aria-label="Réordonner"
          >
            <IconGripVertical size={15} />
          </button>
        )}
      </td>
    </tr>
  );
}

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

type SortKey = 'label' | 'category' | 'order' | 'status';

export default function FailureReasonsTable({ canManage }: { canManage: boolean }) {
  const t = useT();
  const s = t.failureReasonsSettings;
  const catLabel = (c: string) => (t.failureCodes as Record<string, string>)[c] ?? c;
  const scopeLabel = (sc: string) =>
    (t.failureScopes as Record<string, string>)?.[sc] ?? ({ DELIVERY: 'Delivery', ITEM: 'Item', BOTH: 'Both' } as Record<string, string>)[sc] ?? sc;

  const REASON_COLUMNS = useMemo<ColumnDef[]>(() => [
    // The code is the backend's key, not the operator's. They recognise "Client absent", never
    // CUSTOMER_ABSENT, so the column spent width on a string nobody reads and made every row
    // carry a second, uglier name for the same thing. It stays searchable — typing a code still
    // finds its reason, which costs no space and helps whoever knows the identifiers.
    { id: 'label', label: s.tableLabel || 'Reason', pinned: true },
    { id: 'category', label: s.tableCategory || 'Category' },
    { id: 'scope', label: tlabel(s, 'tableScope') || 'Scope' },
    { id: 'order', label: s.formOrder || 'Order' },
    { id: 'status', label: s.tableStatus || 'Status' },
  ], [t]);

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
  // Multi-select (empty = all).
  const [categoryFilters, setCategoryFilters] = useState<string[]>([]);
  const [scopeFilters, setScopeFilters] = useState<string[]>([]);
  const [statusFilters, setStatusFilters] = useState<string[]>([]);
  const [sort, setSort] = useState<{ key: SortKey; dir: 'asc' | 'desc' }>({ key: 'order', dir: 'asc' });
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);

  const fetchReasons = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/admin/failure-reasons');
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
        await api.put(`/admin/failure-reasons/${form.id}`, payload);
        showSuccessToast(s.toastUpdated);
      } else {
        await api.post('/admin/failure-reasons', payload);
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
      await api.delete(`/admin/failure-reasons/${r.id}`);
      showSuccessToast(s.toastDeactivated);
      await fetchReasons();
    } catch (err) {
      showErrorToast(err, s.toastDeactivateFailed);
    }
  };

  const reactivate = async (r: FailureReason) => {
    try {
      await api.put(`/admin/failure-reasons/${r.id}`, {
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
        api.put(`/admin/failure-reasons/${r.id}`, { label: r.label, category: r.category, scope: r.scope, active: r.active, sortOrder: neighbour.sortOrder }),
        api.put(`/admin/failure-reasons/${neighbour.id}`, { label: neighbour.label, category: neighbour.category, scope: neighbour.scope, active: neighbour.active, sortOrder: r.sortOrder }),
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
      if (categoryFilters.length && !categoryFilters.includes(r.category)) return false;
      if (scopeFilters.length && !scopeFilters.includes(r.scope)) return false;
      if (statusFilters.length) {
        const ok = (statusFilters.includes('active') && r.active) || (statusFilters.includes('inactive') && !r.active);
        if (!ok) return false;
      }
      return true;
    });
    const dir = sort.dir === 'asc' ? 1 : -1;
    return filtered.sort((a, b) => {
      switch (sort.key) {
        case 'label': return dir * a.label.localeCompare(b.label);
        case 'category': return dir * catLabel(a.category).localeCompare(catLabel(b.category));
        case 'status': return dir * (Number(a.active) - Number(b.active));
        case 'order':
        default: return dir * ((a.sortOrder - b.sortOrder) || a.label.localeCompare(b.label));
      }
    });
  }, [reasons, search, categoryFilters, scopeFilters, statusFilters, sort, t]);

  // Client-side pagination over the filtered set (bounded reference catalog), same primitive as the
  // Drivers/Vehicles/Users tables.
  const totalPages = Math.max(1, Math.ceil(visible.length / pageSize));
  useEffect(() => { setPage(0); }, [search, categoryFilters, scopeFilters, statusFilters, pageSize]);
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = useMemo(
    () => visible.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [visible, safePage, pageSize],
  );

  /**
   * Dragging is only offered when the row's position actually means something.
   *
   * The order shown has to be the order stored, or a drop would move a row to a place the list is
   * not describing. And the visible set has to be the whole set: positions are rewritten from the
   * ids sent, so reordering a filtered subset would pull everything visible in front of everything
   * hidden — a silent renumbering of rows the operator never saw.
   */
  const listIsFiltered = search.trim() !== '' || categoryFilters.length > 0
    || scopeFilters.length > 0 || statusFilters.length > 0;
  const canReorder = sort.key === 'order' && sort.dir === 'asc' && !listIsFiltered;

  const sensors = useSensors(
    // A small distance so a click on the row still reads as a click, not the start of a drag.
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const onDragEnd = async (e: DragEndEvent) => {
    const { active, over } = e;
    if (!over || active.id === over.id) return;
    const from = visible.findIndex(r => r.id === active.id);
    const to = visible.findIndex(r => r.id === over.id);
    if (from < 0 || to < 0) return;

    // Move optimistically — the list must follow the cursor, not the round trip — then persist the
    // whole order so the stored positions match what is on screen exactly.
    const next = arrayMove(visible, from, to);
    const previous = reasons;
    setReasons(next);
    try {
      await api.put('/admin/failure-reasons/reorder', next.map(r => r.id));
    } catch (err) {
      setReasons(previous);
      showErrorToast(err, tlabel(s, 'reorderError') ?? "L'ordre n'a pas pu être enregistré");
    }
  };

  const toggleSort = (key: SortKey) => setSort(st => st.key === key ? { key, dir: st.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' });
  const sortIcon = (key: SortKey) => sort.key !== key
    ? <IconSelector size={12} className="opacity-40" />
    : (sort.dir === 'asc' ? <IconChevronUp size={12} /> : <IconChevronDown size={12} />);
  const SORTABLE: Record<string, SortKey> = { label: 'label', category: 'category', order: 'order', status: 'status' };

  const scopeOptions = allowedScopes(form.category);

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
      <PageFilterBar
        search={search}
        onSearch={setSearch}
        searchPlaceholder={s.searchPlaceholder}
        attributes={[
          { key: 'category', label: s.tableCategory || 'Category', multi: true, options: CATEGORIES.map(c => ({ value: c, label: catLabel(c) })) },
          { key: 'scope', label: tlabel(s, 'tableScope') || 'Scope', multi: true, options: SCOPES.map(sc => ({ value: sc, label: scopeLabel(sc) })) },
          { key: 'status', label: s.tableStatus || 'Status', multi: true, options: [
            { value: 'active', label: s.active },
            { value: 'inactive', label: s.inactive },
          ]},
        ]}
        activeFilters={{
          ...(categoryFilters.length && { category: categoryFilters }),
          ...(scopeFilters.length && { scope: scopeFilters }),
          ...(statusFilters.length && { status: statusFilters }),
        }}
        onFilterChange={(key, val) => {
          const toggle = (arr: string[], v: string) => arr.includes(v) ? arr.filter(x => x !== v) : [...arr, v];
          if (key === 'category') setCategoryFilters(val === null ? [] : toggle(categoryFilters, val));
          if (key === 'scope') setScopeFilters(val === null ? [] : toggle(scopeFilters, val));
          if (key === 'status') setStatusFilters(val === null ? [] : toggle(statusFilters, val));
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
              {visible.length} {tlabel(s, 'countLabel') || 'reason'}{visible.length !== 1 ? 's' : ''}
            </span>
            <DisplaySettingsDropdown
              columns={orderedColumns}
              visibleIds={visibleIds}
              onToggle={toggleColumn}
              onReorder={moveColumn}
              onReset={resetColumns}
            />
          </div>

          <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
            <div className="overflow-x-auto">
              <table className="w-full border-collapse">
                <thead className="sticky top-0 z-20 border-b border-[var(--border)]" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                  <tr>
                    <th className="w-2 px-0" />
                    {orderedColumns.map(col => {
                      if (!visibleIds.has(col.id)) return null;
                      const sk = SORTABLE[col.id];
                      return (
                        <th key={col.id} className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">
                          {sk ? (
                            <button type="button" onClick={() => toggleSort(sk)} className="inline-flex items-center gap-1 hover:text-[var(--text-primary)]">
                              {col.label} {sortIcon(sk)}
                            </button>
                          ) : col.label}
                        </th>
                      );
                    })}
                    <th className="h-10 px-6 text-end text-xs font-[450] text-[var(--text-muted)]" />
                    <th className="w-8" />
                  </tr>
                </thead>
                <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
                <SortableContext items={pageRows.map(r => r.id)} strategy={verticalListSortingStrategy}>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={visibleIds.size + 3} className="px-6 py-8 text-center text-[var(--text-muted)]">{s.loading}</td></tr>
                  ) : visible.length === 0 ? (
                    <tr><td colSpan={visibleIds.size + 3} className="px-6 py-8 text-center text-[var(--text-muted)]">{s.empty}</td></tr>
                  ) : pageRows.map(r => (
                    <SortableRow key={r.id} id={r.id} disabled={!canReorder}>
                      <td className="p-0">
                        <div className="w-[3px] h-10 rounded-r-[2px]" style={{ backgroundColor: r.active ? 'var(--success)' : 'var(--text-soft)' }} />
                      </td>
                      {orderedColumns.map(col => {
                        if (!visibleIds.has(col.id)) return null;
                        if (col.id === 'label') return (
                          <td key="label" className="px-6 text-xs font-[600] text-[var(--text-primary)] align-middle">{r.label}</td>
                        );
                        if (col.id === 'category') return (
                          <td key="category" className="px-6 align-middle">
                            <StatusBadge status={r.category} label={catLabel(r.category)} size="sm" />
                          </td>
                        );
                        if (col.id === 'scope') return (
                          <td key="scope" className="px-6 align-middle">
                            <span className="text-2xs font-medium px-2 py-0.5 rounded" style={{ background: 'var(--hover-bg)', color: 'var(--text-secondary)' }}>
                              {scopeLabel(r.scope)}
                            </span>
                          </td>
                        );
                        if (col.id === 'order') return (
                          <td key="order" className="px-6 align-middle">
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
                          <td key="status" className="px-6 align-middle">
                            <StatusBadge
                              status={r.active ? 'ACTIVE' : 'INACTIVE'}
                              label={r.active ? s.active : s.inactive}
                              size="sm"
                            />
                          </td>
                        );
                        return null;
                      })}
                      <td className="px-6 text-end align-middle">
                        {canManage && (
                          <div className="flex items-center justify-end gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity">
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
                    </SortableRow>
                  ))}
                </tbody>
                </SortableContext>
                </DndContext>
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
              {tlabel(s, 'formScopeItemHint') || 'Item scope is only available for Refused, Damaged, or Missing categories.'}
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
