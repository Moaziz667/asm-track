import { useEffect, useState, useCallback, useMemo, useRef, Suspense } from 'react';
import { api } from '@/lib/api';
import { Driver, Delivery } from '@/types';
import { useT } from '@/lib/i18n/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { getCurrentRole, isReadOnlyRole } from '@/lib/api/auth';
import { showErrorToast } from '@/lib/ui/toast-service';
import {
  useDrivers,
  useCreateDriver,
  useUpdateDriver,
  useToggleDriverStatus,
  useCancelDriverInvite,
  useResendDriverInvite,
  useForceLogoutDriver,
  useImportDrivers,
} from '@/hooks/useDrivers';

import { Button } from '@/components/ui/button';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { ExportCsvButton } from '@/components/layout/ExportCsvButton';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { AddButton } from '@/components/ui/AddButton';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { EmptyState } from '@/components/feedback/EmptyState';
import { TablePagination } from '@/components/data-display/TablePagination';
import { usePageBreadcrumb } from '@/lib/ui/breadcrumb';
import { TooltipProvider } from '@/components/ui/tooltip';

import { DRIVER_COLUMNS, type DriverCrud } from './constants';
import { SVGUpload, SVGUser } from './icons';
import { DriverTableRow, DriverMobileCard } from './DriverTableRow';
import { useRoutes } from '@/hooks/useRoutes';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useIsMobile } from '@/hooks/use-mobile';
import { DriverDetailsModal } from './DriverDetailsModal';
import { DriverFormModal } from './DriverFormModal';

function DriversPageContent() {
  const isMobile = useIsMobile();
  const t = useT();
  const { locale } = useLocaleStore();
  usePageBreadcrumb([{ label: t.pages.drivers?.title || t.driversPage.pageTitle || 'Drivers' }]);
  const role = getCurrentRole();
  const readOnly = isReadOnlyRole(role);
  const [searchParams, setSearchParams] = useSearchParams();

  const { density, setDensity } = useDensity('drivers', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('drivers', DRIVER_COLUMNS);

  const DRIVER_ROW_H: Record<typeof density, number> = { compact: 40, comfortable: 52, spacious: 64 };
  const [activeTab, setActiveTab] = useState<'info' | 'mission' | 'activity'>('info');

  const { data: drivers = [], isLoading: loading, refetch: fetchDrivers } = useDrivers();
  const navigate = useNavigate();

  // Resolve active-route ids → human route names for the activity column (shows the route name, not a
  // raw UUID slice). Cheap: reuses the shared routes query cache.
  const { data: routes = [] } = useRoutes();
  const routeNameById = useMemo(
    () => Object.fromEntries(routes.map((r) => [r.id, r.name])) as Record<string, string>,
    [routes],
  );
  const openRoute = useCallback((routeId: string) => navigate(`/routes/${routeId}`), [navigate]);

  // Realtime: keep online/offline + account status live via a debounced refetch.
  const driverRtTimer = useRef<number | null>(null);
  useRealtimeEvent(
    ['driver.status_changed', 'driver.events'],
    () => {
      if (driverRtTimer.current != null) return;
      driverRtTimer.current = window.setTimeout(() => { driverRtTimer.current = null; void fetchDrivers(); }, 1500);
    },
  );

  const createDriverMutation = useCreateDriver();
  const updateDriverMutation = useUpdateDriver();
  const toggleStatusMutation = useToggleDriverStatus();
  const cancelInviteMutation = useCancelDriverInvite();
  const resendInviteMutation = useResendDriverInvite();
  const forceLogoutDriverMutation = useForceLogoutDriver();
  const importDriversMutation = useImportDrivers();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [driverDeliveries, setDriverDeliveries] = useState<Delivery[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  const [crudOpen, setCrudOpen] = useState(false);
  const [editingDriver, setEditingDriver] = useState<Driver | null>(null);
  const csvInputRef = useRef<HTMLInputElement>(null);

  const [confirmCancel, setConfirmCancel] = useState<Driver | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [confirmSuspend, setConfirmSuspend] = useState<Driver | null>(null);
  const [suspendReason, setSuspendReason] = useState('');

  const [pendingRowId, setPendingRowId] = useState<string | null>(null);
  const [resendCooldown, setResendCooldown] = useState<number>(0);
  const resendIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Multi-select: empty array = no filter (all). Values OR-ed within a key.
  const [statusFilters, setStatusFilters] = useState<string[]>([]);
  const [activityFilters, setActivityFilters] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const selected = drivers.find((d) => d.id === selectedId) ?? null;

  // Auto-open driver details modal when navigated with ?driverId=... (e.g. from route report)
  useEffect(() => {
    const urlDriverId = searchParams.get('driverId');
    if (urlDriverId && drivers.length > 0) {
      const match = drivers.find((d) => d.id === urlDriverId);
      if (match) {
        setSelectedId(urlDriverId);
        setDetailsOpen(true);
        setSearchParams((prev) => { prev.delete('driverId'); return prev; }, { replace: true });
      }
    }
  }, [searchParams, drivers, setSearchParams]);

  useEffect(() => {
    if (!selectedId) return;
    setDetailLoading(true);
    api.get('/api/admin/deliveries', { params: { driverId: selectedId } })
      .then((res) => {
        const list = res.data.content ?? res.data;
        setDriverDeliveries(Array.isArray(list) ? list : []);
      })
      .catch(() => setDriverDeliveries([]))
      .finally(() => setDetailLoading(false));
  }, [selectedId]);

  // Cleanup driverRtTimer + resendCooldown interval on unmount
  useEffect(() => {
    return () => {
      if (driverRtTimer.current != null) { clearTimeout(driverRtTimer.current); driverRtTimer.current = null; }
      if (resendIntervalRef.current != null) { clearInterval(resendIntervalRef.current); resendIntervalRef.current = null; }
    };
  }, []);

  const isDriverEnLivraison = useCallback((driver: Driver) => {
    return Boolean(driver.activeDeliveryId) || Boolean(driver.activeRouteId);
  }, []);

  const filtered = useMemo(() => {
    return drivers.filter((d) => {
      if (searchTerm) {
        const q = searchTerm.toLowerCase();
        const matches = d.name.toLowerCase().includes(q) || d.phone.includes(q);
        if (!matches) return false;
      }
      if (statusFilters.length) {
        const ok =
          (statusFilters.includes('active') && d.accountStatus === 'ACTIVE') ||
          (statusFilters.includes('suspended') && d.accountStatus === 'SUSPENDED') ||
          (statusFilters.includes('pending') && d.accountStatus === 'PENDING_SETUP');
        if (!ok) return false;
      }
      if (activityFilters.length) {
        const isBusy = isDriverEnLivraison(d);
        const ok =
          (activityFilters.includes('busy') && isBusy) ||
          (activityFilters.includes('available') && !isBusy && d.accountStatus === 'ACTIVE');
        if (!ok) return false;
      }
      return true;
    });
  }, [drivers, searchTerm, statusFilters, activityFilters, isDriverEnLivraison]);

  // Client-side pagination: the drivers list is bounded (dozens–hundreds) and the busy/activity filter
  // is cross-service enrichment, so we page the already-filtered set in-memory — instant, and Export/
  // filters keep operating over the whole set. Reset to page 1 whenever the filtered shape changes.
  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  useEffect(() => { setPage(0); }, [searchTerm, statusFilters, activityFilters, pageSize]);
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = useMemo(
    () => filtered.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [filtered, safePage, pageSize],
  );

  // ── CRUD handlers ─────────────────────────────────────────────────────────
  const openCreate = () => { setEditingDriver(null); setCrudOpen(true); };
  const openEdit = (drv: Driver) => { setEditingDriver(drv); setCrudOpen(true); };

  const saveDriver = async (data: DriverCrud) => {
    try {
      if (editingDriver) {
        await updateDriverMutation.mutateAsync({ id: editingDriver.id, payload: { name: data.name, phone: data.phone, email: data.email } });
      } else {
        await createDriverMutation.mutateAsync({ name: data.name, phone: data.phone, email: data.email });
      }
      setCrudOpen(false);
    } catch (err) {
      // Propagate so the modal can surface field-level errors (e.g. email/phone taken) under the
      // field. The generic toast is already handled in the mutation hook (and suppressed for
      // field-mapped codes), so no double feedback.
      throw err;
    }
  };

  const toggleActive = async (drv: Driver) => {
    setPendingRowId(drv.id);
    try { await toggleStatusMutation.mutateAsync({ id: drv.id, isRegistered: !drv.isRegistered }); }
    catch { /* handled */ } finally { setPendingRowId(null); }
  };

  const openCancelInvite = (drv: Driver) => { setConfirmCancel(drv); setCancelReason(''); };
  const confirmCancelInviteAction = async () => {
    if (!confirmCancel) return;
    setPendingRowId(confirmCancel.id);
    try { await cancelInviteMutation.mutateAsync({ id: confirmCancel.id, reason: cancelReason || undefined }); setConfirmCancel(null); setCancelReason(''); }
    catch { /* handled */ } finally { setPendingRowId(null); }
  };

  const openSuspend = (drv: Driver) => {
    if (drv.accountStatus === 'PENDING_SETUP') { showErrorToast(null, 'errorPendingStatusLocked'); return; }
    setConfirmSuspend(drv); setSuspendReason('');
  };
  const confirmSuspendAction = async () => {
    if (!confirmSuspend) return;
    setPendingRowId(confirmSuspend.id);
    try { await toggleStatusMutation.mutateAsync({ id: confirmSuspend.id, isRegistered: false, reason: suspendReason || undefined }); setConfirmSuspend(null); setSuspendReason(''); }
    catch { /* handled */ } finally { setPendingRowId(null); }
  };

  const handleResendInvite = async (drv: Driver) => {
    if (resendCooldown > 0) return;
    setPendingRowId(drv.id);
    try {
      await resendInviteMutation.mutateAsync(drv.id);
      setResendCooldown(60);
      if (resendIntervalRef.current) clearInterval(resendIntervalRef.current);
      resendIntervalRef.current = setInterval(() => { setResendCooldown((prev) => { if (prev <= 1) { if (resendIntervalRef.current) { clearInterval(resendIntervalRef.current); resendIntervalRef.current = null; } return 0; } return prev - 1; }); }, 1000);
    } catch (err) {
      const retryAfter = (err as { response?: { data?: { retryAfterSeconds?: number } } })?.response?.data?.retryAfterSeconds;
      if (typeof retryAfter === 'number' && retryAfter > 0) {
        setResendCooldown(retryAfter);
        if (resendIntervalRef.current) clearInterval(resendIntervalRef.current);
        resendIntervalRef.current = setInterval(() => { setResendCooldown((prev) => { if (prev <= 1) { if (resendIntervalRef.current) { clearInterval(resendIntervalRef.current); resendIntervalRef.current = null; } return 0; } return prev - 1; }); }, 1000);
      }
    } finally { setPendingRowId(null); }
  };

  const handleForceLogout = async (id: string) => {
    setPendingRowId(id);
    try { await forceLogoutDriverMutation.mutateAsync(id); }
    catch { /* handled */ } finally { setPendingRowId(null); }
  };

  const importCsv = async (file: File | null) => {
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    try { await importDriversMutation.mutateAsync(formData); } catch { /* handled */ }
  };

  const saving = createDriverMutation.isPending || updateDriverMutation.isPending;

  const gridCols = [
    '8px', '220px',
    ...(orderedColumns.filter(c => !c.pinned && visibleIds.has(c.id)).map(c =>
      c.id === 'contact' ? '130px' : c.id === 'activity' ? '1fr' : c.id === 'status' ? '120px' : 'auto'
    )),
    '90px',
  ].join(' ');

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
      <PageFilterBar
        search={searchTerm}
        onSearch={setSearchTerm}
        searchPlaceholder={t.driversPage.searchPlaceholder}
        attributes={[
          { key: 'status', label: t.routesTablePage?.filterStatusLabel ?? t.common?.statut ?? 'Status', multi: true, options: [
            { value: 'active',    label: t.driversPage.statusActive ?? 'Active' },
            { value: 'suspended', label: t.driversPage.statusSuspended ?? 'Suspended' },
            { value: 'pending',   label: t.driversPage.statusPending ?? 'Pending' },
          ]},
          { key: 'activity', label: t.driversPage.tabFilters ?? 'Activity', multi: true, options: [
            { value: 'busy',      label: t.driversPage.busy ?? 'On Mission' },
            { value: 'available', label: t.driversPage.available ?? 'Available' },
          ]},
        ]}
        activeFilters={{
          ...(statusFilters.length && { status: statusFilters }),
          ...(activityFilters.length && { activity: activityFilters }),
        }}
        onFilterChange={(key, val) => {
          const toggle = (arr: string[], v: string) => arr.includes(v) ? arr.filter(x => x !== v) : [...arr, v];
          if (key === 'status')   setStatusFilters(val === null ? [] : toggle(statusFilters, val));
          if (key === 'activity') setActivityFilters(val === null ? [] : toggle(activityFilters, val));
        }}
        onRefresh={fetchDrivers}
        refreshing={loading}
        extraActions={
          <div className="flex items-center gap-1.5">
            <ExportCsvButton
              baseName={t.common?.chauffeur ?? 'drivers'}
              rows={filtered}
              columns={[
                { header: t.common?.nom ?? 'Name', accessor: d => d.name },
                { header: t.common?.telephone ?? 'Phone', accessor: d => d.phone },
                { header: t.driversPage?.tableHeaderAccountStatus ?? 'Account Status', accessor: d => d.accountStatus },
                { header: t.driversPage?.statusOnline ?? 'Online', accessor: d => d.onlineStatus },
              ]}
            />
            {!readOnly && (
              <>
                <input ref={csvInputRef} type="file" accept=".csv" className="hidden" onChange={(e) => importCsv(e.target.files?.[0] ?? null)} />
                <AddButton label={t.driversPage.newDriverButton} onClick={openCreate} />
                <button type="button" className="h-7 px-2.5 flex items-center gap-1.5 text-xs font-[500] rounded-md border transition-colors hover:bg-[var(--hover-bg)] shrink-0" style={{ borderColor: 'var(--border)', color: 'var(--text-muted)' }} onClick={() => csvInputRef.current?.click()}>
                  <SVGUpload size={13} /> {t.driversPage.importCsvButton}
                </button>
              </>
            )}
          </div>
        }
      />

      <div className="flex flex-1 min-h-0 overflow-hidden">
        <div className="flex flex-col flex-1 overflow-hidden min-w-0" style={{ background: 'var(--app-bg)' }}>
          {/* Toolbar */}
          <div className="flex items-center justify-between px-4 h-11 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
            <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
              {filtered.length} {t.common?.chauffeur ?? 'driver'}{filtered.length !== 1 ? 's' : ''}
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
            {isMobile ? (
              <div className="flex flex-col gap-3 p-4">
                {loading ? (
                  Array.from({ length: 5 }).map((_, i) => (
                    <div key={i} className="h-28 border border-[var(--border)] rounded-md animate-pulse bg-[var(--surface)]/50" />
                  ))
                ) : filtered.length === 0 ? (
                  <EmptyState icon={<SVGUser size={32} />} message={t.empty?.drivers || 'Aucun chauffeur enregistré pour le moment.'} />
                ) : (
                  pageRows.map((drv) => (
                    <DriverMobileCard
                      key={drv.id}
                      drv={drv}
                      t={t}
                      readOnly={readOnly}
                      resendCooldown={resendCooldown}
                      isDriverEnLivraison={isDriverEnLivraison}
                      routeNameById={routeNameById}
                      onOpenRoute={openRoute}
                      onOpenDetails={(id) => { setSelectedId(id); setDetailsOpen(true); }}
                      onEdit={openEdit}
                      onResendInvite={handleResendInvite}
                      onCancelInvite={openCancelInvite}
                      onSuspend={openSuspend}
                      onForceLogout={handleForceLogout}
                      onActivate={toggleActive}
                    />
                  ))
                )}
              </div>
            ) : (
              <div className="min-w-[1000px] lg:min-w-0">
                {/* Header Grid Row */}
                <div
                  className="sticky top-0 z-10 grid gap-4 items-center h-[44px] px-0 border-b border-[var(--border)]"
                  style={{ gridTemplateColumns: gridCols, background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}
                >
                  <div className="w-[8px]" />
                  <span className="text-xs font-semibold text-[var(--text-muted)] text-start">{t.driversPage.tableHeaderDriver}</span>
                  {orderedColumns.filter(c => !c.pinned).map(col => !visibleIds.has(col.id) ? null : (
                    <span key={col.id} className="text-xs font-semibold text-[var(--text-muted)] text-start">
                      {col.id === 'contact' ? t.driversPage.tableHeaderContact
                       : col.id === 'activity' ? t.driversPage.tableHeaderActivity
                       : t.driversPage.tableHeaderAccountStatus || 'Account Status'}
                    </span>
                  ))}
                  <span className="text-xs font-semibold text-[var(--text-muted)] text-end pe-6">{t.driversPage.tableHeaderActions}</span>
                </div>

                {/* Data Rows */}
                {loading ? (
                  Array.from({ length: 12 }).map((_, i) => (
                    <div key={i} className="h-[52px] border-b border-[var(--border)] animate-pulse bg-[var(--surface)]/50" />
                  ))
                ) : filtered.length === 0 ? (
                  <EmptyState icon={<SVGUser size={32} />} message={t.empty?.drivers || 'Aucun chauffeur enregistré pour le moment.'} />
                ) : (
                  pageRows.map((drv) => (
                    <DriverTableRow
                      key={drv.id}
                      drv={drv}
                      gridCols={gridCols}
                      rowHeight={DRIVER_ROW_H[density]}
                      orderedColumns={orderedColumns}
                      visibleIds={visibleIds}
                      t={t}
                      readOnly={readOnly}
                      resendCooldown={resendCooldown}
                      isDriverEnLivraison={isDriverEnLivraison}
                      routeNameById={routeNameById}
                      onOpenRoute={openRoute}
                      onOpenDetails={(id) => { setSelectedId(id); setDetailsOpen(true); }}
                      onEdit={openEdit}
                      onResendInvite={handleResendInvite}
                      onCancelInvite={openCancelInvite}
                      onSuspend={openSuspend}
                      onForceLogout={handleForceLogout}
                      onActivate={toggleActive}
                    />
                  ))
                )}
              </div>
            )}
          </div>
          <TablePagination
            page={safePage}
            totalPages={totalPages}
            totalElements={filtered.length}
            size={pageSize}
            onPageChange={setPage}
            onSizeChange={setPageSize}
          />
        </div>
      </div>

      <DriverDetailsModal
        open={detailsOpen}
        onClose={() => setDetailsOpen(false)}
        driver={selected}
        deliveries={driverDeliveries}
        detailLoading={detailLoading}
        activeTab={activeTab}
        setActiveTab={setActiveTab}
        readOnly={readOnly}
        locale={locale}
        t={t}
        resendCooldown={resendCooldown}
        isDriverEnLivraison={isDriverEnLivraison}
        onEdit={openEdit}
        onResendInvite={handleResendInvite}
        onCancelInvite={openCancelInvite}
      />

      <DriverFormModal
        open={crudOpen}
        onClose={() => setCrudOpen(false)}
        editingDriver={editingDriver}
        onSubmit={saveDriver}
        saving={saving}
        t={t}
      />

      <ConfirmModal
        open={!!confirmCancel}
        title={t.driversPage.cancelInviteTitle}
        description={confirmCancel ? t.driversPage.cancelInviteDescription.replace('{driverName}', confirmCancel.name) : ''}
        reasonLabel={t.driversPage.cancelInviteReasonLabel}
        reasonPlaceholder={t.driversPage.cancelInviteReasonPlaceholder}
        reason={cancelReason}
        onReasonChange={setCancelReason}
        confirmLabel={t.driversPage.cancelInviteButton}
        cancelLabel={t.driversPage.cancelButton}
        variant="danger"
        loading={pendingRowId === confirmCancel?.id}
        onConfirm={confirmCancelInviteAction}
        onCancel={() => { setConfirmCancel(null); setCancelReason(''); }}
      />

      <ConfirmModal
        open={!!confirmSuspend}
        title={t.driversPage.suspendDriverTitle}
        description={confirmSuspend ? t.driversPage.suspendDriverDescription.replace('{driverName}', confirmSuspend.name) : ''}
        reasonLabel={t.driversPage.suspendReasonLabel}
        reasonPlaceholder={t.driversPage.suspendReasonPlaceholder}
        reason={suspendReason}
        onReasonChange={setSuspendReason}
        confirmLabel={t.driversPage.suspendDriverButton}
        cancelLabel={t.driversPage.cancelButton}
        variant="danger"
        loading={pendingRowId === confirmSuspend?.id}
        onConfirm={confirmSuspendAction}
        onCancel={() => { setConfirmSuspend(null); setSuspendReason(''); }}
      />
    </div>
  );
}

export default function DriversPage() {
  return (
    <Suspense fallback={
      <div className="flex items-center justify-center h-screen bg-[var(--app-bg)]">
        <svg className="animate-spin h-10 w-10 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
        </svg>
      </div>
    }>
      <TooltipProvider>
        <DriversPageContent />
      </TooltipProvider>
    </Suspense>
  );
}
