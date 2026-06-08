

import React, { useState, useEffect, useMemo } from 'react';
import { IconUser, IconRoute, IconSearch, IconAlertTriangle, IconInfoCircle } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { FieldInput, FieldTextarea } from '@/components/ui/field';
import { AppModal } from './AppModal';
import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';

export interface DriverOption {
  id: string;
  name?: string;
  phone?: string;
  vehicleCapacityKg?: number;
  currentLoadKg?: number;
  todayRouteId?: string;
  todayRouteName?: string;
  todayStopCount?: number;
}

export interface RouteOption {
  id: string;
  name: string;
  driverId?: string;
  driverName?: string;
  stopCount: number;
  city?: string;
  status: string;
  capacityKg?: number;
  currentLoadKg?: number;
}

interface ReassignModalProps {
  open: boolean;
  entityName?: string;
  entityLabel?: string;
  entityWeightKg?: number;
  drivers: DriverOption[];
  routes?: RouteOption[];
  currentDriverId?: string;
  loading?: boolean;
  onConfirm: (payload: {
    targetType: 'driver' | 'route';
    targetId: string;
    startTime?: string;
    endTime?: string;
    note?: string;
    stopOrder?: number;
    acknowledgeWarnings?: boolean;
  }) => void;
  onCancel: () => void;
}

type Tab = 'driver' | 'route';

export function ReassignModal({
  open,
  entityLabel = 'Livraison',
  entityName,
  entityWeightKg = 0,
  drivers,
  routes = [],
  currentDriverId,
  loading = false,
  onConfirm,
  onCancel,
}: ReassignModalProps) {
  const t = useT();
  const [tab, setTab] = useState<Tab>('driver');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [note, setNote] = useState('');
  const [stopOrder, setStopOrder] = useState<number | ''>('');
  const [acknowledgeWarnings, setAcknowledgeWarnings] = useState(false);

  useEffect(() => {
    if (!open) {
      setSearch(''); setSelectedId(null); setStartTime('');
      setEndTime(''); setNote(''); setAcknowledgeWarnings(false); setTab('driver');
    }
  }, [open]);

  const filteredDrivers = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = drivers.filter(d => d.id !== currentDriverId);
    if (!q) return list;
    return list.filter(d => d.name?.toLowerCase().includes(q) || d.phone?.toLowerCase().includes(q));
  }, [drivers, search, currentDriverId]);

  const filteredRoutes = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = routes.filter(r => r.status !== 'CLOSED' && r.status !== 'CANCELLED');
    if (!q) return list;
    return list.filter(r =>
      r.name.toLowerCase().includes(q) || r.driverName?.toLowerCase().includes(q) || r.city?.toLowerCase().includes(q)
    );
  }, [routes, search]);

  const capacityInfo = useMemo(() => {
    if (!selectedId) return null;
    let capacity = 0, currentLoad = 0;
    if (tab === 'driver') {
      const d = drivers.find(x => x.id === selectedId);
      if (!d) return null;
      capacity = d.vehicleCapacityKg ?? 0; currentLoad = d.currentLoadKg ?? 0;
    } else {
      const r = routes.find(x => x.id === selectedId);
      if (!r) return null;
      capacity = r.capacityKg ?? 0; currentLoad = r.currentLoadKg ?? 0;
    }
    if (!capacity) return { capacity: 0, currentLoad, newLoad: currentLoad + entityWeightKg, pct: 0, over: false };
    const newLoad = currentLoad + entityWeightKg;
    const pct = Math.round((newLoad / capacity) * 100);
    return { capacity, currentLoad, newLoad, pct, over: newLoad > capacity };
  }, [selectedId, tab, drivers, routes, entityWeightKg]);

  const canConfirm = !!selectedId && !loading && (!capacityInfo?.over || acknowledgeWarnings);

  const handleConfirm = () => {
    if (!selectedId || (capacityInfo?.over && !acknowledgeWarnings)) return;
    onConfirm({
      targetType: tab, targetId: selectedId,
      startTime: startTime || undefined, endTime: endTime || undefined,
      note: note.trim() || undefined,
      stopOrder: typeof stopOrder === 'number' ? stopOrder : undefined,
      acknowledgeWarnings,
    });
  };

  return (
    <AppModal
      open={open}
      onClose={onCancel}
      title={
        <div className="flex items-center gap-2">
          <span>{t.reassignCommandOverlay.title}</span>
          {entityName && (
            <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-[var(--brand-soft)] text-[var(--brand)]">
              {entityLabel} · {entityName}
            </span>
          )}
        </div>
      }
      size="lg"
      footer={
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={onCancel} disabled={loading}>
            {t.actions.cancel}
          </Button>
          <Button size="sm" onClick={handleConfirm} disabled={!canConfirm}>
            {loading && (
              <svg className="animate-spin -ml-0.5 mr-1.5 h-3 w-3" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
              </svg>
            )}
            {t.reassignCommandOverlay.confirmBtn}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-4">
        {routes.length > 0 && (
          <Tabs value={tab} onValueChange={v => setTab(v as Tab)}>
            <TabsList className="w-full">
              <TabsTrigger value="driver" className="flex-1 gap-1.5">
                <IconUser size={13} /> {t.reassignCommandOverlay.driverTab}
              </TabsTrigger>
              <TabsTrigger value="route" className="flex-1 gap-1.5">
                <IconRoute size={13} /> {t.reassignCommandOverlay.routeTab}
              </TabsTrigger>
            </TabsList>
          </Tabs>
        )}

        <FieldInput
          placeholder={tab === 'driver' ? t.reassignCommandOverlay.searchDriver : t.reassignCommandOverlay.searchRoute}
          leftSection={<IconSearch size={13} />}
          value={search}
          onChange={e => setSearch(e.currentTarget.value)}
        />

        <div className="overflow-y-auto max-h-64 flex flex-col gap-1.5">
          {tab === 'driver' ? filteredDrivers.map(d => {
            const capPct = d.vehicleCapacityKg ? Math.round(((d.currentLoadKg ?? 0) / d.vehicleCapacityKg) * 100) : 0;
            const selected = selectedId === d.id;
            return (
              <button key={d.id} type="button" onClick={() => setSelectedId(d.id)}
                className={cn(
                  'flex items-center gap-3 p-3 rounded border text-left transition-colors w-full',
                  selected ? 'border-[var(--brand)] bg-[var(--brand-soft)]' : 'border-[var(--border)] bg-[var(--surface)] hover:border-[var(--border-strong)]',
                )}
              >
                <div className="w-8 h-8 rounded-md bg-[var(--sidebar-bg)] flex items-center justify-center shrink-0 text-xs font-semibold text-[var(--text-muted)]">
                  {(d.name ?? '?').slice(0, 2).toUpperCase()}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-[var(--text-primary)] truncate">{d.name ?? t.reassignCommandOverlay.unnamed}</span>
                    {d.todayRouteId && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-[#EFF6FF] text-[#2563EB]">{d.todayStopCount ?? 0} {t.reassignCommandOverlay.stops}</span>
                    )}
                  </div>
                  <p className="text-xs text-[var(--text-soft)] truncate">
                    {d.phone ?? '—'}{d.vehicleCapacityKg ? ` · ${d.currentLoadKg ?? 0}/${d.vehicleCapacityKg} kg (${capPct}%)` : ''}
                  </p>
                </div>
              </button>
            );
          }) : filteredRoutes.map(r => {
            const selected = selectedId === r.id;
            return (
              <button key={r.id} type="button" onClick={() => setSelectedId(r.id)}
                className={cn(
                  'flex items-center gap-3 p-3 rounded border text-left transition-colors w-full',
                  selected ? 'border-[var(--brand)] bg-[var(--brand-soft)]' : 'border-[var(--border)] bg-[var(--surface)] hover:border-[var(--border-strong)]',
                )}
              >
                <div className="w-8 h-8 rounded-md bg-[var(--sidebar-bg)] flex items-center justify-center shrink-0">
                  <IconRoute size={16} className="text-[var(--text-muted)]" />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-[var(--text-primary)] truncate">{r.name}</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--sidebar-bg)] text-[var(--text-muted)]">{r.status}</span>
                  </div>
                  <p className="text-xs text-[var(--text-soft)] truncate">
                    {r.driverName ?? t.reassignCommandOverlay.noDriver} · {r.stopCount} {t.reassignCommandOverlay.stops}{r.city ? ` · ${r.city}` : ''}
                  </p>
                </div>
              </button>
            );
          })}
          {((tab === 'driver' && filteredDrivers.length === 0) || (tab === 'route' && filteredRoutes.length === 0)) && (
            <p className="text-sm text-[var(--text-soft)] text-center py-6">{t.reassignCommandOverlay.noResults}</p>
          )}
        </div>

        {tab === 'route' && selectedId && (
          <div className="grid grid-cols-3 gap-2">
            <FieldInput label={t.reassignCommandOverlay.orderLabel} type="number" placeholder={t.reassignCommandOverlay.orderLabel} min={1}
              value={stopOrder} onChange={e => setStopOrder(e.target.value ? Number(e.target.value) : '')} />
            <FieldInput label={t.reassignCommandOverlay.timeStart} type="time" value={startTime} onChange={e => setStartTime(e.target.value)} />
            <FieldInput label={t.reassignCommandOverlay.timeEnd} type="time" value={endTime} onChange={e => setEndTime(e.target.value)} />
          </div>
        )}

        <FieldTextarea
          label={t.reassignCommandOverlay.noteLabel}
          placeholder={t.reassignCommandOverlay.notePlaceholder}
          value={note}
          onChange={e => setNote(e.target.value)}
        />

        {capacityInfo && capacityInfo.capacity > 0 && (
          <div className={cn(
            'rounded border p-3 flex flex-col gap-2',
            capacityInfo.over ? 'bg-[var(--danger-bg)] border-[#FECACA]' : capacityInfo.pct > 80 ? 'bg-[var(--warning-bg)] border-[#FDE68A]' : 'bg-[var(--success-bg)] border-[#A7F3D0]',
          )}>
            <div className="flex items-center gap-2">
              {capacityInfo.over
                ? <IconAlertTriangle size={14} className="text-[var(--danger)]" />
                : <IconInfoCircle size={14} className="text-[var(--text-muted)]" />}
              <span className="text-sm font-medium text-[var(--text-primary)]">
                Capacité cible : {capacityInfo.newLoad} / {capacityInfo.capacity} kg ({capacityInfo.pct}%)
              </span>
            </div>
            <div className="h-1.5 rounded-full bg-black/10 overflow-hidden">
              <div
                className={cn('h-full rounded-full', capacityInfo.over ? 'bg-[var(--danger)]' : capacityInfo.pct > 80 ? 'bg-[var(--warning)]' : 'bg-[var(--success)]')}
                style={{ width: `${Math.min(capacityInfo.pct, 100)}%` }}
              />
            </div>
            {capacityInfo.over && (
              <label className="flex items-center gap-2 cursor-pointer mt-1">
                <input type="checkbox" checked={acknowledgeWarnings} onChange={e => setAcknowledgeWarnings(e.target.checked)} className="w-3.5 h-3.5 accent-[var(--danger)]" />
                <span className="text-xs text-[var(--danger)] font-medium">Forcer malgré le dépassement</span>
              </label>
            )}
          </div>
        )}
      </div>
    </AppModal>
  );
}

export default ReassignModal;

