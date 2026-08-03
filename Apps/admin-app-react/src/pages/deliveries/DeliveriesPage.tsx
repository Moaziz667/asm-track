import { useEffect, useMemo, useRef, useState, Suspense, type Dispatch, type SetStateAction } from 'react';
import { useNavigate as useRouter, useSearchParams } from 'react-router-dom';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { Delivery, DeliveryStatus } from '@/types';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { AppLoader } from '@/components/AppLoader';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { ExportCsvButton } from '@/components/layout/ExportCsvButton';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import { Button } from '@/components/ui/button';
import { FieldSelect } from '@/components/ui/field';
import { TooltipProvider } from '@/components/ui/tooltip';
import { IconScan, IconX, IconLayoutList } from '@tabler/icons-react';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';
import { useGlobalFilters } from '@/lib/state/global-filters';
import { resolveOrderRef } from '@/lib/utils';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { EmptyState } from '@/components/feedback/EmptyState';
import { useT } from '@/lib/i18n/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import {
  useDeliveries,
  useDeliveryCounts,
  useActiveZones,
  useCancelDelivery,
} from '@/hooks/useDeliveries';
import { useFleetDrivers } from '@/hooks/useVehicles';

import { DELIVERY_COLUMNS, DELIVERY_STATUSES } from './constants';
import { getRowId } from './format';
import { useDepots } from '@/hooks/useDepots';
import { QUICK_VIEWS } from './types';
import type { DeliveryRow, QuickView } from './types';
import { useDeliveryListData } from './useDeliveryListData';
import { DeliveryTableRow, DeliveryMobileCard } from './DeliveryTableRow';
import { useIsMobile } from '@/hooks/use-mobile';
import { PinDropoffModal } from './PinDropoffModal';

function DeliveriesPageContent() {
  const isMobile = useIsMobile();
  const router = useRouter();
  const [searchParams] = useSearchParams();
  const initialSyncRef = useRef(false);
  const locale = useLocaleStore(state => state.locale);
  const t = useT();
  const dateTag = locale === 'fr' ? 'fr-FR' : locale === 'ar' ? 'ar' : 'en-US';
  const getStatusLabel = (status: string) => t.statusLabels[status as DeliveryStatus] || status;
  const { density, setDensity } = useDensity('deliveries', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('deliveries', DELIVERY_COLUMNS);

  const { filters: globalFilters, applyFilters, globalContext } = useGlobalFilters();

  const [query, setQuery] = useState(globalFilters.search);
  // Multi-select filters (arrays). Status/driver seed from the shared store for cross-page continuity;
  // zone starts empty (the store holds a zone *name* but this page filters by zone *id*), and these
  // arrays are not written back to the single-value store (they don't map cleanly).
  const [status, setStatus] = useState<string[]>(globalFilters.status ? [globalFilters.status] : []);
  const [dateFrom, setDateFrom] = useState(globalFilters.dateFrom);
  const [dateTo, setDateTo] = useState(globalFilters.dateTo);
  const [driverId, setDriverId] = useState<string[]>(globalFilters.driver ? [globalFilters.driver] : []);
  const [zoneId, setZoneId] = useState<string[]>([]);
  const [depot, setDepot] = useState<string[]>([]);
  // ?view= opens the table on a quick view. The dashboard's backlog tiles have been linking here
  // with it since they were written — the page just never read the parameter, so every one of
  // them landed on "toutes les livraisons" and the operator had to re-pick the filter they had
  // just clicked. Read once at mount rather than synced in an effect: the chips own this state
  // afterwards, and an effect writing it back would fight them. Validated against the list, so
  // an unknown value falls back instead of leaving the table in a state no chip represents.
  const [quickView, setQuickView] = useState<QuickView>(() => {
    const v = searchParams?.get('view');
    return v && (QUICK_VIEWS as readonly string[]).includes(v) ? (v as QuickView) : 'all';
  });
  const [sortAsc, setSortAsc] = useState(false);
  const [groupByClient, setGroupByClient] = useState(false);
  const [groupByZone, setGroupByZone] = useState(false);
  const [groupByStatus, setGroupByStatus] = useState(false);

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);

  // Pin modal — the whole geo-pinning flow lives in PinDropoffModal; the page only holds the target.
  const [pinTarget, setPinTarget] = useState<DeliveryRow | null>(null);

  // Cancellation state
  const [cancelTarget, setCancelTarget] = useState<DeliveryRow | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [downloadingBl, setDownloadingBl] = useState<Set<string>>(new Set());

  const downloadBl = async (deliveryId: string, ref: string) => {
    setDownloadingBl(prev => new Set(prev).add(deliveryId));
    try {
      const res = await api.get(`/admin/deliveries/${deliveryId}/bon-livraison`, { responseType: 'blob' });
      const url  = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `bl-${ref.replace(/\s+/g, '-').toLowerCase()}.pdf`;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      showErrorToast(null, t.deliveriesPage.downloadError);
    } finally {
      setDownloadingBl(prev => { const s = new Set(prev); s.delete(deliveryId); return s; });
    }
  };

  const { data: drivers = [] } = useFleetDrivers();
  const { data: zones = [] } = useActiveZones();
  const { data: depots = [] } = useDepots();
  const activeDepots = useMemo(() => depots.filter(d => d.isActive), [depots]);

  const queryParams = useMemo(() => {
    const params: Parameters<typeof useDeliveries>[0] = { page, size };
    if (status.length) params.status = status;
    if (dateFrom) params.dateFrom = dateFrom;
    if (dateTo) params.dateTo = dateTo;
    if (driverId.length) params.driverId = driverId;
    if (zoneId.length) params.zoneId = zoneId;
    if (depot.length) params.depot = depot;
    // Every quick view goes to the server. Three of them already did; the other eight were
    // applied to the twenty-five rows already loaded, so "Livrées" meant "the delivered ones on
    // this page" — the chip said 9, the table showed 3, and paging on kept cutting the same
    // unfiltered 77 into slices. The predicates exist: countTally computes the chip totals from
    // these very parameters, so the server could always answer; the list was not asking.
    if (quickView === 'needsPinning') params.unpinned = 'true';
    if (quickView === 'returns') params.kind = ['RETURN_PICKUP'];
    if (quickView === 'priority') params.priority = ['HIGH'];
    if (quickView === 'unassigned') params.assigned = 'false';
    if (quickView === 'overdue') params.bucket = 'OVERDUE';
    if (quickView === 'today') params.bucket = 'TODAY';
    if (quickView === 'future') params.bucket = 'FUTURE';

    // A view that is really a set of statuses. When the operator has also picked statuses by
    // hand, both constraints hold — the narrower answer is the honest one, and silently dropping
    // either would show rows the screen claims to have excluded.
    const viewStatuses: Record<string, string[]> = {
      inTransit: ['IN_TRANSIT', 'AWAITING_HANDOFF'],
      completed: ['DELIVERED'],
      failed: ['FAILED', 'CANCELLED'],
    };
    const fromView = viewStatuses[quickView];
    if (fromView) {
      params.status = status.length ? status.filter(x => fromView.includes(x)) : fromView;
    }
    return params;
  }, [page, size, status, dateFrom, dateTo, driverId, zoneId, depot, quickView]);

  const { data: deliveriesResponse, isLoading: loading, refetch: fetchDeliveries } = useDeliveries(queryParams);

  // Realtime: any delivery lifecycle change refetches the list (debounced) so statuses/SLA stay live.
  const delivRtTimer = useRef<number | null>(null);
  useRealtimeEvent(
    ['delivery.created', 'delivery.scheduled', 'delivery.reassigned', 'delivery.replanned',
     'delivery.picked_up', 'delivery.in_transit', 'delivery.completed', 'delivery.failed',
     'delivery.cancelled', 'delivery.backorder_created', 'delivery.redelivery_scheduled'],
    () => {
      if (delivRtTimer.current != null) return;
      delivRtTimer.current = window.setTimeout(() => { delivRtTimer.current = null; void fetchDeliveries(); }, 1500);
    },
  );
  useEffect(() => () => { if (delivRtTimer.current != null) window.clearTimeout(delivRtTimer.current); }, []);

  const cancelDeliveryMutation = useCancelDelivery();
  const cancelling = cancelDeliveryMutation.isPending;

  const rows = useMemo(() => {
    const content = Array.isArray(deliveriesResponse?.content) ? deliveriesResponse.content : [];
    return content.map((item: Delivery) => ({ ...item, rowId: getRowId(item) } as DeliveryRow)).filter((r: DeliveryRow) => !!r.rowId);
  }, [deliveriesResponse]);

  const totalElements = Number(deliveriesResponse?.totalElements ?? rows.length);
  const totalPages = Math.max(1, Number(deliveriesResponse?.totalPages || 1));

  // Sync with global filters & query param
  useEffect(() => {
    if (!globalContext || initialSyncRef.current) return;
    initialSyncRef.current = true;
    const s = searchParams?.get('search');
    if (s) { setQuery(s); applyFilters({ search: s }); }
    else if (globalFilters.search) { setQuery(globalFilters.search); }
  }, [globalContext, searchParams, applyFilters, globalFilters.search]);

  // Deep-link from the dispatch GPS tab: ?pin={deliveryId} → load the unpinned set and open the
  // pin modal for that delivery so the operator fixes its drop-off coordinates directly here.
  const pinParam = searchParams?.get('pin');
  const pinConsumedRef = useRef(false);
  useEffect(() => { if (pinParam) setQuickView('needsPinning'); }, [pinParam]);
  useEffect(() => {
    if (!pinParam || pinConsumedRef.current) return;
    const row = rows.find((r: DeliveryRow) => r.rowId === pinParam || r.deliveryId === pinParam || r.id === pinParam);
    if (row) { setPinTarget(row); pinConsumedRef.current = true; return; }
    // Fallback: fetch the single delivery by ID if not in the current list
    if (rows.length > 0) {
      api.get(`/admin/deliveries/${pinParam}`)
        .then(res => {
          if (!pinConsumedRef.current && res.data) {
            setPinTarget(res.data as DeliveryRow);
            pinConsumedRef.current = true;
          }
        })
        .catch(() => { /* delivery not found — ignore */ });
    }
  }, [pinParam, rows]);

  // Only the single-value fields (search + date range) stay in two-way sync with the shared store;
  // the multi-select fields (status/driver/zone/depot) are page-local (Option B — no store refactor).
  useEffect(() => {
    if (!globalContext) return;
    if (dateFrom !== globalFilters.dateFrom) setDateFrom(globalFilters.dateFrom);
    if (dateTo !== globalFilters.dateTo) setDateTo(globalFilters.dateTo);
  }, [globalFilters, globalContext]);

  useEffect(() => {
    const hasChanged = query !== globalFilters.search || dateFrom !== globalFilters.dateFrom || dateTo !== globalFilters.dateTo;
    if (hasChanged) {
      applyFilters({ search: query, dateFrom, dateTo });
    }
  }, [query, dateFrom, dateTo, applyFilters]);

  const { filteredRows, quickCounts: pageCounts } = useDeliveryListData(rows, { query, quickView, sortAsc, groupByClient, groupByZone, groupByStatus });

  // Real tallies, across every matching delivery rather than the twenty-five on screen. The
  // endpoint was written for exactly this and the page had been counting its own rows instead,
  // so "Toutes les livraisons 25" was reporting the page size and calling it a total.
  // Single-valued filters only: the endpoint takes one driver and one zone, so a multi-select
  // is left out rather than silently narrowed to its first entry.
  const { data: serverCounts } = useDeliveryCounts({
    driverId: driverId.length === 1 ? driverId[0] : undefined,
    zoneId: zoneId.length === 1 ? zoneId[0] : undefined,
    date: dateFrom && dateFrom === dateTo ? dateFrom : undefined,
    q: query || undefined,
  });
  // Page-local counts stand in until the tallies land, so the chips never flash empty.
  const quickCounts = useMemo(
    () => ({ ...pageCounts, ...(serverCounts ?? {}) }) as typeof pageCounts,
    [pageCounts, serverCounts],
  );

  const openRoute = (item: DeliveryRow) => {
    if (item.routeId) { router(`/routes/${item.routeId}?deliveryId=${item.rowId}`); return; }
    router(`/routes?deliveryId=${item.rowId}`);
  };

  const runCancel = async () => {
    if (!cancelTarget || !cancelReason.trim()) return;
    try {
      await cancelDeliveryMutation.mutateAsync({ deliveryId: cancelTarget.rowId, reason: cancelReason.trim() });
      showSuccessToast(t.deliveriesPage.deliveryCancelled);
      setCancelTarget(null);
      setCancelReason('');
    } catch (err) {
      showErrorToast(err);
    }
  };

  // ── PageFilterBar config ───────────────────────────────────────────────────
  const filterAttributes = [
    { key: 'status', label: t.deliveriesPage.statusHeader, multi: true, options: DELIVERY_STATUSES.map(s => ({ value: s.value, label: getStatusLabel(s.value) })) },
    { key: 'driver', label: t.deliveriesPage.driverHeader, multi: true, options: drivers.map(d => ({ value: d.id, label: d.name })) },
    { key: 'zone',   label: t.deliveriesPage.zoneHeader,   multi: true, options: zones.map(z => ({ value: z.id, label: z.name })) },
    { key: 'depot',  label: t.common?.depot ?? 'Dépôt',    multi: true, options: activeDepots.map(d => ({ value: d.id, label: d.name })) },
  ];
  const activeFiltersState: Record<string, string | string[]> = {
    ...(status.length   ? { status } : {}),
    ...(driverId.length ? { driver: driverId } : {}),
    ...(zoneId.length   ? { zone: zoneId } : {}),
    ...(depot.length    ? { depot } : {}),
  };
  const toggle = (setter: Dispatch<SetStateAction<string[]>>, arr: string[], v: string) =>
    setter(arr.includes(v) ? arr.filter(x => x !== v) : [...arr, v]);
  const handleDeliveryFilterChange = (key: string, value: string | null) => {
    setPage(0);
    if (value === null) {
      if (key === 'status') setStatus([]);
      else if (key === 'driver') setDriverId([]);
      else if (key === 'zone') setZoneId([]);
      else if (key === 'depot') setDepot([]);
      return;
    }
    if (key === 'status') toggle(setStatus, status, value);
    else if (key === 'driver') toggle(setDriverId, driverId, value);
    else if (key === 'zone')   toggle(setZoneId, zoneId, value);
    else if (key === 'depot')  toggle(setDepot, depot, value);
  };
  const quickFilterList = [
    { value: 'all',         label: t.deliveriesPage.totalFlow,           count: quickCounts.all },
    { value: 'overdue',     label: t.deliveriesPage.quickViewOverdue,     count: quickCounts.overdue },
    { value: 'today',       label: t.deliveriesPage.quickViewToday,       count: quickCounts.today },
    { value: 'future',      label: t.deliveriesPage.quickViewFuture,      count: quickCounts.future },
    { value: 'needsPinning',label: t.deliveriesPage.quickViewNeedsPinning,count: quickCounts.needsPinning },
    { value: 'priority',    label: t.deliveryPage.priorityHigh,             count: quickCounts.priority },
    { value: 'unassigned',  label: t.deliveriesPage.quickViewUnassigned,  count: quickCounts.unassigned },
    { value: 'inTransit',   label: t.deliveriesPage.quickViewInTransit,   count: quickCounts.inTransit },
    { value: 'completed',   label: t.deliveriesPage.quickViewCompleted,   count: quickCounts.completed },
    { value: 'failed',      label: t.deliveriesPage.quickViewFailed,      count: quickCounts.failed },
    { value: 'returns',     label: t.deliveriesPage.quickViewReturns,     count: quickCounts.returns },
  ];

  /**
   * The column names, translated.
   *
   * DELIVERY_COLUMNS carries French literals — fine as identifiers, wrong on screen. The table
   * headers already went through a translation map; the show/hide menu printed the raw label, so
   * an English or Arabic session got "Référence" and "Client / Adresse" in its column picker.
   * One map now feeds both, and a column added without a translation shows its id rather than
   * silently shipping French.
   */
  const columnLabels: Record<string, string> = useMemo(() => ({
    ref: t.deliveriesPage.refHeader,
    client: `${t.deliveriesPage.clientHeader} · ${t.deliveriesPage.addressHeader}`,
    scheduled: t.deliveriesPage.scheduledHeader,
    status: t.deliveriesPage.statusHeader,
    driver: t.deliveriesPage.driverHeader,
    zone: t.deliveriesPage.zoneHeader,
  }), [t]);
  const translatedColumns = useMemo(
    () => orderedColumns.map(c => ({ ...c, label: columnLabels[c.id] ?? c.id })),
    [orderedColumns, columnLabels],
  );

  const headerSort = (key: 'ref' | 'client' | 'status' | 'zone') => {
    if (key === 'ref') setSortAsc(v => !v);
    if (key === 'client') setGroupByClient(v => !v);
    if (key === 'status') setGroupByStatus(v => !v);
    if (key === 'zone') setGroupByZone(v => !v);
  };
  const groupActive: Record<string, boolean> = { client: groupByClient, status: groupByStatus, zone: groupByZone };

  return (
    <TooltipProvider>
      <div className="h-auto lg:h-[calc(100dvh-56px)] overflow-visible lg:overflow-hidden bg-[var(--app-bg)] flex flex-col">
        <PageFilterBar
          search={query}
          onSearch={v => { setQuery(v); setPage(0); }}
          searchPlaceholder={t.deliveriesPage.searchPlaceholder}
          attributes={filterAttributes}
          activeFilters={activeFiltersState}
          onFilterChange={handleDeliveryFilterChange}
          onRefresh={() => fetchDeliveries()}
          refreshing={loading}
          quickFilters={quickFilterList}
          activeQuickFilter={quickView}
          onQuickFilterChange={v => { setQuickView(v as QuickView); setPage(0); }}
          extraActions={
            <>
            {/* Planifié De/A range — same inline picker pattern as the Audit logs page. */}
            <div className="flex items-center gap-1.5 shrink-0">
              <DatePickerPopover value={dateFrom || null} onChange={v => { setDateFrom(v ?? ''); setQuickView('all'); setPage(0); }} placeholder={t.dispatchDeskPage.dateFrom} />
              <span className="text-xs text-[var(--text-muted)]">→</span>
              <DatePickerPopover value={dateTo || null} onChange={v => { setDateTo(v ?? ''); setQuickView('all'); setPage(0); }} placeholder={t.dispatchDeskPage.dateTo} />
              {(dateFrom || dateTo) && (
                <button
                  type="button"
                  onClick={() => { setDateFrom(''); setDateTo(''); setPage(0); }}
                  className="hover:opacity-70 transition-opacity shrink-0"
                  style={{ color: 'var(--text-muted)' }}
                  aria-label={t.common?.effacer ?? 'Clear'}
                >
                  <IconX size={13} />
                </button>
              )}
            </div>
            <ExportCsvButton
              baseName={t.deliveriesPage?.pageTitleBrand ?? 'deliveries'}
              rows={filteredRows}
              columns={[
                { header: t.common?.reference ?? 'Reference', accessor: (r: DeliveryRow) => resolveOrderRef(r) },
                { header: t.common?.client ?? 'Client', accessor: (r: DeliveryRow) => r.clientName },
                { header: t.common?.adresse ?? 'Address', accessor: (r: DeliveryRow) => r.dropoffAddress },
                { header: t.common?.zone ?? 'Zone', accessor: (r: DeliveryRow) => r.zoneName },
                { header: t.common?.chauffeur ?? 'Driver', accessor: (r: DeliveryRow) => r.driverName },
                { header: t.common?.statut ?? 'Status', accessor: (r: DeliveryRow) => r.status },
                { header: 'SLA', accessor: (r: DeliveryRow) => r.slaHealth },
                { header: t.deliveriesPage?.scheduledHeader ?? 'Scheduled', accessor: (r: DeliveryRow) => r.scheduledAt },
                { header: t.common?.montant ?? 'Amount', accessor: (r: DeliveryRow) => r.totalAmount },
                { header: t.common?.date ?? 'Created', accessor: (r: DeliveryRow) => r.createdAt },
              ]}
            />
            </>
          }
        />

        <div className="flex flex-1 min-h-0 overflow-hidden">
          <div className="flex flex-col flex-1 min-w-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
            {/* Internal Toolbar */}
            <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
              <div className="flex items-center gap-8">
                <span className="text-base font-[600] text-[var(--text-primary)]">
                  {t.deliveriesPage.displayLabel} <span className="font-mono text-[var(--brand)]">{quickView.toUpperCase()}</span>
                </span>
                <span className="text-xs font-[500] text-[var(--text-muted)]">
                  {totalElements} {t.deliveriesPage.entityDetected}
                </span>
              </div>

              <div className="flex items-center gap-2">
                <FieldSelect
                  value={String(size)}
                  onChange={(e) => { setPage(0); setSize(Number(e.currentTarget.value)); }}
                  options={['25', '50', '100'].map(s => ({ value: s, label: `${s} ${t.deliveriesPage.pageSize}` }))}
                  /* py-0 because the shared input padding (py-2) plus a line box is about 36px of
                     content, and h-7 is 28px: the label was being clipped top and bottom by a box
                     too small to hold it. The extra width is for "100 / page" to clear the chevron,
                     which reserves 32px on the inline end. */
                  className="h-7 py-0 text-xs font-medium w-[120px]"
                />
                <DisplaySettingsDropdown
                  columns={translatedColumns}
                  visibleIds={visibleIds}
                  onToggle={toggleColumn}
                  onReorder={moveColumn}
                  onReset={resetColumns}
                  density={density}
                  onDensityChange={setDensity}
                />
              </div>
            </div>

            {/* Full-Bleed Table / Mobile Card List */}
            <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
              {isMobile ? (
                <div className="flex flex-col gap-3 p-4">
                  {loading ? (
                    Array.from({ length: 5 }).map((_, i) => (
                      <div key={i} className="p-4 rounded-md border border-[var(--border)] bg-[var(--surface)] animate-pulse h-32" />
                    ))
                  ) : filteredRows.length === 0 ? (
                    <EmptyState icon={<IconScan size={32} />} message={t.empty.deliveries} />
                  ) : (
                    filteredRows.map((item: DeliveryRow) => (
                      <DeliveryMobileCard
                        key={item.rowId}
                        item={item}
                        dateTag={dateTag}
                        downloadingBl={downloadingBl}
                        t={t}
                        onRowClick={(rowId) => router(`/deliveries/${rowId}`)}
                        onPin={(it) => setPinTarget(it)}
                        onDownloadBl={downloadBl}
                        onOpenRoute={openRoute}
                        onCancel={(it) => { setCancelTarget(it); setCancelReason(''); }}
                      />
                    ))
                  )}
                </div>
              ) : (
                <div className="min-w-[1000px] lg:min-w-0">
                  <table className="w-full border-collapse">
                    <thead className="sticky top-0 z-20 border-b border-[var(--border)]" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                      <tr>
                        <th className="w-2 px-0"></th>
                        {orderedColumns.map(col => {
                          if (!visibleIds.has(col.id)) return null;
                          const labelMap = columnLabels;
                          const sortable = col.id === 'ref' || col.id === 'client' || col.id === 'status' || col.id === 'zone';
                          const align = col.id === 'driver' || col.id === 'zone' ? 'text-center' : 'text-left';
                          return (
                            <th key={col.id} className={`h-10 px-6 ${align} text-xs font-[450] text-[var(--text-muted)]`}>
                              {sortable ? (
                                <button onClick={() => headerSort(col.id as 'ref' | 'client' | 'status' | 'zone')} className="inline-flex items-center gap-1 hover:text-[var(--text-strong)] transition-colors cursor-pointer">
                                  {labelMap[col.id]}
                                  {/* Only Référence sorts. Client, Statut and Zone toggle a
                                      grouping, and they were drawing a sort arrow whose direction
                                      came from sortAsc — the reference column's state — so
                                      grouping by client moved an arrow describing another column.
                                      A grouping is on or off; it has no direction to show. */}
                                  {col.id === 'ref' ? (
                                    <span className="text-2xs">{sortAsc ? '▲' : '▼'}</span>
                                  ) : (
                                    <IconLayoutList
                                      size={12}
                                      style={{
                                        color: groupActive[col.id] ? 'var(--brand)' : 'var(--text-soft)',
                                        opacity: groupActive[col.id] ? 1 : 0.5,
                                      }}
                                    />
                                  )}
                                </button>
                              ) : labelMap[col.id]}
                            </th>
                          );
                        })}
                        <th className="h-10 px-6 text-right text-xs font-[450] text-[var(--text-muted)]">{t.deliveriesPage.actionsHeader}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--border)] bg-[var(--app-bg)]">
                      {loading ? (
                        Array.from({ length: 15 }).map((_, i) => (
                          <tr key={i}>
                            <td colSpan={visibleIds.size + 2} className="px-6 py-6 text-center"><AppLoader size="sm" /></td>
                          </tr>
                        ))
                      ) : filteredRows.length === 0 ? (
                        <tr>
                          <td colSpan={visibleIds.size + 2}>
                            <EmptyState icon={<IconScan size={32} />} message={t.empty.deliveries} />
                          </td>
                        </tr>
                      ) : (
                        filteredRows.map((item: DeliveryRow) => (
                          <DeliveryTableRow
                            key={item.rowId}
                            item={item}
                            density={density}
                            orderedColumns={orderedColumns}
                            visibleIds={visibleIds}
                            dateTag={dateTag}
                            downloadingBl={downloadingBl}
                            t={t}
                            onRowClick={(rowId) => router(`/deliveries/${rowId}`)}
                            onPin={(it) => setPinTarget(it)}
                            onDownloadBl={downloadBl}
                            onOpenRoute={openRoute}
                            onCancel={(it) => { setCancelTarget(it); setCancelReason(''); }}
                          />
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* Pagination footer */}
            {(rows.length >= size || page > 0 || totalPages > 1) && (
              <div className="flex items-center justify-between px-6 py-2.5 border-t border-[var(--border)] bg-[var(--app-bg)] shrink-0">
                {/* What is on screen out of what matched, then the page. "Page 1 · 77 résultats"
                    gave a position and a total and never said how many of them you were looking
                    at — the one number the reader is checking. */}
                <span className="text-xs text-[var(--text-muted)]">
                  <span className="font-mono tabular-nums text-[var(--text-secondary)]">
                    {rows.length}/{totalElements}
                  </span>
                  {' '}{t.deliveriesPage.resultsLabel}
                  {totalPages > 1 && ` · ${t.deliveriesPage.pageLabel} ${page + 1}/${totalPages}`}
                </span>
                <div className="flex items-center gap-1.5">
                  <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)} className="h-7 px-3 text-xs font-[700]">
                    {t.deliveriesPage.prevButton}
                  </Button>
                  <Button variant="outline" size="sm" disabled={rows.length < size} onClick={() => setPage(p => p + 1)} className="h-7 px-3 text-xs font-[700]">
                    {t.deliveriesPage.nextButton}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </div>

        <PinDropoffModal
          target={pinTarget}
          onClose={() => setPinTarget(null)}
          onPinned={(routeId) => { if (routeId) safeStorage.setItem(`route_needs_refresh_${routeId}`, 'true'); }}
        />

        <ConfirmModal
          open={cancelTarget !== null}
          title={t.deliveriesPage.cancelModalTitle}
          description={t.deliveriesPage.cancelModalDescription}
          variant="danger"
          reasonLabel={t.deliveriesPage.cancelModalLabel}
          reason={cancelReason}
          onReasonChange={setCancelReason}
          confirmLabel={t.deliveriesPage.cancelButtonConfirm}
          cancelLabel={t.deliveriesPage.cancelButtonKeep}
          loading={cancelling}
          onConfirm={() => void runCancel()}
          onCancel={() => { setCancelTarget(null); setCancelReason(''); }}
        />
      </div>
    </TooltipProvider>
  );
}

export default function DeliveriesPage() {
  const t = useT();
  return (
    <Suspense fallback={<AppLoader centered height="100vh" size="xl" label={t.deliveriesPage.pageLoading} />}>
      <DeliveriesPageContent />
    </Suspense>
  );
}
