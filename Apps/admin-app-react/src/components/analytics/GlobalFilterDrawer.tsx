import { useMemo, useCallback, useState, useEffect } from 'react';
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
import { useT } from '@/lib/i18n/LocaleContext';
import { cn } from '@/lib/utils';
import type { AnalyticsScope, DeliveryStatus } from '@/types';

// ── Constants ─────────────────────────────────────────────────────────────────

const DELIVERY_STATUSES: DeliveryStatus[] = [
  'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT',
  'AWAITING_HANDOFF', 'DELIVERED', 'PARTIALLY_DELIVERED', 'CANCELLED', 'FAILED',
];

// Deliveries store the coarse FailureCode category (not the granular reason-catalog code), so the
// motif pivot filters on these — the only failure dimension persisted queryably on a delivery.
const FAILURE_CATEGORIES = ['CLIENT_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'DAMAGED', 'MISSING', 'OTHER'] as const;

/*
 * No 'source' pivot.
 *
 * A tenant connects exactly one ERP, so every delivery it holds carries the same source: the filter
 * could only ever return everything or nothing. It also listed "DUX", a provider this platform has
 * never supported — a filter that offers a choice which cannot exist teaches the operator to
 * distrust the ones that can.
 */
type FilterCategory = 'period' | 'zone' | 'driver' | 'status' | 'motif' | 'depot';

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

  const activeZones = useMemo(() => zones.filter(z => z.isActive), [zones]);
  const activeDepots = useMemo(() => depots.filter(d => d.isActive), [depots]);

  const [activeCategory, setActiveCategory] = useState<FilterCategory | null>(null);

  // Reset category when drawer closes so it starts fresh next time.
  useEffect(() => {
    if (!open) setActiveCategory(null);
  }, [open]);

  const counts = useMemo(() => ({
    period: range !== defaultRange ? 1 : 0,
    zone: value.zone?.length ?? 0,
    driver: value.driverId?.length ?? 0,
    status: value.status?.length ?? 0,
    motif: value.motif?.length ?? 0,
    depot: value.depot?.length ?? 0,
  }), [value, range, defaultRange]);

  const totalActive = Object.values(counts).reduce((s, n) => s + n, 0);

  // Field key per category + toggle helper (add/remove within a multi-value pivot).
  const FIELD_BY_CAT: Record<Exclude<FilterCategory, 'period'>, keyof AnalyticsScope> = {
    zone: 'zone', driver: 'driverId', status: 'status', motif: 'motif', depot: 'depot',
  };
  const toggleValue = useCallback(<K extends keyof AnalyticsScope>(field: K, v: string) => {
    const cur = (value[field] as string[] | undefined) ?? [];
    const next = cur.includes(v) ? cur.filter(x => x !== v) : [...cur, v];
    onChange({ ...value, [field]: next.length ? next : undefined });
  }, [value, onChange]);

  const clearAll = useCallback(() => {
    onChange({});
    onRangeChange(defaultRange);
    onCustomFromChange?.('');
    onCustomToChange?.('');
  }, [onChange, onRangeChange, defaultRange, onCustomFromChange, onCustomToChange]);

  const removeChip = useCallback((key: FilterCategory, val?: string) => {
    if (key === 'period') { onRangeChange(defaultRange); return; }
    const field = FIELD_BY_CAT[key as Exclude<FilterCategory, 'period'>];
    const cur = (value[field] as string[] | undefined) ?? [];
    const next = cur.filter(x => x !== val);
    onChange({ ...value, [field]: next.length ? next : undefined });
  }, [value, defaultRange, onRangeChange, onChange]);

  const chips = useMemo(() => {
    const items: { key: FilterCategory; val?: string; label: string }[] = [];
    if (counts.period) {
      const opt = rangeOptions.find(o => o.value === range);
      items.push({ key: 'period', label: opt?.label ?? String(range) });
    }
    (value.zone ?? []).forEach(z => items.push({ key: 'zone', val: z, label: z }));
    (value.driverId ?? []).forEach(id => {
      const d = drivers.find(d => d.id === id);
      items.push({ key: 'driver', val: id, label: d?.name ?? id.slice(0, 8) });
    });
    (value.status ?? []).forEach(s => items.push({ key: 'status', val: s, label: t.statusLabels?.[s] ?? s }));
    (value.motif ?? []).forEach(m => items.push({ key: 'motif', val: m, label: t.failureCodes?.[m] ?? m }));
    (value.depot ?? []).forEach(id => {
      const d = depots.find(d => d.id === id);
      items.push({ key: 'depot', val: id, label: d?.name ?? id.slice(0, 8) });
    });
    return items;
  }, [counts, value, drivers, depots, t, range, rangeOptions]);

  // ── Categories for left panel ──
  const categories: { key: FilterCategory; label: string; count: number }[] = [
    { key: 'period', label: t.scopeFilters?.period ?? 'Période', count: counts.period },
    { key: 'zone', label: t.scopeFilters?.zone ?? 'Zone', count: counts.zone },
    { key: 'driver', label: t.scopeFilters?.driver ?? 'Chauffeur', count: counts.driver },
    { key: 'status', label: t.scopeFilters?.status ?? 'Statut', count: counts.status },
    { key: 'motif', label: t.scopeFilters?.motif ?? 'Motif', count: counts.motif },
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
                    key={`${chip.key}-${chip.val ?? ''}`}
                    variant="outline"
                    className="gap-1 cursor-pointer hover:bg-[var(--hover-bg)] transition-colors h-5 text-2xs"
                    onClick={() => removeChip(chip.key, chip.val)}
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
                  <ZoneOptions zones={activeZones} selected={value.zone} onSelect={name => toggleValue('zone', name)} onClear={() => onChange({ ...value, zone: undefined })} />
                )}
                {activeCategory === 'driver' && (
                  <DriverOptions drivers={drivers} selectedIds={value.driverId} onSelect={id => toggleValue('driverId', id)} onClear={() => onChange({ ...value, driverId: undefined })} />
                )}
                {activeCategory === 'status' && (
                  <StatusOptions selected={value.status} onSelect={s => toggleValue('status', s)} onClear={() => onChange({ ...value, status: undefined })} />
                )}
                {activeCategory === 'motif' && (
                  <MotifOptions selected={value.motif} onSelect={c => toggleValue('motif', c)} labels={t.failureCodes} onClear={() => onChange({ ...value, motif: undefined })} />
                )}
                {activeCategory === 'depot' && (
                  <DepotOptions depots={activeDepots} selectedIds={value.depot} onSelect={id => toggleValue('depot', id)} onClear={() => onChange({ ...value, depot: undefined })} />
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

function ZoneOptions({ zones, selected, onSelect, onClear }: { zones: Array<{ name: string; color?: string }>; selected?: string[]; onSelect: (name: string) => void; onClear: () => void }) {
  const [search, setSearch] = useState('');
  const filtered = useMemo(() => {
    if (!search.trim()) return zones;
    const q = search.toLowerCase();
    return zones.filter(z => z.name.toLowerCase().includes(q));
  }, [zones, search]);

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
        <CheckboxRow checked={!selected?.length} label="(Tous)" onClick={onClear} />
        {filtered.map(zone => (
          <CheckboxRow
            key={zone.name}
            checked={!!selected?.includes(zone.name)}
            label={zone.name}
            color={zone.color}
            onClick={() => onSelect(zone.name)}
          />
        ))}
      </div>
    </div>
  );
}

// ── Driver Options ────────────────────────────────────────────────────────────

function DriverOptions({ drivers, selectedIds, onSelect, onClear }: { drivers: Array<{ id: string; name: string; onlineStatus?: string }>; selectedIds?: string[]; onSelect: (id: string) => void; onClear: () => void }) {
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
        <CheckboxRow checked={!selectedIds?.length} label="(Tous)" onClick={onClear} />
        {filtered.map(driver => (
          <div
            key={driver.id}
            className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => onSelect(driver.id)}
          >
            <div className={cn(
              'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
              selectedIds?.includes(driver.id)
                ? 'bg-[var(--brand)] border-[var(--brand)]'
                : 'border-[var(--border)] bg-[var(--surface)]',
            )}>
              {selectedIds?.includes(driver.id) && (
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

function StatusOptions({ selected, onSelect, onClear }: { selected?: DeliveryStatus[]; onSelect: (s: DeliveryStatus) => void; onClear: () => void }) {
  return (
    <div className="flex flex-col">
      <CheckboxRow checked={!selected?.length} label="(Tous)" onClick={onClear} />
      {DELIVERY_STATUSES.map(status => (
        <div
          key={status}
          className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
          onClick={() => onSelect(status)}
        >
          <div className={cn(
            'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
            selected?.includes(status)
              ? 'bg-[var(--brand)] border-[var(--brand)]'
              : 'border-[var(--border)] bg-[var(--surface)]',
          )}>
            {selected?.includes(status) && (
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

function MotifOptions({ selected, onSelect, labels, onClear }: { selected?: string[]; onSelect: (code: string) => void; labels?: Record<string, string>; onClear: () => void }) {
  return (
    <div className="flex flex-col">
      <CheckboxRow checked={!selected?.length} label="(Tous)" onClick={onClear} />
      {FAILURE_CATEGORIES.map(code => (
        <CheckboxRow
          key={code}
          checked={!!selected?.includes(code)}
          label={labels?.[code] ?? code}
          onClick={() => onSelect(code)}
        />
      ))}
    </div>
  );
}


// ── Depot Options ─────────────────────────────────────────────────────────────

function DepotOptions({ depots, selectedIds, onSelect, onClear }: { depots: Array<{ id: string; name: string; warehouseCode?: string }>; selectedIds?: string[]; onSelect: (id: string) => void; onClear: () => void }) {
  const [search, setSearch] = useState('');
  const filtered = useMemo(() => {
    if (!search.trim()) return depots;
    const q = search.toLowerCase();
    return depots.filter(d => d.name.toLowerCase().includes(q) || (d.warehouseCode && d.warehouseCode.toLowerCase().includes(q)));
  }, [depots, search]);

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
        <CheckboxRow checked={!selectedIds?.length} label="(Tous)" onClick={onClear} />
        {filtered.map(depot => (
          <div
            key={depot.id}
            className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => onSelect(depot.id)}
          >
            <div className={cn(
              'w-3.5 h-3.5 rounded-sm border flex items-center justify-center shrink-0 transition-colors',
              selectedIds?.includes(depot.id)
                ? 'bg-[var(--brand)] border-[var(--brand)]'
                : 'border-[var(--border)] bg-[var(--surface)]',
            )}>
              {selectedIds?.includes(depot.id) && (
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
    </div>
  );
}

// ── Shared Checkbox Row ───────────────────────────────────────────────────────

function CheckboxRow({ checked, label, color, onClick }: { checked: boolean; label: string; color?: string; onClick: () => void }) {
  return (
    <div
      role="checkbox"
      aria-checked={checked}
      tabIndex={0}
      className="flex items-center gap-2 px-1 py-1 rounded cursor-pointer hover:bg-[var(--hover-bg)] transition-colors"
      onClick={onClick}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
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
