

import { useCallback, useEffect, useMemo, useState, Suspense } from 'react';
import { lazy as dynamic } from 'react';
import { Link } from 'react-router-dom';
import { useNavigate as useRouter, useSearchParams } from 'react-router-dom';
import { useRef } from 'react';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { Delivery, DeliverySource, DeliveryStatus, Driver, Zone, GeocodeSuggestion } from '@/types';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { STATUS_COLORS } from '@/components/StatusBadge';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { PageHeader } from '@/components/layout/PageHeader';
import { AppLoader } from '@/components/AppLoader';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import type { ColumnDef } from '@/hooks/useColumnSettings';
import { Button } from '@/components/ui/button';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { AppModal } from '@/components/overlays/AppModal';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';
import {
  IconRefresh,
  IconSearch,
  IconRoute,
  IconFilter,
  IconMapPin,
  IconFileText,
  IconClock,
  IconUser,
  IconCheck,
  IconX,
  IconScan,
  IconAlertCircle,
  IconLink,
  IconMap2,
  IconTruck,
  IconChevronLeft,
  IconChevronRight,
} from '@tabler/icons-react';
import { formatRelative } from '@/lib/date';
import { useGlobalFilters } from '@/lib/global-filters';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { cn, resolveOrderRef, shortId } from '@/lib/utils';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { EmptyState } from '@/components/feedback/EmptyState';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { getDayBucket } from '@/lib/sla';
import {
  useDeliveries,
  useActiveZones,
  usePinDropoff,
  useCreateBackorder,
  useCancelDelivery
} from '@/hooks/useDeliveries';
import { useFleetDrivers } from '@/hooks/useVehicles';


const DELIVERY_COLUMNS: ColumnDef[] = [
  { id: 'ref',       label: 'Référence',  pinned: true },
  { id: 'client',    label: 'Client / Adresse', pinned: true },
  { id: 'scheduled', label: 'Programmée' },
  { id: 'status',    label: 'Statut' },
  { id: 'driver',    label: 'Chauffeur' },
  { id: 'zone',      label: 'Zone' },
];

const DELIVERY_ROW_H = { compact: 'h-10', comfortable: 'h-14', spacious: 'h-20' } as const;

function cleanTunisianAdminName(name: string | null | undefined): string {
  if (!name) return '';
  if (name.startsWith('Gouvernorat ')) return name.substring('Gouvernorat '.length);
  if (name.startsWith('Délégation ')) return name.substring('Délégation '.length);
  if (name.startsWith('Delegation ')) return name.substring('Delegation '.length);
  return name;
}

const RouteTrackingMap = dynamic(() => import('@/components/RouteTrackingMap'));

type QuickView = 'all' | 'needsPinning' | 'unassigned' | 'inTransit' | 'completed' | 'failed' | 'overdue' | 'today' | 'future';
type DeliveryRow = Delivery & { rowId: string };

const DELIVERY_STATUSES: Array<{ value: string; label: string }> = [
  { value: 'UNSCHEDULED', label: '' },
  { value: 'SCHEDULED', label: '' },
  { value: 'PICKED_UP', label: '' },
  { value: 'IN_TRANSIT', label: '' },
  { value: 'DELIVERED', label: '' },
  { value: 'PARTIALLY_DELIVERED', label: '' },
  { value: 'FAILED', label: '' },
  { value: 'CANCELLED', label: '' },
];

// Translated status labels are resolved dynamically using useT hook.

function getRowId(item: Delivery) {
  return String(item.deliveryId ?? item.id ?? '');
}

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

/** Inline spinner replacing Mantine Loader */
function Spinner({ className }: { className?: string }) {
  return (
    <svg className={cn('animate-spin h-4 w-4', className)} fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
    </svg>
  );
}

function DeliveriesPageContent() {
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
  const { filters: globalFilters, applyFilters, clearFilters, globalContext } = useGlobalFilters();

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

  // Pin modal state
  const [pinModal, setPinModal] = useState<{ deliveryId: string; clientName: string; address: string; city: string; locked: boolean } | null>(null);
  const [geocodeLoading, setGeocodeLoading] = useState(false);
  const [pinLat, setPinLat] = useState<number | null>(null);
  const [pinLng, setPinLng] = useState<number | null>(null);
  const [pinAddress, setPinAddress] = useState('');
  const [pinCity, setPinCity] = useState('');
  const [pinPostalCode, setPinPostalCode] = useState('');
  const [reverseGeocoding, setReverseGeocoding] = useState(false);

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [addressSearch, setAddressSearch] = useState('');
  const [addressResults, setAddressResults] = useState<any[]>([]);
  const [showAddressResults, setShowAddressResults] = useState(false);
  const [searchingAddress, setSearchingAddress] = useState(false);
  const [flyCenter, setFlyCenter] = useState<[number, number] | undefined>(undefined);
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  // Cancellation state
  const [cancelTarget, setCancelTarget] = useState<DeliveryRow | null>(null);
  const [downloadingBl, setDownloadingBl] = useState<Set<string>>(new Set());

  const downloadBl = async (deliveryId: string, ref: string) => {
    setDownloadingBl(prev => new Set(prev).add(deliveryId));
    try {
      const res = await api.get(`/api/admin/deliveries/${deliveryId}/bon-livraison`, {
        responseType: 'blob',
      });
      const url  = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href     = url;
      link.download = `bl-${ref.replace(/\s+/g, '-').toLowerCase()}.pdf`;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      showErrorToast(null, t.deliveriesPage.downloadError);
    } finally {
      setDownloadingBl(prev => { const s = new Set(prev); s.delete(deliveryId); return s; });
    }
  };
  const [cancelReason, setCancelReason] = useState('');

  // Custom Query Hooks
  const { data: drivers = [] } = useFleetDrivers();
  const { data: zones = [] } = useActiveZones();

  const effectiveZoneId = useMemo(() => {
    const raw = (zoneId ?? '').trim();
    if (!raw || isUuid(raw)) return raw;
    const byName = zones.find((z) => z.name?.trim().toLowerCase() === raw.toLowerCase());
    return byName?.id ?? '';
  }, [zoneId, zones]);

  // Query Params logic
  const queryParams = useMemo(() => {
    const params: any = { page, size };
    if (status) params.status = status;
    if (date) params.date = date;
    if (driverId) params.driverId = driverId;
    if (effectiveZoneId) params.zoneId = effectiveZoneId;
    if (quickView === 'needsPinning') params.unpinned = 'true';
    return params;
  }, [page, size, status, date, driverId, effectiveZoneId, quickView]);

  const { data: deliveriesResponse, isLoading: loading, isFetching: refreshing, refetch: fetchDeliveries } = useDeliveries(queryParams);
  const pinDropoffMutation = usePinDropoff();
  const createBackorderMutation = useCreateBackorder();
  const cancelDeliveryMutation = useCancelDelivery();

  const pinSaving = pinDropoffMutation.isPending;
  const creatingBackorderFor = createBackorderMutation.isPending ? createBackorderMutation.variables : null;
  const cancelling = cancelDeliveryMutation.isPending;

  const rows = useMemo(() => {
    const content = Array.isArray(deliveriesResponse?.content) ? deliveriesResponse.content : [];
    return content.map((item: Delivery) => ({ ...item, rowId: getRowId(item) } as DeliveryRow)).filter((r: DeliveryRow) => !!r.rowId);
  }, [deliveriesResponse]);

  const totalElements = Number(deliveriesResponse?.totalElements ?? rows.length);
  const totalPages = Math.max(1, Number(deliveriesResponse?.totalPages || 1));

  useEffect(() => {
    if (deliveriesResponse) {
      setLastUpdated(new Date());
    }
  }, [deliveriesResponse]);

  // Sync with global filters & query param
  useEffect(() => {
    if (!globalContext || initialSyncRef.current) return;
    initialSyncRef.current = true;
    const s = searchParams?.get('search');
    if (s) {
      setQuery(s);
      applyFilters({ search: s });
    } else if (globalFilters.search) {
      setQuery(globalFilters.search);
    }
  }, [globalContext, searchParams, applyFilters, globalFilters.search]);

  useEffect(() => {
    if (!globalContext) return;
    if (status !== (globalFilters.status || '')) setStatus(globalFilters.status || '');
    if (date !== globalFilters.dateFrom) setDate(globalFilters.dateFrom);
    if (driverId !== globalFilters.driver) setDriverId(globalFilters.driver);
    if (zoneId !== globalFilters.zone) setZoneId(globalFilters.zone);
  }, [globalFilters, globalContext]);

  // Push to global filters
  useEffect(() => {
    const hasChanged = query !== globalFilters.search || status !== (globalFilters.status || '') || date !== globalFilters.dateFrom || driverId !== globalFilters.driver || zoneId !== globalFilters.zone;
    if (hasChanged) {
      applyFilters({ search: query, status, dateFrom: date, driver: driverId, zone: zoneId, route: globalFilters.route, dateTo: globalFilters.dateTo });
    }
  }, [query, status, date, driverId, zoneId, applyFilters]);

  const filteredRows = useMemo(() => {
    const q = query.trim().toLowerCase();

    let result = rows.filter((item: DeliveryRow) => {
      const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
      const bucket = getDayBucket(item.scheduledAt);

      if (quickView === 'needsPinning') return !item.dropoffPinned;
      if (quickView === 'unassigned' && Boolean(item.driverId)) return false;
      if (quickView === 'inTransit' && item.status !== 'IN_TRANSIT') return false;
      if (quickView === 'completed' && item.status !== 'DELIVERED') return false;
      if (quickView === 'failed' && !['FAILED','CANCELLED'].includes(item.status)) return false;
      if (quickView === 'overdue') return isPending && bucket === 'overdue';
      if (quickView === 'today') return isPending && bucket === 'today';
      if (quickView === 'future') return isPending && bucket === 'future';
      
      if (!q) return true;
      return [item.rowId, item.orderId, item.erpOrderId, item.orderRef, item.erpId, item.clientName, item.dropoffCity, item.driverName, item.routeName, item.status]
        .some(v => String(v ?? '').toLowerCase().includes(q));
    });

    // Sort layers: status → zone → client → createdAt (stacked group-by toggles)
    result.sort((a: DeliveryRow, b: DeliveryRow) => {
      if (groupByStatus) {
        const cmp = (a.status ?? '').localeCompare(b.status ?? '');
        if (cmp !== 0) return cmp;
      }
      if (groupByZone) {
        const cmp = (a.zoneName ?? '').localeCompare(b.zoneName ?? '');
        if (cmp !== 0) return cmp;
      }
      if (groupByClient) {
        const cmp = (a.clientName ?? '').localeCompare(b.clientName ?? '');
        if (cmp !== 0) return cmp;
      }
      const da = a.createdAt ?? '';
      const db = b.createdAt ?? '';
      const cmp = da.localeCompare(db);
      return sortAsc ? cmp : -cmp;
    });

    return result;
  }, [query, quickView, rows, sortAsc, groupByClient, groupByZone, groupByStatus]);

  const quickCounts = useMemo(() => {
    let needsPinning = 0, unassigned = 0, inTransit = 0, completed = 0, failed = 0;
    let overdue = 0, today = 0, future = 0;

    rows.forEach((item: DeliveryRow) => {
      if (!item.dropoffPinned) needsPinning++;
      if (!item.driverId) unassigned++;
      if (item.status === 'IN_TRANSIT') inTransit++;
      if (item.status === 'DELIVERED') completed++;
      if (['FAILED','CANCELLED'].includes(item.status)) failed++;

      const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
      if (isPending && item.scheduledAt) {
        const bucket = getDayBucket(item.scheduledAt);
        if (bucket === 'overdue') overdue++;
        else if (bucket === 'today') today++;
        else if (bucket === 'future') future++;
      }
    });
    return { all: rows.length, needsPinning, unassigned, inTransit, completed, failed, overdue, today, future };
  }, [rows]);

  const openPinModal = async (item: DeliveryRow) => {
    const locked = ['PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED'].includes(item.status);
    setPinModal({ deliveryId: item.rowId, clientName: item.clientName, address: item.dropoffAddress ?? '', city: item.dropoffCity ?? '', locked });
    setAddressSearch('');
    setAddressResults([]);
    setShowAddressResults(false);
    setSearchingAddress(false);
    setFlyCenter(item.dropoffLat && item.dropoffLng ? [item.dropoffLat, item.dropoffLng] : undefined);

    if (item.dropoffPinned && item.dropoffLat != null) {
      setPinLat(item.dropoffLat ?? null); setPinLng(item.dropoffLng ?? null);
      setPinAddress(item.dropoffAddress ?? ''); setPinCity(item.dropoffCity ?? '');
      setPinPostalCode(item.dropoffPostalCode ?? '');
      return;
    }

    setPinLat(null); setPinLng(null); setPinAddress(item.dropoffAddress ?? ''); setPinCity(item.dropoffCity ?? '');
    if (!locked) {
      setGeocodeLoading(true);
      try {
        const res = await api.get(`/api/admin/deliveries/${item.rowId}/geocode`);
        const sug: GeocodeSuggestion = res.data;
        if (sug.found) {
          setPinLat(sug.lat ?? null); setPinLng(sug.lng ?? null);
          if (!item.dropoffAddress) setPinAddress(sug.displayName ?? '');
          if (sug.postalCode) setPinPostalCode(sug.postalCode);
        }
      } catch {} finally { setGeocodeLoading(false); }
    }
  };

  const handleMapPick = async (lat: number, lng: number) => {
    setPinLat(lat); setPinLng(lng); setReverseGeocoding(true);
    try {
      const res = await api.get('/api/admin/deliveries/reverse-geocode', { params: { lat, lng } });
      const rev: GeocodeSuggestion = res.data;
      if (rev.found) {
        setPinAddress(rev.displayName ?? '');
        if (rev.city) setPinCity(cleanTunisianAdminName(rev.city));
        if (rev.postalCode) setPinPostalCode(rev.postalCode);
      }
    } catch {} finally { setReverseGeocoding(false); }
  };

  const confirmPin = async () => {
    if (!pinModal || pinLat == null || pinLng == null) return;
    try {
      await pinDropoffMutation.mutateAsync({
        deliveryId: pinModal.deliveryId,
        payload: { lat: pinLat, lng: pinLng, dropoffAddress: pinAddress, dropoffCity: pinCity, dropoffPostalCode: pinPostalCode }
      });
      showSuccessToast(t.deliveriesPage.pinModalConfirm);
      const row = rows.find((r: DeliveryRow) => r.rowId === pinModal.deliveryId);
      if (row?.routeId) {
        safeStorage.setItem(`route_needs_refresh_${row.routeId}`, 'true');
      }
      setPinModal(null);
    } catch (err: any) { showErrorToast(err?.response?.data?.message); }
  };

  const openRoute = async (item: DeliveryRow) => {
    if (item.routeId) { router(`/routes/${item.routeId}?deliveryId=${item.rowId}`); return; }
    router(`/routes?deliveryId=${item.rowId}`);
  };

  const createBackorder = async (item: DeliveryRow) => {
    try {
      await createBackorderMutation.mutateAsync(item.rowId);
      showSuccessToast(t.deliveriesPage.backorderCreated);
    } catch { showErrorToast(null, t.deliveriesPage.backorderError); }
  };

  const runCancel = async () => {
    if (!cancelTarget || !cancelReason.trim()) return;
    try {
      await cancelDeliveryMutation.mutateAsync({
        deliveryId: cancelTarget.rowId,
        reason: cancelReason.trim()
      });
      showSuccessToast(t.deliveriesPage.deliveryCancelled);
      setCancelTarget(null);
      setCancelReason('');
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    }
  };

  const handleAddressSearch = async (q: string) => {
    setAddressSearch(q);
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current);

    if (q.trim().length < 3) {
      setAddressResults([]);
      setShowAddressResults(false);
      return;
    }

    searchDebounceRef.current = setTimeout(async () => {
      setSearchingAddress(true);
      try {
        const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=${encodeURIComponent(q)}&countrycodes=tn`;
        const res = await fetch(url);
        const data = await res.json();
        setAddressResults(data);
        setShowAddressResults(data && data.length > 0);
      } catch {
        setAddressResults([]);
        setShowAddressResults(false);
      } finally {
        setSearchingAddress(false);
      }
    }, 400);
  };

  const pickAddressSuggestion = (r: any) => {
    setFlyCenter([parseFloat(r.lat), parseFloat(r.lon)]);
    setAddressSearch(r.display_name.split(',')[0]);
    setShowAddressResults(false);
    showSuccessToast(t.deliveriesPage.addressLocated);
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

  return (
    <TooltipProvider>
      <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
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
        />

        <div className="flex flex-1 min-h-0 overflow-hidden">
          {/* ── Main Data Slab ── */}
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

            {/* Full-Bleed Table */}
            <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
              <div className="min-w-[1000px] lg:min-w-0">
                <table className="w-full border-collapse">
                  <thead className="sticky top-0 z-20 border-b border-[var(--border)]" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                    <tr>
                      <th className="w-2 px-0"></th>
                      {orderedColumns.map(col => {
                        if (!visibleIds.has(col.id)) return null;
                        if (col.id === 'ref') return (
                          <th key="ref" className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">
                            <button onClick={() => setSortAsc(v => !v)} className="inline-flex items-center gap-1 hover:text-[var(--text-strong)] transition-colors cursor-pointer">
                              {t.deliveriesPage.refHeader}
                              <span className="text-[9px]">{sortAsc ? '▲' : '▼'}</span>
                            </button>
                          </th>
                        );
                        if (col.id === 'client') return (
                          <th key="client" className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">
                            <button onClick={() => setGroupByClient(v => !v)} className="inline-flex items-center gap-1 hover:text-[var(--text-strong)] transition-colors cursor-pointer">
                              {t.deliveriesPage.clientHeader} · {t.deliveriesPage.addressHeader}
                              <span className="text-[9px]">{groupByClient ? (sortAsc ? '▲' : '▼') : '⇅'}</span>
                            </button>
                          </th>
                        );
                        if (col.id === 'scheduled') return (
                          <th key="scheduled" className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">{t.deliveriesPage.scheduledHeader}</th>
                        );
                        if (col.id === 'status') return (
                          <th key="status" className="h-10 px-6 text-left text-xs font-[450] text-[var(--text-muted)]">
                            <button onClick={() => setGroupByStatus(v => !v)} className="inline-flex items-center gap-1 hover:text-[var(--text-strong)] transition-colors cursor-pointer">
                              {t.deliveriesPage.statusHeader}
                              <span className="text-[9px]">{groupByStatus ? (sortAsc ? '▲' : '▼') : '⇅'}</span>
                            </button>
                          </th>
                        );
                        if (col.id === 'driver') return (
                          <th key="driver" className="h-10 px-6 text-center text-xs font-[450] text-[var(--text-muted)]">{t.deliveriesPage.driverHeader}</th>
                        );
                        if (col.id === 'zone') return (
                          <th key="zone" className="h-10 px-6 text-center text-xs font-[450] text-[var(--text-muted)]">
                            <button onClick={() => setGroupByZone(v => !v)} className="inline-flex items-center gap-1 hover:text-[var(--text-strong)] transition-colors cursor-pointer">
                              {t.deliveriesPage.zoneHeader}
                              <span className="text-[9px]">{groupByZone ? (sortAsc ? '▲' : '▼') : '⇅'}</span>
                            </button>
                          </th>
                        );
                        return null;
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
                        <tr
                          key={item.rowId}
                          className={cn('hover:bg-[var(--hover-bg)] transition-all group cursor-pointer', DELIVERY_ROW_H[density])}
                          onClick={() => router(`/deliveries/${item.rowId}`)}
                        >
                          {/* Status Vertical Ribbon */}
                          <td className="p-0">
                             <div
                               className="w-[3px] h-10 rounded-r-[2px]"
                               style={{ backgroundColor: STATUS_COLORS[(item.status ?? '').toUpperCase()] || 'var(--border)' }}
                             />
                          </td>

                          {orderedColumns.map(col => {
                            if (!visibleIds.has(col.id)) return null;
                            if (col.id === 'ref') return (
                              <td key="ref" className="px-6">
                                <div className="flex flex-col gap-0">
                                  <Link to={`/deliveries/${item.rowId ?? item.id}`} onClick={(e) => e.stopPropagation()} style={{ textDecoration: 'none' }}>
                                    <span className="text-xs font-[700] font-mono tabular-nums hover:text-[var(--brand)] transition-colors" style={{ color: 'var(--brand)', cursor: 'pointer' }}>
                                      {resolveOrderRef(item)}
                                    </span>
                                  </Link>
                                  <span className="text-xs font-[600] text-[var(--text-muted)] uppercase font-mono tracking-tighter opacity-70">
                                    #{shortId(item.rowId)}
                                  </span>
                                </div>
                              </td>
                            );
                            if (col.id === 'client') return (
                              <td key="client" className="px-6">
                                <div className="flex flex-col gap-0.5 max-w-[400px]">
                                  <span className="text-xs font-[600] text-[var(--text-primary)] line-clamp-1 group-hover:underline decoration-[var(--brand)]/20">
                                    {item.clientName || t.deliveriesPage.unknownDriver}
                                  </span>
                                  <div className="flex items-center gap-1 flex-nowrap">
                                    <IconMapPin size={10} className="text-[var(--text-muted)]" />
                                    <span className="text-2xs font-[500] text-[var(--text-soft)] truncate line-clamp-1">{item.dropoffAddress || t.deliveriesPage.pinReverseGeocoding}</span>
                                  </div>
                                </div>
                              </td>
                            );
                            if (col.id === 'scheduled') return (
                              <td key="scheduled" className="px-6">
                                {(() => {
                                  if (!item.scheduledAt) {
                                    return <span className="text-xs text-[var(--text-muted)] italic">{t.deliveriesPage.unscheduled}</span>;
                                  }
                                  const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
                                  const bucket = getDayBucket(item.scheduledAt);
                                  let colorClass = 'text-[var(--text-soft)] bg-[var(--surface)] border-[var(--border)]';
                                  if (isPending) {
                                    if (bucket === 'overdue') colorClass = 'text-[var(--danger)] bg-red-50 border-red-200';
                                    else if (bucket === 'today') colorClass = 'text-[var(--warning)] bg-orange-50 border-orange-200';
                                    else colorClass = 'text-[var(--info)] bg-blue-50 border-blue-200';
                                  }
                                  return (
                                    <div className="inline-flex items-center gap-1">
                                      <span className={cn('text-xs font-bold', colorClass.split(' ')[0])}>
                                        {new Date(item.scheduledAt).toLocaleDateString(dateTag)}
                                        <span className="mr-0.5">,</span>
                                        {new Date(item.scheduledAt).toLocaleTimeString(dateTag, { hour: '2-digit', minute: '2-digit' })}
                                      </span>
                                      {item.rescheduledAt && (
                                        <span
                                          title={t.deliveryPage.rescheduledTooltip}
                                          className="text-[9px] font-bold px-1 py-0.5 rounded-xs"
                                          style={{ color: '#0891B2', background: 'rgba(8,145,178,0.12)' }}
                                        >
                                          {t.deliveryPage.rescheduledBadge}
                                        </span>
                                      )}
                                    </div>
                                  );
                                })()}
                              </td>
                            );
                            if (col.id === 'status') return (
                              <td key="status" className="px-6">
                                <div className="flex items-center gap-1.5 flex-wrap">
                                  <StatusBadge status={item.status} size="sm" />
                                  <SlaHealthBadge health={item.slaHealth} />
                                </div>
                              </td>
                            );
                            if (col.id === 'driver') return (
                              <td key="driver" className="px-6">
                                <div className="flex items-center gap-1.5 justify-center">
                                  {item.driverName ? (
                                    <>
                                      <div className="size-5 rounded-xs bg-[var(--surface)] border border-[var(--border)] flex items-center justify-center text-[9px] font-bold text-[var(--text-primary)] uppercase">
                                        {item.driverName.charAt(0)}
                                      </div>
                                      <span className="text-xs font-[600] text-[var(--text-soft)] truncate max-w-[100px]">{item.driverName}</span>
                                    </>
                                  ) : (
                                    <span className="text-xs font-[400] text-[var(--text-muted)]">{t.deliveriesPage.notAssigned}</span>
                                  )}
                                </div>
                              </td>
                            );
                            if (col.id === 'zone') return (
                              <td key="zone" className="px-6">
                                <div className="flex justify-center">
                                  <span
                                    className="text-xs font-[500] px-2 py-0.5 rounded-full"
                                    style={{
                                      color: item.zoneColor || 'var(--text-muted)',
                                      backgroundColor: item.zoneColor ? `${item.zoneColor}12` : 'rgba(161,161,170,0.10)',
                                    }}
                                  >
                                    {item.zoneName || t.deliveriesPage.outOfZone}
                                  </span>
                                </div>
                              </td>
                            );
                            return null;
                          })}

                          <td className="px-6">
                            <div className="flex items-center gap-1.5 justify-end flex-wrap">
                              {(() => {
                                const locked = ['PICKED_UP','IN_TRANSIT','DELIVERED','PARTIALLY_DELIVERED','FAILED'].includes(item.status);
                                return (
                                  <>
                                    {/* Pin / Re-pin */}
                                    {!item.dropoffPinned ? (
                                      <Tooltip>
                                        <TooltipTrigger asChild>
                                          <button
                                            type="button"
                                            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                                            style={{ color: 'var(--brand)' }}
                                            onClick={(e) => { e.stopPropagation(); void openPinModal(item); }}
                                          >
                                            <IconMapPin size={14} />
                                          </button>
                                        </TooltipTrigger>
                                        <TooltipContent>{t.deliveriesPage.tooltipPinLocation}</TooltipContent>
                                      </Tooltip>
                                    ) : !locked ? (
                                      <Tooltip>
                                        <TooltipTrigger asChild>
                                          <button
                                            type="button"
                                            className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                                            style={{ color: 'var(--brand)' }}
                                            onClick={(e) => { e.stopPropagation(); void openPinModal(item); }}
                                          >
                                            <IconMapPin size={15} />
                                          </button>
                                        </TooltipTrigger>
                                        <TooltipContent>{t.deliveriesPage.tooltipRepin}</TooltipContent>
                                      </Tooltip>
                                    ) : null}

                                    {/* Bon de livraison (ERP PDF) */}
                                    {item.blNumber && (
                                      <Tooltip>
                                        <TooltipTrigger asChild>
                                          <button
                                            type="button"
                                            className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                                            style={{ color: 'var(--brand)' }}
                                            onClick={(e) => { e.stopPropagation(); window.open(`/api/admin/deliveries/${item.id}/bon-livraison`, '_blank'); }}
                                          >
                                            <IconFileText size={14} />
                                          </button>
                                        </TooltipTrigger>
                                        <TooltipContent>{t.deliveryPage.viewBL}</TooltipContent>
                                      </Tooltip>
                                    )}

                                    {/* Route */}
                                    {item.routeName && (
                                      <button
                                        type="button"
                                        className="h-7 px-3 bg-[var(--surface)] border border-[var(--border)] rounded-xs flex items-center gap-2 hover:bg-[var(--hover-bg)] hover:border-[var(--border-strong)] transition-all text-[var(--text-primary)] font-[500]"
                                        onClick={(e) => { e.stopPropagation(); void openRoute(item); }}
                                      >
                                        <IconRoute size={12} />
                                        <span className="text-2xs truncate max-w-[80px]">{item.routeName}</span>
                                      </button>
                                    )}
                                  </>
                                );
                              })()}
                              {item.status === 'UNSCHEDULED' && (
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <button
                                      type="button"
                                      className="w-7 h-7 flex items-center justify-center rounded-xs border border-red-300 hover:bg-red-50 transition-colors"
                                      onClick={(e) => { e.stopPropagation(); setCancelTarget(item); setCancelReason(''); }}
                                    >
                                      <IconX size={16} className="text-[var(--danger)]" />
                                    </button>
                                  </TooltipTrigger>
                                  <TooltipContent>{t.deliveriesPage.tooltipCancel}</TooltipContent>
                                </Tooltip>
                              )}
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <button
                                    type="button"
                                    className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors disabled:opacity-50"
                                    disabled={downloadingBl.has(item.rowId)}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      downloadBl(item.rowId, resolveOrderRef(item));
                                    }}
                                  >
                                    {downloadingBl.has(item.rowId) ? <Spinner className="h-3 w-3" /> : <IconFileText size={14} />}
                                  </button>
                                </TooltipTrigger>
                                <TooltipContent>{t.deliveriesPage.tooltipDownloadBL}</TooltipContent>
                              </Tooltip>
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <button
                                    type="button"
                                    className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors"
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      const url = `${window.location.origin}/track/${item.rowId}`;
                                      navigator.clipboard.writeText(url);
                                      showSuccessToast(t.deliveriesPage.trackingCopied);
                                    }}
                                  >
                                    <IconLink size={14} />
                                  </button>
                                </TooltipTrigger>
                                <TooltipContent>{t.deliveriesPage.trackingLink}</TooltipContent>
                              </Tooltip>
                            </div>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Pagination footer */}
            {(rows.length >= size || page > 0 || totalPages > 1) && (
              <div className="flex items-center justify-between px-6 py-2.5 border-t border-[var(--border)] bg-[var(--surface)] shrink-0">
                <span className="text-xs text-[var(--text-muted)]">
                  {t.deliveriesPage.pageLabel} {page + 1}{totalElements > rows.length ? ` · ${totalElements} ${t.deliveriesPage.resultsLabel}` : ''}
                </span>
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    disabled={page === 0}
                    onClick={() => setPage(p => p - 1)}
                    className="h-7 px-3 text-xs font-[700] rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] disabled:opacity-40 transition-colors"
                  >
                    {t.deliveriesPage.prevButton}
                  </button>
                  <button
                    type="button"
                    disabled={rows.length < size}
                    onClick={() => setPage(p => p + 1)}
                    className="h-7 px-3 text-xs font-[700] rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] disabled:opacity-40 transition-colors"
                  >
                    {t.deliveriesPage.nextButton}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* ── Surgical Pin Modal ────────────────────────────────────── */}
        <AppModal
          opened={!!pinModal}
          onClose={() => setPinModal(null)}
          size="lg"
          title={
            <div className="flex items-center justify-between w-full gap-4 flex-nowrap">
              <div className="flex flex-col gap-0">
                <span className="text-lg font-[700] tracking-tight text-[var(--text-primary)]">
                  {pinModal?.locked ? t.deliveriesPage.lockedGeocoding : t.deliveriesPage.pinModalTitle}
                </span>
              </div>
              {!pinModal?.locked && (
                <div className="relative w-[280px]">
                  <FieldInput
                    placeholder={t.deliveriesPage.pinModalSearch}
                    leftSection={<IconSearch size={14} className="text-[var(--text-muted)]" />}
                    rightSection={searchingAddress ? <Spinner className="h-3 w-3" /> : undefined}
                    value={addressSearch}
                    onChange={(e) => handleAddressSearch(e.currentTarget.value)}
                    onFocus={() => addressResults.length > 0 && setShowAddressResults(true)}
                    onBlur={() => setTimeout(() => setShowAddressResults(false), 200)}
                    className="h-8 text-xs"
                  />
                  {showAddressResults && (
                    <div
                      className="absolute top-full left-0 right-0 z-[1001] bg-[var(--surface)] border border-[var(--border)] rounded mt-1 shadow-xl max-h-[200px] overflow-y-auto"
                    >
                      <div className="flex flex-col gap-0">
                        {addressResults.map((r, idx) => (
                          <div
                            key={idx}
                            className="px-3 py-2 hover:bg-[var(--surface)] cursor-pointer border-b border-[var(--border)] last:border-0"
                            onClick={() => pickAddressSuggestion(r)}
                          >
                            <span className="text-sm font-[500] text-[var(--text-primary)] line-clamp-1">{r.display_name}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          }
        >
          <div className="flex flex-col gap-0">
            {pinModal?.locked && (
              <div className="p-3 bg-[var(--brand-soft)] border-b border-[var(--brand)]/20 mb-3 rounded">
                <div className="flex items-start gap-2 flex-nowrap">
                  <IconAlertCircle size={18} className="text-[var(--brand)] shrink-0" />
                  <span className="text-sm font-[500] text-[var(--brand)] leading-relaxed italic">
                    {t.deliveriesPage.lockedDeliveryMessage}
                  </span>
                </div>
              </div>
            )}

            <div className="relative">
              <RouteTrackingMap
                stops={[]}
                height={400}
                pinLat={pinLat} pinLng={pinLng}
                onPick={!pinModal?.locked ? handleMapPick : undefined}
                center={flyCenter}
                zoom={flyCenter ? 16 : 12}
              />
              {reverseGeocoding && (
                <div className="absolute inset-0 bg-white/40 backdrop-blur-sm flex items-center justify-center z-50">
                  <div className="flex items-center gap-2 bg-[var(--surface)] px-6 py-2 rounded-xs shadow-xl border border-[var(--border)]">
                    <AppLoader size="sm" />
                    <span className="text-sm font-[500] text-[var(--text-primary)]">{t.deliveriesPage.analyzeInProgress}</span>
                  </div>
                </div>
              )}
            </div>

            {!pinModal?.locked ? (
              <div className="p-6 bg-[var(--surface)] border-t border-[var(--border)]">
                <div className="grid grid-cols-[1fr_auto] gap-3">
                  <FieldInput
                    label={<span className="text-xs font-[500] text-[var(--text-muted)]">{t.deliveriesPage.addressTarget}</span>}
                    placeholder={t.deliveriesPage.addressPlaceholder}
                    value={pinAddress}
                    onChange={(e) => setPinAddress(e.currentTarget.value)}
                    className="h-10 font-[600]"
                  />
                  <FieldInput
                    label={<span className="text-xs font-[500] text-[var(--text-muted)]">{t.deliveriesPage.postalCodeLabel}</span>}
                    placeholder={t.deliveriesPage.postalCodePlaceholder}
                    value={pinPostalCode}
                    onChange={(e) => setPinPostalCode(e.currentTarget.value)}
                    className="h-10 font-[700] font-mono w-28"
                  />
                </div>

                <div className="flex items-center gap-3 mt-6">
                  <button
                    type="button"
                    className="flex-1 h-10 font-[500] hover:bg-[var(--hover-bg)] text-[var(--text-soft)] text-sm border border-[var(--border)] rounded transition-colors"
                    onClick={() => setPinModal(null)}
                  >
                    {t.actions.cancel}
                  </button>
                  <button
                    type="button"
                    className="flex-1 h-10 bg-[var(--brand)] hover:opacity-90 text-white font-[500] text-sm rounded-xs flex items-center justify-center gap-2 transition-opacity disabled:opacity-50"
                    onClick={confirmPin}
                    disabled={pinLat == null || pinSaving}
                  >
                    {pinSaving ? <Spinner className="h-3 w-3 text-white" /> : null}
                    {t.deliveriesPage.pinButtonConfirm}
                  </button>
                </div>
              </div>
            ) : (
              <div className="p-6 bg-[var(--surface)] border-t border-[var(--border)]">
                <button
                  type="button"
                  className="w-full h-10 border border-[var(--border)] text-[var(--text-primary)] font-[500] text-sm rounded hover:bg-[var(--hover-bg)] transition-colors"
                  onClick={() => setPinModal(null)}
                >
                  {t.actions.close}
                </button>
              </div>
            )}
          </div>
        </AppModal>

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

        <style>{`
          .scrollbar-hide::-webkit-scrollbar { display: none; }
          .scrollbar-hide { -ms-overflow-style: none; scrollbar-width: none; }
        `}</style>
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

