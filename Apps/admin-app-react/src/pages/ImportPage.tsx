
import { useCallback, useEffect, useMemo, useState, Suspense } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
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
  IconClock
} from '@tabler/icons-react';
import { cn, formatMoney } from '@/lib/utils';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { AppDrawer } from '@/components/overlays/AppDrawer';
import { Button } from '@/components/ui/button';
import { useT } from '@/lib/LocaleContext';

const ITEMS_PER_PAGE = 25;

const money = (value: number | null | undefined, currency = 'TND') => formatMoney(value, currency);

function formatDate(value: string | null | undefined) {
  return formatDateTime(value);
}

function ImportErpPageContent() {
  const t = useT();
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
  const [activeTab, setActiveTab] = useState<'all' | 'ready' | 'done'>('all');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkImporting, setBulkImporting] = useState(false);

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
      const res = await api.get(`/api/admin/erp/pending-orders/${encodeURIComponent(erpOrderId)}`);
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
      await api.post(`/api/admin/erp/import-order/${encodeURIComponent(erpOrderId)}`);
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

  const [mobileTab, setMobileTab] = useState<'filters' | 'list'>('list');

  const pillDefs = [
    { id: 'all', label: t.importPage.pillAll, count: stats.total, icon: <IconPackage size={14} />, color: 'var(--text-muted)' },
    { id: 'ready', label: t.importPage.pillReady, count: stats.importable, icon: <IconDownload size={14} />, color: '#5E6AD2' },
    { id: 'done', label: t.importPage.pillDone, count: stats.alreadyImported, icon: <IconCheck size={14} />, color: '#4CAF82' },
  ];

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Mobile Tab Bar */}
      <div className="lg:hidden flex shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        {([['filters', t.importPage.tabFilters], ['list', t.importPage.tabOrders]] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setMobileTab(tab)}
            className={`flex-1 h-10 text-[11px] font-semibold transition-colors ${
              mobileTab === tab ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]' : 'text-[var(--text-muted)]'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 gap-0" style={{ minHeight: 0, overflow: 'hidden' }}>
        {/* ── Rail Gauche ────────────────── */}
        <div className={`lg:w-[280px] border-r border-[var(--border)] bg-[var(--surface)] shrink-0 flex flex-col ${mobileTab === 'filters' ? 'flex w-full' : 'hidden lg:flex'}`}>
          {/* Header */}
          <div className="p-5 border-b border-[var(--border)]">
            <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.importPage.pageSubtitle}</span>
            <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
              {t.importPage.pageTitle} <span className="text-[var(--brand)]">{t.importPage.pageTitleBrand}</span>
            </h1>
          </div>

          {/* Search & Sync */}
          <div className="flex flex-col gap-3 p-5 border-b border-[var(--border)]">
            <div className="relative">
              <IconSearch size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
              <input
                className="w-full h-9 pl-9 pr-3 text-[11px] rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                placeholder={t.importPage.searchPlaceholder}
                value={query}
                onChange={(e) => setQuery(e.currentTarget.value)}
              />
            </div>
            <button
              className="w-full h-9 flex items-center justify-center gap-2 font-semibold text-[11px] rounded-[2px] bg-[var(--brand)] text-white hover:opacity-90 transition-opacity"
              onClick={() => loadPendingOrders(true, true)}
              disabled={loading || refreshing}
            >
              <IconRefresh size={14} className={refreshing ? 'animate-spin' : ''} />
              {t.importPage.syncButton}
            </button>
          </div>

          {/* Filter pills */}
          <div className="overflow-y-auto flex-1">
            <div className="flex flex-col p-2.5">
              {pillDefs.map((pill) => (
                <button
                  key={pill.id}
                  onClick={() => setActiveTab(pill.id as any)}
                  className={cn(
                    "px-4 py-3 rounded-[2px] transition-all flex items-center justify-between group text-left",
                    activeTab === pill.id
                      ? "bg-[var(--app-bg)] border-l-2 border-[var(--brand)]"
                      : "hover:bg-[var(--hover-bg)] border-l-2 border-transparent"
                  )}
                >
                  <div className="flex items-center gap-3">
                    <span style={{ color: activeTab === pill.id ? 'var(--brand)' : pill.color }}>{pill.icon}</span>
                    <span className={cn("text-[11px] font-semibold", activeTab === pill.id ? "text-[var(--text-primary)]" : "text-[var(--text-muted)]")}>
                      {pill.label}
                    </span>
                  </div>
                  <span className="text-[10px] font-bold font-mono text-[var(--text-muted)]">{pill.count}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Footer */}
          <div className="p-5 border-t border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
            <p className="text-[11px] font-semibold text-[var(--text-muted)] italic leading-relaxed">
              {t.importPage.lastSync}<br />
              {formatDateTime(new Date().toISOString())}
            </p>
          </div>
        </div>

        {/* ── Main Table ────────────────────────── */}
        <div className={`flex-1 flex flex-col bg-[var(--surface)] overflow-hidden min-w-0 ${mobileTab === 'list' ? 'flex' : 'hidden lg:flex'}`}>
          <div className="overflow-y-auto flex-1">
            <table className="border-collapse min-w-[1000px] w-full">
              <thead className="sticky top-0 z-10" style={{ background: 'var(--app-bg)' }}>
                <tr className="border-b border-[var(--border)]">
                  <th className="w-[8px] p-0"></th>
                  <th className="w-10 py-4 pl-4 text-left">
                    <input type="checkbox" checked={allPageSelected} onChange={toggleSelectAll}
                      className="w-3.5 h-3.5 cursor-pointer accent-[var(--brand)]" />
                  </th>
                  <th className="text-[11px] font-semibold text-[var(--text-muted)] py-4 text-left px-3">{t.importPage.headerReference}</th>
                  <th className="text-[11px] font-semibold text-[var(--text-muted)] py-4 text-left px-3">{t.importPage.headerCustomer}</th>
                  <th className="text-[11px] font-semibold text-[var(--text-muted)] py-4 text-left px-3">{t.importPage.headerDestination}</th>
                  <th className="text-[11px] font-semibold text-[var(--text-muted)] py-4 text-right px-3">{t.importPage.headerAmount}</th>
                  <th className="text-[11px] font-semibold text-[var(--text-muted)] py-4 text-left px-3">{t.importPage.headerStatus}</th>
                  <th className="text-[11px] font-semibold text-[var(--text-muted)] py-4 text-right px-3">{t.importPage.headerActions}</th>
                </tr>
              </thead>
              <tbody>
                {loading && !refreshing ? (
                  Array.from({ length: 15 }).map((_, i) => (
                    <tr key={i} className="border-b border-[var(--border)] animate-pulse">
                      <td colSpan={8} className="py-4 px-3">
                        <div className="h-3 rounded-full w-3/4 mx-auto" style={{ background: 'var(--hover-bg)' }} />
                      </td>
                    </tr>
                  ))
                ) : paginatedRows.length === 0 ? (
                  <tr>
                    <td colSpan={8}>
                      <div className="flex flex-col items-center gap-2 py-20">
                        <IconPackage size={32} strokeWidth={1.5} className="text-[var(--border)]" />
                        <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.importPage.emptyState}</p>
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
                          <div className="w-[3px] h-[14px] rounded-r-[1px]"
                            style={{ backgroundColor: isImported ? '#4CAF82' : '#5E6AD2' }} />
                        </td>
                        {/* Checkbox */}
                        <td className="pl-4 py-3">
                          {!isImported && (
                            <input type="checkbox"
                              checked={selectedIds.has(row.erpOrderId)}
                              onChange={() => toggleSelect(row.erpOrderId)}
                              className="w-3.5 h-3.5 accent-[var(--brand)] cursor-pointer" />
                          )}
                        </td>
                        <td className="px-3 py-3">
                          <div>
                            <p className="text-[11px] font-bold font-mono text-[var(--text-primary)] tracking-tight">{row.erpOrderId}</p>
                            {row.externalRef && (
                              <p className="text-[11px] font-semibold text-[var(--text-muted)]">REF: {row.externalRef}</p>
                            )}
                          </div>
                        </td>
                        <td className="px-3 py-3">
                          <div>
                            <p className="text-[11px] font-semibold text-[var(--text-primary)]">{row.customerName}</p>
                            <div className="flex items-center gap-1">
                              <IconPhone size={10} className="text-[var(--text-muted)]" />
                              <p className="text-[10px] font-medium text-[var(--text-muted)]">{row.customerPhone}</p>
                            </div>
                          </div>
                        </td>
                        <td className="px-3 py-3">
                          <div className="max-w-[220px]">
                            <p className="text-[11px] font-medium text-[var(--text-primary)] truncate">{row.deliveryAddress}</p>
                            <p className="text-[11px] font-semibold text-[var(--text-muted)]">{row.deliveryCity}</p>
                          </div>
                        </td>
                        <td className="px-3 py-3 text-right">
                          <p className="text-[11px] font-bold font-mono text-[var(--text-primary)] tabular-nums">{money(row.totalAmount, row.currency)}</p>
                        </td>
                        <td className="px-3 py-3">
                          <div className="flex flex-col gap-1">
                            <div className="flex items-center gap-1">
                              <IconCalendarClock size={12} className="text-[var(--text-muted)]" />
                              <p className="text-[10px] font-semibold text-[var(--text-primary)]">{formatDate(row.scheduledAt)}</p>
                            </div>
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
                        <td className="px-3 py-3">
                          <div className="flex gap-2 justify-end">
                            <button
                              type="button"
                              title={t.importPage.tooltipDetails}
                              className="w-7 h-7 flex items-center justify-center rounded-[2px] border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
                              onClick={() => openPreview(row.erpOrderId)}
                            >
                              <IconEye size={14} />
                            </button>
                            {isImported ? (
                              <button
                                type="button"
                                className="h-7 px-3 text-[11px] font-semibold rounded-[2px] bg-[#4CAF82] hover:opacity-90 text-white transition-opacity border-none"
                                onClick={() => window.open('/deliveries', '_blank')}
                              >
                                {t.importPage.buttonView}
                              </button>
                            ) : (
                              <button
                                type="button"
                                className={cn(
                                  "h-7 px-3 text-[11px] font-[500] rounded-[2px] border transition-all",
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
              <span className="text-[11px] font-semibold text-[var(--surface)] flex-1">
                {selectedIds.size} {t.importPage.selectedMessage.replace('{plural}', selectedIds.size > 1 ? 's' : '')}
              </span>
              <button
                type="button"
                onClick={() => setSelectedIds(new Set())}
                className="text-[11px] font-semibold text-[var(--surface)] opacity-60 hover:opacity-100 transition-opacity"
              >
                {t.importPage.deselect}
              </button>
              <button
                type="button"
                className="flex items-center gap-1 h-[30px] px-4 bg-[var(--brand)] hover:opacity-90 text-white font-semibold text-[11px] rounded-[2px] transition-opacity"
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
              <p className="text-[11px] font-semibold text-[var(--text-muted)]">
                {t.importPage.showing.replace('{showing}', paginatedRows.length.toString()).replace('{total}', filteredRows.length.toString())}
              </p>
              {totalPages > 1 && (
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    disabled={currentPage === 1}
                    onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                    className="w-7 h-7 flex items-center justify-center rounded-[2px] border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-40 text-[10px] font-[500]"
                  >‹</button>
                  {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
                    const pg = i + 1;
                    return (
                      <button
                        key={pg}
                        type="button"
                        onClick={() => setCurrentPage(pg)}
                        className={cn(
                          "w-7 h-7 flex items-center justify-center rounded-[2px] border text-[10px] font-[500] transition-colors",
                          currentPage === pg ? "border-[var(--border-strong)] bg-[var(--hover-bg)] text-[var(--text-primary)] font-[600]" : "border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]"
                        )}
                      >{pg}</button>
                    );
                  })}
                  <button
                    type="button"
                    disabled={currentPage === totalPages}
                    onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                    className="w-7 h-7 flex items-center justify-center rounded-[2px] border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-40 text-[10px] font-[500]"
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
              <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.importPage.loadingError}</p>
            </div>
          ) : (
            <div className="flex flex-col gap-6">
              {/* Client info */}
              <div className="p-4 rounded-[2px]" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.importPage.previewRefERP}</p>
                    <p className="text-[13px] font-bold font-mono text-[var(--text-primary)]">{preview.erpOrderId}</p>
                  </div>
                  <div>
                    <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.importPage.previewCustomer}</p>
                    <p className="text-[13px] font-bold text-[var(--text-primary)]">{preview.customerName}</p>
                  </div>
                </div>
                <div className="h-px bg-[var(--border)] my-4 border-dashed" />
                <div>
                  <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.importPage.previewLocation}</p>
                  <div className="flex items-start gap-1 mt-1">
                    <IconMapPin size={12} style={{ color: 'var(--brand)' }} className="mt-0.5 shrink-0" />
                    <p className="text-[12px] font-bold text-[var(--text-primary)]">{preview.deliveryAddress}</p>
                  </div>
                  <p className="text-[11px] font-semibold pl-4 text-[var(--text-muted)]">{preview.deliveryCity}</p>
                </div>
              </div>

              {/* Métriques */}
              <div className="grid grid-cols-3 gap-3">
                {[
                  { label: t.importPage.metricsArticles, value: preview.totalQuantity },
                  { label: t.importPage.metricsWeight, value: `${preview.totalWeightKg?.toFixed(2)} KG` },
                  { label: t.importPage.metricsTotal, value: money(preview.totalAmount || 0, preview.currency) },
                ].map((stat, i) => (
                  <div key={i} className="p-3 rounded-[2px] text-center" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                    <p className="text-[11px] font-semibold text-[var(--text-muted)]">{stat.label}</p>
                    <p className="text-[14px] font-bold font-mono text-[var(--text-primary)] mt-1">{stat.value}</p>
                  </div>
                ))}
              </div>

              {/* Items list */}
              <div className="flex flex-col gap-2">
                <p className="text-[11px] font-semibold text-[var(--text-primary)]">{t.importPage.itemsListTitle}</p>
                <div className="rounded-[2px] overflow-hidden" style={{ border: '1px solid var(--border)' }}>
                  <table className="w-full border-collapse">
                    <thead style={{ background: 'var(--app-bg)' }}>
                      <tr>
                        <th className="text-[11px] font-semibold text-[var(--text-muted)] py-2 px-3 text-left">{t.importPage.itemsColArticle}</th>
                        <th className="text-[11px] font-semibold text-[var(--text-muted)] py-2 px-3 text-right">{t.importPage.itemsColQty}</th>
                        <th className="text-[11px] font-semibold text-[var(--text-muted)] py-2 px-3 text-right">{t.importPage.itemsColPrice}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.items?.map((item, idx) => (
                        <tr key={idx} className="border-t border-[var(--border)]">
                          <td className="px-3 py-2">
                            <p className="text-[11px] font-semibold text-[var(--text-primary)]">{item.name}</p>
                            <p className="text-[9px] text-[var(--text-muted)] font-mono">{item.unitWeightKg?.toFixed(2)} KG/U</p>
                          </td>
                          <td className="px-3 py-2 text-right">
                            <p className="text-[11px] font-bold font-mono text-[var(--text-primary)]">{item.quantity}</p>
                          </td>
                          <td className="px-3 py-2 text-right">
                            <p className="text-[11px] font-semibold font-mono text-[var(--text-primary)]">{money(item.unitPrice || 0, preview.currency)}</p>
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
                  <div className="p-3 rounded-[2px] flex items-center gap-2 bg-[#FEFCE8]" style={{ border: '1px solid #FEF08A' }}>
                    <IconAlertCircle size={14} className="text-[#A16207]" />
                    <p className="text-[11px] font-semibold text-[#A16207]">{t.importPage.backorderWarning.replace('{backorderId}', String(preview.existingBackorderId))}</p>
                  </div>
                )}
                <button
                  type="button"
                  className={cn(
                    "w-full h-12 flex items-center justify-center gap-2 font-[500] rounded-[2px] text-[13px] border transition-all",
                    preview.alreadyImported
                      ? "bg-[#4CAF82] hover:opacity-90 text-white border-[#4CAF82]"
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

