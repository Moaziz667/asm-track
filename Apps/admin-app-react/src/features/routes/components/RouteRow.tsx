
import React, { useState } from 'react';
import { IconEye as Eye, IconUserCheck as UserCheck, IconCircleX as XCircle, IconCircleCheck as CheckCircle, IconTrash as Trash2, IconFileText as FileText, IconChevronDown as ChevronDown, IconChevronRight as ChevronRight } from '@tabler/icons-react';
import { colors, spacing, btn } from '@/lib/design-tokens';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import type { RouteItem } from '../hooks/useRoutes';

interface RouteRowProps {
  route: RouteItem;
  isSelected?: boolean;
  isHighlighted?: boolean;
  isFocused?: boolean;
  index: number;
  readOnly?: boolean;
  onSelect?: () => void;
  onRowClick?: (route: RouteItem) => void;
  onView?: (route: RouteItem) => void;
  onValidate?: (route: RouteItem) => void;
  onCancel?: (route: RouteItem) => void;
  onReassign?: (route: RouteItem) => void;
  onClose?: (route: RouteItem) => void;
  onDelete?: (route: RouteItem) => void;
  validatingId?: string | null;
  cancellingId?: string | null;
  closingId?: string | null;
  deletingId?: string | null;
}

function formatTime(t?: string | null): string {
  if (!t) return '—';
  return String(t).slice(0, 5);
}

function formatDate(d?: string): string {
  if (!d) return '—';
  try {
    return new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
  } catch {
    return d.slice(0, 10);
  }
}

function progressColor(n: number): string {
  if (n >= 90) return colors.success;
  if (n >= 50) return colors.warning;
  return colors.danger;
}

/**
 * Single route row with:
 * - Inline progress bar
 * - Icon actions (visible on hover/focus)
 * - Status-appropriate action set (no irrelevant buttons shown)
 * - Keyboard shortcut hints in action area
 */
export function RouteRow({
  route,
  isSelected = false,
  isHighlighted = false,
  isFocused = false,
  index,
  readOnly = false,
  onSelect,
  onRowClick,
  onView,
  onValidate,
  onCancel,
  onReassign,
  onClose,
  onDelete,
  validatingId,
  cancellingId,
  closingId,
  deletingId,
}: RouteRowProps) {
  const [hovered, setHovered] = useState(false);
  const showActions = hovered || isFocused;

  const completedStops = route.stops.filter(
    (s) => s.status === 'COMPLETED' || s.status === 'FAILED' || s.status === 'PARTIAL',
  ).length;
  const totalStops = route.stops.length;
  const progressPct = totalStops > 0 ? Math.round((completedStops / totalStops) * 100) : 0;
  const activeStopsCount = route.stops.filter(
    (s) => s.status === 'PICKED_UP' || s.status === 'IN_TRANSIT',
  ).length;

  const bg = isSelected
    ? colors.surfaceSelected
    : isHighlighted
    ? '#F0FDF4'
    : index % 2 === 0
    ? colors.surface
    : colors.bg;

  return (
    <div
      onClick={() => onRowClick?.(route)}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{
        display: 'grid',
        gridTemplateColumns: '32px 1fr 100px 90px 80px 70px auto',
        alignItems: 'center',
        height: 44,
        background: hovered && !isSelected ? colors.surfaceHover : bg,
        borderBottom: `1px solid ${colors.border}`,
        borderLeft: isFocused
          ? `3px solid ${colors.primary}`
          : route.status === 'IN_PROGRESS'
          ? `3px solid ${colors.warning}`
          : '3px solid transparent',
        cursor: 'pointer',
        transition: 'background 0.1s',
        paddingLeft: 4,
        paddingRight: 8,
      }}
    >
      {/* Checkbox */}
      <div
        onClick={(e) => { e.stopPropagation(); onSelect?.(); }}
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}
      >
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onSelect?.()}
          style={{ cursor: 'pointer', accentColor: colors.primary }}
        />
      </div>

      {/* Main info */}
      <div style={{ padding: '0 8px', overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
          <span style={{
            fontSize: 12,
            fontWeight: 700,
            color: colors.textPrimary,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}>
            {route.name}
          </span>
          {route.detectedZoneLabel && (
            <span style={{
              fontSize: 10,
              fontWeight: 600,
              color: colors.textMuted,
              background: colors.bg,
              border: `1px solid ${colors.border}`,
              borderRadius: spacing.radius.sm,
              padding: '1px 5px',
              whiteSpace: 'nowrap',
              flexShrink: 0,
            }}>
              {route.detectedZoneLabel}
            </span>
          )}
          {route.routeVersion != null && route.routeVersion > 1 && (
            <span style={{
              fontSize: 9,
              fontWeight: 700,
              color: colors.textMuted,
              background: colors.bg,
              border: `1px solid ${colors.border}`,
              borderRadius: spacing.radius.sm,
              padding: '1px 4px',
              whiteSpace: 'nowrap',
              flexShrink: 0,
              fontVariantNumeric: 'tabular-nums',
            }}>
              v{route.routeVersion}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 10, fontWeight: 500, color: colors.textMuted, whiteSpace: 'nowrap' }}>
            {route.driverName ?? route.driverId.slice(0, 8)}
          </span>
          <span style={{ color: colors.border }}>·</span>
          <span style={{ fontSize: 10, fontWeight: 500, color: colors.textMuted }}>
            {formatDate(route.date)}
          </span>
          <span style={{ color: colors.border }}>·</span>
          <span style={{ fontSize: 10, fontWeight: 500, color: colors.textMuted }}>
            {formatTime(route.plannedStartTime)}–{formatTime(route.plannedEndTime)}
          </span>
        </div>
      </div>

      {/* Status */}
      <div style={{ padding: '0 8px' }}>
        <StatusBadge status={route.status} />
      </div>

      {/* Stop count */}
      <div style={{ padding: '0 8px', textAlign: 'right' }}>
        <span style={{
          fontSize: 12,
          fontWeight: 700,
          color: colors.textPrimary,
          fontVariantNumeric: 'tabular-nums',
        }}>
          {completedStops}/{totalStops}
        </span>
        <span style={{ fontSize: 10, fontWeight: 500, color: colors.textMuted }}> arrêts</span>
      </div>

      {/* Progress bar */}
      <div style={{ padding: '0 8px' }}>
        {totalStops > 0 && (
          <div>
            <div style={{
              height: 4,
              borderRadius: 2,
              background: colors.border,
              overflow: 'hidden',
              marginBottom: 2,
            }}>
              <div style={{
                width: `${progressPct}%`,
                height: '100%',
                background: progressColor(progressPct),
                transition: 'width 0.3s ease',
                borderRadius: 2,
              }} />
            </div>
            <div style={{ fontSize: 9, fontWeight: 700, color: colors.textMuted, textAlign: 'right' }}>
              {progressPct}%
            </div>
          </div>
        )}
      </div>

      {/* Delay indicator */}
      <div style={{ padding: '0 8px', textAlign: 'right' }}>
        {(route.cumulativeDelayMinutes ?? 0) > 0 ? (
          <span style={{
            fontSize: 10,
            fontWeight: 700,
            color: colors.danger,
            fontVariantNumeric: 'tabular-nums',
          }}>
            +{route.cumulativeDelayMinutes}min
          </span>
        ) : null}
      </div>

      {/* Actions — visible on hover/focus */}
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 2,
          opacity: showActions ? 1 : 0,
          transition: 'opacity 0.15s',
          flexShrink: 0,
        }}
      >
        {/* View detail */}
        <ActionIcon
          icon={<Eye size={14} />}
          title="Voir détails"
          onClick={() => onView?.(route)}
        />

        {/* Status-specific actions */}
        {!readOnly && (
          <>
            {route.status === 'DRAFT' && (
              <>
                <ActionIcon
                  icon={<CheckCircle size={14} />}
                  title="Valider (V)"
                  color={colors.success}
                  loading={validatingId === route.id}
                  onClick={() => onValidate?.(route)}
                />
                <ActionIcon
                  icon={<Trash2 size={14} />}
                  title="Supprimer"
                  color={colors.danger}
                  loading={deletingId === route.id}
                  onClick={() => onDelete?.(route)}
                />
              </>
            )}

            {(route.status === 'VALIDATED' || route.status === 'IN_PROGRESS') && (
              <>
                <ActionIcon
                  icon={<UserCheck size={14} />}
                  title="Réaffecter (A)"
                  color={colors.primary}
                  loading={false}
                  onClick={() => onReassign?.(route)}
                />
                <ActionIcon
                  icon={<XCircle size={14} />}
                  title={activeStopsCount > 0
                    ? `Annuler (${activeStopsCount} arrêt${activeStopsCount > 1 ? 's' : ''} déjà ramassé${activeStopsCount > 1 ? 's' : ''} — restera avec le chauffeur)`
                    : 'Annuler (C)'}
                  color={colors.danger}
                  loading={cancellingId === route.id}
                  onClick={() => onCancel?.(route)}
                />
              </>
            )}

            {route.status === 'IN_PROGRESS' && (
              <ActionIcon
                icon={<CheckCircle size={14} />}
                title="Clôturer"
                color={colors.success}
                loading={closingId === route.id}
                onClick={() => onClose?.(route)}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}

function ActionIcon({
  icon,
  title,
  onClick,
  color = '',
  loading = false,
}: {
  icon: React.ReactNode;
  title: string;
  onClick: () => void;
  color?: string;
  loading?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={loading}
      title={title}
      style={{
        width: 28,
        height: 28,
        borderRadius: spacing.radius.sm,
        border: 'none',
        background: 'transparent',
        cursor: loading ? 'not-allowed' : 'pointer',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: color || colors.textSecondary,
        opacity: loading ? 0.5 : 1,
        transition: 'background 0.1s, color 0.1s',
      }}
      onMouseEnter={(e) => {
        (e.currentTarget as HTMLButtonElement).style.background = colors.bg;
        if (color) (e.currentTarget as HTMLButtonElement).style.color = color;
      }}
      onMouseLeave={(e) => {
        (e.currentTarget as HTMLButtonElement).style.background = 'transparent';
        (e.currentTarget as HTMLButtonElement).style.color = color || colors.textSecondary;
      }}
    >
      {loading ? (
        <div style={{
          width: 12,
          height: 12,
          border: `2px solid ${color || colors.textSecondary}`,
          borderTopColor: 'transparent',
          borderRadius: '50%',
          animation: 'asm-spin 0.6s linear infinite',
        }} />
      ) : icon}
    </button>
  );
}

