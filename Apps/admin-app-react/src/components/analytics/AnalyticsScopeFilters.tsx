import { useMemo, useCallback } from 'react';
import { IconX } from '@tabler/icons-react';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { useZones } from '@/hooks/useZones';
import { useDepots } from '@/hooks/useDepots';
import { useDrivers } from '@/hooks/useDrivers';
import { useFailureReasons } from '@/hooks/useFailureReasons';
import { useT } from '@/lib/i18n/LocaleContext';
import { cn } from '@/lib/utils';
import type { AnalyticsScope, DeliveryStatus, OrderSource } from '@/types';

interface AnalyticsScopeFiltersProps extends Omit<React.HTMLAttributes<HTMLDivElement>, 'onChange'> {
  value: AnalyticsScope;
  onChange: (scope: AnalyticsScope) => void;
  visible?: Array<keyof AnalyticsScope>;
}

const DELIVERY_STATUSES: DeliveryStatus[] = [
  'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT',
  'AWAITING_HANDOFF', 'DELIVERED', 'PARTIALLY_DELIVERED', 'CANCELLED', 'FAILED',
];

const ORDER_SOURCES: OrderSource[] = ['ODOO', 'DUX'];

export function AnalyticsScopeFilters({ value, onChange, visible, className, ...divProps }: AnalyticsScopeFiltersProps) {
  const t = useT();
  const { data: zones = [] } = useZones();
  const { data: depots = [] } = useDepots();
  const { data: drivers = [] } = useDrivers();
  const { data: failureReasons = [] } = useFailureReasons();

  const activeZones = useMemo(() => zones.filter(z => z.isActive), [zones]);
  const activeDepots = useMemo(() => depots.filter(d => d.isActive), [depots]);
  const activeReasons = useMemo(() => failureReasons.filter(r => r.active), [failureReasons]);

  const cities = useMemo(() => {
    const all = new Set<string>();
    for (const z of activeZones) {
      for (const c of z.cities ?? []) all.add(c);
    }
    return [...all].sort();
  }, [activeZones]);

  const set = useCallback(<K extends keyof AnalyticsScope>(key: K, val: AnalyticsScope[K]) => {
    onChange({ ...value, [key]: val ?? undefined });
  }, [value, onChange]);

  const clear = useCallback(() => onChange({}), [onChange]);

  const visibleFields = visible ?? (['zone', 'driverId', 'status', 'motif', 'city', 'source', 'depot'] as const);

  const hasFilters = Object.values(value).some(v => v != null && v !== '');

  const chips = useMemo(() => {
    const items: { key: keyof AnalyticsScope; label: string }[] = [];
    if (value.zone) items.push({ key: 'zone', label: `${t.scopeFilters?.zone ?? 'Zone'}: ${value.zone}` });
    if (value.driverId) {
      const d = drivers.find(d => d.id === value.driverId);
      items.push({ key: 'driverId', label: `${t.scopeFilters?.driver ?? 'Chauffeur'}: ${d?.name ?? value.driverId.slice(0, 8)}` });
    }
    if (value.status) items.push({ key: 'status', label: `${t.scopeFilters?.status ?? 'Statut'}: ${t.statusLabels?.[value.status] ?? value.status}` });
    if (value.motif) items.push({ key: 'motif', label: `${t.scopeFilters?.motif ?? 'Motif'}: ${t.failureCodes?.[value.motif] ?? value.motif}` });
    if (value.city) items.push({ key: 'city', label: `${t.scopeFilters?.city ?? 'Ville'}: ${value.city}` });
    if (value.source) items.push({ key: 'source', label: `${t.scopeFilters?.source ?? 'Source'}: ${t.sources?.[value.source] ?? value.source}` });
    if (value.depot) {
      const d = depots.find(d => d.id === value.depot);
      items.push({ key: 'depot', label: `${t.scopeFilters?.depot ?? 'Dépôt'}: ${d?.name ?? value.depot.slice(0, 8)}` });
    }
    return items;
  }, [value, drivers, depots, t]);

  return (
    <div className={cn('flex items-center gap-2 flex-wrap', className)} {...divProps}>
      {visibleFields.includes('zone') && (
        <ScopeSelect
          value={value.zone ?? ''}
          onValueChange={v => set('zone', v || undefined)}
          placeholder={t.scopeFilters?.zone ?? 'Zone'}
          items={activeZones.map(z => ({ value: z.name, label: z.name }))}
        />
      )}
      {visibleFields.includes('driverId') && (
        <ScopeSelect
          value={value.driverId ?? ''}
          onValueChange={v => set('driverId', v || undefined)}
          placeholder={t.scopeFilters?.driver ?? 'Chauffeur'}
          items={drivers.map(d => ({ value: d.id, label: d.name }))}
        />
      )}
      {visibleFields.includes('status') && (
        <ScopeSelect
          value={value.status ?? ''}
          onValueChange={v => set('status', (v || undefined) as DeliveryStatus | undefined)}
          placeholder={t.scopeFilters?.status ?? 'Statut'}
          items={DELIVERY_STATUSES.map(s => ({ value: s, label: t.statusLabels?.[s] ?? s }))}
        />
      )}
      {visibleFields.includes('motif') && (
        <ScopeSelect
          value={value.motif ?? ''}
          onValueChange={v => set('motif', v || undefined)}
          placeholder={t.scopeFilters?.motif ?? 'Motif'}
          items={activeReasons.map(r => ({ value: r.code, label: r.label }))}
        />
      )}
      {visibleFields.includes('city') && (
        <ScopeSelect
          value={value.city ?? ''}
          onValueChange={v => set('city', v || undefined)}
          placeholder={t.scopeFilters?.city ?? 'Ville'}
          items={cities.map(c => ({ value: c, label: c }))}
        />
      )}
      {visibleFields.includes('source') && (
        <ScopeSelect
          value={value.source ?? ''}
          onValueChange={v => set('source', (v || undefined) as OrderSource | undefined)}
          placeholder={t.scopeFilters?.source ?? 'Source'}
          items={ORDER_SOURCES.map(s => ({ value: s, label: t.sources?.[s] ?? s }))}
        />
      )}
      {visibleFields.includes('depot') && (
        <ScopeSelect
          value={value.depot ?? ''}
          onValueChange={v => set('depot', v || undefined)}
          placeholder={t.scopeFilters?.depot ?? 'Dépôt'}
          items={activeDepots.map(d => ({ value: d.id, label: d.name }))}
        />
      )}

      {chips.length > 0 && (
        <>
          <div className="w-px h-4 bg-[var(--border)]" />
          {chips.map(chip => (
            <Badge
              key={chip.key}
              variant="outline"
              className="gap-1 cursor-pointer hover:bg-[var(--hover-bg)] transition-colors h-6"
              onClick={() => set(chip.key, undefined)}
            >
              {chip.label}
              <IconX size={10} className="shrink-0 opacity-60" />
            </Badge>
          ))}
          <button
            type="button"
            onClick={clear}
            className="text-2xs font-semibold text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors cursor-pointer"
          >
            {t.scopeFilters?.clearAll ?? 'Effacer tout'}
          </button>
        </>
      )}
    </div>
  );
}

// ── Internal: compact scope select ────────────────────────────────────────────

interface ScopeSelectProps {
  value: string;
  onValueChange: (value: string) => void;
  placeholder: string;
  items: Array<{ value: string; label: string }>;
}

function ScopeSelect({ value, onValueChange, placeholder, items }: ScopeSelectProps) {
  return (
    <Select value={value} onValueChange={v => onValueChange(v ?? '')}>
      <SelectTrigger
        size="sm"
        className={cn(
          'h-7 text-xs rounded-md border border-[var(--border)] bg-[var(--surface)]',
          'text-[var(--text-secondary)] hover:border-[var(--text-muted)] transition-colors',
          value && 'border-[var(--brand)]/40 bg-[var(--brand-soft)] text-[var(--brand)]',
        )}
      >
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        {items.map(item => (
          <SelectItem key={item.value} value={item.value}>
            {item.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
