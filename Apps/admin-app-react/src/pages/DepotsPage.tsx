

import { lazy as dynamic, useState, Suspense } from 'react';
import { useDepots, useSyncDepotsFromErp, useGeolocateDepot } from '@/hooks/useDepots';
import { useT } from '@/lib/LocaleContext';
import {
  IconRefresh, IconBuildingWarehouse, IconWorld, IconLayoutDashboard, IconCloudDownload, IconMapPin, IconPencil,
} from '@tabler/icons-react';
import { isReadOnlyRole, getCurrentRole } from '@/lib/auth';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import type { Depot } from '@/types';

// ─── Lazy components ──────────────────────────────────────────────────────────

const DepotsOverviewMap = dynamic(() => import('@/components/DepotsOverviewMap'));
const DepotLocationModal = dynamic(() => import('@/components/DepotLocationModal'));

// ─── Spinner ──────────────────────────────────────────────────────────────────

function Spinner({ size = 24 }: { size?: number }) {
  return (
    <svg className="animate-spin text-[var(--brand)]" width={size} height={size} viewBox="0 0 24 24" fill="none">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
    </svg>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function DepotsPage() {
  const t = useT();
  const role = getCurrentRole();
  const readOnly = isReadOnlyRole(role);

  const { data: depots = [], isLoading: loading, refetch: fetchDepots } = useDepots();
  const syncMutation = useSyncDepotsFromErp();
  const geolocateMutation = useGeolocateDepot();
  const [editingDepot, setEditingDepot] = useState<Depot | null>(null);

  const mappableDepots = depots.filter(d => d.latitude != null && d.longitude != null && !isNaN(Number(d.latitude)) && !isNaN(Number(d.longitude)));

  return (
    <div className="flex flex-col overflow-hidden" style={{ height: 'calc(100vh - 64px)', background: 'var(--app-bg)' }}>

      {/* ── Compact Action Bar ── */}
      <div
        className="flex items-center gap-3 px-4 h-11 shrink-0"
        style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}
      >
        <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          {t.depotsPage.depotsCount.replace('{count}', depots.length.toString())}
        </span>
        <div className="ml-auto flex items-center gap-2">
          {!readOnly && (
            <Button
              className="h-7 px-3 text-xs font-bold rounded-md"
              style={{ background: 'var(--brand)', color: 'white', border: 'none' }}
              onClick={() => syncMutation.mutate()}
              disabled={syncMutation.isPending}
            >
              {syncMutation.isPending ? <Spinner size={13} /> : <IconCloudDownload size={13} />}
              {t.depotsPage.syncButton}
            </Button>
          )}
          <button
            type="button"
            onClick={() => fetchDepots()}
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
            style={{ background: 'var(--app-bg)' }}
          >
            {loading ? <Spinner size={14} /> : <IconRefresh size={14} />}
          </button>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="overflow-y-auto flex-1">
        <div className="flex flex-col gap-8 max-w-[1400px] mx-auto p-4 md:p-8">

          {/* ── ERP read-only note ── */}
          <div
            className="px-4 py-2.5 rounded-md text-xs font-medium flex items-center gap-2"
            style={{ background: 'var(--brand-soft)', color: 'var(--text-secondary)', border: '1px solid var(--border)' }}
          >
            <IconCloudDownload size={14} style={{ color: 'var(--brand)' }} />
            {t.depotsPage.erpReadOnlyNote}
          </div>

          {/* ── Overview Map ── */}
          <div
            className="rounded-lg overflow-hidden"
            style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-card)' }}
          >
            <div
              className="px-5 py-3 flex items-center justify-between"
              style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
            >
              <div className="flex items-center gap-2">
                <IconWorld size={16} style={{ color: 'var(--brand)' }} />
                <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>
                  {t.depotsPage.mapTitle}
                </span>
              </div>
            </div>

            <div className="relative">
              <DepotsOverviewMap depots={mappableDepots} height={400} />
              <div className="absolute bottom-4 left-4 z-[1000]">
                <div
                  className="px-3 py-2 rounded-md opacity-90"
                  style={{ border: '1px solid var(--border)', background: 'var(--surface)', backdropFilter: 'blur(4px)' }}
                >
                  <div className="flex items-center gap-4">
                    <div>
                      <p className="text-xs font-[600]" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.totalHubs}</p>
                      <p className="text-base font-[600] font-mono" style={{ color: 'var(--text-primary)' }}>{depots.length}</p>
                    </div>
                    <div className="w-px h-5 bg-[var(--border)]" />
                    <div>
                      <p className="text-xs font-[600]" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.operationalHubs}</p>
                      <p className="text-base font-[600] font-mono text-[var(--success)]">{depots.filter(d => d.isActive).length}</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* ── Registry ── */}
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-2 mb-1">
              <IconLayoutDashboard size={14} style={{ color: 'var(--brand)' }} />
              <span className="text-xs font-[600]" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.registryTitle}</span>
            </div>

            {loading ? (
              <div className="flex items-center justify-center h-[200px]"><Spinner size={28} /></div>
            ) : depots.length === 0 ? (
              <div
                className="rounded-lg p-[60px] flex flex-col items-center gap-2"
                style={{ border: '1px dashed var(--border)', background: 'var(--surface)' }}
              >
                <IconBuildingWarehouse size={40} style={{ color: 'var(--border)' }} />
                <p className="text-xs font-bold" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.noDepots}</p>
              </div>
            ) : (
              <div
                className="rounded-lg overflow-hidden"
                style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-card)' }}
              >
                <div className="overflow-x-auto">
                  <div className="min-w-[800px] lg:min-w-0">
                    {/* Table header */}
                    <div
                      className="grid grid-cols-[2fr_1fr_3fr_2fr_1fr_40px] px-4 py-3"
                      style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
                    >
                      {[t.depotsPage.headerDesignation, t.depotsPage.headerWarehouseCode, t.depotsPage.headerLocation, t.depotsPage.headerCoordinates, t.depotsPage.headerStatus, ''].map((h, i) => (
                        <div
                          key={i}
                          className={cn('text-xs font-[600]', i === 3 || i === 4 ? 'text-center' : '')}
                          style={{ color: 'var(--text-muted)' }}
                        >
                          {h}
                        </div>
                      ))}
                    </div>

                    {/* Table rows */}
                    {depots.map((depot) => (
                      <div
                        key={depot.id}
                        className="grid grid-cols-[2fr_1fr_3fr_2fr_1fr_40px] px-4 py-3 items-center transition-colors hover:bg-[var(--hover-bg)]"
                        style={{ borderBottom: '1px solid var(--border)' }}
                      >
                        {/* Name */}
                        <div className="flex items-center gap-3">
                          <div
                            className="w-[30px] h-[30px] flex items-center justify-center rounded-md"
                            style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}
                          >
                            <IconBuildingWarehouse size={14} style={{ color: 'var(--brand)' }} />
                          </div>
                          <p className="text-xs font-bold" style={{ color: 'var(--text-primary)' }}>{depot.name}</p>
                        </div>

                        {/* Warehouse code */}
                        <div>
                          <span
                            className="text-xs font-bold font-mono px-2 py-1 rounded-md inline-block"
                            style={{ color: 'var(--text-secondary)', background: 'var(--app-bg)' }}
                          >
                            {depot.warehouseCode || '—'}
                          </span>
                        </div>

                        {/* Address */}
                        <p className="text-xs max-w-[300px] truncate" style={{ color: 'var(--text-muted)' }}>
                          {depot.address || '—'}
                        </p>

                        {/* Coordinates */}
                        <div className="flex justify-center items-center gap-2">
                          {depot.latitude != null && depot.longitude != null && !isNaN(Number(depot.latitude)) && !isNaN(Number(depot.longitude)) ? (
                            <span
                              className="text-xs font-bold font-mono px-2 py-1 rounded-md inline-block"
                              style={{ color: 'var(--text-primary)', background: 'var(--app-bg)' }}
                            >
                              {Number(depot.latitude).toFixed(5)}, {Number(depot.longitude).toFixed(5)}
                            </span>
                          ) : (
                            <span className="text-xs font-semibold" style={{ color: 'var(--danger)' }}>
                              {t.depotsPage.coordsMissing}
                            </span>
                          )}
                          {!readOnly && depot.address && (
                            <button
                              type="button"
                              title="Géolocaliser"
                              disabled={geolocateMutation.isPending}
                              onClick={() => geolocateMutation.mutate(depot.id)}
                              className="w-6 h-6 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors shrink-0"
                              style={{ background: 'var(--app-bg)' }}
                            >
                              {geolocateMutation.isPending && geolocateMutation.variables === depot.id
                                ? <Spinner size={11} />
                                : <IconMapPin size={11} style={{ color: 'var(--brand)' }} />}
                            </button>
                          )}
                        </div>

                        {/* Status badge */}
                        <div className="flex justify-center">
                          <span
                            className="text-xs font-bold px-2 py-0.5 rounded-md flex items-center gap-1.5 border"
                            style={{
                              color: depot.isActive ? '#2D8A5E' : '#6B7280',
                              background: depot.isActive ? 'rgba(76,175,130,0.09)' : 'rgba(138,143,152,0.08)',
                              borderColor: depot.isActive ? 'rgba(76,175,130,0.15)' : 'rgba(138,143,152,0.15)',
                            }}
                          >
                            <span
                              className="inline-block w-1.5 h-1.5 rounded-full"
                              style={{ background: depot.isActive ? '#2D8A5E' : '#6B7280' }}
                            />
                            {depot.isActive ? t.depotsPage.statusOperational : t.depotsPage.statusInactive}
                          </span>
                        </div>

                        {/* Edit location */}
                        <div className="flex justify-center">
                          {!readOnly && (
                            <button
                              type="button"
                              title="Modifier la localisation"
                              onClick={() => setEditingDepot(depot)}
                              className="w-7 h-7 flex items-center justify-center rounded-md border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                              style={{ background: 'var(--app-bg)' }}
                            >
                              <IconPencil size={12} style={{ color: 'var(--brand)' }} />
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── Location edit modal ── */}
      {editingDepot && (
        <Suspense fallback={null}>
          <DepotLocationModal
            depot={editingDepot}
            onClose={() => setEditingDepot(null)}
          />
        </Suspense>
      )}
    </div>
  );
}
