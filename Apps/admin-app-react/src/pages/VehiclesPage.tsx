

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import {
  IconRefresh, IconPlus, IconSearch,
  IconTruck, IconCar, IconUserCheck,
  IconWeight, IconCalendar, IconPencil, IconTrash,
  IconGauge, IconX,
  IconPoint, IconPackage
} from '@tabler/icons-react';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import type { Driver } from '@/types';
import { getCurrentRole, isReadOnlyRole } from '@/lib/auth';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { AppModal } from '@/components/overlays/AppModal';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import {
  useVehicles,
  useFleetDrivers,
  useCreateVehicle,
  useUpdateVehicle,
  useDeleteVehicle,
  useReactivateVehicle,
  VehicleItem
} from '@/hooks/useVehicles';

// ─── Types ────────────────────────────────────────────────────────────────────

type VehicleType = 'TRUCK' | 'VAN' | 'CAR' | 'MOTO';

const VEHICLE_TYPES: VehicleType[] = ['TRUCK', 'VAN', 'CAR', 'MOTO'];

// ─── Spinner ──────────────────────────────────────────────────────────────────

function Spinner({ size = 24, className }: { size?: number; className?: string }) {
  return (
    <svg
      className={cn('animate-spin text-[var(--brand)]', className)}
      width={size} height={size} viewBox="0 0 24 24" fill="none"
    >
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
    </svg>
  );
}

// ─── Progress Bar ─────────────────────────────────────────────────────────────

function ProgressBar({ value, color = 'var(--brand)' }: { value: number; color?: string }) {
  return (
    <div className="h-[2px] rounded-full bg-[var(--border)]">
      <div
        className="h-full rounded-full transition-all"
        style={{ width: `${Math.min(Math.max(value, 0), 100)}%`, background: color }}
      />
    </div>
  );
}

// ─── Vehicle Card ─────────────────────────────────────────────────────────────

function VehicleTechnicalCard({
  vehicle,
  driverName,
  onEdit,
  onDelete,
  onReactivate,
  readOnly,
  canEdit,
}: {
  vehicle: VehicleItem;
  driverName?: string;
  onEdit: (v: VehicleItem) => void;
  onDelete: (v: VehicleItem) => void;
  onReactivate: (v: VehicleItem) => void;
  readOnly: boolean;
  canEdit: boolean;
}) {
  const t = useT();
  const typeLabel: Record<VehicleType, string> = {
    TRUCK: t.vehiclesPage.vehicleTypeHeavy,
    VAN:   t.vehiclesPage.vehicleTypeVan,
    CAR:   t.vehiclesPage.vehicleTypeCar,
    MOTO:  t.vehiclesPage.vehicleTypeMoto,
  };
  const isRetired = !vehicle.active;
  const isBusy = vehicle.assigned ?? Boolean(vehicle.driverId);
  const statusColor = isRetired ? '#A52B24' : (isBusy ? '#4C56B8' : '#2D8A5E');
  const statusLabel = isRetired ? t.vehiclesPage.statusRetired : (isBusy ? t.vehiclesPage.statusEngaged : t.vehiclesPage.statusAvailable);
  const dotColor = statusColor;

  return (
    <Card className={cn(
      "group relative overflow-hidden flex flex-col transition-all hover:shadow-md border-border bg-card p-0 gap-0 rounded-lg",
      isRetired && "opacity-60"
    )}>
      {/* Status ribbon */}
      <div className="absolute left-0 top-0 bottom-0 w-[3px] z-10" style={{ background: statusColor }} />

      {/* Visual header */}
      <div className="h-[130px] relative w-full border-b border-border bg-muted/30 shrink-0">
        {vehicle.imageUrl ? (
          <img
            src={vehicle.imageUrl}
            alt={vehicle.make}
            className="w-full h-full object-cover opacity-80 group-hover:opacity-100 transition-opacity"
          />
        ) : (
          <div className="flex items-center justify-center h-full">
            <IconTruck size={48} className="text-muted-foreground/30" />
          </div>
        )}

        {/* Type badge */}
        <div className="absolute top-3 left-6">
          <span className="text-[11px] font-semibold px-2 py-0.5 rounded-md border bg-background text-foreground shadow-sm">
            {typeLabel[vehicle.type]}
          </span>
        </div>

        {/* Retired badge */}
        {isRetired && (
          <div className="absolute top-3 right-3">
            <span className="text-[10px] font-bold px-2 py-1 rounded-md bg-red-500/10 text-red-600 border border-red-500/20">
              {t.vehiclesPage.statusRetired}
            </span>
          </div>
        )}
      </div>

      <CardContent className="flex flex-col gap-4 p-4 flex-1">
        <div className="flex flex-col gap-0.5">
          <div className="flex justify-between items-start gap-2">
            <h3 className="text-sm font-semibold tracking-tight truncate max-w-[70%]">
              {vehicle.make}{' '}
              <span className="font-medium text-muted-foreground">
                {vehicle.model}
              </span>
            </h3>
            <span
              className="text-[11px] font-semibold px-2 py-0.5 rounded-md flex items-center gap-1.5 whitespace-nowrap border"
              style={{
                color: dotColor,
                background: isRetired ? 'rgba(199,55,47,0.09)' : (isBusy ? 'rgba(94,106,210,0.09)' : 'rgba(76,175,130,0.09)'),
                borderColor: isRetired ? 'rgba(199,55,47,0.15)' : (isBusy ? 'rgba(94,106,210,0.15)' : 'rgba(76,175,130,0.15)'),
              }}
            >
              <span
                className="inline-block w-1.5 h-1.5 rounded-full"
                style={{ background: dotColor }}
              />
              {statusLabel}
            </span>
          </div>
          <p className="text-xs font-semibold font-mono tracking-tight text-primary">
            {vehicle.plate}
          </p>
        </div>

        <div className="h-px bg-border" />

        <div className="grid grid-cols-2 gap-4">
          <div>
            <div className="flex items-center gap-1.5 mb-1.5">
              <IconWeight size={14} className="text-muted-foreground" />
              <span className="text-xs font-medium text-muted-foreground">
                {t.vehiclesPage.capacityLabel}
              </span>
            </div>
            <p className="text-xs font-bold font-mono">
              {vehicle.payloadKg?.toLocaleString() || '—'} KG
            </p>
            <div className="mt-1.5">
              <ProgressBar
                value={vehicle.payloadKg ? Math.min((vehicle.payloadKg / 5000) * 100, 100) : 0}
                color="hsl(var(--muted-foreground))"
              />
            </div>
          </div>
          <div>
            <div className="flex items-center gap-1.5 mb-1.5">
              <IconGauge size={14} className="text-muted-foreground" />
              <span className="text-xs font-medium text-muted-foreground">
                {t.vehiclesPage.volumeLabel}
              </span>
            </div>
            <p className="text-xs font-bold font-mono">
              {vehicle.volumeM3?.toFixed(1) || '—'} M³
            </p>
            <div className="mt-1.5">
              <ProgressBar
                value={vehicle.volumeM3 ? Math.min((vehicle.volumeM3 / 25) * 100, 100) : 0}
                color="hsl(var(--primary))"
              />
            </div>
          </div>
        </div>
      </CardContent>

      <CardFooter className="bg-muted/30 border-t p-3 px-4 flex justify-between items-center mt-auto rounded-b-xl pb-3">
        <div>
          <p className="text-[11px] font-medium text-muted-foreground mb-0.5">
            {t.vehiclesPage.assignedDriver}
          </p>
          <p className="text-xs font-semibold">
            {driverName || t.vehiclesPage.unassigned}
          </p>
        </div>
        <div className="flex items-center gap-1.5">
          {!readOnly && canEdit && (
            <div className="flex gap-1 mr-1">
              {isRetired ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-8 px-3 text-[10px] font-bold text-emerald-600 hover:bg-emerald-500/10"
                  onClick={() => onReactivate(vehicle)}
                >
                  <IconRefresh size={14} className="mr-1" />
                  {t.vehiclesPage.reactivateButton}
                </Button>
              ) : (
                <>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="w-8 h-8"
                    onClick={() => onEdit(vehicle)}
                  >
                    <IconPencil size={15} className="text-muted-foreground" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="w-8 h-8 hover:bg-destructive/10 hover:text-destructive text-muted-foreground transition-colors"
                    onClick={() => onDelete(vehicle)}
                  >
                    <IconTrash size={15} />
                  </Button>
                </>
              )}
            </div>
          )}
          <div className="w-8 h-8 flex items-center justify-center rounded-md bg-background border shadow-sm">
            <IconCar size={16} className="text-primary" />
          </div>
        </div>
      </CardFooter>
    </Card>
  );
}

// ─── Main Content ─────────────────────────────────────────────────────────────

function VehiclesPageContent() {
  const t = useT();
  const typeLabel: Record<VehicleType, string> = {
    TRUCK: t.vehiclesPage.vehicleTypeHeavy,
    VAN:   t.vehiclesPage.vehicleTypeVan,
    CAR:   t.vehiclesPage.vehicleTypeCar,
    MOTO:  t.vehiclesPage.vehicleTypeMoto,
  };
  const locale = useLocaleStore(state => state.locale);
  const role = getCurrentRole();
  const readOnly = isReadOnlyRole(role);

  // TanStack Query Hooks
  const { data: vehicles = [], isLoading: loadingVehicles, refetch: refetchVehicles } = useVehicles();
  const { data: drivers = [], isLoading: loadingDrivers, refetch: refetchDrivers } = useFleetDrivers();
  const createVehicleMutation = useCreateVehicle();
  const updateVehicleMutation = useUpdateVehicle();
  const deleteVehicleMutation = useDeleteVehicle();
  const reactivateVehicleMutation = useReactivateVehicle();

  const loading = loadingVehicles || loadingDrivers;
  const fetchData = useCallback(async () => {
    await Promise.all([refetchVehicles(), refetchDrivers()]);
  }, [refetchVehicles, refetchDrivers]);

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  // Confirm delete state
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<VehicleItem | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const [form, setForm] = useState({
    make: '', model: '', manufactureYear: String(new Date().getFullYear()),
    color: '', vin: '', fuelType: '', payloadKg: '', volumeM3: '',
    mileageKm: '', plate: '', type: 'VAN' as VehicleType, active: true,
    imageBase64: '', imagePreviewUrl: '',
  });

  const filtered = useMemo(() => {
    return vehicles.filter((v) => {
      const matchesSearch =
        v.make.toLowerCase().includes(searchTerm.toLowerCase()) ||
        v.model.toLowerCase().includes(searchTerm.toLowerCase()) ||
        v.plate.toLowerCase().includes(searchTerm.toLowerCase());
      const isBusy = v.assigned ?? Boolean(v.driverId);
      if (statusFilter === 'ACTIVE') return matchesSearch && v.active && !isBusy;
      if (statusFilter === 'BUSY') return matchesSearch && v.active && isBusy;
      if (statusFilter === 'MAINTENANCE') return matchesSearch && v.active && !v.active;
      if (statusFilter === 'RETIRED') return matchesSearch && !v.active;
      return matchesSearch;
    });
  }, [vehicles, searchTerm, statusFilter]);

  const stats = useMemo(() => {
    const total = vehicles.length;
    const active = vehicles.filter(v => v.active).length;
    const busy = vehicles.filter(v => v.active && (v.assigned ?? Boolean(v.driverId))).length;
    const retired = vehicles.filter(v => !v.active).length;
    const tonnage = vehicles.reduce((acc, v) => acc + (v.payloadKg || 0), 0);
    return { total, active, busy, retired, tonnage };
  }, [vehicles]);

  const resetForm = () => {
    setForm({
      make: '', model: '', manufactureYear: String(new Date().getFullYear()),
      color: '', vin: '', fuelType: '', payloadKg: '', volumeM3: '',
      mileageKm: '', plate: '', type: 'VAN', active: true,
      imageBase64: '', imagePreviewUrl: '',
    });
    setEditingId(null);
    setModalOpen(false);
  };

  const openEdit = (v: VehicleItem) => {
    setEditingId(v.id);
    setForm({
      make: v.make, model: v.model, manufactureYear: String(v.manufactureYear || ''),
      color: v.color || '', vin: v.vin || '', fuelType: v.fuelType || '',
      payloadKg: String(v.payloadKg || ''), volumeM3: String(v.volumeM3 || ''),
      mileageKm: String(v.mileageKm || ''), plate: v.plate,
      type: v.type, active: v.active, imageBase64: '',
      imagePreviewUrl: v.imageUrl || '',
    });
    setModalOpen(true);
  };

  const saveVehicle = async () => {
    if (!form.make || !form.plate || !form.payloadKg) return showErrorToast(null, 'errorVehiclePlateRequired');
    try {
      const payload = {
        ...form,
        manufactureYear: Number(form.manufactureYear),
        payloadKg: Number(form.payloadKg),
        volumeM3: form.volumeM3 ? Number(form.volumeM3) : null,
        mileageKm: form.mileageKm ? Number(form.mileageKm) : null,
      };
      if (editingId) {
        await updateVehicleMutation.mutateAsync({ id: editingId, payload });
      } else {
        await createVehicleMutation.mutateAsync(payload);
      }
      resetForm();
    } catch (err) {
      // Errors are handled by query mutation callbacks
    }
  };

  const deleteVehicle = (v: VehicleItem) => {
    setPendingDelete(v);
    setConfirmOpen(true);
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    try {
      await deleteVehicleMutation.mutateAsync(pendingDelete.id);
      setConfirmOpen(false);
      setPendingDelete(null);
    } catch (err) {
      // Errors are handled by query mutation callbacks
    }
  };

  const reactivateVehicle = async (v: VehicleItem) => {
    try {
      await reactivateVehicleMutation.mutateAsync(v.id);
    } catch (err) {
      // Errors are handled by query mutation callbacks
    }
  };

  const saving = createVehicleMutation.isPending || updateVehicleMutation.isPending;
  const confirmDeleting = deleteVehicleMutation.isPending;

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (ev) => {
        const result = ev.target?.result as string;
        setForm(f => ({ ...f, imageBase64: result, imagePreviewUrl: result }));
      };
      reader.readAsDataURL(file);
    }
  };

  const [mobileTab, setMobileTab] = useState<'filters' | 'list'>('list');

  const fleetOccupancy = stats.total > 0 ? (stats.busy / stats.total) * 100 : 0;

  return (
    <div
      className="flex flex-col overflow-hidden"
      style={{ height: 'calc(100vh - 64px)', background: 'var(--app-bg)' }}
    >
      {/* Mobile Tab Bar */}
      <div className="lg:hidden flex shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        {([['filters', t.vehiclesPage.tabFilters], ['list', t.vehiclesPage.tabList]] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setMobileTab(tab)}
            className={`flex-1 h-10 text-[12px] font-bold tracking-wide transition-colors ${
              mobileTab === tab ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]' : 'text-[var(--text-muted)]'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* ── Fleet Rail ── */}
        <div
          className={`lg:w-[300px] shrink-0 overflow-y-auto flex flex-col ${mobileTab === 'filters' ? 'flex w-full' : 'hidden lg:flex'}`}
          style={{ borderRight: '1px solid var(--border)', background: 'var(--surface)' }}
        >
          {/* Header */}
          <div className="p-5 border-b border-[var(--border)]">
            <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.vehiclesPage.pageSubtitle}</span>
            <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
              {t.vehiclesPage.pageTitle} <span className="text-[var(--brand)]">{t.vehiclesPage.pageTitleBrand}</span>
            </h1>
          </div>

          {/* Actions & Search */}
          <div className="flex flex-col gap-3 p-5" style={{ borderBottom: '1px solid var(--border)' }}>
            {!readOnly && (
              <Button
                className="w-full font-bold tracking-widest uppercase text-[10px] rounded-md flex items-center justify-center gap-2 text-white dark:text-[#121212]"
                style={{ background: 'var(--brand)', border: 'none' }}
                onClick={() => { resetForm(); setModalOpen(true); }}
              >
                <IconPlus size={14} />
                {t.vehiclesPage.newVehicleButton}
              </Button>
            )}
            <FieldInput
              placeholder={t.vehiclesPage.searchPlaceholder}
              leftSection={<IconSearch size={14} />}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.currentTarget.value)}
            />
          </div>

          {/* Filter pills */}
          <div className="overflow-y-auto flex-1 p-3">
            <p className="px-3 mb-2 text-[11px] font-semibold text-[var(--text-muted)]">
              {t.vehiclesPage.operationalStatusLabel}
            </p>
            {[
              { id: 'ALL', label: t.vehiclesPage.fleetTotal, icon: <IconTruck size={14} />, count: stats.total },
              { id: 'ACTIVE', label: t.vehiclesPage.operational, icon: <IconPoint size={14} className="text-[#2D8A5E]" />, count: stats.active },
              { id: 'BUSY', label: t.vehiclesPage.statusEngaged, icon: <IconUserCheck size={14} />, count: stats.busy },
              { id: 'RETIRED', label: t.vehiclesPage.statusRetired, icon: <IconX size={14} className="text-[#A52B24]" />, count: stats.retired },
            ].map((pill) => (
              <button
                key={pill.id}
                type="button"
                onClick={() => setStatusFilter(pill.id)}
                className={cn(
                  'w-full px-4 py-3 rounded-md transition-all flex items-center justify-between group text-left',
                  statusFilter === pill.id
                    ? 'bg-[var(--hover-bg)] border-l-2 border-[var(--brand)]'
                    : 'hover:bg-[var(--hover-bg)] border-l-2 border-transparent'
                )}
              >
                <div className="flex items-center gap-2">
                  <span style={{ color: statusFilter === pill.id ? 'var(--brand)' : 'var(--text-muted)' }}>
                    {pill.icon}
                  </span>
                  <span
                    className="text-[11px] font-semibold"
                    style={{ color: statusFilter === pill.id ? 'var(--text-primary)' : 'var(--text-muted)' }}
                  >
                    {pill.label}
                  </span>
                </div>
                <span className="text-[11px] font-semibold font-mono" style={{ color: 'var(--text-muted)' }}>
                  {pill.count}
                </span>
              </button>
            ))}

            <div className="h-px bg-[var(--border)] my-4" />

            <p className="px-3 mb-4 text-[11px] font-semibold text-[var(--text-muted)]">
              {t.vehiclesPage.logisticsCapacityLabel}
            </p>

            <div className="flex flex-col gap-4 px-3">
              <div>
                <div className="flex justify-between mb-1.5">
                  <span className="text-[11px] font-semibold text-[var(--text-muted)]">
                    {t.vehiclesPage.totalTonnageLabel}
                  </span>
                  <span className="text-[11px] font-semibold font-mono" style={{ color: 'var(--text-primary)' }}>
                    {(stats.tonnage / 1000).toFixed(1)}T
                  </span>
                </div>
                <ProgressBar value={75} color="#1f2937" />
              </div>
              <div>
                <div className="flex justify-between mb-1.5">
                  <span className="text-[11px] font-semibold text-[var(--text-muted)]">
                    {t.vehiclesPage.fleetOccupancyLabel}
                  </span>
                  <span className="text-[11px] font-semibold font-mono" style={{ color: 'var(--text-primary)' }}>
                    {Math.round(fleetOccupancy)}%
                  </span>
                </div>
                <ProgressBar value={fleetOccupancy} color="var(--brand)" />
              </div>
            </div>
          </div>

          <div
            className="p-5 text-center"
            style={{ borderTop: '1px solid var(--border)', background: 'var(--surface)' }}
          >
            <p className="text-[10px] font-semibold" style={{ color: 'var(--text-muted)' }}>
              Surgical Slab · Vehicle Registry
            </p>
          </div>
        </div>

        {/* ── Technical Grid ── */}
        <div
          className={`flex flex-col flex-1 overflow-hidden min-w-0 ${mobileTab === 'list' ? 'flex' : 'hidden lg:flex'}`}
          style={{ background: 'var(--app-bg)' }}
        >
          {/* Toolbar */}
          <div
            className="flex items-center justify-between px-6 h-16 shrink-0"
            style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface)' }}
          >
            <p className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
              {t.vehiclesPage.displayedCount.replace('{count}', filtered.length.toString())}
            </p>
            <button
              type="button"
              onClick={() => fetchData()}
              className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
            >
              {loading
                ? <Spinner size={14} />
                : <IconRefresh size={16} style={{ color: 'var(--text-primary)' }} />
              }
            </button>
          </div>

          {/* Grid */}
          <div className="flex-1 overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center h-[400px]">
                <Spinner size={40} />
              </div>
            ) : filtered.length === 0 ? (
              <div className="flex flex-col items-center py-[120px] gap-2">
                <IconTruck size={48} style={{ color: 'var(--border)' }} />
                <p className="text-[11px] font-semibold text-[var(--text-muted)]">
                  {t.vehiclesPage.noVehiclesFound}
                </p>
              </div>
            ) : (
              <div className="p-8">
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                  {filtered.map((v) => (
                    <VehicleTechnicalCard
                      key={v.id}
                      vehicle={v}
                      driverName={drivers.find(d => d.id === v.driverId)?.name}
                      onEdit={openEdit}
                      onDelete={deleteVehicle}
                      onReactivate={reactivateVehicle}
                      readOnly={readOnly}
                      canEdit={!readOnly}
                    />
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── Vehicle Config Modal ── */}
      <AppModal
        open={modalOpen}
        onClose={resetForm}
        size="lg"
        title={t.vehiclesPage.modalTitle}
        subtitle={editingId ? t.vehiclesPage.editSubtitle : t.vehiclesPage.createSubtitle}
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={resetForm} className="text-[11px] font-semibold rounded-md">
              {t.vehiclesPage.cancelButton}
            </Button>
            <Button
              size="sm"
              onClick={saveVehicle}
              disabled={saving}
              className="text-[11px] font-semibold rounded-md"
              style={{ background: 'var(--brand)', color: '#fff', border: 'none' }}
            >
              {saving && <Spinner size={12} className="mr-1.5" />}
              {editingId ? t.vehiclesPage.saveButton : t.vehiclesPage.createButton}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-[1fr_2fr] gap-4">
            {/* Image upload */}
            <div>
              <p className="text-[11px] font-semibold mb-2" style={{ color: 'var(--text-muted)' }}>
                {t.vehiclesPage.modalImageLabel}
              </p>
              <div
                className="rounded-lg overflow-hidden flex flex-col items-center justify-center relative"
                style={{
                  height: 180,
                  border: '1px solid var(--border)',
                  background: 'var(--app-bg)',
                }}
              >
                {form.imagePreviewUrl ? (
                  <img src={form.imagePreviewUrl} alt="Preview" className="w-full h-full object-cover" />
                ) : (
                  <IconPlus size={32} style={{ color: 'var(--border)' }} />
                )}
                <div className="absolute bottom-2">
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/png,image/jpeg"
                    className="hidden"
                    onChange={handleFileChange}
                  />
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="text-[10px] font-bold px-2 py-1 rounded-md shadow-sm border border-[var(--border)]"
                    style={{ background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                  >
                    {form.imagePreviewUrl ? t.vehiclesPage.imageChangeButton : t.vehiclesPage.imageUploadButton}
                  </button>
                </div>
              </div>
            </div>

            {/* Form fields */}
            <div className="flex flex-col gap-3">
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.makeLabel}
                  value={form.make}
                  onChange={(e) => setForm({ ...form, make: e.target.value })}
                  required
                />
                <FieldInput
                  label={t.vehiclesPage.modelLabel}
                  value={form.model}
                  onChange={(e) => setForm({ ...form, model: e.target.value })}
                  required
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.plateLabel}
                  value={form.plate}
                  onChange={(e) => setForm({ ...form, plate: e.target.value })}
                  required
                  className="font-mono font-black"
                />
                <FieldSelect
                  label={t.vehiclesPage.typeLabel}
                  value={form.type}
                  onChange={(e) => setForm({ ...form, type: (e.target as HTMLSelectElement).value as VehicleType })}
                  options={VEHICLE_TYPES.map(t => ({ value: t, label: typeLabel[t] }))}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.capacityKgLabel}
                  value={form.payloadKg}
                  onChange={(e) => setForm({ ...form, payloadKg: e.target.value })}
                  required
                  leftSection={<IconWeight size={14} />}
                />
                <FieldInput
                  label={t.vehiclesPage.volumeM3Label}
                  value={form.volumeM3}
                  onChange={(e) => setForm({ ...form, volumeM3: e.target.value })}
                  leftSection={<IconPackage size={14} />}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.yearLabel}
                  value={form.manufactureYear}
                  onChange={(e) => setForm({ ...form, manufactureYear: e.target.value })}
                  leftSection={<IconCalendar size={14} />}
                />
                <FieldSelect
                  label={t.vehiclesPage.statusLabel}
                  value={form.active ? 'true' : 'false'}
                  onChange={(e) => setForm({ ...form, active: (e.target as HTMLSelectElement).value === 'true' })}
                  options={[
                    { value: 'true', label: t.vehiclesPage.operationalStatus },
                    { value: 'false', label: t.vehiclesPage.statusOutOfService },
                  ]}
                />
              </div>
            </div>
          </div>
        </div>
      </AppModal>

      {/* ── Retire Confirm Modal ── */}
      <ConfirmModal
        open={confirmOpen}
        title={t.vehiclesPage.retireTitle}
        description={
          pendingDelete
            ? t.vehiclesPage.retireDescription.replace('{vehicleName}', `${pendingDelete.make} ${pendingDelete.model}`)
            : ''
        }
        confirmLabel={t.vehiclesPage.retireButton}
        cancelLabel={t.vehiclesPage.deleteCancel}
        variant="danger"
        loading={confirmDeleting}
        onConfirm={confirmDelete}
        onCancel={() => { setConfirmOpen(false); setPendingDelete(null); }}
      />
    </div>
  );
}

export default function VehiclesPage() {
  return <VehiclesPageContent />;
}

