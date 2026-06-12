
import { useCallback, useEffect, useMemo, useRef, useState, Suspense } from 'react';
import { useNavigate as useRouter, useSearchParams } from 'react-router-dom';
import { formatDateTime } from '@/lib/date';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { api } from '@/lib/api';
import {
  ErpPendingOrderPreviewDTO,
  ErpPendingOrderSummaryDTO,
} from '@/types/erp';
import {
  IconRefresh,
  IconSearch,
  IconEye,
  IconDownload,
  IconTruck,
  IconPackage,
  IconPhone,
  IconMapPin,
  IconCalendarClock,
  IconCheck,
  IconAlertCircle,
  IconClock,
  IconCloudDownload,
} from '@tabler/icons-react';
import { cn, formatMoney } from '@/lib/utils';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { AppDrawer } from '@/components/overlays/AppDrawer';
import { Button } from '@/components/ui/button';
import { useT } from '@/lib/LocaleContext';
import { getDayBucket } from '@/lib/sla';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import type { ColumnDef } from '@/hooks/useColumnSettings';

const IMPORT_COLUMNS: ColumnDef[] = [
  { id: 'ref',      label: 'Référence',      pinned: true },
  { id: 'customer', label: 'Client',          pinned: true },
  { id: 'dest',     label: 'Destination' },
  { id: 'amount',   label: 'Montant' },
  { id: 'date',     label: 'Date Planifiée' },
  { id: 'status',   label: 'Statut' },
];

const CELL_PADDING: Record<'compact' | 'comfortable' | 'spacious', string> = {
  compact: 'px-3 py-1.5',
  comfortable: 'px-3 py-3',
  spacious: 'px-3 py-5',
};

const ITEMS_PER_PAGE = 25;

const money = (value: number | null | undefined, currency = 'TND') => formatMoney(value, currency);

function formatDate(value: string | null | undefined) {
  return formatDateTime(value);
}

function ImportErpPageContent() {
  const t = useT();
  const { density, setDensity } = useDensity('import', 'comfortable');
  const thPaddingClass = density === 'compact' ? 'py-2' : density === 'spacious' ? 'py-6' : 'py-4';
  const rowPaddingClass = density === 'compact' ? 'py-1.5' : density === 'spacious' ? 'py-5' : 'py-3';
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('import', IMPORT_COLUMNS);
  usePageBreadcrumb([{ label: t.importPage?.pageTitle ? `${t.importPage.pageTitle} ${t.importPage.pageTitleBrand}` : 'Import ERP' }]);
  const router = useRouter();

  const [rows, setRows] = useState<ErpPendingOrderSummaryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');

  const [previewLoading, setPreviewLoading] = useState(false);
  const [preview, setPreview] = useState<ErpPendingOrderPreviewDTO | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);

  const [importingId, setImportingId] = useState<string | null>(null);
  const [confirmForId, setConfirmForId] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [searchParams] = useSearchParams();
  const [activeTab, setActiveTab] = useState<'all' | 'ready' | 'done'>(() => {
    const tab = searchParams.get('tab');
    return tab === 'ready' || tab === 'done' ? tab : 'all';
  });
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkImporting, setBulkImporting] = useState(false);

  // ERP connection health — so the Import page doesn't silently show an empty/stale list
  // when the active source's credentials are wrong. Read-only; ADMIN-gated endpoint, so we
  // fail quiet for non-admins (they simply see no banner).
  const [erpConn, setErpConn] = useState<{ provider: string; status: string; error?: string | null } | null>(null);
  useEffect(() => {
    let alive = true;
    api.get('/api/settings/erp')
      .then(res => { if (alive && res.data) setErpConn({ provider: res.data.activeErpProvider, status: res.data.connectionStatus, error: res.data.lastError }); })
      .catch(() => { /* not admin / no settings — no banner */ });
    return () => { alive = false; };
  }, []);
  const erpUnhealthy = erpConn && erpConn.provider !== 'NONE' && erpConn.status !== 'CONNECTED';

  const loadPendingOrders = useCallback(async (silent = false, forceRefresh = false) => {
    try {
      if (silent) setRefreshing(true);
      else setLoading(true);

      const res = await api.get('/api/admin/erp/pending-orders', { params: { limit: 200, forceRefresh } });
      const data = Array.isArray(res.data) ? res.data : [];
      setRows(data);
    } catch (err) {
      showErrorToast(err, 'errorDataLoadFailed');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadPendingOrders();
    const interval = setInterval(() => {
      loadPendingOrders(true);
    }, 45000);
    return () => clearInterval(interval);
  }, [loadPendingOrders]);

  // When the backend's ERP poller detects new orders (erp.orders_ready), pull a
  // fresh list immediately instead of waiting for the 45s tick. Debounced so a
  // burst of events triggers a single forced refetch.
  const erpRefetchTimer = useRef<number | null>(null);
  useRealtimeEvent(['erp.orders_ready'], () => {
    if (erpRefetchTimer.current != null) return;
    erpRefetchTimer.current = window.setTimeout(() => {
      erpRefetchTimer.current = null;
      void loadPendingOrders(true, true);
    }, 1500);
  });

  const filteredRows = useMemo(() => {
    let result = rows;
    if (activeTab === 'ready') result = result.filter(r => !r.alreadyImported);
    if (activeTab === 'done') result = result.filter(r => r.alreadyImported);
    const q = query.trim().toLowerCase();
    if (q) {
      result = result.filter((row) =>
        row.erpOrderId?.toLowerCase().includes(q)
        || row.customerName?.toLowerCase().includes(q)
        || (row.customerPhone ?? '').toLowerCase().includes(q)
        || (row.externalRef ?? '').toLowerCase().includes(q)
        || String(row.existingBackorderId ?? '').toLowerCase().includes(q)
      );
    }

    // Sort by appearing time (order date/creation order) descending - newest/last ones first
    result.sort((a, b) => {
      const timeA = a.dateOrder ? new Date(a.dateOrder).getTime() : (a.scheduledAt ? new Date(a.scheduledAt).getTime() : 0);
      const timeB = b.dateOrder ? new Date(b.dateOrder).getTime() : (b.scheduledAt ? new Date(b.scheduledAt).getTime() : 0);
      if (timeA !== timeB) {
        return timeB - timeA;
      }
      return b.erpOrderId.localeCompare(a.erpOrderId);
    });

    return result;
  }, [rows, query, activeTab]);

  const totalPages = Math.max(1, Math.ceil(filteredRows.length / ITEMS_PER_PAGE));
  const paginatedRows = filteredRows.slice((currentPage - 1) * ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE);

  useEffect(() => { setCurrentPage(1); }, [query, activeTab]);

  const stats = useMemo(() => {
    const total = rows.length;
    const importable = rows.filter((x) => !x.alreadyImported).length;
    const alreadyImported = rows.filter((x) => x.alreadyImported).length;
    return { total, importable, alreadyImported };
  }, [rows]);

  const openPreview = useCallback(async (erpOrderId: string) => {
    setPreviewOpen(true);
    setPreviewLoading(true);
    try {
      const res = await api.get('/api/admin/erp/pending-orders/preview', { params: { erpOrderId } });
      setPreview(res.data);
    } catch (err) {
      showErrorToast(err, 'errorDataLoadFailed');
      setPreview(null);
    } finally {
      setPreviewLoading(false);
    }
  }, []);

  const doBulkImport = useCallback(async () => {
    const ids = Array.from(selectedIds).filter(id => {
      const row = rows.find(r => r.erpOrderId === id);
      return row && !row.alreadyImported;
    });
    if (ids.length === 0) return;
    try {
      setBulkImporting(true);
      await api.post('/api/admin/erp/bulk-import', ids);
      showSuccessToast('successImportCompleted');
      setSelectedIds(new Set());
      await loadPendingOrders(true);
    } catch (err) {
      showErrorToast(err, 'errorImportBatchFailed');
    } finally {
      setBulkImporting(false);
    }
  }, [selectedIds, rows, loadPendingOrders]);

  const toggleSelect = useCallback((id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }, []);

  const selectablePage = paginatedRows?.filter(r => !r.alreadyImported) ?? [];
  const allPageSelected = selectablePage.length > 0 && selectablePage.every(r => selectedIds.has(r.erpOrderId));
  const toggleSelectAll = useCallback(() => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (allPageSelected) selectablePage.forEach(r => next.delete(r.erpOrderId));
      else selectablePage.forEach(r => next.add(r.erpOrderId));
      return next;
    });
  }, [allPageSelected, selectablePage]);

  const doImport = useCallback(async (erpOrderId: string) => {
    try {
      setImportingId(erpOrderId);
      await api.post('/api/admin/erp/import-order', null, { params: { erpOrderId } });
      showSuccessToast('successImportSingle');
      setConfirmForId(null);
      setPreviewOpen(false);
      setPreview(null);
      await loadPendingOrders(true);
      window.open('/deliveries', '_blank');
    } catch (err: any) {
      const status = err?.response?.status;
      if (status === 409) {
        showErrorToast(null, 'errorImportAlreadyExists');
      } else {
        showErrorToast(err, 'errorImportSingleFailed');
      }
    } finally {
      setImportingId(null);
    }
  }, [loadPendingOrders]);

  const importQuickFilters = [
    { value: 'all',   label: t.importPage.pillAll,   count: stats.total },
    { value: 'ready', label: t.importPage.pillReady, count: stats.importable },
    { value: 'done',  label: t.importPage.pillDone,  count: stats.alreadyImported },
  ];

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>
      <PageFilterBar
        search={query}
        onSearch={setQuery}
        searchPlaceholder={t.importPage.searchPlaceholder}
        onRefresh={() => loadPendingOrders(true, true)}
        refreshing={refreshing || loading}
        quickFilters={importQuickFilters}
        activeQuickFilter={activeTab}
        onQuickFilterChange={v => setActiveTab(v as 'all' | 'ready' | 'done')}
      />

      {/* ERP connection health banner — the active source's creds aren't verified, so this
          list may be empty or stale. Sends the admin straight to the config page to fix it. */}
      {erpUnhealthy && (
        <div
          className="flex items-center gap-2.5 px-4 py-2.5 shrink-0 text-xs"
          style={{ background: 'color-mix(in srgb, var(--warning) 10%, transparent)', color: 'var(--warning)', borderBottom: '1px solid color-mix(in srgb, var(--warning) 25%, transparent)' }}
        >
          <IconAlertCircle size={15} className="shrink-0" />
          <span className="font-[600] min-w-0">
            {erpConn?.status === 'ERROR'
              ? (t.importPage.erpUnhealthyError ?? 'La connexion ERP a échoué — les commandes ne sont pas synchronisées.')
              : (t.importPage.erpUnhealthyUntested ?? 'La source ERP n’est pas vérifiée — testez la connexion pour garantir la synchronisation.')}
            {erpConn?.error ? ` (${erpConn.error})` : ''}
          </span>
          <button
            type="button"
            onClick={() => router('/settings/erp')}
            className="ms-auto shrink-0 font-bold underline hover:no-underline"
          >
            {t.importPage.erpFixLink ?? 'Configurer'}
          </button>
        </div>
      )}

      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* ── Main Table (full-width, sidebar removed) ── */}
        <div className="flex-1 flex flex-col overflow-hidden min-w-0" style={{ background: 'var(--surface)' }}>
          {/* Toolbar */}
          <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
            <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
              {filteredRows.length} commande{filteredRows.length !== 1 ? 's' : ''}
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => loadPendingOrders(true, true)}
                disabled={refreshing || loading}
                className="h-7 px-3 flex items-center gap-1.5 text-xs font-bold rounded-md transition-colors hover:opacity-90 disabled:opacity-50 shrink-0 text-white"
                style={{ background: 'var(--brand)', border: 'none' }}
              >
                {refreshing
                  ? <svg className="animate-spin h-3 w-3" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/></svg>
                  : <IconCloudDownload size={13} strokeWidth={2.5} />}
                {t.importPage.syncErpButton}
              </button>
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
          </div>
          <div className="overflow-y-auto flex-1">
            <table className="border-collapse min-w-[1000px] w-full">
              <thead className="sticky top-0 z-10" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                <tr className="border-b border-[var(--border)]">
                  <th className="w-[8px] p-0"></th>
                  <th className={cn("w-10 pl-4 text-left", thPaddingClass)}>
                    <input type="checkbox" checked={allPageSelected} onChange={toggleSelectAll}
                      className="w-3.5 h-3.5 cursor-pointer accent-[var(--brand)]" />
                  </th>
                  {orderedColumns.map((col) => {
                    if (!visibleIds.has(col.id)) return null;
                    if (col.id === 'ref') return (
                      <th key="ref" className={cn("text-xs font-semibold text-[var(--text-muted)] text-left px-3", thPaddingClass)}>
                        {t.importPage.headerReference}
                      </th>
                    );
                    if (col.id === 'customer') return (
                      <th key="customer" className={cn("text-xs font-semibold text-[var(--text-muted)] text-left px-3", thPaddingClass)}>
                        {t.importPage.headerCustomer}
                      </th>
                    );
                    if (col.id === 'dest') return (
                      <th key="dest" className={cn("text-xs font-semibold text-[var(--text-muted)] text-left px-3", thPaddingClass)}>
                        {t.importPage.headerDestination}
                      </th>
                    );
                    if (col.id === 'amount') return (
                      <th key="amount" className={cn("text-xs font-semibold text-[var(--text-muted)] text-right px-3", thPaddingClass)}>
                        {t.importPage.headerAmount}
                      </th>
                    );
                    if (col.id === 'date') return (
                      <th key="date" className={cn("text-xs font-semibold text-[var(--text-muted)] text-left px-3", thPaddingClass)}>
                        Date Planifiée
                      </th>
                    );
                    if (col.id === 'status') return (
                      <th key="status" className={cn("text-xs font-semibold text-[var(--text-muted)] text-left px-3", thPaddingClass)}>
                        {t.importPage.headerStatus}
                      </th>
                    );
                    return null;
                  })}
                  <th className={cn("text-xs font-semibold text-[var(--text-muted)] text-right px-3", thPaddingClass)}>
                    {t.importPage.headerActions}
                  </th>
                </tr>
              </thead>
              <tbody>
                {loading && !refreshing ? (
                  Array.from({ length: 15 }).map((_, i) => (
                    <tr key={i} className="border-b border-[var(--border)] animate-pulse">
                      <td colSpan={visibleIds.size + 3} className={cn("px-3", rowPaddingClass)}>
                        <div className="h-3 rounded-full w-3/4 mx-auto" style={{ background: 'var(--hover-bg)' }} />
                      </td>
                    </tr>
                  ))
                ) : paginatedRows.length === 0 ? (
                  <tr>
                    <td colSpan={visibleIds.size + 3}>
                      <div className="flex flex-col items-center gap-2 py-20">
                        <IconPackage size={32} strokeWidth={1.5} className="text-[var(--border)]" />
                        <p className="text-xs font-semibold text-[var(--text-muted)]">{t.importPage.emptyState}</p>
                      </div>
                    </td>
                  </tr>
                ) : (
                  paginatedRows.map((row) => {
                    const isImported = row.alreadyImported;
                    return (
                      <tr
                        key={row.erpOrderId}
                        className={cn(
                          "group border-b border-[var(--border)] transition-colors",
                          isImported ? "opacity-60" : "hover:bg-[var(--hover-bg)]"
                        )}
                      >
                        {/* Ribbon */}
                        <td className="p-0">
                          <div className={cn("w-[3px] rounded-r-[1px]", density === 'compact' ? "h-[10px]" : density === 'spacious' ? "h-[22px]" : "h-[14px]")}
                            style={{ backgroundColor: isImported ? '#4CAF82' : '#5E6AD2' }} />
                        </td>
                        {/* Checkbox */}
                        <td className={cn("pl-4", rowPaddingClass)}>
                          {!isImported && (
                            <input type="checkbox"
                              checked={selectedIds.has(row.erpOrderId)}
                              onChange={() => toggleSelect(row.erpOrderId)}
                              className="w-3.5 h-3.5 accent-[var(--brand)] cursor-pointer" />
                          )}
                        </td>
                        {orderedColumns.map((col) => {
                          if (!visibleIds.has(col.id)) return null;
                          if (col.id === 'ref') return (
                            <td key="ref" className={cn(CELL_PADDING[density])}>
                              <div className="flex flex-col gap-0.5">
                                <div className="flex items-center gap-1.5">
                                  <p className="text-xs font-bold font-mono text-[var(--text-primary)] tracking-tight">
                                    {row.blNumber || row.erpOrderId}
                                  </p>
                                  {row.warehouseCode && (
                                    <span className="px-1.5 py-0.5 bg-[var(--hover-bg)] text-[var(--text-muted)] border border-[var(--border)] rounded text-2xs font-bold font-mono tracking-widest uppercase">
                                      {row.warehouseCode}
                                    </span>
                                  )}
                                </div>
                                {row.blNumber && row.erpOrderId && (
                                  <p className="text-2xs font-semibold text-[var(--text-muted)]">SO: {row.erpOrderId}</p>
                                )}
                                {row.externalRef && (
                                  <p className="text-2xs font-semibold text-[var(--text-muted)]">REF: {row.externalRef}</p>
                                )}
                              </div>
                            </td>
                          );
                          if (col.id === 'customer') return (
                            <td key="customer" className={cn(CELL_PADDING[density])}>
                              <div>
                                <p className="text-xs font-semibold text-[var(--text-primary)]">{row.customerName}</p>
                                <div className="flex items-center gap-1">
                                  <IconPhone size={10} className="text-[var(--text-muted)]" />
                                  <p className="text-2xs font-medium text-[var(--text-muted)]">{row.customerPhone}</p>
                                </div>
                              </div>
                            </td>
                          );
                          if (col.id === 'dest') return (
                            <td key="dest" className={cn(CELL_PADDING[density])}>
                              <div className="max-w-[220px]">
                                <p className="text-xs font-medium text-[var(--text-primary)] truncate">{row.deliveryAddress}</p>
                                <p className="text-xs font-semibold text-[var(--text-muted)]">{row.deliveryCity}</p>
                              </div>
                            </td>
                          );
                          if (col.id === 'amount') return (
                            <td key="amount" className={cn(CELL_PADDING[density], "text-right")}>
                              <p className="text-xs font-bold font-mono text-[var(--text-primary)] tabular-nums">{money(row.totalAmount, row.currency)}</p>
                            </td>
                          );
                          if (col.id === 'date') return (
                            <td key="date" className={cn(CELL_PADDING[density])}>
                              {(() => {
                                // Same-day calendar model: red = scheduled date already
                                // passed, amber = due today. Matches Deliveries 'Overdue'/'Today'
                                // so an order's date reads identically on every screen.
                                const bucket = isImported ? 'none' : getDayBucket(row.scheduledAt);
                                const isLate = bucket === 'overdue';
                                const isSoon = bucket === 'today';
                                const color = isLate ? '#dc2626' : isSoon ? '#d97706' : undefined;
                                const tooltip = isLate ? t.importPage.lateTooltip : isSoon ? t.importPage.soonTooltip : null;
                                return (
                                  <div className="relative group/datecell flex flex-col gap-1">
                                    <div className="flex items-center gap-1.5">
                                      <IconCalendarClock size={13} style={{ color: color ?? 'var(--text-muted)' }} />
                                      <span
                                        className="text-xs font-bold tracking-tight"
                                        style={{ color: color ?? 'var(--text-primary)' }}
                                      >
                                        {row.scheduledAt ? formatDate(row.scheduledAt) : 'Non planifié'}
                                      </span>
                                    </div>
                                    {tooltip && (
                                      <div
                                        className="absolute bottom-full left-0 mb-1.5 z-50 hidden group-hover/datecell:block w-max max-w-[260px] px-2.5 py-1.5 rounded-md text-2xs font-medium leading-snug pointer-events-none"
                                        style={{ background: '#1c1c1e', color: '#f4f4f5', boxShadow: '0 4px 12px rgba(0,0,0,0.25)' }}
                                      >
                                        {tooltip}
                                        <div
                                          className="absolute top-full left-3"
                                          style={{ width: 0, height: 0, borderLeft: '5px solid transparent', borderRight: '5px solid transparent', borderTop: '5px solid #1c1c1e' }}
                                        />
                                      </div>
                                    )}
                                  </div>
                                );
                              })()}
                            </td>
                          );
                          if (col.id === 'status') return (
                            <td key="status" className={cn(CELL_PADDING[density])}>
                              <div className="flex flex-col gap-1">
                                {isImported ? (
                                  <span style={{
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    gap: 4,
                                    height: 18,
                                    padding: '0 6px',
                                    borderRadius: 99,
                                    background: 'rgba(76,175,130,0.09)',
                                    color: '#2D8A5E',
                                    fontSize: 10,
                                    fontWeight: 600,
                                    border: '1px solid rgba(76,175,130,0.15)'
                                  }}>
                                    <span style={{ width: 4.5, height: 4.5, borderRadius: '50%', background: '#4CAF82' }} />
                                    {t.importPage.statusSynced}
                                  </span>
                                ) : (
                                  <span style={{
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    gap: 4,
                                    height: 18,
                                    padding: '0 6px',
                                    borderRadius: 99,
                                    background: 'rgba(94,106,210,0.09)',
                                    color: '#4C56B8',
                                    fontSize: 10,
                                    fontWeight: 600,
                                    border: '1px solid rgba(94,106,210,0.15)'
                                  }}>
                                    <span style={{ width: 4.5, height: 4.5, borderRadius: '50%', background: '#5E6AD2' }} />
                                    {t.importPage.statusReady}
                                  </span>
                                )}
                              </div>
                            </td>
                          );
                          return null;
                        })}
                        <td className={cn(CELL_PADDING[density])}>
                          <div className="flex gap-2 justify-end">
                            <button
                              type="button"
                              title={t.importPage.tooltipDetails}
                              className="w-7 h-7 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
                              onClick={() => openPreview(row.erpOrderId)}
                            >
                              <IconEye size={14} />
                            </button>
                            {isImported ? (
                              <button
                                type="button"
                                className="h-7 px-3 text-xs font-bold rounded-md bg-[var(--success)] hover:opacity-90 text-white transition-opacity border-none"
                                onClick={() => window.open('/deliveries', '_blank')}
                              >
                                {t.importPage.buttonView}
                              </button>
                            ) : (
                              <button
                                type="button"
                                className={cn(
                                  "h-7 px-3 text-xs font-bold rounded-md border transition-all",
                                  confirmForId === row.erpOrderId
                                    ? "bg-amber-500 hover:bg-amber-600 text-white animate-pulse border-amber-600"
                                    : "border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] text-[var(--text-primary)] shadow-[0_1px_2px_rgba(0,0,0,0.05)]"
                                )}
                                disabled={importingId === row.erpOrderId}
                                onClick={() => confirmForId === row.erpOrderId ? doImport(row.erpOrderId) : setConfirmForId(row.erpOrderId)}
                              >
                                {importingId === row.erpOrderId ? (
                                  <svg className="animate-spin h-3 w-3" fill="none" viewBox="0 0 24 24">
                                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                                  </svg>
                                ) : (confirmForId === row.erpOrderId ? t.importPage.buttonConfirm : t.importPage.buttonImport)}
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Bulk Action Bar */}
          {selectedIds.size > 0 && (
            <div className="flex items-center gap-3 px-4 py-3 border-t border-[var(--border)]" style={{ background: 'var(--text-primary)' }}>
              <span className="text-xs font-semibold text-[var(--surface)] flex-1">
                {selectedIds.size} {t.importPage.selectedMessage.replace('{plural}', selectedIds.size > 1 ? 's' : '')}
              </span>
              <button
                type="button"
                onClick={() => setSelectedIds(new Set())}
                className="text-xs font-semibold text-[var(--surface)] opacity-60 hover:opacity-100 transition-opacity"
              >
                {t.importPage.deselect}
              </button>
              <button
                type="button"
                className="flex items-center gap-1.5 h-7 px-3 bg-[var(--brand)] hover:opacity-90 text-white font-bold text-xs rounded-md transition-opacity"
                disabled={bulkImporting}
                onClick={doBulkImport}
              >
                {bulkImporting
                  ? <svg className="animate-spin h-3 w-3" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/></svg>
                  : <IconDownload size={13} />}
                {t.importPage.bulkImport.replace('{count}', selectedIds.size.toString())}
              </button>
            </div>
          )}

          {/* Footer / Pagination */}
          <div className="h-px bg-[var(--border)]" />
          <div className="p-4 bg-[var(--surface)]">
            <div className="flex items-center justify-between">
              <p className="text-xs font-semibold text-[var(--text-muted)]">
                {t.importPage.showing.replace('{showing}', paginatedRows.length.toString()).replace('{total}', filteredRows.length.toString())}
              </p>
              {totalPages > 1 && (
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    disabled={currentPage === 1}
                    onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                    className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-40 text-2xs font-[500]"
                  >‹</button>
                  {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
                    const pg = i + 1;
                    return (
                      <button
                        key={pg}
                        type="button"
                        onClick={() => setCurrentPage(pg)}
                        className={cn(
                          "w-7 h-7 flex items-center justify-center rounded-xs border text-2xs font-[500] transition-colors",
                          currentPage === pg ? "border-[var(--border-strong)] bg-[var(--hover-bg)] text-[var(--text-primary)] font-[600]" : "border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]"
                        )}
                      >{pg}</button>
                    );
                  })}
                  <button
                    type="button"
                    disabled={currentPage === totalPages}
                    onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                    className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-40 text-2xs font-[500]"
                  >›</button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* ── Preview Drawer ───────────────────────────────────────── */}
      <AppDrawer
        open={previewOpen}
        onClose={() => { setPreviewOpen(false); setPreview(null); }}
        title={t.importPage.drawerTitle}
        subtitle={preview?.erpOrderId}
        width={520}
      >
        <div className="overflow-y-auto h-full p-6" style={{ background: 'var(--surface)' }}>
          {previewLoading ? (
            <div className="flex items-center justify-center py-24">
              <svg className="animate-spin h-8 w-8 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
              </svg>
            </div>
          ) : !preview ? (
            <div className="flex flex-col items-center gap-2 py-24">
              <IconAlertCircle size={32} color="#EF4444" />
              <p className="text-xs font-semibold text-[var(--text-muted)]">{t.importPage.loadingError}</p>
            </div>
          ) : (
            <div className="flex flex-col gap-6">
              {/* Client info */}
              <div className="p-4 rounded-xs" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-xs font-semibold text-[var(--text-muted)]">{t.importPage.previewRefERP}</p>
                    <p className="text-base font-bold font-mono text-[var(--text-primary)]">{preview.erpOrderId}</p>
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-[var(--text-muted)]">{t.importPage.previewCustomer}</p>
                    <p className="text-base font-bold text-[var(--text-primary)]">{preview.customerName}</p>
                  </div>
                </div>
                <div className="h-px bg-[var(--border)] my-4 border-dashed" />
                <div>
                  <p className="text-xs font-semibold text-[var(--text-muted)]">{t.importPage.previewLocation}</p>
                  <div className="flex items-start gap-1 mt-1">
                    <IconMapPin size={12} style={{ color: 'var(--brand)' }} className="mt-0.5 shrink-0" />
                    <p className="text-sm font-bold text-[var(--text-primary)]">{preview.deliveryAddress}</p>
                  </div>
                  <p className="text-xs font-semibold pl-4 text-[var(--text-muted)]">{preview.deliveryCity}</p>
                </div>
              </div>

              {/* Métriques */}
              <div className="grid grid-cols-3 gap-3">
                {[
                  { label: t.importPage.metricsArticles, value: preview.totalQuantity },
                  { label: t.importPage.metricsWeight, value: `${preview.totalWeightKg?.toFixed(2)} KG` },
                  { label: t.importPage.metricsTotal, value: money(preview.totalAmount || 0, preview.currency) },
                ].map((stat, i) => (
                  <div key={i} className="p-3 rounded-xs text-center" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                    <p className="text-xs font-semibold text-[var(--text-muted)]">{stat.label}</p>
                    <p className="text-md font-bold font-mono text-[var(--text-primary)] mt-1">{stat.value}</p>
                  </div>
                ))}
              </div>

              {/* Items list */}
              <div className="flex flex-col gap-2">
                <p className="text-xs font-semibold text-[var(--text-primary)]">{t.importPage.itemsListTitle}</p>
                <div className="rounded-xs overflow-hidden" style={{ border: '1px solid var(--border)' }}>
                  <table className="w-full border-collapse">
                    <thead style={{ background: 'var(--app-bg)' }}>
                      <tr>
                        <th className="text-xs font-semibold text-[var(--text-muted)] py-2 px-3 text-left">{t.importPage.itemsColArticle}</th>
                        <th className="text-xs font-semibold text-[var(--text-muted)] py-2 px-3 text-right">{t.importPage.itemsColQty}</th>
                        <th className="text-xs font-semibold text-[var(--text-muted)] py-2 px-3 text-right">{t.importPage.itemsColPrice}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.items?.map((item, idx) => (
                        <tr key={idx} className="border-t border-[var(--border)]">
                          <td className="px-3 py-2">
                            <p className="text-xs font-semibold text-[var(--text-primary)]">{item.name}</p>
                            <p className="text-2xs text-[var(--text-muted)] font-mono">{item.unitWeightKg?.toFixed(2)} KG/U</p>
                          </td>
                          <td className="px-3 py-2 text-right">
                            <p className="text-xs font-bold font-mono text-[var(--text-primary)]">{item.quantity}</p>
                          </td>
                          <td className="px-3 py-2 text-right">
                            <p className="text-xs font-semibold font-mono text-[var(--text-primary)]">{money(item.unitPrice || 0, preview.currency)}</p>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Action */}
              <div className="flex flex-col gap-3 mt-auto pt-4">
                {preview.existingBackorderId && (
                  <div className="p-3 rounded-xs flex items-center gap-2 bg-[var(--warning-bg)]" style={{ border: '1px solid #FEF08A' }}>
                    <IconAlertCircle size={14} className="text-[var(--warning)]" />
                    <p className="text-xs font-semibold text-[var(--warning)]">{t.importPage.backorderWarning.replace('{backorderId}', String(preview.existingBackorderId))}</p>
                  </div>
                )}
                <button
                  type="button"
                  className={cn(
                    "w-full h-12 flex items-center justify-center gap-2 font-[500] rounded-xs text-base border transition-all",
                    preview.alreadyImported
                      ? "bg-[var(--success)] hover:opacity-90 text-white border-[var(--success)]"
                      : "border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] text-[var(--text-primary)] shadow-sm"
                  )}
                  onClick={() => preview.alreadyImported ? window.open('/deliveries', '_blank') : doImport(preview.erpOrderId)}
                  disabled={importingId === preview.erpOrderId}
                >
                  {importingId === preview.erpOrderId ? (
                    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                    </svg>
                  ) : preview.alreadyImported ? <IconTruck size={18} /> : <IconDownload size={18} />}
                  {preview.alreadyImported ? t.importPage.buttonViewDelivery : t.importPage.buttonImportFlow}
                </button>
              </div>
            </div>
          )}
        </div>
      </AppDrawer>
    </div>
  );
}

export default function ImportErpPage() {
  return (
    <Suspense fallback={
      <div className="flex items-center justify-center h-screen">
        <svg className="animate-spin h-8 w-8" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
        </svg>
      </div>
    }>
      <ImportErpPageContent />
    </Suspense>
  );
}

