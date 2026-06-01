
import React, { useState } from 'react';
import { IconMapPin as MapPin, IconRoute as Route, IconRefresh as RotateCcw, IconCircleX as XCircle } from '@tabler/icons-react';
import { colors, spacing, typography } from '@/lib/design-tokens';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import type { Delivery } from '@/types';

type DeliveryRowItem = Delivery & { rowId: string };

interface DeliveryRowProps {
  item: DeliveryRowItem;
  index: number;
  isFocused?: boolean;
  onRowClick?: (item: DeliveryRowItem) => void;
  onPin?: (item: DeliveryRowItem) => void;
  onOpenRoute?: (item: DeliveryRowItem) => void;
  onBackorder?: (item: DeliveryRowItem) => void;
  onCancel?: (item: DeliveryRowItem) => void;
  creatingBackorderFor?: string | null;
  cancellingOrderId?: string | null;
}

function cleanTunisianAdminName(name: string | null | undefined): string {
  if (!name) return '';
  if (name.startsWith('Gouvernorat ')) return name.substring('Gouvernorat '.length);
  if (name.startsWith('Délégation ') || name.startsWith('Delegation ')) return name.substring(name.indexOf(' ') + 1);
  return name;
}

function getRef(item: DeliveryRowItem): string {
  return (item.erpId || item.orderId || `L-${item.rowId.slice(0, 6)}`).toUpperCase();
}

const cell: React.CSSProperties = {
  padding: '0 12px',
  verticalAlign: 'middle',
  fontSize: 12,
  color: colors.textPrimary,
  overflow: 'hidden',
  whiteSpace: 'nowrap',
};

interface ActionIconProps {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
  disabled?: boolean;
  variant?: 'default' | 'danger' | 'primary';
}

function ActionIcon({ icon, label, onClick, disabled, variant = 'default' }: ActionIconProps) {
  const [hover, setHover] = useState(false);
  const hoverBg = variant === 'danger'
    ? colors.dangerBg
    : variant === 'primary'
    ? colors.primaryBg
    : colors.bg;
  return (
    <button
      title={label}
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      disabled={disabled}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 26,
        height: 26,
        borderRadius: spacing.radius.sm,
        border: `1px solid ${hover ? colors.borderEmphasis : 'transparent'}`,
        background: hover ? hoverBg : 'transparent',
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.4 : 1,
        transition: 'all 0.1s',
        flexShrink: 0,
      }}
    >
      {icon}
    </button>
  );
}

/**
 * Single delivery row — 44px height, shows key info + context-appropriate actions.
 * Actions visible on hover/focus only (via CSS opacity trick).
 */
export function DeliveryRow({
  item,
  index,
  isFocused = false,
  onRowClick,
  onPin,
  onOpenRoute,
  onBackorder,
  onCancel,
  creatingBackorderFor,
  cancellingOrderId,
}: DeliveryRowProps) {
  const [hover, setHover] = useState(false);
  const isActive = hover || isFocused;

  const canCancel = (item.status === 'UNSCHEDULED' || item.status === 'SCHEDULED') && !!item.orderId;
  const canBackorder = item.status === 'PARTIALLY_DELIVERED';
  const isPinned = !!item.dropoffPinned;

  return (
    <tr
      onClick={() => onRowClick?.(item)}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        height: spacing.rowHeight,
        background: isFocused
          ? colors.surfaceSelected
          : isActive
          ? colors.surfaceHover
          : colors.surface,
        cursor: onRowClick ? 'pointer' : 'default',
        transition: 'background 0.1s',
        outline: isFocused ? `2px solid ${colors.primary}` : 'none',
        outlineOffset: -2,
      }}
      tabIndex={-1}
      data-row-index={index}
    >
      {/* Reference */}
      <td style={{ ...cell, width: 120, fontFamily: typography.mono.fontFamily, fontWeight: 700, fontSize: 11 }}>
        <div style={{ color: colors.textPrimary }}>{getRef(item)}</div>
        <div style={{ fontSize: 9, color: colors.textMuted, marginTop: 1 }}>
          {item.rowId.slice(0, 8)}
        </div>
      </td>

      {/* Client & Destination */}
      <td style={{ ...cell, maxWidth: 220 }}>
        <div style={{
          fontWeight: 700,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}>
          {item.clientName || 'Client inconnu'}
        </div>
        <div style={{
          fontSize: 11,
          color: colors.textSecondary,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          marginTop: 1,
        }}>
          {item.dropoffAddress || '—'}
          {item.dropoffCity && (
            <span style={{ marginLeft: 4, color: colors.success, fontWeight: 700, fontSize: 10 }}>
              {cleanTunisianAdminName(item.dropoffCity)}
            </span>
          )}
        </div>
      </td>

      {/* Status */}
      <td style={{ ...cell, width: 110 }}>
        <StatusBadge status={item.status} />
      </td>

      {/* Assignment */}
      <td style={{ ...cell, width: 140 }}>
        {item.driverName ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ width: 6, height: 6, borderRadius: '50%', background: '#9CA3AF', flexShrink: 0 }} />
            <span style={{
              fontSize: 11,
              fontWeight: 600,
              color: colors.textSecondary,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}>
              {item.driverName}
            </span>
          </div>
        ) : (
          <span style={{ fontSize: 10, fontWeight: 500, color: colors.textMuted }}>
            Non assigné
          </span>
        )}
      </td>

      {/* Zone / Pin status */}
      <td style={{ ...cell, width: 110 }}>
        {!isPinned ? (
          <button
            onClick={(e) => { e.stopPropagation(); onPin?.(item); }}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              height: 22,
              padding: '0 8px',
              borderRadius: spacing.radius.sm,
              border: `1px solid ${colors.dangerBorder}`,
              background: colors.dangerBg,
              color: colors.danger,
              fontSize: 10,
              fontWeight: 700,
              cursor: 'pointer',
              textTransform: 'uppercase',
              letterSpacing: '0.04em',
            }}
          >
            <MapPin size={10} />
            Épingler
          </button>
        ) : (
          <span style={{
            display: 'inline-block',
            height: 20,
            padding: '0 7px',
            borderRadius: spacing.radius.sm,
            border: `1px solid ${(item as any).zoneColor ? `${(item as any).zoneColor}44` : colors.border}`,
            background: (item as any).zoneColor ? `${(item as any).zoneColor}11` : colors.bg,
            color: (item as any).zoneColor || colors.textSecondary,
            fontSize: 10,
            fontWeight: 700,
            textTransform: 'uppercase',
            letterSpacing: '0.04em',
            lineHeight: '20px',
          }}>
            {(item as any).zoneName || 'HORS ZONE'}
          </span>
        )}
      </td>

      {/* Route */}
      <td style={{ ...cell, width: 120 }}>
        {item.routeName ? (
          <button
            onClick={(e) => { e.stopPropagation(); onOpenRoute?.(item); }}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 5,
              height: 22,
              padding: '0 8px',
              borderRadius: spacing.radius.sm,
              border: `1px solid ${colors.border}`,
              background: colors.surface,
              color: colors.textSecondary,
              fontSize: 10,
              fontWeight: 600,
              cursor: 'pointer',
              maxWidth: 108,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            <Route size={10} color={colors.success} style={{ flexShrink: 0 }} />
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{item.routeName}</span>
          </button>
        ) : (
          <span style={{ fontSize: 10, color: colors.textMuted }}>—</span>
        )}
      </td>

      {/* Actions */}
      <td style={{ ...cell, width: 80 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 2,
            opacity: isActive ? 1 : 0,
            transition: 'opacity 0.15s',
          }}
        >
          {!isPinned && (
            <ActionIcon
              icon={<MapPin size={13} color={colors.danger} />}
              label="Épingler la position (P)"
              onClick={() => onPin?.(item)}
              variant="danger"
            />
          )}
          {canBackorder && (
            <ActionIcon
              icon={<RotateCcw size={13} color={colors.warning} />}
              label="Créer un backorder"
              onClick={() => onBackorder?.(item)}
              disabled={creatingBackorderFor === item.rowId}
            />
          )}
          {canCancel && (
            <ActionIcon
              icon={<XCircle size={13} color={colors.danger} />}
              label="Annuler la commande (C)"
              onClick={() => onCancel?.(item)}
              disabled={cancellingOrderId === item.orderId}
              variant="danger"
            />
          )}
        </div>
      </td>
    </tr>
  );
}

