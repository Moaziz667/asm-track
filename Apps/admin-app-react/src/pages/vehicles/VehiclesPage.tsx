

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/i18n/LocaleContext';
import { applyFieldError } from '@/lib/utils/form-errors';
import {
  IconRefresh, IconPlus, IconSearch,
  IconTruck, IconCar, IconUserCheck,
  IconWeight, IconCalendar, IconPencil, IconTrash,
  IconGauge, IconX,
  IconPoint, IconPackage
} from '@tabler/icons-react';
import type { Driver } from '@/types';
import { getCurrentRole, isReadOnlyRole } from '@/lib/api/auth';
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
import { useIsMobile } from '@/hooks/use-mobile';
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
import { TablePagination } from '@/components/data-display/TablePagination';

// ─── Types ────────────────────────────────────────────────────────────────────

type VehicleType = 'TRUCK' | 'VAN' | 'CAR' | 'MOTO';

const VEHICLE_COLUMNS_BASE: ColumnDef[] = [
  { id: 'vehicle',  label: '', pinned: true },
  { id: 'plate',    label: '', pinned: true },
  { id: 'payload',  label: '' },
  { id: 'driver',   label: '' },
  { id: 'status',   label: '' },
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
  const isMobile = useIsMobile();
  const VEHICLE_COLUMNS: ColumnDef[] = [
    { id: 'vehicle',  label: t.common?.vehicule ?? 'Vehicle',     pinned: true },
    { id: 'plate',    label: t.common?.plaque ?? 'Plate',         pinned: true },
    { id: 'payload',  label: t.common?.chargement ?? 'Payload' },
    { id: 'driver',   label: t.common?.chauffeur ?? 'Driver' },
    { id: 'status',   label: t.common?.statut ?? 'Status' },
  ];
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
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  // Confirm delete state
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<VehicleItem | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  // Image is kept outside the validated form (file/preview, not a schema field).
  const [image, setImage] = useState({ base64: '', previewUrl: '' });

  const vehicleSchema = useMemo(() => z.object({
    make: z.string().trim().min(1, t.validation.required),
    model: z.string().trim().min(1, t.validation.required),
    plate: z.string().trim().min(1, t.validation.required),
    payloadKg: z.string().trim().min(1, t.validation.required)
      .refine((v) => Number(v) > 0, t.validation.positiveNumber),
    volumeM3: z.string().optional()
      .refine((v) => !v || Number(v) > 0, t.validation.invalidNumber),
    manufactureYear: z.string().optional()
      .refine((v) => !v || (/^\d{4}$/.test(v) && Number(v) >= 1950 && Number(v) <= new Date().getFullYear() + 1),
        t.validation.invalidYear),
    type: z.string(),
    active: z.string(),
    color: z.string().optional(),
    vin: z.string().optional(),
    fuelType: z.string().optional(),
    mileageKm: z.string().optional(),
  }), [t]);

  type VehicleForm = z.infer<typeof vehicleSchema>;

  const VEHICLE_DEFAULTS: VehicleForm = {
    make: '', model: '', manufactureYear: String(new Date().getFullYear()),
    color: '', vin: '', fuelType: '', payloadKg: '', volumeM3: '',
    mileageKm: '', plate: '', type: 'VAN', active: 'true',
  };

  const { register, handleSubmit, reset, setError, formState: { errors } } = useForm<VehicleForm>({
    resolver: zodResolver(vehicleSchema),
    defaultValues: VEHICLE_DEFAULTS,
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

  // Client-side pagination over the filtered fleet (bounded data; keeps instant search/filter + full stats).
  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  useEffect(() => { setPage(0); }, [searchTerm, statusFilter, pageSize]);
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = useMemo(
    () => filtered.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [filtered, safePage, pageSize],
  );

  const stats = useMemo(() => {
    const total = vehicles.length;
    const active = vehicles.filter(v => v.active).length;
    const busy = vehicles.filter(v => v.active && (v.assigned ?? Boolean(v.driverId))).length;
    const retired = vehicles.filter(v => !v.active).length;
    const tonnage = vehicles.reduce((acc, v) => acc + (v.payloadKg || 0), 0);
    return { total, active, busy, retired, tonnage };
  }, [vehicles]);

  const resetForm = () => {
    reset(VEHICLE_DEFAULTS);
    setImage({ base64: '', previewUrl: '' });
    setEditingId(null);
    setModalOpen(false);
  };

  const openEdit = (v: VehicleItem) => {
    setEditingId(v.id);
    reset({
      make: v.make, model: v.model, manufactureYear: String(v.manufactureYear || ''),
      color: v.color || '', vin: v.vin || '', fuelType: v.fuelType || '',
      payloadKg: String(v.payloadKg || ''), volumeM3: String(v.volumeM3 || ''),
      mileageKm: String(v.mileageKm || ''), plate: v.plate,
      type: v.type, active: v.active ? 'true' : 'false',
    });
    setImage({ base64: '', previewUrl: v.imageUrl || '' });
    setModalOpen(true);
  };

  const saveVehicle = handleSubmit(async (data) => {
    try {
      const payload = {
        make: data.make, model: data.model, plate: data.plate,
        type: data.type as VehicleType,
        color: data.color, vin: data.vin, fuelType: data.fuelType,
        manufactureYear: Number(data.manufactureYear),
        payloadKg: Number(data.payloadKg),
        volumeM3: data.volumeM3 ? Number(data.volumeM3) : null,
        mileageKm: data.mileageKm ? Number(data.mileageKm) : null,
        active: data.active === 'true',
        imageBase64: image.base64 || undefined,
      };
      if (editingId) {
        await updateVehicleMutation.mutateAsync({ id: editingId, payload });
      } else {
        await createVehicleMutation.mutateAsync(payload);
      }
      resetForm();
    } catch (err) {
      // Show a taken plate under the field; the generic toast (in the mutation hook) is suppressed
      // for this code, so there's no disconnected toast.
      applyFieldError(err, setError, {
        VEHICLE_PLATE_EXISTS: { field: 'plate', message: t.validation.plateTaken },
      });
    }
  });

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
        setImage({ base64: result, previewUrl: result });
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
      className="flex flex-col overflow-visible lg:overflow-hidden h-auto lg:h-[calc(100dvh-56px)]"
      style={{ background: 'var(--app-bg)' }}
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
            ) : isMobile ? (
              <div className="flex flex-col gap-2 p-3">
                {pageRows.map((v) => {
                  const isBusy = v.assigned ?? Boolean(v.driverId);
                  const isRetired = !v.active;
                  const statusLabel = isRetired ? t.vehiclesPage.statusRetired : isBusy ? t.vehiclesPage.statusEngaged : t.vehiclesPage.statusAvailable;
                  const driverName = drivers.find(d => d.id === v.driverId)?.name;
                  return (
                    <div key={v.id} className={cn('rounded-lg border border-[var(--border)] p-3 flex flex-col gap-2', isRetired && 'opacity-60')} style={{ background: 'var(--surface)' }}>
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-md overflow-hidden flex items-center justify-center shrink-0" style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}>
                          {v.imageUrl ? <img src={v.imageUrl} alt={v.make} className="w-full h-full object-cover" /> : <IconTruck size={18} style={{ color: 'var(--text-muted)' }} />}
                        </div>
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-bold truncate" style={{ color: 'var(--text-primary)' }}>{v.make} <span className="font-medium" style={{ color: 'var(--text-secondary)' }}>{v.model}</span></p>
                          <p className="font-mono text-xs font-bold" style={{ color: 'var(--text-muted)' }}>{v.plate}</p>
                        </div>
                        <StatusBadge status={isRetired ? 'RETIRED' : isBusy ? 'ENGAGED' : 'AVAILABLE'} label={statusLabel} size="sm" />
                      </div>
                      <div className="flex items-center justify-between text-xs" style={{ color: 'var(--text-muted)' }}>
                        <span>{v.payloadKg ? `${(v.payloadKg / 1000).toFixed(1)} T` : '—'} · {driverName ?? '—'}</span>
                        {!readOnly && (
                          <div className="flex items-center gap-1.5">
                            {isRetired ? (
                              <button type="button" onClick={() => reactivateVehicle(v)} className="w-8 h-8 flex items-center justify-center rounded border border-[var(--border)]" style={{ background: 'var(--app-bg)', color: 'var(--text-muted)' }} title={t.vehiclesPage.reactivateButton}><IconPoint size={14} /></button>
                            ) : (
                              <>
                                <button type="button" onClick={() => openEdit(v)} className="w-8 h-8 flex items-center justify-center rounded border border-[var(--border)]" style={{ background: 'var(--app-bg)', color: 'var(--text-muted)' }} title={t.vehiclesPage.editButton}><IconPencil size={14} /></button>
                                <button type="button" onClick={() => deleteVehicle(v)} className="w-8 h-8 flex items-center justify-center rounded border border-[var(--border)]" style={{ background: 'var(--app-bg)', color: 'var(--text-muted)' }} title={t.vehiclesPage.deleteButton}><IconTrash size={14} /></button>
                              </>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
            <div className="overflow-x-auto">
              <table className="w-full border-collapse min-w-[800px]">
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
                  {pageRows.map((v) => {
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
                {image.previewUrl ? (
                  <img src={image.previewUrl} alt="Preview" className="w-full h-full object-cover" />
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
                    {image.previewUrl ? t.vehiclesPage.imageChangeButton : t.vehiclesPage.imageUploadButton}
                  </button>
                </div>
              </div>
            </div>

            {/* Form fields */}
            <div className="flex flex-col gap-3">
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.makeLabel}
                  required
                  {...register('make')}
                  error={errors.make?.message}
                />
                <FieldInput
                  label={t.vehiclesPage.modelLabel}
                  required
                  {...register('model')}
                  error={errors.model?.message}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.plateLabel}
                  required
                  className="font-mono font-black"
                  {...register('plate')}
                  error={errors.plate?.message}
                />
                <FieldSelect
                  label={t.vehiclesPage.typeLabel}
                  {...register('type')}
                  options={VEHICLE_TYPES.map(t => ({ value: t, label: typeLabel[t] }))}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.capacityKgLabel}
                  required
                  leftSection={<IconWeight size={14} />}
                  {...register('payloadKg')}
                  error={errors.payloadKg?.message}
                />
                <FieldInput
                  label={t.vehiclesPage.volumeM3Label}
                  leftSection={<IconPackage size={14} />}
                  {...register('volumeM3')}
                  error={errors.volumeM3?.message}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <FieldInput
                  label={t.vehiclesPage.yearLabel}
                  leftSection={<IconCalendar size={14} />}
                  {...register('manufactureYear')}
                  error={errors.manufactureYear?.message}
                />
                <FieldSelect
                  label={t.vehiclesPage.statusLabel}
                  {...register('active')}
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

