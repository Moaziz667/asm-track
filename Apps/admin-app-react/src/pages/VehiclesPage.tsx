

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
import { AddButton } from '@/components/ui/AddButton';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import type { ColumnDef } from '@/hooks/useColumnSettings';
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

const VEHICLE_COLUMNS: ColumnDef[] = [
  { id: 'vehicle',  label: 'Véhicule',        pinned: true },
  { id: 'plate',    label: 'Immatriculation',  pinned: true },
  { id: 'payload',  label: 'Charge utile' },
  { id: 'driver',   label: 'Chauffeur' },
  { id: 'status',   label: 'Statut' },
];

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
          <span className="text-xs font-semibold px-2 py-0.5 rounded-md border bg-background text-foreground shadow-sm">
            {typeLabel[vehicle.type]}
          </span>
        </div>

        {/* Retired badge */}
        {isRetired && (
          <div className="absolute top-3 right-3">
            <span className="text-2xs font-bold px-2 py-1 rounded-md bg-red-500/10 text-red-600 border border-red-500/20">
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
            <StatusBadge
              status={isRetired ? 'RETIRED' : isBusy ? 'ENGAGED' : 'AVAILABLE'}
              label={statusLabel}
              size="sm"
            />
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
          <p className="text-xs font-medium text-muted-foreground mb-0.5">
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
                  className="h-8 px-3 text-2xs font-bold text-emerald-600 hover:bg-emerald-500/10"
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

  const fleetOccupancy = stats.total > 0 ? (stats.busy / stats.total) * 100 : 0;

  const { density, setDensity } = useDensity('vehicles', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('vehicles', VEHICLE_COLUMNS);

  const ROW_H: Record<typeof density, string> = {
    compact:     'h-9',
    comfortable: 'h-12',
    spacious:    'h-16',
  };

  const vehicleQuickFilters = [
    { value: 'ALL',     label: t.vehiclesPage.fleetTotal,      count: stats.total },
    { value: 'ACTIVE',  label: t.vehiclesPage.operational,     count: stats.active },
    { value: 'BUSY',    label: t.vehiclesPage.statusEngaged,   count: stats.busy },
    { value: 'RETIRED', label: t.vehiclesPage.statusRetired,   count: stats.retired },
  ];

  return (
    <div
      className="flex flex-col overflow-hidden"
      style={{ height: 'calc(100vh - 64px)', background: 'var(--app-bg)' }}
    >
      <PageFilterBar
        search={searchTerm}
        onSearch={setSearchTerm}
        searchPlaceholder={t.vehiclesPage.searchPlaceholder}
        onRefresh={fetchData}
        refreshing={loading}
        quickFilters={vehicleQuickFilters}
        activeQuickFilter={statusFilter}
        onQuickFilterChange={setStatusFilter}
        extraActions={
          !readOnly ? (
            <AddButton label={t.vehiclesPage.newVehicleButton} onClick={() => { resetForm(); setModalOpen(true); }} />
          ) : undefined
        }
      />

      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* ── Technical Grid ── */}
        <div className="flex flex-col flex-1 overflow-hidden min-w-0" style={{ background: 'var(--app-bg)' }}>
          {/* Toolbar */}
          <div
            className="flex items-center justify-between px-4 h-11 shrink-0"
            style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}
          >
            <p className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
              {t.vehiclesPage.displayedCount.replace('{count}', filtered.length.toString())}
            </p>
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

          {/* Table */}
          <div className="flex-1 overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center h-[400px]"><Spinner size={40} /></div>
            ) : filtered.length === 0 ? (
              <div className="flex flex-col items-center py-[120px] gap-2">
                <IconTruck size={48} style={{ color: 'var(--border)' }} />
                <p className="text-xs font-semibold text-[var(--text-muted)]">{t.vehiclesPage.noVehiclesFound}</p>
              </div>
            ) : (
              <table className="w-full border-collapse">
                <thead className="sticky top-0 z-10" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                  <tr style={{ boxShadow: '0 1px 0 var(--border)' }}>
                    {/* Thumbnail always first */}
                    <th className="px-4 py-2.5" style={{ color: 'var(--text-muted)', width: 56 }} />
                    {orderedColumns.map(col => visibleIds.has(col.id) && (
                      <th key={col.id} className="px-4 py-2.5 text-left text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
                        {col.label}
                      </th>
                    ))}
                    <th className="px-4 py-2.5" style={{ color: 'var(--text-muted)', width: 80 }} />
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((v) => {
                    const isBusy = v.assigned ?? Boolean(v.driverId);
                    const isRetired = !v.active;
                    const statusColor = isRetired ? '#A52B24' : isBusy ? '#4C56B8' : '#2D8A5E';
                    const statusLabel = isRetired ? t.vehiclesPage.statusRetired : isBusy ? t.vehiclesPage.statusEngaged : t.vehiclesPage.statusAvailable;
                    const driverName = drivers.find(d => d.id === v.driverId)?.name;
                    const vTypeLabel: Record<string, string> = {
                      TRUCK: t.vehiclesPage.vehicleTypeHeavy,
                      VAN:   t.vehiclesPage.vehicleTypeVan,
                      CAR:   t.vehiclesPage.vehicleTypeCar,
                      MOTO:  t.vehiclesPage.vehicleTypeMoto,
                    };
                    return (
                      <tr
                        key={v.id}
                        className={cn(ROW_H[density], 'group transition-colors hover:bg-[var(--hover-bg)]', isRetired && 'opacity-60')}
                        style={{ borderBottom: '1px solid var(--border)' }}
                      >
                        {/* Thumbnail always first */}
                        <td className="px-4">
                          <div
                            className="w-9 h-9 rounded-md overflow-hidden flex items-center justify-center shrink-0"
                            style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}
                          >
                            {v.imageUrl
                              ? <img src={v.imageUrl} alt={v.make} className="w-full h-full object-cover" />
                              : <IconTruck size={16} style={{ color: 'var(--text-muted)' }} />
                            }
                          </div>
                        </td>
                        {orderedColumns.map(col => {
                          if (!visibleIds.has(col.id)) return null;
                          if (col.id === 'vehicle') return (
                            <td key="vehicle" className="px-4">
                              <p className="text-sm font-bold" style={{ color: 'var(--text-primary)' }}>{v.make} <span className="font-medium" style={{ color: 'var(--text-secondary)' }}>{v.model}</span></p>
                              <p className="text-2xs font-medium mt-0.5" style={{ color: 'var(--text-muted)' }}>{vTypeLabel[v.type] ?? v.type}{v.manufactureYear ? ` · ${v.manufactureYear}` : ''}</p>
                            </td>
                          );
                          if (col.id === 'plate') return (
                            <td key="plate" className="px-4">
                              <span className="font-mono text-sm font-bold" style={{ color: 'var(--text-primary)' }}>{v.plate}</span>
                            </td>
                          );
                          if (col.id === 'payload') return (
                            <td key="payload" className="px-4">
                              <span className="text-sm font-mono" style={{ color: 'var(--text-secondary)' }}>
                                {v.payloadKg ? `${(v.payloadKg / 1000).toFixed(1)} T` : '—'}
                              </span>
                            </td>
                          );
                          if (col.id === 'driver') return (
                            <td key="driver" className="px-4">
                              <span className="text-sm" style={{ color: driverName ? 'var(--text-secondary)' : 'var(--text-muted)' }}>
                                {driverName ?? '—'}
                              </span>
                            </td>
                          );
                          if (col.id === 'status') return (
                            <td key="status" className="px-4">
                              <StatusBadge
                                status={isRetired ? 'RETIRED' : isBusy ? 'ENGAGED' : 'AVAILABLE'}
                                label={statusLabel}
                                size="sm"
                              />
                            </td>
                          );
                          return null;
                        })}
                        {/* Actions always last */}
                        <td className="px-4 text-right">
                          <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                            {isRetired ? (
                              !readOnly && (
                                <button type="button" onClick={() => reactivateVehicle(v)}
                                  className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                                  style={{ background: 'var(--app-bg)', color: 'var(--text-muted)' }}
                                  title={t.vehiclesPage.reactivateButton}
                                >
                                  <IconPoint size={13} />
                                </button>
                              )
                            ) : (
                              <>
                                {!readOnly && (
                                  <button type="button" onClick={() => openEdit(v)}
                                    className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                                    style={{ background: 'var(--app-bg)', color: 'var(--text-muted)' }}
                                    title={t.vehiclesPage.editButton}
                                  >
                                    <IconPencil size={13} />
                                  </button>
                                )}
                                {!readOnly && (
                                  <button type="button" onClick={() => deleteVehicle(v)}
                                    className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] hover:border-red-300 hover:text-red-500 transition-colors"
                                    style={{ background: 'var(--app-bg)', color: 'var(--text-muted)' }}
                                    title={t.vehiclesPage.retireButton}
                                  >
                                    <IconTrash size={13} />
                                  </button>
                                )}
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
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
            <Button variant="ghost" size="sm" onClick={resetForm} className="h-7 px-3 text-xs font-bold rounded-md">
              {t.vehiclesPage.cancelButton}
            </Button>
            <Button
              size="sm"
              onClick={saveVehicle}
              disabled={saving}
              className="h-7 px-3 text-xs font-bold rounded-md"
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
              <p className="text-xs font-semibold mb-2" style={{ color: 'var(--text-muted)' }}>
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
                    className="text-2xs font-bold px-2 py-1 rounded-md shadow-sm border border-[var(--border)]"
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

