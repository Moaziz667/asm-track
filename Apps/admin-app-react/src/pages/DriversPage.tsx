import { useEffect, useState, useCallback, useMemo, useRef, Suspense } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '@/lib/api';
import { Driver, Delivery } from '@/types';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { getCurrentRole, isReadOnlyRole } from '@/lib/auth';
import { showErrorToast } from '@/lib/toast-service';
import {
  useDrivers,
  useCreateDriver,
  useUpdateDriver,
  useToggleDriverStatus,
  useCancelDriverInvite,
  useResendDriverInvite,
  useImportDrivers,
} from '@/hooks/useDrivers';

// Custom inline SVG icons for a natural human aesthetic
const SVGPlus = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M12 5v14M5 12h14" />
  </svg>
);

const SVGUpload = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" />
  </svg>
);

const SVGSearch = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <circle cx="11" cy="11" r="8" />
    <path d="m21 21-4.3-4.3" />
  </svg>
);

const SVGUser = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </svg>
);

const SVGActivity = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
  </svg>
);

const SVGBan = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <circle cx="12" cy="12" r="10" />
    <path d="m4.9 4.9 14.2 14.2" />
  </svg>
);

const SVGLock = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <rect width="18" height="11" x="3" y="11" rx="2" ry="2" />
    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
  </svg>
);

const SVGRefresh = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M3 21v-5h5" />
  </svg>
);

const SVGPhone = ({ size = 10, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
  </svg>
);

const SVGDots = ({ size = 14, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <circle cx="12" cy="12" r="1" />
    <circle cx="19" cy="12" r="1" />
    <circle cx="5" cy="12" r="1" />
  </svg>
);

const SVGPencil = ({ size = 13, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
  </svg>
);

const SVGSend = ({ size = 13, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="m22 2-7 20-4-9-9-4Z M22 2 11 13" />
  </svg>
);

const SVGX = ({ size = 13, className = "" }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M18 6 6 18M6 6l12 12" />
  </svg>
);

// Shadcn UI Components
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { FieldInput } from '@/components/ui/field';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { AppModal } from '@/components/overlays/AppModal';
import { Skeleton } from '@/components/ui/skeleton';
import StatusBadge from '@/components/StatusBadge';
import { EmptyState } from '@/components/feedback/EmptyState';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { TooltipProvider } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';

const TERMINAL_STATUSES = new Set(['DELIVERED', 'FAILED', 'CANCELLED', 'PARTIALLY_DELIVERED']);

type DriverCrud = {
  id?: string;
  name: string;
  phone: string;
  email: string;
};

// ── Aligned StatusBadge for Driver Account Status (Linear Philosophy) ─────────
interface DriverStatusBadgeProps {
  status: string;
  size?: 'sm' | 'md';
}

const DRIVER_STATUS_COLORS: Record<string, { dot: string; bg: string; text: string; ribbon: string }> = {
  ACTIVE:        { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E', ribbon: '#4CAF82' },
  SUSPENDED:     { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24', ribbon: '#C7372F' },
  PENDING_SETUP: { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10', ribbon: '#C4881A' },
};

function DriverStatusBadge({ status, size = 'md' }: DriverStatusBadgeProps) {
  const t = useT();
  const cfg = DRIVER_STATUS_COLORS[status] ?? { dot: '#8A8F98', bg: 'rgba(138,143,152,0.08)', text: '#6B7280', ribbon: '#8A8F98' };
  
  const displayLabel = status === 'ACTIVE' ? t.driversPage.statusActive 
                     : status === 'SUSPENDED' ? t.driversPage.statusSuspended 
                     : status === 'PENDING_SETUP' ? t.driversPage.statusPending 
                     : status;

  const dotPx = size === 'sm' ? 5 : 5.5;
  const fontSize = size === 'sm' ? 10 : 11;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 7 : 8;

  return (
    <span
      role="status"
      aria-label={displayLabel}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        height,
        padding: `0 ${px}px`,
        borderRadius: 99,
        background: cfg.bg,
        flexShrink: 0,
      }}
    >
      <span
        style={{
          width: dotPx,
          height: dotPx,
          borderRadius: '50%',
          background: cfg.dot,
          flexShrink: 0,
        }}
      />
      <span
        style={{
          fontSize,
          fontWeight: 500,
          color: cfg.text,
          letterSpacing: '-0.01em',
          lineHeight: 1,
          whiteSpace: 'nowrap',
        }}
      >
        {displayLabel}
      </span>
    </span>
  );
}

// ── Main Page Content Component ──────────────────────────────────────────────
function DriversPageContent() {
  const t = useT();
  const { locale } = useLocaleStore();
  usePageBreadcrumb([{ label: t.pages.drivers?.title || t.driversPage.pageTitle || 'Chauffeurs' }]);
  const role = getCurrentRole();
  const readOnly = isReadOnlyRole(role);
  const [activeTab, setActiveTab] = useState<'info' | 'mission' | 'activity'>('info');

  // TanStack Query Hooks
  const { data: drivers = [], isLoading: loading, refetch: fetchDrivers } = useDrivers();
  const createDriverMutation = useCreateDriver();
  const updateDriverMutation = useUpdateDriver();
  const toggleStatusMutation = useToggleDriverStatus();
  const cancelInviteMutation = useCancelDriverInvite();
  const resendInviteMutation = useResendDriverInvite();
  const importDriversMutation = useImportDrivers();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [driverDeliveries, setDriverDeliveries] = useState<Delivery[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  // CRUD state
  const [crudOpen, setCrudOpen] = useState(false);
  const [editingDriver, setEditingDriver] = useState<Driver | null>(null);
  const [form, setForm] = useState<DriverCrud>({ name: '', phone: '', email: '' });
  const csvInputRef = useRef<HTMLInputElement>(null);

  // Modals state
  const [confirmCancel, setConfirmCancel] = useState<Driver | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [confirmSuspend, setConfirmSuspend] = useState<Driver | null>(null);
  const [suspendReason, setSuspendReason] = useState('');

  const [pendingRowId, setPendingRowId] = useState<string | null>(null);
  const [resendCooldown, setResendCooldown] = useState<number>(0);
  const [operationalFilter, setOperationalFilter] = useState<'all' | 'active' | 'suspended' | 'pending'>('all');
  const [activityFilter, setActivityFilter] = useState<'all' | 'busy' | 'available'>('all');
  const [mobileTab, setMobileTab] = useState<'filters' | 'list'>('list');

  const selected = drivers.find((d) => d.id === selectedId) ?? null;

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

  const isDriverEnLivraison = useCallback((driver: Driver) => {
    return Boolean(driver.activeDeliveryId) || Boolean(driver.activeRouteId);
  }, []);

  const filtered = useMemo(() => {
    return drivers.filter((d) => {
      // Search term
      if (searchTerm) {
        const q = searchTerm.toLowerCase();
        const matches = d.name.toLowerCase().includes(q) || d.phone.includes(q);
        if (!matches) return false;
      }
      
      // Status filter
      if (operationalFilter !== 'all') {
        if (operationalFilter === 'active' && d.accountStatus !== 'ACTIVE') return false;
        if (operationalFilter === 'suspended' && d.accountStatus !== 'SUSPENDED') return false;
        if (operationalFilter === 'pending' && d.accountStatus !== 'PENDING_SETUP') return false;
      }

      // Activity filter
      if (activityFilter !== 'all') {
        const isBusy = isDriverEnLivraison(d);
        if (activityFilter === 'busy' && !isBusy) return false;
        if (activityFilter === 'available' && (isBusy || d.accountStatus !== 'ACTIVE')) return false;
      }

      return true;
    });
  }, [drivers, searchTerm, operationalFilter, activityFilter, isDriverEnLivraison]);

  const stats = useMemo(() => {
    const totalDrivers = drivers.length;
    const busyDrivers = drivers.filter((d) => isDriverEnLivraison(d)).length;
    const pendingDrivers = drivers.filter((d) => d.accountStatus === 'PENDING_SETUP').length;
    const suspendedDrivers = drivers.filter((d) => d.accountStatus === 'SUSPENDED').length;
    const availableDrivers = drivers.filter((d) => !isDriverEnLivraison(d) && d.accountStatus === 'ACTIVE').length;
    return { totalDrivers, busyDrivers, pendingDrivers, suspendedDrivers, availableDrivers };
  }, [drivers, isDriverEnLivraison]);

  const activeDeliveriesForSelected = driverDeliveries.filter((d) => !TERMINAL_STATUSES.has(d.status));
  const historyDeliveriesForSelected = driverDeliveries.filter((d) => TERMINAL_STATUSES.has(d.status));

  // ── CRUD handlers ─────────────────────────────────────────────────────────
  const openCreate = () => {
    setEditingDriver(null);
    setForm({ name: '', phone: '', email: '' });
    setCrudOpen(true);
  };

  const openEdit = (drv: Driver) => {
    setEditingDriver(drv);
    setForm({ id: drv.id, name: drv.name, phone: drv.phone, email: '' });
    setCrudOpen(true);
  };

  const saveDriver = async () => {
    if (!form.name.trim() || !form.phone.trim()) {
      return showErrorToast(null, 'errorDriverNameRequired');
    }
    if (!editingDriver && !form.email.trim()) {
      return showErrorToast(null, 'errorDriverEmailRequired');
    }
    
    try {
      if (editingDriver) {
        await updateDriverMutation.mutateAsync({
          id: editingDriver.id,
          payload: { name: form.name, phone: form.phone }
        });
      } else {
        await createDriverMutation.mutateAsync({
          name: form.name,
          phone: form.phone,
          email: form.email
        });
      }
      setCrudOpen(false);
    } catch (err) {}
  };

  const toggleActive = async (drv: Driver) => {
    setPendingRowId(drv.id);
    try {
      await toggleStatusMutation.mutateAsync({ id: drv.id, active: !drv.active });
    } catch {} finally {
      setPendingRowId(null);
    }
  };

  const openCancelInvite = (drv: Driver) => {
    setConfirmCancel(drv);
    setCancelReason('');
  };

  const confirmCancelInviteAction = async () => {
    if (!confirmCancel) return;
    setPendingRowId(confirmCancel.id);
    try {
      await cancelInviteMutation.mutateAsync({ id: confirmCancel.id, reason: cancelReason || undefined });
      setConfirmCancel(null);
      setCancelReason('');
    } catch {} finally {
      setPendingRowId(null);
    }
  };

  const openSuspend = (drv: Driver) => {
    if (drv.accountStatus === 'PENDING_SETUP') {
      showErrorToast(null, 'errorPendingStatusLocked');
      return;
    }
    setConfirmSuspend(drv);
    setSuspendReason('');
  };

  const confirmSuspendAction = async () => {
    if (!confirmSuspend) return;
    setPendingRowId(confirmSuspend.id);
    try {
      await toggleStatusMutation.mutateAsync({
        id: confirmSuspend.id,
        active: false,
        reason: suspendReason || undefined,
      });
      setConfirmSuspend(null);
      setSuspendReason('');
    } catch {} finally {
      setPendingRowId(null);
    }
  };

  const handleResendInvite = async (drv: Driver) => {
    if (resendCooldown > 0) return;
    setPendingRowId(drv.id);
    try {
      const result = await resendInviteMutation.mutateAsync(drv.id);
      setResendCooldown(60);
      const interval = setInterval(() => {
        setResendCooldown((prev) => {
          if (prev <= 1) {
            clearInterval(interval);
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
      void result;
    } catch (err: any) {
      const retryAfter = err?.response?.data?.retryAfterSeconds;
      if (typeof retryAfter === 'number' && retryAfter > 0) {
        setResendCooldown(retryAfter);
        const interval = setInterval(() => {
          setResendCooldown((prev) => {
            if (prev <= 1) {
              clearInterval(interval);
              return 0;
            }
            return prev - 1;
          });
        }, 1000);
      }
    } finally {
      setPendingRowId(null);
    }
  };

  const importCsv = async (file: File | null) => {
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    try {
      await importDriversMutation.mutateAsync(formData);
    } catch {}
  };

  const saving = createDriverMutation.isPending || updateDriverMutation.isPending;

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden bg-[var(--app-bg)] flex flex-col">
      {/* Mobile Tab Bar */}
      <div className="lg:hidden flex shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        {([['filters', t.routesTablePage?.tabFilters || 'Filtres'], ['list', t.routesTablePage?.tabList || 'Liste']] as const).map(([tab, label]) => (
          <button
            key={tab}
            type="button"
            onClick={() => setMobileTab(tab)}
            className={cn(
              'flex-1 h-10 text-[12px] font-bold tracking-wide transition-colors',
              mobileTab === tab
                ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]'
                : 'text-[var(--text-soft)]'
            )}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* ── Left Sidebar (Utility Rail) ────────────────────────────────── */}
        <div className={cn(
          'lg:w-[280px] lg:border-e border-[var(--border)] bg-[var(--surface)] shrink-0 overflow-y-auto flex flex-col',
          mobileTab === 'filters' ? 'flex w-full' : 'hidden lg:flex'
        )}>
          {/* Header */}
          <div className="p-5 border-b border-[var(--border)]">
            <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.driversPage.pageSubtitle}</span>
            <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
              {t.driversPage.pageTitle} <span className="text-[var(--brand)]">{t.driversPage.pageTitleBrand}</span>
            </h1>
          </div>

          {/* Action buttons + search */}
          <div className="flex flex-col gap-3 p-5 border-b border-[var(--border)]">
            {!readOnly && (
              <>
                <button
                  type="button"
                  className="w-full h-9 bg-[var(--brand)] hover:opacity-90 text-white font-bold text-[11px] rounded-[2px] flex items-center justify-center gap-2 transition-opacity"
                  onClick={openCreate}
                >
                  <SVGPlus size={14} />
                  {t.driversPage.newDriverButton}
                </button>
                <input
                  ref={csvInputRef}
                  type="file"
                  accept=".csv"
                  className="hidden"
                  onChange={(e) => importCsv(e.target.files?.[0] ?? null)}
                />
                <button
                  type="button"
                  className="w-full h-9 border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] text-[var(--text-soft)] font-bold text-[11px] rounded-[2px] flex items-center justify-center gap-2 transition-colors"
                  onClick={() => csvInputRef.current?.click()}
                >
                  <SVGUpload size={14} />
                  {t.driversPage.importCsvButton}
                </button>
              </>
            )}
            <FieldInput
              placeholder={t.driversPage.searchPlaceholder}
              leftSection={<SVGSearch size={14} className="text-[var(--text-muted)]" />}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="h-9 text-[11px]"
            />
          </div>

          {/* Status Navigation Pills */}
          <div className="flex-1 overflow-y-auto">
            <div className="flex flex-col gap-0 p-2.5">
              <p className="px-3 mb-2 text-[11px] font-[600] text-[var(--text-muted)]">{t.routesTablePage?.filterStatusLabel || 'Statut'}</p>
              {[
                { id: 'all', label: t.driversPage.allFleet || 'Toute la flotte', icon: <SVGUser size={14} />, count: stats.totalDrivers },
                { id: 'active', label: t.driversPage.statusActive || 'Actif', icon: <SVGActivity size={14} />, count: stats.totalDrivers - stats.pendingDrivers - stats.suspendedDrivers },
                { id: 'suspended', label: t.driversPage.statusSuspended || 'Suspendu', icon: <SVGBan size={14} />, count: stats.suspendedDrivers },
                { id: 'pending', label: t.driversPage.statusPending || 'Invitation', icon: <SVGLock size={14} />, count: stats.pendingDrivers },
              ].map((pill) => (
                <button
                  type="button"
                  key={pill.id}
                  onClick={() => setOperationalFilter(pill.id as any)}
                  className={cn(
                    "px-4 py-3 rounded-[2px] transition-all flex items-center justify-between group text-start",
                    operationalFilter === pill.id
                      ? "bg-[var(--surface)] border-s-2 border-[var(--brand)] ps-[14px]"
                      : "hover:bg-[var(--surface)] border-s-2 border-transparent"
                  )}
                >
                  <div className="flex items-center gap-2">
                    <span style={{ color: operationalFilter === pill.id ? 'var(--brand)' : 'var(--text-soft)' }}>{pill.icon}</span>
                    <span className={cn(
                      "text-[11px] font-[600]",
                      operationalFilter === pill.id ? "text-[var(--text-primary)]" : "text-[var(--text-soft)]"
                    )}>{pill.label}</span>
                  </div>
                  <span className="text-[10px] font-[800] font-mono text-[var(--text-muted)]">{pill.count}</span>
                </button>
              ))}

              {/* Divider */}
              <div className="h-px bg-[var(--border)] my-3.5" />

              <p className="px-3 mb-2 text-[11px] font-[600] text-[var(--text-muted)]">{t.driversPage.tabFilters || 'Activité'}</p>
              
              <div className="flex flex-col gap-1 px-2.5">
                {[
                  { id: 'all', label: t.driversPage.allFleet || 'Tous' },
                  { id: 'busy', label: t.driversPage.busy || 'En mission' },
                  { id: 'available', label: t.driversPage.available || 'Disponible' }
                ].map((item) => (
                  <button
                    type="button"
                    key={item.id}
                    onClick={() => setActivityFilter(item.id as any)}
                    className={cn(
                      "w-full h-8 text-start px-3 text-[11px] font-semibold rounded-[2px] transition-colors flex items-center justify-between",
                      activityFilter === item.id 
                        ? "bg-[var(--hover-bg)] text-[var(--text-primary)] font-bold" 
                        : "text-[var(--text-soft)] hover:bg-[var(--hover-bg)]"
                    )}
                  >
                    <span>{item.label}</span>
                    {item.id === 'busy' && (
                      <span className="text-[10px] font-bold text-sky-600 bg-sky-50 px-1.5 py-0.5 rounded font-mono">{stats.busyDrivers}</span>
                    )}
                    {item.id === 'available' && (
                      <span className="text-[10px] font-bold text-emerald-600 bg-emerald-50 px-1.5 py-0.5 rounded font-mono">{stats.availableDrivers}</span>
                    )}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Refresh footer */}
          <div className="p-5 border-t border-[var(--border)] bg-[var(--surface)] shrink-0">
            <button
              type="button"
              className="w-full font-bold text-[11px] text-[var(--text-soft)] flex items-center justify-center gap-2 h-8 rounded hover:bg-[var(--hover-bg)] transition-colors"
              onClick={() => fetchDrivers()}
            >
              <SVGRefresh size={14} className={loading ? 'animate-spin' : ''} />
              {t.driversPage.refreshButton || 'Actualiser'}
            </button>
          </div>
        </div>

        {/* ── Right Slab Registro (Main Table) ─────────────────────────────── */}
        <div className={cn(
          'flex flex-col flex-1 bg-[var(--surface)] overflow-hidden min-w-0',
          mobileTab === 'list' ? 'flex' : 'hidden lg:flex'
        )}>
          <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
            <div className="min-w-[1000px] lg:min-w-0">
              {/* Header Grid Row */}
              <div className="sticky top-0 bg-[var(--surface)] z-10 grid grid-cols-[8px_220px_130px_1fr_120px_90px] gap-4 items-center h-[44px] px-0 border-b border-[var(--border)]">
                <div className="w-[8px]" />
                <span className="text-[11px] font-[600] text-[var(--text-muted)] text-start">{t.driversPage.tableHeaderDriver}</span>
                <span className="text-[11px] font-[600] text-[var(--text-muted)] text-start">{t.driversPage.tableHeaderContact}</span>
                <span className="text-[11px] font-[600] text-[var(--text-muted)] text-start">{t.driversPage.tableHeaderActivity}</span>
                <span className="text-[11px] font-[600] text-[var(--text-muted)] text-start">{t.driversPage.statusActive || 'Statut'}</span>
                <span className="text-[11px] font-[600] text-[var(--text-muted)] text-end pe-6">{t.driversPage.tableHeaderActions}</span>
              </div>

              {/* Data Rows */}
              {loading ? (
                Array.from({ length: 12 }).map((_, i) => (
                  <div key={i} className="h-[52px] border-b border-[var(--border)] animate-pulse bg-[var(--surface)]/50" />
                ))
              ) : filtered.length === 0 ? (
                <EmptyState 
                  icon={<SVGUser size={32} />} 
                  message={t.empty?.drivers || 'Aucun chauffeur enregistré pour le moment.'} 
                />
              ) : (
                filtered.map((drv) => {
                  const inLivraison = isDriverEnLivraison(drv);
                  const statusConfig = DRIVER_STATUS_COLORS[drv.accountStatus ?? 'PENDING_SETUP'] ?? {
                    dot: '#8A8F98', bg: 'rgba(138,143,152,0.08)', text: '#6B7280', ribbon: '#8A8F98'
                  };

                  return (
                    <div key={drv.id} className="border-b border-[var(--border)]">
                      <div
                        className="grid grid-cols-[8px_220px_130px_1fr_120px_90px] gap-4 items-center h-[52px] cursor-pointer group hover:bg-[var(--hover-bg)] transition-colors"
                        onClick={() => { setSelectedId(drv.id); setDetailsOpen(true); }}
                      >
                        {/* Ribbon indicator reflecting the status exact color */}
                        <div className="w-[3px] h-4 rounded-r-[1px] rtl:rounded-l-[1px] rtl:rounded-r-none" style={{ backgroundColor: statusConfig.ribbon }} />

                        {/* Driver info (Avatar & Name/Email) */}
                        <div className="flex items-center gap-3 overflow-hidden text-start">
                          <div className="relative shrink-0 select-none">
                            <div className="h-8 w-8 rounded-full border border-[var(--border)] bg-[var(--surface)] flex items-center justify-center font-mono text-[10px] font-bold text-[var(--brand)]">
                              {drv.name.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase()}
                            </div>
                            {drv.accountStatus === 'ACTIVE' && (
                              <span 
                                className={cn(
                                  "absolute bottom-0 right-0 block h-2 w-2 rounded-full ring-1 ring-[var(--surface)]",
                                  drv.onlineStatus === 'ONLINE' ? "bg-emerald-500" : "bg-slate-400"
                                )} 
                              />
                            )}
                          </div>
                          <div className="flex flex-col gap-0.5 truncate">
                            <span className="text-[11px] font-[700] text-[var(--text-primary)] truncate">{drv.name}</span>
                            {drv.email && (
                              <span className="text-[9px] font-[500] text-[var(--text-muted)] truncate">{drv.email}</span>
                            )}
                          </div>
                        </div>

                        {/* Phone details */}
                        <div className="flex items-center gap-1.5 text-start">
                          <SVGPhone size={10} className="text-[var(--text-muted)]" />
                          <span className="text-[11px] font-[600] text-[var(--text-soft)] font-mono tracking-wide">{drv.phone}</span>
                        </div>

                        {/* Mission status */}
                        <div className="flex items-center gap-1.5 flex-nowrap text-start">
                          {drv.activeDeliveryId ? (
                            <div className="flex items-center gap-1.5">
                              <StatusBadge status="IN_TRANSIT" size="sm" label={t.driversPage.activeDelivery} />
                              <span className="font-mono text-[9px] font-bold text-[var(--text-muted)] bg-[var(--surface)] px-1.5 py-0.5 rounded border border-[var(--border)]">
                                {drv.activeDeliveryId.slice(0, 8).toUpperCase()}
                              </span>
                            </div>
                          ) : drv.activeRouteId ? (
                            <div className="flex items-center gap-1.5">
                              <StatusBadge status="IN_PROGRESS" size="sm" label={t.driversPage.onRoute} />
                              <span className="font-mono text-[9px] font-bold text-[var(--text-muted)] bg-[var(--surface)] px-1.5 py-0.5 rounded border border-[var(--border)]">
                                {drv.activeRouteId.slice(0, 8).toUpperCase()}
                              </span>
                            </div>
                          ) : (
                            <StatusBadge status="CLOSED" size="sm" label={t.driversPage.free} />
                          )}
                        </div>

                        {/* Custom Badge & Online Status */}
                        <div className="text-start flex flex-col gap-0.5 items-start justify-center">
                          <DriverStatusBadge status={drv.accountStatus ?? 'PENDING_SETUP'} size="sm" />
                          {drv.accountStatus === 'ACTIVE' && (
                            <span className="text-[8px] font-bold text-[var(--text-muted)] tracking-wider">
                              {drv.onlineStatus === 'ONLINE' ? 'ONLINE' : 'OFFLINE'}
                            </span>
                          )}
                        </div>

                        {/* Slick action dropdown */}
                        <div className="flex items-center justify-end pe-6" onClick={(e) => e.stopPropagation()}>
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <button
                                type="button"
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--surface)] hover:text-[var(--text-primary)] transition-all shrink-0"
                              >
                                <SVGDots size={14} />
                              </button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end" className="w-40 bg-[var(--surface)] border border-[var(--border)] shadow-lg rounded-sm p-1">
                              <DropdownMenuItem
                                onClick={() => { setSelectedId(drv.id); setDetailsOpen(true); }}
                                className="text-[11px] font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5"
                              >
                                <SVGUser size={13} className="text-[var(--text-muted)]" />
                                {t.driversPage.profileModalTitle}
                              </DropdownMenuItem>

                              {drv.accountStatus !== 'PENDING_SETUP' && !readOnly && (
                                <DropdownMenuItem
                                  onClick={() => openEdit(drv)}
                                  className="text-[11px] font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5"
                                >
                                  <SVGPencil size={13} className="text-[var(--text-muted)]" />
                                  {t.driversPage.modifyButton}
                                </DropdownMenuItem>
                              )}

                              {drv.accountStatus === 'PENDING_SETUP' && !readOnly && (
                                <>
                                  <DropdownMenuItem
                                    onClick={() => handleResendInvite(drv)}
                                    disabled={resendCooldown > 0}
                                    className="text-[11px] font-semibold text-amber-600 hover:text-amber-800 hover:bg-amber-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5"
                                  >
                                    <SVGSend size={13} className="text-amber-500" />
                                    {resendCooldown > 0 ? `${resendCooldown}s` : t.driversPage.resendInviteButton}
                                  </DropdownMenuItem>
                                  <DropdownMenuItem
                                    onClick={() => openCancelInvite(drv)}
                                    className="text-[11px] font-semibold text-red-600 hover:text-red-800 hover:bg-red-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5"
                                  >
                                    <SVGX size={13} className="text-red-500" />
                                    {t.driversPage.cancelInviteButton}
                                  </DropdownMenuItem>
                                </>
                              )}
                              {drv.accountStatus === 'ACTIVE' && !readOnly && (
                                <DropdownMenuItem onClick={() => openSuspend(drv)} className="text-[11px] font-semibold text-red-600 hover:text-red-800 hover:bg-red-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                                  <SVGBan size={13} className="text-red-500" />
                                  {t.driversPage.suspendDriverButton}
                                </DropdownMenuItem>
                              )}
                              {drv.accountStatus === 'SUSPENDED' && !readOnly && (
                                <DropdownMenuItem onClick={() => toggleActive(drv)} className="text-[11px] font-semibold text-emerald-600 hover:text-emerald-800 hover:bg-emerald-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                                  <SVGActivity size={13} className="text-emerald-500" />
                                  {t.driversPage.activateTooltip}
                                </DropdownMenuItem>
                              )}
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </div>

      {/* ── Driver Details Inspector Modal (AppModal) ─────────────────────────── */}
      <AppModal
        open={detailsOpen}
        onClose={() => setDetailsOpen(false)}
        title={selected ? selected.name : ''}
        subtitle={selected ? `${t.driversPage.profileModalTitle} · ID ${selected.id.slice(0, 8).toUpperCase()}` : ''}
        size="md"
        footer={
          <div className="flex items-center justify-end gap-3 w-full mt-2">
            {!readOnly && selected && selected.accountStatus !== 'PENDING_SETUP' && (
              <button
                type="button"
                className="h-8 px-4 border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] text-[var(--text-soft)] hover:text-[var(--text-primary)] font-semibold text-xs rounded transition-colors flex items-center justify-center gap-1.5"
                onClick={() => { setDetailsOpen(false); openEdit(selected); }}
              >
                <SVGPencil size={13} />
                {t.driversPage.modifyButton}
              </button>
            )}
            <Button
              size="sm"
              onClick={() => setDetailsOpen(false)}
              className="text-xs font-semibold bg-[var(--brand)] hover:opacity-90 text-white rounded-[2px]"
            >
              {t.driversPage.cancelButton || 'Fermer'}
            </Button>
          </div>
        }
      >
        {selected && (
          <div className="flex flex-col w-full">
            {/* Custom Simple State-Based Switcher (Absolutely No Boxes) */}
            <div className="border-b border-[var(--border)]/40 shrink-0 pb-2.5 mb-5 flex gap-7 justify-start">
              {([
                ['info', t.driversPage.profileModalTitle],
                ['mission', t.driversPage.fleetStatusLabel],
                ['activity', t.driversPage.tabFilters]
              ] as const).map(([tab, label]) => {
                const active = activeTab === tab;
                return (
                  <button
                    key={tab}
                    type="button"
                    onClick={() => setActiveTab(tab)}
                    className={cn(
                      "text-xs font-semibold pb-1.5 transition-all text-start relative bg-transparent border-0 cursor-pointer outline-none",
                      active 
                        ? "text-[var(--brand)] font-bold" 
                        : "text-[var(--text-soft)] hover:text-[var(--text-primary)]"
                    )}
                  >
                    {label}
                    {active && (
                      <span 
                        className="absolute bottom-0 left-0 right-0 h-[2px] bg-[var(--brand)] rounded-full"
                        style={{ bottom: '-10px' }}
                      />
                    )}
                  </button>
                );
              })}
            </div>

            <div dir={locale === 'ar' ? 'rtl' : 'ltr'} className="flex-1 overflow-y-auto max-h-[380px] pr-1 pl-1 text-start" style={{ scrollbarWidth: 'thin' }}>
              {(() => {
                const tabInfoDesc = locale === 'ar' 
                  ? 'المعلومات الشخصية، بيانات الاتصال، وحالة تنشيط حساب السائق.'
                  : locale === 'en' 
                    ? 'Personal information, contact details, and driver account activation status.'
                    : 'Informations personnelles, coordonnées et statut d\'activation du compte chauffeur.';

                const tabMissionDesc = locale === 'ar' 
                  ? 'مؤشرات الرحلة النشطة في الوقت الفعلي، تقدم المسار وتتبع إحداثيات الموقع.'
                  : locale === 'en' 
                    ? 'Real-time active mission indicators, route progression, and GPS coordinate tracking.'
                    : 'Indicateurs de mission active en temps réel, progression de la tournée et coordonnées GPS.';

                const tabActivityDesc = locale === 'ar' 
                  ? 'سجل محاولات التسليم المكتملة أو الملغاة أو الفاشلة التي قام بها هذا السائق.'
                  : locale === 'en' 
                    ? 'History of completed, cancelled, or failed delivery attempts managed by this driver.'
                    : 'Historique des tentatives de livraison terminées, annulées ou échouées gérées par ce chauffeur.';

                return (
                  <>
                    {/* Tab 1: Profile Info */}
                    {activeTab === 'info' && (
                      <div className="space-y-6">
                        {/* Simple Header */}
                        <div className="flex items-center gap-4 pb-4 border-b border-[var(--border)]/60">
                          <div className="h-12 w-12 rounded-[2px] border border-[var(--border)] bg-[var(--hover-bg)] flex items-center justify-center font-mono text-sm font-bold text-[var(--brand)] shrink-0 select-none">
                            {selected.name.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase()}
                          </div>
                          <div className="flex-1 min-w-0">
                            <span className="text-sm font-bold text-[var(--text-primary)] block truncate">{selected.name}</span>
                            <span className="text-xs text-[var(--text-muted)] font-mono block truncate">{selected.email || '—'}</span>
                          </div>
                          <DriverStatusBadge status={selected.accountStatus ?? 'PENDING_SETUP'} size="sm" />
                        </div>

                        {/* Tab Description (Simple and Spacious) */}
                        <p className="text-xs text-[var(--text-soft)] leading-relaxed italic pr-2 pl-2">
                          {tabInfoDesc}
                        </p>

                        {/* Contact Details List */}
                        <div className="flex flex-col gap-1 pr-2 pl-2">
                          <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] mb-3 block">
                            {t.driversPage.tableHeaderContact || 'Coordonnées'}
                          </span>
                          
                          <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                            <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.nameLabel}</span>
                            <span className="text-xs text-[var(--text-primary)] font-bold">{selected.name}</span>
                          </div>

                          <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                            <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.phoneLabel}</span>
                            <span className="text-xs text-[var(--text-primary)] font-mono font-bold">{selected.phone}</span>
                          </div>

                          {selected.email && (
                            <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                              <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.emailLabel}</span>
                              <span className="text-xs text-[var(--text-primary)] font-mono font-bold truncate max-w-[200px]">{selected.email}</span>
                            </div>
                          )}

                          <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                            <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.tableHeaderActivity}</span>
                            <span className="text-xs text-[var(--text-primary)] font-semibold">{selected.onlineStatus || 'OFFLINE'}</span>
                          </div>

                          {selected.suspendedReason && (
                            <div className="flex flex-col gap-2 pt-3">
                              <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.suspendReasonLabel}</span>
                              <span className="text-xs font-medium text-[#A52B24] bg-[rgba(199,55,47,0.06)] border border-[rgba(199,55,47,0.15)] px-3 py-2.5 rounded">
                                {selected.suspendedReason}
                              </span>
                            </div>
                          )}
                        </div>

                        {/* Invite Setup Actions */}
                        {!readOnly && selected.accountStatus === 'PENDING_SETUP' && (
                          <div className="pt-3 flex flex-col gap-2.5 pr-2 pl-2">
                            <button
                              type="button"
                              onClick={() => handleResendInvite(selected)}
                              disabled={resendCooldown > 0}
                              className="w-full h-9 bg-[var(--brand)] hover:opacity-90 disabled:opacity-50 text-white font-bold text-xs rounded transition-opacity"
                            >
                              {resendCooldown > 0 
                                ? t.driversPage.resendCooldown.replace('{seconds}', String(resendCooldown)) 
                                : t.driversPage.resendInviteButton}
                            </button>
                            <button
                              type="button"
                              onClick={() => openCancelInvite(selected)}
                              className="w-full h-9 border border-red-200 text-red-600 hover:bg-red-50/20 font-bold text-xs rounded transition-colors"
                            >
                              {t.driversPage.cancelInviteButton}
                            </button>
                          </div>
                        )}
                      </div>
                    )}

                    {/* Tab 2: Active Mission */}
                    {activeTab === 'mission' && (
                      <div className="space-y-5">
                        {/* Tab Description */}
                        <p className="text-xs text-[var(--text-soft)] leading-relaxed italic pr-2 pl-2">
                          {tabMissionDesc}
                        </p>

                        {isDriverEnLivraison(selected) ? (
                          <div className="space-y-5 pr-2 pl-2">
                            <div className="flex flex-col gap-1">
                              <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] mb-3 block">
                                {t.driversPage.fleetStatusLabel || 'Mission en cours'}
                              </span>

                              {selected.activeRouteId && (
                                <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                                  <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.onRoute}</span>
                                  <div className="flex items-center gap-2">
                                    <StatusBadge status="IN_PROGRESS" size="sm" label={t.driversPage.onRoute} />
                                    <span className="text-xs text-[var(--text-primary)] font-mono font-bold">
                                      {selected.activeRouteId.slice(0, 8).toUpperCase()}
                                    </span>
                                  </div>
                                </div>
                              )}

                              {selected.activeDeliveryId && (
                                <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                                  <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.activeDelivery}</span>
                                  <div className="flex items-center gap-2">
                                    <StatusBadge status="IN_TRANSIT" size="sm" label={t.driversPage.activeDelivery} />
                                    <span className="text-xs text-[var(--text-primary)] font-mono font-bold">
                                      {selected.activeDeliveryId.slice(0, 8).toUpperCase()}
                                    </span>
                                  </div>
                                </div>
                              )}

                              {(selected.currentLat || selected.currentLng) && (
                                <div className="flex flex-col gap-2 py-3">
                                  <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.tableHeaderActivity || 'Position GPS'}</span>
                                  <span className="text-xs font-mono text-[var(--text-soft)] bg-[var(--hover-bg)] p-3 border border-[var(--border)] rounded select-all text-center">
                                    {selected.currentLat?.toFixed(6)}, {selected.currentLng?.toFixed(6)}
                                  </span>
                                </div>
                              )}
                            </div>
                          </div>
                        ) : (
                          <div className="py-14 text-center pr-2 pl-2">
                            <div className="p-3 bg-[rgba(76,175,130,0.06)] text-[#2D8A5E] rounded-full w-fit mx-auto mb-3 border border-[rgba(76,175,130,0.12)]">
                              <SVGActivity size={20} />
                            </div>
                            <h4 className="text-xs font-bold text-[var(--text-primary)]">{t.driversPage.free}</h4>
                            <p className="text-xs text-[var(--text-muted)] mt-1.5">{t.driversPage.noActivityDetected}</p>
                          </div>
                        )}
                      </div>
                    )}

                    {/* Tab 3: Recent Activity (Deliveries) */}
                    {activeTab === 'activity' && (
                      <div className="space-y-4">
                        {/* Tab Description */}
                        <p className="text-xs text-[var(--text-soft)] leading-relaxed italic mb-3 pr-2 pl-2">
                          {tabActivityDesc}
                        </p>
                        
                        <div className="space-y-1.5 pr-2 pl-2">
                          {detailLoading ? (
                            Array.from({ length: 3 }).map((_, i) => (
                              <div key={i} className="h-12 w-full rounded bg-[var(--hover-bg)] animate-pulse" />
                            ))
                          ) : historyDeliveriesForSelected.length === 0 ? (
                            <div className="py-10 border border-dashed border-[var(--border)] rounded text-center bg-[var(--hover-bg)]/20">
                              <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.noActivityDetected}</span>
                            </div>
                          ) : (
                            historyDeliveriesForSelected.slice(0, 10).map((d) => (
                              <div key={d.id} className="py-3 border-b border-[var(--border)]/40 flex items-center justify-between gap-3 text-start">
                                <div className="flex items-center gap-3 overflow-hidden">
                                  <StatusBadge status={d.status} size="sm" />
                                  <span className="text-xs font-bold text-[var(--text-primary)] truncate">{d.clientName}</span>
                                </div>
                                <span className="text-xs font-bold text-[var(--text-muted)] font-mono shrink-0">
                                  {d.orderRef || d.erpId || d.orderId?.slice(0, 8)}
                                </span>
                              </div>
                            ))
                          )}
                        </div>
                      </div>
                    )}
                  </>
                );
              })()}
            </div>
          </div>
        )}
      </AppModal>

      {/* ── Create / Edit Dialog ────────────────────────────────────────────── */}
      <AppModal
        open={crudOpen}
        onClose={() => setCrudOpen(false)}
        title={editingDriver ? t.driversPage.editModalTitle : t.driversPage.newDriverModalTitle}
        subtitle={editingDriver ? `ID: ${editingDriver.id.slice(0, 12)}` : t.driversPage.formInstructionLabel}
        size="sm"
        footer={
          <div className="flex items-center justify-end gap-2 w-full mt-2">
            <Button variant="ghost" size="sm" className="text-xs font-semibold text-slate-500 rounded" onClick={() => setCrudOpen(false)}>{t.driversPage.cancelButton}</Button>
            <Button
              size="sm"
              onClick={saveDriver}
              disabled={saving}
              className="text-xs font-semibold bg-[var(--brand)] hover:opacity-90 text-white rounded px-4"
            >
              {saving ? t.driversPage.resendInProgress : editingDriver ? t.driversPage.saveButton : t.driversPage.createButton}
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-4 mt-2 text-start">
          <FieldInput
            label={t.driversPage.nameLabel}
            placeholder={t.driversPage.namePlaceholder}
            required
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="text-slate-800"
          />
          <FieldInput
            label={t.driversPage.phoneLabel}
            placeholder={t.driversPage.phonePlaceholder}
            required
            value={form.phone}
            onChange={(e) => setForm({ ...form, phone: e.target.value })}
            className="font-mono text-slate-800"
          />
          {!editingDriver && (
            <FieldInput
              label={t.driversPage.emailLabel}
              placeholder={t.driversPage.emailPlaceholder}
              type="email"
              required
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
              hint={t.driversPage.invitationEmailHelp}
              className="text-slate-800"
            />
          )}
        </div>
      </AppModal>

      {/* ── Cancel Invite Alert Modal ─────────────────────────────────────────── */}
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

      {/* ── Suspend Alert Modal ─────────────────────────────────────────────── */}
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
