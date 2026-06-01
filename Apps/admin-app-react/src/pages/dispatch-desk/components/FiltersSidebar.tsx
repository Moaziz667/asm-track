import React from 'react';
import { cn } from '@/lib/utils';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { IconSearch, IconX, IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import { STATUS_DOT, STATUS_TIP } from '../constants';

export function FiltersSidebar() {
  const {
    t,
    filtersOpen,
    setFiltersOpen,
    mobileTab,
    search,
    setSearch,
    period,
    setPeriod,
    customFrom,
    setCustomFrom,
    customTo,
    setCustomTo,
    driverId,
    setDriverId,
    drivers,
    zoneFilter,
    setZoneFilter,
    zones,
    routeFilter,
    setRouteFilter,
    routeOptions,
    statusFilter,
    setStatusFilter,
    refreshing,
    doRefresh,
    clearFilters,
  } = useDispatchDeskContext();

  return (
    <div className={cn(
      'shrink-0 overflow-y-auto scrollbar-hide flex flex-col transition-all duration-200',
      filtersOpen ? 'lg:w-[240px]' : 'lg:w-[48px]',
      mobileTab === 'filters' ? 'w-full' : 'hidden lg:flex',
    )} style={{ background: 'var(--surface)', borderRight: '1px solid var(--border)' }}>

      {/* Header */}
      <div className="p-4 border-b border-[var(--border)] flex items-center justify-between gap-2 shrink-0">
        {filtersOpen && (
          <div className="min-w-0">
            <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.pages.dispatch.subtitle}</span>
            <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
              {t.pages.dispatch.title}
            </h1>
          </div>
        )}
        <button
          type="button"
          onClick={() => setFiltersOpen(o => !o)}
          className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors shrink-0"
          title={filtersOpen ? t.dispatchDeskPage.filterToggleReduce : t.dispatchDeskPage.filterToggleShow}
        >
          {filtersOpen ? <IconChevronLeft size={13} stroke={2.5} /> : <IconChevronRight size={13} stroke={2.5} />}
        </button>
      </div>

      {filtersOpen && (
        <div className="flex flex-col overflow-hidden">
          {/* Filters */}
          <div className="flex flex-col gap-3 p-5 border-b border-[var(--border)]">
            <FieldInput
              label={<span className="text-[11px] font-[500] text-[var(--text-muted)]">{t.dispatchDeskPage.filterQuickSearch}</span>}
              placeholder={t.dispatchDeskPage.filterQuickSearchPlaceholder}
              leftSection={<IconSearch size={14} stroke={2.5} className="text-[var(--text-muted)]" />}
              value={search}
              onChange={e => setSearch(e.currentTarget.value)}
              className="h-9 text-[12px]"
            />

            {/* Period pills */}
            <div>
              <p className="text-[11px] font-medium text-[var(--text-muted)] mb-2">{t.dispatchDeskPage.filterPeriod}</p>
              <div className="flex items-center gap-1.5 flex-wrap">
                {([
                  ['day', t.dispatchDeskPage.periodDay],
                  ['week', t.dispatchDeskPage.periodWeek],
                  ['month', t.dispatchDeskPage.periodMonth],
                  ['all', t.dispatchDeskPage.periodAll],
                  ['custom', t.dispatchDeskPage.periodCustom]
                ] as const).map(([v, label]) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => setPeriod(v)}
                    className={cn(
                      'px-3 py-1.5 text-[11px] font-medium rounded-[4px] transition-all cursor-pointer',
                      period === v
                        ? 'bg-[var(--app-bg)] text-[var(--brand)] border border-[var(--brand)]'
                        : 'bg-transparent text-[var(--text-muted)] border border-transparent hover:bg-[var(--app-bg)]',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
              {period === 'custom' && (
                <div className="flex flex-col gap-1.5 mt-2">
                  <FieldInput
                    type="date"
                    label={<span className="text-[11px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.dateFrom}</span>}
                    value={customFrom}
                    onChange={e => setCustomFrom(e.currentTarget.value)}
                  />
                  <FieldInput
                    type="date"
                    label={<span className="text-[11px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.dateTo}</span>}
                    value={customTo}
                    onChange={e => setCustomTo(e.currentTarget.value)}
                  />
                </div>
              )}
            </div>

            <FieldSelect
              label={<span className="text-[11px] font-[500] text-[var(--text-muted)]">{t.dispatchDeskPage.filterDriver}</span>}
              placeholder={t.dispatchDeskPage.filterDriverPlaceholder}
              value={driverId}
              onChange={e => setDriverId(e.currentTarget.value)}
              options={[{ value: '', label: t.dispatchDeskPage.filterDriverNoFilter }, ...drivers.map(d => ({ value: d.id, label: d.name }))]}
              className="h-9 text-[12px]"
            />

            <FieldSelect
              label={<span className="text-[11px] font-[500] text-[var(--text-muted)]">{t.dispatchDeskPage.filterZone}</span>}
              placeholder={t.dispatchDeskPage.filterZonePlaceholder}
              value={zoneFilter}
              onChange={e => setZoneFilter(e.currentTarget.value)}
              options={[{ value: '', label: t.dispatchDeskPage.filterZoneGlobal }, ...zones.map(z => ({ value: z.name, label: z.name }))]}
              className="h-9 text-[12px]"
            />

            {routeOptions.length > 0 && (
              <FieldSelect
                label={<span className="text-[11px] font-[500] text-[var(--text-muted)]">{t.dispatchDeskPage.filterRoute}</span>}
                placeholder={t.dispatchDeskPage.filterRoutePlaceholder}
                value={routeFilter}
                onChange={e => setRouteFilter(e.currentTarget.value)}
                options={routeOptions}
                className="h-9 text-[12px]"
              />
            )}

            <FieldSelect
              label={<span className="text-[11px] font-[500] text-[var(--text-muted)]">{t.dispatchDeskPage.filterStatus}</span>}
              placeholder={t.dispatchDeskPage.filterStatusPlaceholder}
              value={statusFilter}
              onChange={e => setStatusFilter(e.currentTarget.value)}
              options={[
                { value: '', label: t.dispatchDeskPage.filterStatusAll },
                { value: 'UNSCHEDULED', label: t.dispatchDeskPage.filterStatusUnscheduled },
                { value: 'SCHEDULED',   label: t.dispatchDeskPage.filterStatusScheduled },
                { value: 'PICKED_UP',   label: t.dispatchDeskPage.filterStatusPickedUp },
                { value: 'IN_TRANSIT',  label: t.dispatchDeskPage.filterStatusInTransit },
                { value: 'DELIVERED',   label: t.dispatchDeskPage.filterStatusDelivered },
                { value: 'PARTIALLY_DELIVERED', label: t.dispatchDeskPage.filterStatusPartial },
                { value: 'CANCELLED',   label: t.dispatchDeskPage.filterStatusCancelled },
                { value: 'FAILED',      label: t.dispatchDeskPage.filterStatusFailed },
              ]}
              className="h-9 text-[12px]"
            />

            <div className="flex gap-2">
              <button
                type="button"
                className="flex-1 h-9 rounded-[2px] border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] text-[var(--text-primary)] font-[500] text-[12px] shadow-[0_1px_2px_rgba(0,0,0,0.05)] transition-all flex items-center justify-center gap-2 disabled:opacity-50 active:scale-[0.98]"
                onClick={doRefresh}
                disabled={refreshing}
              >
                {refreshing ? t.dispatchDeskPage.buttonLoading : t.dispatchDeskPage.buttonRefresh}
              </button>
              <button
                type="button"
                className="w-9 h-9 flex items-center justify-center rounded-[2px] border border-[var(--border)] text-[var(--text-muted)] hover:border-[var(--brand)] transition-colors"
                onClick={clearFilters}
              >
                <IconX size={14} stroke={2.5} />
              </button>
            </div>
          </div>

          {/* Active drivers */}
          {(() => {
            const active = drivers.filter(d => d.onlineStatus === 'ONLINE' || d.onlineStatus === 'ON_BREAK');
            if (active.length === 0) return null;
            return (
              <div className="px-4 py-3" style={{ borderTop: '1px solid var(--border)' }}>
                <p className="text-[11px] font-[500] mb-2" style={{ color: 'var(--text-muted)' }}>
                  {t.dispatchDeskPage.activeDriversLabel} · {active.length}
                </p>
                <div className="flex flex-col gap-0.5">
                  {active.map(d => (
                    <div key={d.id} className="flex items-center gap-2 px-2 py-1 rounded-[var(--radius)] hover:bg-[var(--hover-bg)] transition-colors">
                      <div style={{ width: 6, height: 6, borderRadius: '50%', background: STATUS_DOT[d.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                      <span className="text-[12px] font-[500] truncate flex-1" style={{ color: 'var(--text-primary)' }}>{d.name}</span>
                      <span className="text-[10px] font-[500]" style={{ color: 'var(--text-muted)' }}>
                        {STATUS_TIP[d.onlineStatus ?? 'OFFLINE']}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            );
          })()}
        </div>
      )}
    </div>
  );
}
