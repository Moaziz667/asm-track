import { useEffect, useMemo, useRef, useState, Suspense } from 'react';
import { useNavigate as useRouter, useSearchParams } from 'react-router-dom';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { Delivery, DeliveryStatus } from '@/types';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
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
import { IconScan } from '@tabler/icons-react';
import { useGlobalFilters } from '@/lib/global-filters';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { resolveOrderRef } from '@/lib/utils';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { EmptyState } from '@/components/feedback/EmptyState';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import {
  useDeliveries,
  useActiveZones,
  useCancelDelivery,
} from '@/hooks/useDeliveries';
import { useFleetDrivers } from '@/hooks/useVehicles';

import { DELIVERY_COLUMNS, DELIVERY_STATUSES } from './deliveries/constants';
import { getRowId, isUuid } from './deliveries/helpers';
import type { DeliveryRow, QuickView } from './deliveries/types';
import { useDeliveryListData } from './deliveries/useDeliveryListData';
import { DeliveryTableRow, DeliveryMobileCard } from './deliveries/DeliveryTableRow';
import { useIsMobile } from '@/hooks/use-mobile';
import { PinDropoffModal } from './deliveries/PinDropoffModal';

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

  useEffect(() => { const r = getCurrentRole(); if (r !== 'UNKNOWN' && !canDispatch(r)) { router('/dashboard', { replace: true }); } }, [router]);
  const { filters: globalFilters, applyFilters, globalContext } = useGlobalFilters();

  const [query, setQuery] = useState(globalFilters.search);
  const [status, setStatus] = useState<string>(globalFilters.status || '');
  const [date, setDate] = useState(globalFilters.dateFrom);
  const [driverId, setDriverId] = useState(globalFilters.driver);
  const [zoneId, setZoneId] = useState(globalFilters.zone);
  const [quickView, setQuickView] = useState<QuickView>('all');
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
      const res = await api.get(`/api/admin/deliveries/${deliveryId}/bon-livraison`, { responseType: 'blob' });
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

  const effectiveZoneId = useMemo(() => {
    const raw = (zoneId ?? '').trim();
    if (!raw || isUuid(raw)) return raw;
    const byName = zones.find((z) => z.name?.trim().toLowerCase() === raw.toLowerCase());
    return byName?.id ?? '';
  }, [zoneId, zones]);

  const queryParams = useMemo(() => {
    const params: any = { page, size };
    if (status) params.status = status;
    if (date) params.date = date;
    if (driverId) params.driverId = driverId;
    if (effectiveZoneId) params.zoneId = effectiveZoneId;
    if (quickView === 'needsPinning') params.unpinned = 'true';
    return params;
  }, [page, size, status, date, driverId, effectiveZoneId, quickView]);

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
    const row = rows.find((r: DeliveryRow) => r.rowId === pinParam || (r as any).deliveryId === pinParam || (r as any).id === pinParam);
    if (row) { setPinTarget(row); pinConsumedRef.current = true; }
  }, [pinParam, rows]);

  useEffect(() => {
    if (!globalContext) return;
    if (status !== (globalFilters.status || '')) setStatus(globalFilters.status || '');
    if (date !== globalFilters.dateFrom) setDate(globalFilters.dateFrom);
    if (driverId !== globalFilters.driver) setDriverId(globalFilters.driver);
    if (zoneId !== globalFilters.zone) setZoneId(globalFilters.zone);
  }, [globalFilters, globalContext]);

  useEffect(() => {
    const hasChanged = query !== globalFilters.search || status !== (globalFilters.status || '') || date !== globalFilters.dateFrom || driverId !== globalFilters.driver || zoneId !== globalFilters.zone;
    if (hasChanged) {
      applyFilters({ search: query, status, dateFrom: date, driver: driverId, zone: zoneId, route: globalFilters.route, dateTo: globalFilters.dateTo });
    }
  }, [query, status, date, driverId, zoneId, applyFilters]);

  const { filteredRows, quickCounts } = useDeliveryListData(rows, { query, quickView, sortAsc, groupByClient, groupByZone, groupByStatus });

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
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    }
  };

  // ── PageFilterBar config ───────────────────────────────────────────────────
  const filterAttributes = [
    { key: 'status', label: t.deliveriesPage.statusHeader, options: DELIVERY_STATUSES.map(s => ({ value: s.value, label: getStatusLabel(s.value) })) },
    { key: 'driver', label: t.deliveriesPage.driverHeader, options: drivers.map(d => ({ value: d.id, label: d.name })) },
    { key: 'zone',   label: t.deliveriesPage.zoneHeader,   options: zones.map(z => ({ value: z.id, label: z.name })) },
    { key: 'date',   label: t.deliveriesPage.dateLabel,    type: 'date' as const },
  ];
  const activeFiltersState: Record<string, string> = {
    ...(status   && { status }),
    ...(driverId && { driver: driverId }),
    ...(zoneId   && { zone: zoneId }),
    ...(date     && { date }),
  };
  const handleDeliveryFilterChange = (key: string, value: string | null) => {
    setPage(0);
    if (key === 'status') setStatus(value ?? '');
    if (key === 'driver') setDriverId(value ?? '');
    if (key === 'zone')   setZoneId(value ?? '');
    if (key === 'date')   { setDate(value ?? ''); setQuickView('all'); }
  };
  const quickFilterList = [
    { value: 'all',         label: t.deliveriesPage.totalFlow,           count: quickCounts.all },
    { value: 'overdue',     label: t.deliveriesPage.quickViewOverdue,     count: quickCounts.overdue },
    { value: 'today',       label: t.deliveriesPage.quickViewToday,       count: quickCounts.today },
    { value: 'future',      label: t.deliveriesPage.quickViewFuture,      count: quickCounts.future },
    { value: 'needsPinning',label: t.deliveriesPage.quickViewNeedsPinning,count: quickCounts.needsPinning },
    { value: 'unassigned',  label: t.deliveriesPage.quickViewUnassigned,  count: quickCounts.unassigned },
    { value: 'inTransit',   label: t.deliveriesPage.quickViewInTransit,   count: quickCounts.inTransit },
    { value: 'completed',   label: t.deliveriesPage.quickViewCompleted,   count: quickCounts.completed },
    { value: 'failed',      label: t.deliveriesPage.quickViewFailed,      count: quickCounts.failed },
  ];

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
            <ExportCsvButton
              baseName="livraisons"
              rows={filteredRows}
              columns={[
                { header: 'Référence', accessor: (r: any) => resolveOrderRef(r) },
                { header: 'Client', accessor: (r: any) => r.clientName },
                { header: 'Adresse', accessor: (r: any) => r.dropoffAddress },
                { header: 'Zone', accessor: (r: any) => r.zoneName },
                { header: 'Chauffeur', accessor: (r: any) => r.driverName },
                { header: 'Statut', accessor: (r: any) => r.status },
                { header: 'SLA', accessor: (r: any) => (r as any).slaHealth },
                { header: 'Planifié', accessor: (r: any) => (r as any).scheduledAt },
                { header: 'Montant', accessor: (r: any) => (r as any).totalAmount },
                { header: 'Créé le', accessor: (r: any) => r.createdAt },
              ]}
            />
          }
        />

        <div className="flex flex-1 min-h-0 overflow-hidden">
          <div className="flex flex-col flex-1 min-w-0 overflow-hidden" style={{ background: 'var(--surface)' }}>
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
                  className="h-7 text-xs font-[700] w-[100px]"
                />
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
                          const labelMap: Record<string, string> = {
                            ref: t.deliveriesPage.refHeader,
                            client: `${t.deliveriesPage.clientHeader} · ${t.deliveriesPage.addressHeader}`,
                            scheduled: t.deliveriesPage.scheduledHeader,
                            status: t.deliveriesPage.statusHeader,
                            driver: t.deliveriesPage.driverHeader,
                            zone: t.deliveriesPage.zoneHeader,
                          };
                          const sortable = col.id === 'ref' || col.id === 'client' || col.id === 'status' || col.id === 'zone';
                          const align = col.id === 'driver' || col.id === 'zone' ? 'text-center' : 'text-left';
                          return (
                            <th key={col.id} className={`h-10 px-6 ${align} text-xs font-[450] text-[var(--text-muted)]`}>
                              {sortable ? (
                                <button onClick={() => headerSort(col.id as any)} className="inline-flex items-center gap-1 hover:text-[var(--text-strong)] transition-colors cursor-pointer">
                                  {labelMap[col.id]}
                                  <span className="text-2xs">{col.id === 'ref' ? (sortAsc ? '▲' : '▼') : (groupActive[col.id] ? (sortAsc ? '▲' : '▼') : '⇅')}</span>
                                </button>
                              ) : labelMap[col.id]}
                            </th>
                          );
                        })}
                        <th className="h-10 px-6 text-right text-xs font-[450] text-[var(--text-muted)]">{t.deliveriesPage.actionsHeader}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--border)] bg-[var(--surface)]">
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
              <div className="flex items-center justify-between px-6 py-2.5 border-t border-[var(--border)] bg-[var(--surface)] shrink-0">
                <span className="text-xs text-[var(--text-muted)]">
                  {t.deliveriesPage.pageLabel} {page + 1}{totalElements > rows.length ? ` · ${totalElements} ${t.deliveriesPage.resultsLabel}` : ''}
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
