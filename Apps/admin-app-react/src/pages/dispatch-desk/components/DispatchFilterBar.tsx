import React, { useRef, useState, useEffect } from 'react';
import { IconSearch, IconChevronDown, IconChevronLeft, IconX } from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { cn } from '@/lib/utils';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';

// ── Types ─────────────────────────────────────────────────────────────────────

type AttrKey = 'status' | 'driver' | 'zone' | 'period';

// ── Dropdown ──────────────────────────────────────────────────────────────────

function FilterDropdown({
  anchorRef,
  open,
  onClose,
}: {
  anchorRef: React.RefObject<HTMLElement | null>;
  open: boolean;
  onClose: () => void;
}) {
  const {
    t,
    driverId, setDriverId, drivers,
    zoneFilter, setZoneFilter, zones,
    statusFilter, setStatusFilter,
    period, setPeriod,
    customFrom, setCustomFrom,
    customTo, setCustomTo,
  } = useDispatchDeskContext();

  const [step, setStep] = useState<'attrs' | AttrKey>('attrs');
  const panelRef = useRef<HTMLDivElement>(null);

  // Reset to attrs list on open
  useEffect(() => { if (open) setStep('attrs'); }, [open]);

  // Click-outside
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (
        panelRef.current && !panelRef.current.contains(e.target as Node) &&
        anchorRef.current && !anchorRef.current.contains(e.target as Node)
      ) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, onClose, anchorRef]);

  if (!open) return null;

  const attrs: Array<{ key: AttrKey; label: string; active: boolean }> = [
    { key: 'status', label: t.dispatchDeskPage.filterStatus,  active: !!statusFilter },
    { key: 'driver', label: t.dispatchDeskPage.filterDriver,  active: !!driverId },
    { key: 'zone',   label: t.dispatchDeskPage.filterZone,    active: !!zoneFilter },
    { key: 'period', label: t.dispatchDeskPage.filterPeriod,  active: period !== 'all' },
  ];

  const STATUSES = [
    { v: '',                    l: t.dispatchDeskPage.filterStatusAll },
    { v: 'UNSCHEDULED',         l: t.dispatchDeskPage.filterStatusUnscheduled },
    { v: 'SCHEDULED',           l: t.dispatchDeskPage.filterStatusScheduled },
    { v: 'PICKED_UP',           l: t.dispatchDeskPage.filterStatusPickedUp },
    { v: 'IN_TRANSIT',          l: t.dispatchDeskPage.filterStatusInTransit },
    { v: 'DELIVERED',           l: t.dispatchDeskPage.filterStatusDelivered },
    { v: 'PARTIALLY_DELIVERED', l: t.dispatchDeskPage.filterStatusPartial },
    { v: 'CANCELLED',           l: t.dispatchDeskPage.filterStatusCancelled },
    { v: 'FAILED',              l: t.dispatchDeskPage.filterStatusFailed },
  ];

  const PERIODS = [
    { v: 'all',    l: t.dispatchDeskPage.periodAll },
    { v: 'day',    l: t.dispatchDeskPage.periodDay },
    { v: 'week',   l: t.dispatchDeskPage.periodWeek },
    { v: 'month',  l: t.dispatchDeskPage.periodMonth },
    { v: 'custom', l: t.dispatchDeskPage.periodCustom },
  ] as const;

  return (
    <div
      ref={panelRef}
      className="absolute left-0 top-[calc(100%+4px)] z-50 min-w-[220px] rounded-[var(--radius-sm)] border border-[var(--border)] bg-[var(--surface)] shadow-[0_4px_16px_rgba(0,0,0,0.10)]"
      style={{ fontFamily: "'Clear Sans', system-ui, sans-serif" }}
    >
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2.5 border-b border-[var(--border)]">
        {step !== 'attrs' && (
          <button
            type="button"
            onClick={() => setStep('attrs')}
            className="text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
          >
            <IconChevronLeft size={13} stroke={2.5} />
          </button>
        )}
        <span className="text-xs font-[600] text-[var(--text-muted)] uppercase tracking-wide">
          {step === 'attrs'
            ? t.dispatchDeskPage.filterByAttributeHeader
            : attrs.find(a => a.key === step)?.label}
        </span>
      </div>

      {/* Attribute list */}
      {step === 'attrs' && (
        <ul className="py-1">
          {attrs.map(({ key, label, active }) => (
            <li key={key}>
              <button
                type="button"
                onClick={() => setStep(key)}
                className="w-full flex items-center justify-between px-3 py-2 text-base text-[var(--text-primary)] hover:bg-[var(--hover-bg)] transition-colors"
              >
                <span>{label}</span>
                {active && (
                  <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />
                )}
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* Status values */}
      {step === 'status' && (
        <ul className="py-1">
          {STATUSES.map(({ v, l }) => (
            <li key={v}>
              <button
                type="button"
                onClick={() => { setStatusFilter(v); onClose(); }}
                className={cn(
                  'w-full flex items-center px-3 py-2 text-base transition-colors',
                  statusFilter === v
                    ? 'text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                    : 'text-[var(--text-primary)] hover:bg-[var(--hover-bg)]',
                )}
              >
                <span className={cn(
                  'w-3.5 h-3.5 rounded-full border mr-2.5 flex items-center justify-center shrink-0',
                  statusFilter === v ? 'border-[var(--brand-blue)]' : 'border-[var(--border-strong)]',
                )}>
                  {statusFilter === v && (
                    <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />
                  )}
                </span>
                {l}
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* Driver values */}
      {step === 'driver' && (
        <ul className="py-1 max-h-52 overflow-y-auto">
          <li>
            <button
              type="button"
              onClick={() => { setDriverId(''); onClose(); }}
              className={cn(
                'w-full flex items-center px-3 py-2 text-base transition-colors',
                !driverId
                  ? 'text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                  : 'text-[var(--text-primary)] hover:bg-[var(--hover-bg)]',
              )}
            >
              <span className={cn(
                'w-3.5 h-3.5 rounded-full border mr-2.5 flex items-center justify-center shrink-0',
                !driverId ? 'border-[var(--brand-blue)]' : 'border-[var(--border-strong)]',
              )}>
                {!driverId && <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />}
              </span>
              {t.dispatchDeskPage.filterDriverNoFilter}
            </button>
          </li>
          {drivers.map(d => (
            <li key={d.id}>
              <button
                type="button"
                onClick={() => { setDriverId(d.id); onClose(); }}
                className={cn(
                  'w-full flex items-center px-3 py-2 text-base transition-colors',
                  driverId === d.id
                    ? 'text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                    : 'text-[var(--text-primary)] hover:bg-[var(--hover-bg)]',
                )}
              >
                <span className={cn(
                  'w-3.5 h-3.5 rounded-full border mr-2.5 flex items-center justify-center shrink-0',
                  driverId === d.id ? 'border-[var(--brand-blue)]' : 'border-[var(--border-strong)]',
                )}>
                  {driverId === d.id && <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />}
                </span>
                {d.name}
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* Zone values */}
      {step === 'zone' && (
        <ul className="py-1 max-h-52 overflow-y-auto">
          <li>
            <button
              type="button"
              onClick={() => { setZoneFilter(''); onClose(); }}
              className={cn(
                'w-full flex items-center px-3 py-2 text-base transition-colors',
                !zoneFilter
                  ? 'text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                  : 'text-[var(--text-primary)] hover:bg-[var(--hover-bg)]',
              )}
            >
              <span className={cn(
                'w-3.5 h-3.5 rounded-full border mr-2.5 flex items-center justify-center shrink-0',
                !zoneFilter ? 'border-[var(--brand-blue)]' : 'border-[var(--border-strong)]',
              )}>
                {!zoneFilter && <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />}
              </span>
              {t.dispatchDeskPage.filterZoneGlobal}
            </button>
          </li>
          {zones.map(z => (
            <li key={z.name}>
              <button
                type="button"
                onClick={() => { setZoneFilter(z.name); onClose(); }}
                className={cn(
                  'w-full flex items-center px-3 py-2 text-base transition-colors',
                  zoneFilter === z.name
                    ? 'text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                    : 'text-[var(--text-primary)] hover:bg-[var(--hover-bg)]',
                )}
              >
                <span className={cn(
                  'w-3.5 h-3.5 rounded-full border mr-2.5 flex items-center justify-center shrink-0',
                  zoneFilter === z.name ? 'border-[var(--brand-blue)]' : 'border-[var(--border-strong)]',
                )}>
                  {zoneFilter === z.name && <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />}
                </span>
                {z.name}
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* Period values */}
      {step === 'period' && (
        <div className="py-1">
          <ul>
            {PERIODS.map(({ v, l }) => (
              <li key={v}>
                <button
                  type="button"
                  onClick={() => { setPeriod(v); if (v !== 'custom') onClose(); }}
                  className={cn(
                    'w-full flex items-center px-3 py-2 text-base transition-colors',
                    period === v
                      ? 'text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                      : 'text-[var(--text-primary)] hover:bg-[var(--hover-bg)]',
                  )}
                >
                  <span className={cn(
                    'w-3.5 h-3.5 rounded-full border mr-2.5 flex items-center justify-center shrink-0',
                    period === v ? 'border-[var(--brand-blue)]' : 'border-[var(--border-strong)]',
                  )}>
                    {period === v && <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand-blue)]" />}
                  </span>
                  {l}
                </button>
              </li>
            ))}
          </ul>
          {period === 'custom' && (
            <div className="px-3 pb-3 pt-1 flex flex-col gap-2 border-t border-[var(--border)] mt-1">
              <div className="flex flex-col gap-1">
                <label className="text-xs font-[500] text-[var(--text-muted)]">
                  {t.dispatchDeskPage.dateFrom}
                </label>
                <input
                  type="date"
                  value={customFrom}
                  onChange={e => setCustomFrom(e.currentTarget.value)}
                  className="h-7 px-2 rounded border border-[var(--border)] bg-[var(--app-bg)] text-sm text-[var(--text-primary)] focus:outline-none focus:border-[var(--brand-blue)]"
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-xs font-[500] text-[var(--text-muted)]">
                  {t.dispatchDeskPage.dateTo}
                </label>
                <input
                  type="date"
                  value={customTo}
                  onChange={e => setCustomTo(e.currentTarget.value)}
                  className="h-7 px-2 rounded border border-[var(--border)] bg-[var(--app-bg)] text-sm text-[var(--text-primary)] focus:outline-none focus:border-[var(--brand-blue)]"
                />
              </div>
              <button
                type="button"
                onClick={onClose}
                className="mt-1 h-7 rounded bg-[var(--brand-blue)] text-white text-sm font-[600] hover:opacity-90 transition-opacity"
              >
                {t.dispatchDeskPage.filterApply}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Applied filter chip ───────────────────────────────────────────────────────

function FilterToken({ label, onRemove }: { label: string; onRemove: () => void }) {
  return (
    <span className="inline-flex items-center gap-1 h-6 px-2 rounded border border-[var(--brand-blue)] bg-[var(--brand-blue-soft)] text-[var(--brand-blue)] text-xs font-[500]">
      {label}
      <button
        type="button"
        onClick={onRemove}
        className="ml-0.5 text-[var(--brand-blue)] opacity-70 hover:opacity-100 transition-opacity"
        aria-label={`Remove ${label}`}
      >
        <IconX size={10} stroke={2.5} />
      </button>
    </span>
  );
}

// ── Main bar ──────────────────────────────────────────────────────────────────

export function DispatchFilterBar() {
  const {
    t,
    search, setSearch,
    driverId, setDriverId, drivers,
    zoneFilter, setZoneFilter,
    statusFilter, setStatusFilter,
    period, setPeriod,
    refreshing, doRefresh,
    clearFilters,
  } = useDispatchDeskContext();

  const [dropdownOpen, setDropdownOpen] = useState(false);
  const btnRef = useRef<HTMLButtonElement>(null);

  const activeTokens: Array<{ label: string; onRemove: () => void }> = [];

  if (statusFilter) {
    const label = {
      UNSCHEDULED: t.dispatchDeskPage.filterStatusUnscheduled,
      SCHEDULED: t.dispatchDeskPage.filterStatusScheduled,
      PICKED_UP: t.dispatchDeskPage.filterStatusPickedUp,
      IN_TRANSIT: t.dispatchDeskPage.filterStatusInTransit,
      DELIVERED: t.dispatchDeskPage.filterStatusDelivered,
      PARTIALLY_DELIVERED: t.dispatchDeskPage.filterStatusPartial,
      CANCELLED: t.dispatchDeskPage.filterStatusCancelled,
      FAILED: t.dispatchDeskPage.filterStatusFailed,
    }[statusFilter] ?? statusFilter;
    activeTokens.push({ label: `${t.dispatchDeskPage.filterStatus}: ${label}`, onRemove: () => setStatusFilter('') });
  }

  if (driverId) {
    const driver = drivers.find(d => d.id === driverId);
    activeTokens.push({ label: `${t.dispatchDeskPage.filterDriver}: ${driver?.name ?? driverId}`, onRemove: () => setDriverId('') });
  }

  if (zoneFilter) {
    activeTokens.push({ label: `${t.dispatchDeskPage.filterZone}: ${zoneFilter}`, onRemove: () => setZoneFilter('') });
  }

  if (period !== 'all') {
    const periodLabel = {
      day: t.dispatchDeskPage.periodDay,
      week: t.dispatchDeskPage.periodWeek,
      month: t.dispatchDeskPage.periodMonth,
      custom: t.dispatchDeskPage.periodCustom,
    }[period] ?? period;
    activeTokens.push({ label: `${t.dispatchDeskPage.filterPeriod}: ${periodLabel}`, onRemove: () => setPeriod('all') });
  }

  const hasActive = activeTokens.length > 0 || !!search;

  return (
    <div
      className="shrink-0"
      style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)', fontFamily: "'Clear Sans', system-ui, sans-serif" }}
    >
      {/* Toolbar row */}
      <div className="flex items-center gap-2 px-4 h-11">
        {/* Search */}
        <div className="relative flex items-center flex-1 min-w-0 max-w-sm">
          <IconSearch size={13} className="absolute left-2.5 text-[var(--text-muted)] pointer-events-none" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.currentTarget.value)}
            placeholder={t.dispatchDeskPage.filterQuickSearchPlaceholder}
            className={cn(
              'w-full h-8 pl-8 pr-3 border border-[var(--border)] bg-[var(--app-bg)]',
              'text-base text-[var(--text-primary)] placeholder:text-[var(--text-muted)]',
              'focus:outline-none focus:border-[var(--brand-blue)] focus:ring-1 focus:ring-[var(--brand-blue)] focus:ring-opacity-30',
              'rounded-[var(--radius-xs)] transition-colors',
            )}
          />
        </div>

        {/* Filter by attribute button */}
        <div className="relative">
          <button
            ref={btnRef}
            type="button"
            onClick={() => setDropdownOpen(o => !o)}
            className={cn(
              'h-8 px-3 flex items-center gap-1.5 border rounded-[var(--radius-xs)] text-base font-[500] transition-colors shrink-0 whitespace-nowrap',
              dropdownOpen
                ? 'border-[var(--brand-blue)] text-[var(--brand-blue)] bg-[var(--brand-blue-soft)]'
                : 'border-[var(--border)] text-[var(--text-primary)] bg-[var(--surface)] hover:border-[var(--border-strong)]',
            )}
          >
            {t.dispatchDeskPage.filterButton}
            {activeTokens.length > 0 && (
              <span className="inline-flex items-center justify-center w-4 h-4 rounded-full bg-[var(--brand-blue)] text-white text-2xs font-[700] leading-none">
                {activeTokens.length}
              </span>
            )}
            <IconChevronDown
              size={12}
              stroke={2.5}
              className={cn('transition-transform', dropdownOpen && 'rotate-180')}
            />
          </button>

          <FilterDropdown
            anchorRef={btnRef}
            open={dropdownOpen}
            onClose={() => setDropdownOpen(false)}
          />
        </div>

        <div className="ml-auto flex items-center gap-1.5 shrink-0">
          {hasActive && (
            <button
              type="button"
              onClick={clearFilters}
              className="h-8 px-2.5 flex items-center gap-1 border border-[var(--border)] rounded-[var(--radius-xs)] text-sm font-[500] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] transition-colors"
            >
              <IconX size={12} stroke={2.5} />
              {t.dispatchDeskPage.filterClear}
            </button>
          )}
          <RefreshButton refreshing={refreshing} onClick={doRefresh} />
        </div>
      </div>

      {/* Applied tokens row */}
      {activeTokens.length > 0 && (
        <div className="flex items-center gap-1.5 px-4 pb-2.5 flex-wrap">
          {activeTokens.map((tok) => (
            <FilterToken key={tok.label} label={tok.label} onRemove={tok.onRemove} />
          ))}
        </div>
      )}
    </div>
  );
}
