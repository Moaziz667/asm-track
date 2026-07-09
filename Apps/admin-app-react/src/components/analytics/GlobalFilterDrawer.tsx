import { useMemo, useCallback, useState } from 'react';
import { IconX, IconChevronRight, IconSearch, IconFilter, IconCalendar, IconGitCompare, IconTrash } from '@tabler/icons-react';
import { Sheet, SheetContent, SheetTitle, SheetClose } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';
import { Badge } from '@/components/ui/badge';
import { SegmentedControl, type SegmentOption } from '@/components/ui/SegmentedControl';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { useZones } from '@/hooks/useZones';
import { useDepots } from '@/hooks/useDepots';
import { useDrivers } from '@/hooks/useDrivers';
import { useFailureReasons } from '@/hooks/useFailureReasons';
import { useT } from '@/lib/i18n/LocaleContext';
import { cn } from '@/lib/utils';
import type { AnalyticsScope, DeliveryStatus, OrderSource } from '@/types';

// ── Constants ─────────────────────────────────────────────────────────────────

const DELIVERY_STATUSES: DeliveryStatus[] = [
  'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT',
  'AWAITING_HANDOFF', 'DELIVERED', 'PARTIALLY_DELIVERED', 'CANCELLED', 'FAILED',
];

const ORDER_SOURCES: { value: OrderSource; icon: string; label: string }[] = [
  { value: 'ODOO', icon: '🏭', label: 'ERP Odoo' },
  { value: 'DUX', icon: '📦', label: 'DUX' },
];

type FilterCategory = 'period' | 'zone' | 'driver' | 'status' | 'motif' | 'source' | 'depot';

// ── Types ─────────────────────────────────────────────────────────────────────

interface GlobalFilterDrawerProps<R extends string> {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  value: AnalyticsScope;
  onChange: (scope: AnalyticsScope) => void;
  resultCount?: number;

  range: R;
  onRangeChange: (r: R) => void;
  rangeOptions: SegmentOption<R>[];
  defaultRange: R;
  customFrom?: string;
  customTo?: string;
  onCustomFromChange?: (v: string) => void;
  onCustomToChange?: (v: string) => void;

  compare?: boolean;
  onCompareChange?: (v: boolean) => void;
  compareLabel?: string;
}

// ── Main Component ────────────────────────────────────────────────────────────

export function GlobalFilterDrawer<R extends string>({
  open, onOpenChange, value, onChange, resultCount,
  range, onRangeChange, rangeOptions, defaultRange,
  customFrom, customTo, onCustomFromChange, onCustomToChange,
  compare, onCompareChange, compareLabel = 'vs période préc.',
}: GlobalFilterDrawerProps<R>) {
  const t = useT();
  const { data: zones = [] } = useZones();
  const { data: depots = [] } = useDepots();
  const { data: drivers = [] } = useDrivers();
  const { data: failureReasons = [] } = useFailureReasons();

  const activeZones = useMemo(() => zones.filter(z => z.isActive), [zones]);
  const activeDepots = useMemo(() => depots.filter(d => d.isActive), [depots]);
  const activeReasons = useMemo(() => failureReasons.filter(r => r.active), [failureReasons]);

  const [activeCategory, setActiveCategory] = useState<FilterCategory | null>(null);

  const counts = useMemo(() => ({
    period: range !== defaultRange ? 1 : 0,
    zone: value.zone ? 1 : 0,
    driver: value.driverId ? 1 : 0,
    status: value.status ? 1 : 0,
    motif: value.motif ? 1 : 0,
    source: value.source ? 1 : 0,
    depot: value.depot ? 1 : 0,
  }), [value, range, defaultRange]);

  const totalActive = Object.values(counts).reduce((s, n) => s + n, 0);

  const clearAll = useCallback(() => {
    onChange({});
    onRangeChange(defaultRange);
    onCustomFromChange?.('');
    onCustomToChange?.('');
  }, [onChange, onRangeChange, defaultRange, onCustomFromChange, onCustomToChange]);

  const removeChip = useCallback((key: FilterCategory) => {
    if (key === 'period') { onRangeChange(defaultRange); return; }
    if (key === 'zone') { onChange({ ...value, zone: undefined }); return; }
    if (key === 'driver') { onChange({ ...value, driverId: undefined }); return; }
    if (key === 'status') { onChange({ ...value, status: undefined }); return; }
    if (key === 'motif') { onChange({ ...value, motif: undefined }); return; }
    if (key === 'source') { onChange({ ...value, source: undefined }); return; }
    if (key === 'depot') { onChange({ ...value, depot: undefined }); return; }
  }, [value, defaultRange, onRangeChange, onChange]);

  const chips = useMemo(() => {
    const items: { key: FilterCategory; label: string }[] = [];
    if (counts.period) {
      const opt = rangeOptions.find(o => o.value === range);
      items.push({ key: 'period', label: opt?.label ?? String(range) });
    }
    if (counts.zone) items.push({ key: 'zone', label: value.zone! });
    if (counts.driver) {
      const d = drivers.find(d => d.id === value.driverId);
      items.push({ key: 'driver', label: d?.name ?? value.driverId!.slice(0, 8) });
    }
    if (counts.status) items.push({ key: 'status', label: t.statusLabels?.[value.status!] ?? value.status! });
    if (counts.motif) items.push({ key: 'motif', label: t.failureCodes?.[value.motif!] ?? value.motif! });
    if (counts.source) items.push({ key: 'source', label: t.sources?.[value.source!] ?? value.source! });
    if (counts.depot) {
      const d = depots.find(d => d.id === value.depot);
      items.push({ key: 'depot', label: d?.name ?? value.depot!.slice(0, 8) });
    }
    return items;
  }, [counts, value, drivers, depots, t, range, rangeOptions]);

  // ── Categories for left panel ──
  const categories: { key: FilterCategory; label: string; count: number }[] = [
    { key: 'period', label: t.scopeFilters?.period ?? 'Période', count: counts.period },
    { key: 'zone', label: t.scopeFilters?.zone ?? 'Zone', count: counts.zone },
    { key: 'driver', label: t.scopeFilters?.driver ?? 'Chauffeur', count: counts.driver },
    { key: 'status', label: t.scopeFilters?.status ?? 'Statut', count: counts.status },
    { key: 'motif', label: t.scopeFilters?.motif ?? 'Motif', count: counts.motif },
    { key: 'source', label: t.scopeFilters?.source ?? 'Source', count: counts.source },
    { key: 'depot', label: t.scopeFilters?.depot ?? 'Dépôt', count: counts.depot },
  ];

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" showCloseButton={false} className="!h-[100dvh] !max-h-[100dvh] w-[380px] max-w-[85vw] p-0 flex flex-col gap-0">
        {/* ── Active Chips Bar ── */}
        {chips.length > 0 && (
          <div className="px-3 py-2 border-b border-[var(--border)] shrink-0 bg-[var(--surface)]">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-2xs font-semibold uppercase tracking-wider text-[var(--text-muted)] shrink-0">
                {totalActive} actif{totalActive > 1 ? 's' : ''}
              </span>
              <div className="flex items-center gap-1 flex-wrap flex-1 min-w-0">
                {chips.map(chip => (
                  <Badge
                    key={chip.key}
                    variant="outline"
                    className="gap-1 cursor-pointer hover:bg-[var(--hover-bg)] transition-colors h-5 text-2xs"
                    onClick={() => removeChip(chip.key)}
                  >
                    <span className="truncate">{chip.label}</span>
                    <IconX size={9} className="shrink-0 opacity-60" />
                  </Badge>
                ))}
              </div>
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger
                    render={
                      <button
                        type="button"
                        onClick={clearAll}
                        className="w-5 h-5 flex items-center justify-center rounded text-[var(--danger)] hover:bg-[var(--danger)]/10 transition-colors cursor-pointer shrink-0"
                      />
                    }
                  >
                    <IconTrash size={13} />
                  </TooltipTrigger>
                  <TooltipContent className="z-[100]">{(t.scopeFilters?.clearAll as string) ?? 'Effacer tout'}</TooltipContent>
                </Tooltip>
              </TooltipProvider>
            </div>
          </div>
        )}

        {/* ── Two-Panel Layout ── */}
        <div className="flex flex-1 min-h-0">
          {/* ── Left Panel: Categories ── */}
          <div className={cn(
            "flex flex-col border-r border-[var(--border)] shrink-0 transition-all duration-200 h-full",
            activeCategory ? "w-[145px]" : "w-full"
          )}>
            {/* Header */}
            <div className="flex items-center justify-between px-3 py-2.5 border-b border-[var(--border)] shrink-0">
              <div className="flex items-center gap-1.5">
                <IconFilter size={13} className="text-[var(--brand)]" />
                <SheetTitle className="text-xs font-semibold">{t.scopeFilters?.title ?? 'Filtres'}</SheetTitle>
              </div>
              <SheetClose render={<Button variant="ghost" size="icon-sm" />} />
            </div>

            {/* Category List */}
            <div className="flex-1 overflow-y-auto">
              {categories.map(cat => (
                <button
                  key={cat.key}
                  type="button"
                  onClick={() => setActiveCategory(activeCategory === cat.key ? null : cat.key)}
                  className={cn(
                    'w-full flex items-center justify-between px-3 py-2.5 text-xs transition-colors cursor-pointer border-b border-[var(--border)]',
                    activeCategory === cat.key
                      ? 'bg-[var(--brand-soft)] text-[var(--brand)]'
                      : 'text-[var(--text-secondary)] hover:bg-[var(--hover-bg)]',
                  )}
                >
                  <div className="flex items-center gap-1.5 min-w-0">
                    <span className="truncate">{cat.label}</span>
                    {cat.count > 0 && (
                      <span className="min-w-[14px] h-3.5 px-1 rounded-full bg-[var(--brand)] text-white text-2xs font-bold flex items-center justify-center shrink-0">
                        {cat.count}
                      </span>
                    )}
                  </div>
                  <IconChevronRight size={12} className="text-[var(--text-muted)] shrink-0" />
                </button>
              ))}
            </div>
          </div>

          {/* ── Right Panel: Options ── */}
          {activeCategory && (
            <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
              {/* Right Header */}
              <div className="flex items-center gap-2 px-3 py-2.5 border-b border-[var(--border)] shrink-0">
                <button
                  type="button"
                  onClick={() => setActiveCategory(null)}
                  className="w-5 h-5 flex items-center justify-center rounded text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer"
                >
                  <IconChevronRight size={12} className="rotate-180" />
                </button>
                <span className="text-xs font-semibold text-[var(--text-primary)] truncate">
                  {categories.find(c => c.key === activeCategory)?.label}
                </span>
              </div>

              {/* Options Content */}
              <div className="flex-1 p-3 flex flex-col overflow-y-auto min-h-0">
                {activeCategory === 'period' && (
                  <PeriodOptions
                    range={range}
                    onRangeChange={onRangeChange}
                    rangeOptions={rangeOptions}
                    customFrom={customFrom}
                    customTo={customTo}
                    onCustomFromChange={onCustomFromChange}
                    onCustomToChange={onCustomToChange}
                    compare={compare}
                    onCompareChange={onCompareChange}
                    compareLabel={compareLabel}
                  />
                )}
                {activeCategory === 'zone' && (
                  <ZoneOptions zones={activeZones} selected={value.zone} onSelect={name => onChange({ ...value, zone: value.zone === name ? undefined : name })} />
                )}
                {activeCategory === 'driver' && (
                  <DriverOptions drivers={drivers} selectedId={value.driverId} onSelect={id => onChange({ ...value, driverId: value.driverId === id ? undefined : id })} />
                )}
                {activeCategory === 'status' && (
                  <StatusOptions selected={value.status} onSelect={s => onChange({ ...value, status: value.status === s ? undefined : s })} />
                )}
                {activeCategory === 'motif' && (
                  <MotifOptions reasons={activeReasons} selected={value.motif} onSelect={c => onChange({ ...value, motif: value.motif === c ? undefined : c })} labels={t.failureCodes} />
                )}
                {activeCategory === 'source' && (
                  <SourceOptions selected={value.source} onSelect={s => onChange({ ...value, source: value.source === s ? undefined : s })} />
                )}
                {activeCategory === 'depot' && (
                  <DepotOptions depots={activeDepots} selectedId={value.depot} onSelect={id => onChange({ ...value, depot: value.depot === id ? undefined : id })} />
                )}
              </div>
            </div>
          )}
        </div>

        {/* ── Sticky Footer ── */}
        <div className="border-t border-[var(--border)] px-3 py-2.5 shrink-0 bg-[var(--surface)]">
          <Button size="sm" onClick={() => onOpenChange(false)} className="w-full">
            {resultCount != null
              ? `Voir ${resultCount.toLocaleString()} résultats`
              : t.scopeFilters?.done ?? 'Terminé'}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
}

// ── Period Options ────────────────────────────────────────────────────────────

function PeriodOptions<R extends string>({ range, onRangeChange, rangeOptions, customFrom, customTo, onCustomFromChange, onCustomToChange, compare, onCompareChange, compareLabel }: {
  range: R; onRangeChange: (r: R) => void; rangeOptions: SegmentOption<R>[];
  customFrom?: string; customTo?: string; onCustomFromChange?: (v: string) => void; onCustomToChange?: (v: string) => void;
  compare?: boolean; onCompareChange?: (v: boolean) => void; compareLabel?: string;
}) {
  return (
    <div className="flex flex-col gap-3">
      <SegmentedControl value={range} onChange={onRangeChange} options={rangeOptions} className="flex-wrap" />
      {range === ('custom' as R) && onCustomFromChange && onCustomToChange && (
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center gap-1.5">
            <IconCalendar size={11} className="text-[var(--text-muted)]" />
            <span className="text-2xs text-[var(--text-muted)]">Du</span>
            <DatePickerPopover value={customFrom || null} onChange={v => onCustomFromChange(v ?? '')} className="flex-1" />
          </div>
          <div className="flex items-center gap-1.5">
            <IconCalendar size={11} className="text-[var(--text-muted)]" />
            <span className="text-2xs text-[var(--text-muted)]">Au</span>
            <DatePickerPopover value={customTo || null} onChange={v => onCustomToChange(v ?? '')} className="flex-1" />
          </div>
        </div>
      )}
      {onCompareChange && (
        <button
          type="button"
          onClick={() => onCompareChange(!compare)}
          aria-pressed={compare}
          className={cn(
            'inline-flex items-center gap-1 px-2 py-1 rounded border text-2xs font-semibold transition-colors cursor-pointer',
            compare
              ? 'text-[var(--brand)] border-[var(--brand)]/40 bg-[var(--brand-soft)]'
              : 'text-[var(--text-secondary)] border-[var(--border)] hover:bg-[var(--hover-bg)]',
          )}
        >
          <IconGitCompare size={10} /> {compareLabel}
        </button>
      )}
    </div>
  );
}

// ── Zone Options ──────────────────────────────────────────────────────────────

function ZoneOptions({ zones, selected, onSelect }: { zones: Array<{ name: string; color?: string }>; selected?: string; onSelect: (name: string) => void }) {
  return (
    <div className="flex flex-col">
      <CheckboxRow checked={false} label="(Tous)" onClick={() => {}} />
      {zones.map(zone => (
        <CheckboxRow
          key={zone.name}
          checked={selected === zone.name}
          label={zone.name}
          color={zone.color}
          onClick={() => onSelect(zone.name)}
        />
      ))}
    </div>
  );
}

// ── Driver Options ────────────────────────────────────────────────────────────

function DriverOptions({ drivers, selectedId, onSelect }: { drivers: Array<{ id: string; name: string; onlineStatus?: string }>; selectedId?: string; onSelect: (id: string) => void }) {
  const [search, setSearch] = useState('');
  const filtered = useMemo(() => {
    if (!search.trim()) return drivers;
    const q = search.toLowerCase();
    return drivers.filter(d => d.name.toLowerCase().includes(q));
  }, [drivers, search]);

  return (
    <div className="flex flex-col gap-2">
      <div className="relative">
        <IconSearch size={11} className="absolute start-2 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
        <input
          type="text"
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Filtrer..."
          className="w-full h-7 ps-6 pe-2 rounded border border-[var(--border)] bg-[var(--surface)] text-2xs text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:border-[var(--brand)]"
        />
      </div>
      <div className="flex flex-col flex-1">
        <CheckboxRow checked={false} label="(Tous)" onClick={() => {}} />
        {filtered.map(driver => (
          <div
            key={driver.id}
            className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => onSelect(driver.id)}
          >
            <div className={cn(
              'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
              selectedId === driver.id
                ? 'bg-[var(--brand)] border-[var(--brand)]'
                : 'border-[var(--border)] bg-[var(--surface)]',
            )}>
              {selectedId === driver.id && (
                <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 4L3 6L7 2" stroke="white" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
              )}
            </div>
            <DriverAvatarById driverId={driver.id} name={driver.name} size={16} />
            <span className="text-2xs text-[var(--text-secondary)] truncate">{driver.name}</span>
            {driver.onlineStatus === 'ONLINE' && (
              <span className="ms-auto w-1.5 h-1.5 rounded-full bg-[var(--success)] shrink-0" />
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Status Options ────────────────────────────────────────────────────────────

function StatusOptions({ selected, onSelect }: { selected?: DeliveryStatus; onSelect: (s: DeliveryStatus) => void }) {
  return (
    <div className="flex flex-col">
      <CheckboxRow checked={false} label="(Tous)" onClick={() => {}} />
      {DELIVERY_STATUSES.map(status => (
        <div
          key={status}
          className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
          onClick={() => onSelect(status)}
        >
          <div className={cn(
            'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
            selected === status
              ? 'bg-[var(--brand)] border-[var(--brand)]'
              : 'border-[var(--border)] bg-[var(--surface)]',
          )}>
            {selected === status && (
              <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 4L3 6L7 2" stroke="white" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
            )}
          </div>
          <StatusBadge status={status} size="sm" />
        </div>
      ))}
    </div>
  );
}

// ── Motif Options ─────────────────────────────────────────────────────────────

function MotifOptions({ reasons, selected, onSelect, labels }: { reasons: Array<{ code: string; label: string }>; selected?: string; onSelect: (code: string) => void; labels?: Record<string, string> }) {
  const [search, setSearch] = useState('');
  const filtered = useMemo(() => {
    if (!search.trim()) return reasons;
    const q = search.toLowerCase();
    return reasons.filter(r => (labels?.[r.code] ?? r.label).toLowerCase().includes(q));
  }, [reasons, search, labels]);

  return (
    <div className="flex flex-col gap-2">
      <div className="relative">
        <IconSearch size={11} className="absolute start-2 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
        <input
          type="text"
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Filtrer..."
          className="w-full h-7 ps-6 pe-2 rounded border border-[var(--border)] bg-[var(--surface)] text-2xs text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:border-[var(--brand)]"
        />
      </div>
      <div className="flex flex-col flex-1">
        <CheckboxRow checked={false} label="(Tous)" onClick={() => {}} />
        {filtered.map(reason => (
          <CheckboxRow
            key={reason.code}
            checked={selected === reason.code}
            label={labels?.[reason.code] ?? reason.label}
            onClick={() => onSelect(reason.code)}
          />
        ))}
      </div>
    </div>
  );
}

// ── Source Options ────────────────────────────────────────────────────────────

function SourceOptions({ selected, onSelect }: { selected?: OrderSource; onSelect: (s: OrderSource) => void }) {
  return (
    <div className="flex flex-col">
      <CheckboxRow checked={false} label="(Tous)" onClick={() => {}} />
      {ORDER_SOURCES.map(source => (
        <div
          key={source.value}
          className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
          onClick={() => onSelect(source.value)}
        >
          <div className={cn(
            'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
            selected === source.value
              ? 'bg-[var(--brand)] border-[var(--brand)]'
              : 'border-[var(--border)] bg-[var(--surface)]',
          )}>
            {selected === source.value && (
              <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 4L3 6L7 2" stroke="white" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
            )}
          </div>
          <span className="text-xs">{source.icon}</span>
          <span className="text-2xs text-[var(--text-secondary)]">{source.label}</span>
        </div>
      ))}
    </div>
  );
}

// ── Depot Options ─────────────────────────────────────────────────────────────

function DepotOptions({ depots, selectedId, onSelect }: { depots: Array<{ id: string; name: string; warehouseCode?: string }>; selectedId?: string; onSelect: (id: string) => void }) {
  return (
    <div className="flex flex-col">
      <CheckboxRow checked={false} label="(Tous)" onClick={() => {}} />
      {depots.map(depot => (
        <div
          key={depot.id}
          className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
          onClick={() => onSelect(depot.id)}
        >
          <div className={cn(
            'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
            selectedId === depot.id
              ? 'bg-[var(--brand)] border-[var(--brand)]'
              : 'border-[var(--border)] bg-[var(--surface)]',
          )}>
            {selectedId === depot.id && (
              <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 4L3 6L7 2" stroke="white" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
            )}
          </div>
          <span className="text-2xs text-[var(--text-secondary)] truncate">{depot.name}</span>
          {depot.warehouseCode && (
            <span className="ms-auto text-2xs text-[var(--text-muted)] shrink-0">{depot.warehouseCode}</span>
          )}
        </div>
      ))}
    </div>
  );
}

// ── Shared Checkbox Row ───────────────────────────────────────────────────────

function CheckboxRow({ checked, label, color, onClick }: { checked: boolean; label: string; color?: string; onClick: () => void }) {
  return (
    <div
      className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
      onClick={onClick}
    >
      <div className={cn(
        'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
        checked
          ? 'bg-[var(--brand)] border-[var(--brand)]'
          : 'border-[var(--border)] bg-[var(--surface)]',
      )}>
        {checked && (
          <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 4L3 6L7 2" stroke="white" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
        )}
      </div>
      {color && <span className="w-2 h-2 rounded-full shrink-0" style={{ background: color }} />}
      <span className="text-2xs text-[var(--text-secondary)] leading-tight">{label}</span>
    </div>
  );
}
