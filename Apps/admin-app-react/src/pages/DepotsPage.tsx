

import { lazy as dynamic } from 'react';
import { useDepots, useSyncDepotsFromErp } from '@/hooks/useDepots';
import { useT } from '@/lib/LocaleContext';
import {
  IconRefresh, IconBuildingWarehouse, IconWorld, IconLayoutDashboard, IconCloudDownload,
} from '@tabler/icons-react';
import { isReadOnlyRole, getCurrentRole } from '@/lib/auth';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

// ─── Map Components (SSR Disabled) ───────────────────────────────────────────

const DepotsOverviewMap = dynamic(() => import('@/components/DepotsOverviewMap'));

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

  const mappableDepots = depots.filter(d => d.latitude != null && d.longitude != null);

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', background: 'var(--app-bg)' }}>

      {/* ── Header ── */}
      <div
        className="sticky top-0 z-20 min-h-16 h-auto lg:h-16 py-4 lg:py-0 flex items-center"
        style={{ background: 'var(--surface)', borderBottom: '1px solid var(--border)' }}
      >
        <div className="px-6 w-full flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4 sm:gap-8">
            <div>
              <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.depotsPage.pageSubtitle}</span>
              <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
                {t.depotsPage.pageTitle} <span className="text-[var(--brand)]">{t.depotsPage.pageTitleBrand}</span>
              </h1>
            </div>
            <div className="hidden sm:block w-px h-6 bg-[var(--border)]" />
            <p className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
              {t.depotsPage.depotsCount.replace('{count}', depots.length.toString())}
            </p>
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto justify-between sm:justify-start">
            {!readOnly && (
              <Button
                className="font-bold text-[11px] rounded-md"
                style={{ background: 'var(--brand)', color: 'white', border: 'none' }}
                onClick={() => syncMutation.mutate()}
                disabled={syncMutation.isPending}
              >
                {syncMutation.isPending ? <Spinner size={14} /> : <IconCloudDownload size={14} />}
                {t.depotsPage.syncButton}
              </Button>
            )}
            <button
              type="button"
              onClick={() => fetchDepots()}
              className="w-9 h-9 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
              style={{ background: 'var(--app-bg)' }}
            >
              {loading ? <Spinner size={18} /> : <IconRefresh size={18} style={{ color: 'var(--text-muted)' }} />}
            </button>
          </div>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="overflow-y-auto" style={{ height: 'calc(100vh - 128px)' }}>
        <div className="flex flex-col gap-8 max-w-[1400px] mx-auto p-4 md:p-8">

          {/* ── ERP read-only note ── */}
          <div
            className="px-4 py-2.5 rounded-md text-[11px] font-medium flex items-center gap-2"
            style={{ background: 'var(--brand-soft)', color: 'var(--text-secondary)', border: '1px solid var(--border)' }}
          >
            <IconCloudDownload size={14} style={{ color: 'var(--brand)' }} />
            {t.depotsPage.erpReadOnlyNote}
          </div>

          {/* ── Overview Map ── */}
          <div
            className="rounded-lg overflow-hidden shadow-sm"
            style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}
          >
            <div
              className="px-5 py-3 flex items-center justify-between"
              style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
            >
              <div className="flex items-center gap-2">
                <IconWorld size={16} style={{ color: 'var(--brand)' }} />
                <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
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
                      <p className="text-[11px] font-[600]" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.totalHubs}</p>
                      <p className="text-[13px] font-[600] font-mono" style={{ color: 'var(--text-primary)' }}>{depots.length}</p>
                    </div>
                    <div className="w-px h-5 bg-[var(--border)]" />
                    <div>
                      <p className="text-[11px] font-[600]" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.operationalHubs}</p>
                      <p className="text-[13px] font-[600] font-mono text-[#2D8A5E]">{depots.filter(d => d.isActive).length}</p>
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
              <span className="text-[11px] font-[600]" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.registryTitle}</span>
            </div>

            {loading ? (
              <div className="flex items-center justify-center h-[200px]"><Spinner size={28} /></div>
            ) : depots.length === 0 ? (
              <div
                className="rounded-lg p-[60px] flex flex-col items-center gap-2"
                style={{ border: '1px dashed var(--border)', background: 'var(--surface)' }}
              >
                <IconBuildingWarehouse size={40} style={{ color: 'var(--border)' }} />
                <p className="text-[11px] font-bold" style={{ color: 'var(--text-muted)' }}>{t.depotsPage.noDepots}</p>
              </div>
            ) : (
              <div
                className="rounded-lg overflow-hidden shadow-sm"
                style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}
              >
                <div className="overflow-x-auto">
                  <div className="min-w-[800px] lg:min-w-0">
                    {/* Table header */}
                    <div
                      className="grid grid-cols-[2fr_1fr_3fr_2fr_1fr] px-4 py-3"
                      style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
                    >
                      {[t.depotsPage.headerDesignation, t.depotsPage.headerWarehouseCode, t.depotsPage.headerLocation, t.depotsPage.headerCoordinates, t.depotsPage.headerStatus].map((h, i) => (
                        <div
                          key={i}
                          className={cn('text-[11px] font-[600]', i === 3 || i === 4 ? 'text-center' : '')}
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
                        className="grid grid-cols-[2fr_1fr_3fr_2fr_1fr] px-4 py-3 items-center transition-colors hover:bg-[var(--hover-bg)]"
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
                          <p className="text-[11px] font-bold" style={{ color: 'var(--text-primary)' }}>{depot.name}</p>
                        </div>

                        {/* Warehouse code */}
                        <div>
                          <span
                            className="text-[11px] font-bold font-mono px-2 py-1 rounded-md inline-block"
                            style={{ color: 'var(--text-secondary)', background: 'var(--app-bg)' }}
                          >
                            {depot.warehouseCode || '—'}
                          </span>
                        </div>

                        {/* Address */}
                        <p className="text-[11px] max-w-[300px] truncate" style={{ color: 'var(--text-muted)' }}>
                          {depot.address || '—'}
                        </p>

                        {/* Coordinates */}
                        <div className="flex justify-center">
                          {depot.latitude != null && depot.longitude != null ? (
                            <span
                              className="text-[11px] font-bold font-mono px-2 py-1 rounded-md inline-block"
                              style={{ color: 'var(--text-primary)', background: 'var(--app-bg)' }}
                            >
                              {depot.latitude.toFixed(5)}, {depot.longitude.toFixed(5)}
                            </span>
                          ) : (
                            <span className="text-[11px] font-semibold" style={{ color: 'var(--danger)' }}>
                              {t.depotsPage.coordsMissing}
                            </span>
                          )}
                        </div>

                        {/* Status badge */}
                        <div className="flex justify-center">
                          <span
                            className="text-[11px] font-bold px-2 py-0.5 rounded-md flex items-center gap-1.5 border"
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
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
