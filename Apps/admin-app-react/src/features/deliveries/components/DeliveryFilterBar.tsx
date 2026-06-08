
import React from 'react';
import { IconSearch as Search, IconRefresh as RefreshCw, IconMapPin as MapPin, IconRoute as Route, IconX as X } from '@tabler/icons-react';
import { colors, spacing, btn } from '@/lib/design-tokens';
import { Spinner } from '@/components/feedback/LoadingBar';
import type { DeliveriesFilters } from '@/hooks/useDeliveries';
import type { Driver, Zone } from '@/types';
import { useT } from '@/lib/LocaleContext';

function getDeliveryStatuses(t: any) {
  return [
  { value: '',                    label: t.dispatchDeskPage.filterStatusAll },
  { value: 'UNSCHEDULED',         label: t.statusLabels.UNSCHEDULED },
  { value: 'SCHEDULED',           label: t.statusLabels.SCHEDULED },
  { value: 'PICKED_UP',           label: t.statusLabels.PICKED_UP },
  { value: 'IN_TRANSIT',          label: t.statusLabels.IN_TRANSIT },
  { value: 'DELIVERED',           label: t.statusLabels.DELIVERED },
  { value: 'PARTIALLY_DELIVERED', label: t.statusLabels.PARTIALLY_DELIVERED },
  { value: 'FAILED',              label: t.statusLabels.FAILED },
  { value: 'CANCELLED',           label: t.statusLabels.CANCELLED },
  ];
}

export interface QuickCounts {
  all: number;
  needsPinning: number;
  unassigned: number;
  inTransit: number;
  completed: number;
  failed: number;
}

interface DeliveryFilterBarProps {
  filters: DeliveriesFilters;
  onChange: (patch: Partial<DeliveriesFilters>) => void;
  onClear: () => void;
  onRefresh: () => void;
  refreshing?: boolean;
  totalElements?: number;
  drivers: Driver[];
  zones: Zone[];
  quickCounts?: QuickCounts;
}

const inputStyle: React.CSSProperties = {
  height: spacing.buttonHeight,
  borderRadius: spacing.radius.md,
  border: `1px solid ${colors.border}`,
  background: colors.surface,
  fontSize: 12,
  fontWeight: 500,
  color: colors.textPrimary,
  padding: '0 10px',
  outline: 'none',
};

const selectStyle: React.CSSProperties = {
  ...inputStyle,
  cursor: 'pointer',
};

type QuickViewId = DeliveriesFilters['quickView'];

function getQuickTabs(t: any): Array<{
  id: QuickViewId;
  label: string;
  icon?: React.ReactNode;
  dangerActive?: boolean;
}> {
  return [
  { id: 'all',          label: t.deliveriesPage.quickViewAll },
  { id: 'needsPinning', label: t.deliveriesPage.quickViewNeedsPinning,  icon: <MapPin size={11} />, dangerActive: true },
  { id: 'unassigned',   label: t.deliveriesPage.quickViewUnassigned, icon: <Route size={11} /> },
  { id: 'inTransit',    label: t.deliveriesPage.quickViewInTransit,   icon: <RefreshCw size={11} /> },
  { id: 'completed',    label: t.deliveriesPage.quickViewCompleted },
  { id: 'failed',       label: t.deliveriesPage.quickViewFailed },
  ];
}

function getCount(id: QuickViewId, counts?: QuickCounts): number | undefined {
  if (!counts) return undefined;
  const map: Record<string, number> = {
    all: counts.all,
    needsPinning: counts.needsPinning,
    unassigned: counts.unassigned,
    inTransit: counts.inTransit,
    completed: counts.completed,
    failed: counts.failed,
  };
  return map[id ?? 'all'];
}

/**
 * Filter bar for /deliveries page.
 * Quick-view pills + status/driver/zone/date/search filters.
 */
export function DeliveryFilterBar({
  filters,
  onChange,
  onClear,
  onRefresh,
  refreshing = false,
  totalElements,
  drivers,
  zones,
  quickCounts,
}: DeliveryFilterBarProps) {
  const t = useT();
  const activeQuick = filters.quickView ?? 'all';
  const statusOptions = getDeliveryStatuses(t);
  const quickTabs = getQuickTabs(t);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
      {/* Quick view pills */}
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
        {quickTabs.map(({ id, label, icon, dangerActive }) => {
          const isActive = activeQuick === id;
          const count = getCount(id, quickCounts);
          const activeDanger = dangerActive && isActive;
          return (
            <button
              key={id}
              onClick={() => onChange({ quickView: id })}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 5,
                height: 28,
                padding: '0 10px',
                borderRadius: 14,
                border: `1px solid ${activeDanger ? '#EF4444' : isActive ? colors.textPrimary : colors.border}`,
                background: activeDanger ? '#EF4444' : isActive ? colors.textPrimary : colors.surface,
                color: isActive ? '#fff' : colors.textSecondary,
                fontSize: 11,
                fontWeight: isActive ? 700 : 500,
                cursor: 'pointer',
                transition: 'all 0.1s',
                whiteSpace: 'nowrap',
              }}
            >
              {icon}
              {label}
              {count !== undefined && (
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  minWidth: 18,
                  height: 16,
                  padding: '0 4px',
                  borderRadius: 4,
                  background: isActive ? 'rgba(255,255,255,0.2)' : colors.bg,
                  color: isActive ? '#fff' : colors.textPrimary,
                  fontSize: 10,
                  fontWeight: 800,
                  fontVariantNumeric: 'tabular-nums',
                }}>
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* Filter inputs row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {/* Search */}
        <div style={{ position: 'relative' }}>
          <Search
            size={12}
            color={colors.textMuted}
            style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', pointerEvents: 'none' }}
          />
          <input
            value={filters.query ?? ''}
            onChange={(e) => onChange({ query: e.target.value || undefined })}
            placeholder={t.placeholders.searchDeliveries}
            style={{ ...inputStyle, paddingLeft: 28, width: 180 }}
          />
        </div>

        {/* Status */}
        <select
          value={filters.status ?? ''}
          onChange={(e) => onChange({ status: (e.target.value as any) || undefined })}
          style={{ ...selectStyle, width: 140 }}
        >
          {statusOptions.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>

        {/* Driver */}
        <select
          value={filters.driverId ?? ''}
          onChange={(e) => onChange({ driverId: e.target.value || undefined })}
          style={{ ...selectStyle, width: 150 }}
        >
          <option value="">{t.dispatchDeskPage.filterDriverPlaceholder}</option>
          {drivers.map((d) => (
            <option key={d.id} value={d.id}>{d.name}</option>
          ))}
        </select>

        {/* Zone */}
        <select
          value={filters.zoneId ?? ''}
          onChange={(e) => onChange({ zoneId: e.target.value || undefined })}
          style={{ ...selectStyle, width: 140 }}
        >
          <option value="">{t.deliveriesPage.filterByZone}</option>
          {zones.map((z) => (
            <option key={z.id} value={z.id}>{(z as any).name ?? z.id}</option>
          ))}
        </select>

        {/* Date */}
        <input
          type="date"
          value={filters.date ?? ''}
          onChange={(e) => onChange({ date: e.target.value || undefined })}
          style={{ ...inputStyle, width: 136 }}
        />

        {/* Spacer */}
        <div style={{ flex: 1 }} />

        {/* Count */}
        {totalElements !== undefined && (
          <span style={{
            fontSize: 11,
            fontWeight: 700,
            color: colors.textMuted,
            fontVariantNumeric: 'tabular-nums',
          }}>
            {totalElements} {t.deliveriesPage.totalFlow}
          </span>
        )}

        {/* Clear */}
        <button
          onClick={onClear}
          style={{
            ...btn.secondary,
            width: spacing.buttonHeight,
            padding: 0,
            justifyContent: 'center',
          }}
          title={t.actions.clearFilters}
        >
          <X size={13} color={colors.textSecondary} />
        </button>

        {/* Refresh */}
        <button
          onClick={onRefresh}
          disabled={refreshing}
          style={{
            ...btn.secondary,
            width: spacing.buttonHeight,
            padding: 0,
            justifyContent: 'center',
          }}
          title={t.actions.refresh}
        >
          {refreshing
            ? <Spinner size={13} color={colors.textSecondary} />
            : <RefreshCw size={13} color={colors.textSecondary} />
          }
        </button>
      </div>
    </div>
  );
}

