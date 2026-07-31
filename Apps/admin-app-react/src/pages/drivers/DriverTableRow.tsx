import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { DriverAvatar } from '@/components/data-display/DriverAvatar';
import { cn } from '@/lib/utils';
import type { Driver } from '@/types';
import type { useT } from '@/lib/i18n/LocaleContext';
import type { ColumnDef } from '@/hooks/useColumnSettings';
import { DRIVER_STATUS_COLORS } from './constants';
import { DriverStatusBadge } from './DriverStatusBadge';
import { SVGPhone, SVGUser, SVGDots, SVGPencil, SVGSend, SVGX, SVGBan, SVGLogout, SVGActivity } from './icons';

interface Props {
  drv: Driver;
  gridCols: string;
  rowHeight: number;
  orderedColumns: ColumnDef[];
  visibleIds: Set<string>;
  t: ReturnType<typeof useT>;
  readOnly: boolean;
  resendCooldown: number;
  isDriverEnLivraison: (d: Driver) => boolean;
  routeNameById?: Record<string, string>;
  onOpenRoute?: (routeId: string) => void;
  onOpenDetails: (id: string) => void;
  onEdit: (drv: Driver) => void;
  onResendInvite: (drv: Driver) => void;
  onCancelInvite: (drv: Driver) => void;
  onSuspend: (drv: Driver) => void;
  onForceLogout: (id: string) => void;
  onActivate: (drv: Driver) => void;
}

/**
 * Activity cell: badge + the driver's active *route name* (clickable → route detail), falling back to
 * a short id only when the name isn't resolved yet. Never shows a raw UUID slice as the primary label.
 */
function DriverActivity({
  drv, t, routeNameById, onOpenRoute,
}: Pick<Props, 'drv' | 't' | 'routeNameById' | 'onOpenRoute'>) {
  const routeId = drv.activeRouteId;
  const onDuty = Boolean(drv.activeDeliveryId || routeId);
  if (!onDuty) return <span className="text-xs text-[var(--text-soft)]">{t.driversPage.free}</span>;

  const label = (routeId && routeNameById?.[routeId])
    || (routeId ? routeId.slice(0, 8).toUpperCase() : drv.activeDeliveryId!.slice(0, 8).toUpperCase());

  return (
    <div className="flex items-center gap-1.5 flex-nowrap min-w-0">
      <StatusBadge
        status={drv.activeDeliveryId ? 'IN_TRANSIT' : 'IN_PROGRESS'}
        size="sm"
        label={drv.activeDeliveryId ? t.driversPage.activeDelivery : t.driversPage.onRoute}
      />
      {routeId && onOpenRoute ? (
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); onOpenRoute(routeId); }}
          title={label}
          className="text-xs font-medium text-[var(--brand)] hover:underline truncate max-w-[160px] cursor-pointer"
        >
          {label}
        </button>
      ) : (
        <span title={label} className="text-xs font-medium text-[var(--text-muted)] truncate max-w-[160px]">{label}</span>
      )}
    </div>
  );
}

export function DriverTableRow({
  drv, gridCols, rowHeight, orderedColumns, visibleIds, t, readOnly, resendCooldown,
  routeNameById, onOpenRoute, onOpenDetails, onEdit, onResendInvite, onCancelInvite, onSuspend, onForceLogout, onActivate,
}: Props) {
  const statusConfig = DRIVER_STATUS_COLORS[drv.accountStatus ?? 'PENDING_SETUP'] ?? {
    dot: '#8A8F98', bg: 'rgba(138,143,152,0.08)', text: '#6B7280', ribbon: '#8A8F98',
  };

  return (
    <div className="border-b border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
      <div
        className="grid gap-4 items-center cursor-pointer group hover:bg-[var(--hover-bg)] transition-colors"
        style={{ gridTemplateColumns: gridCols, height: rowHeight }}
        onClick={() => onOpenDetails(drv.id)}
      >
        <div className="w-[3px] h-4 rounded-r-[1px] rtl:rounded-l-[1px] rtl:rounded-r-none" style={{ backgroundColor: statusConfig.ribbon }} />

        {/* Driver info (pinned) */}
        <div className="flex items-center gap-3 overflow-hidden text-start">
          <div className="relative shrink-0 select-none">
            <DriverAvatar name={drv.name} photoUrl={drv.photoUrl} size={32} />
            {drv.accountStatus === 'ACTIVE' && (
              <span
                title={drv.onlineStatus === 'ONLINE' ? (t.driversPage.statusOnline ?? 'En ligne') : (t.driversPage.statusOffline ?? 'Hors ligne')}
                className={cn('absolute bottom-0 right-0 block h-2 w-2 rounded-full ring-1 ring-[var(--surface)]', drv.onlineStatus === 'ONLINE' ? 'bg-emerald-500' : 'bg-[var(--text-soft)]')}
              />
            )}
          </div>
          <div className="flex flex-col gap-0.5 truncate">
            <span className="text-xs font-[700] text-[var(--text-primary)] truncate">{drv.name}</span>
            {drv.email && <span className="text-2xs font-[500] text-[var(--text-muted)] truncate">{drv.email}</span>}
          </div>
        </div>

        {/* Optional columns */}
        {orderedColumns.filter(c => !c.pinned).map(col => {
          if (!visibleIds.has(col.id)) return null;
          if (col.id === 'contact') return (
            <div key="contact" className="flex items-center gap-1.5 text-start">
              <SVGPhone size={10} className="text-[var(--text-muted)]" />
              <span className="text-xs font-[600] text-[var(--text-soft)] font-mono tracking-wide">{drv.phone}</span>
            </div>
          );
          if (col.id === 'activity') return (
            <div key="activity" className="text-start min-w-0">
              <DriverActivity drv={drv} t={t} routeNameById={routeNameById} onOpenRoute={onOpenRoute} />
            </div>
          );
          if (col.id === 'status') return (
            <div key="status" className="text-start flex items-center">
              <DriverStatusBadge status={drv.accountStatus ?? 'PENDING_SETUP'} size="sm" />
            </div>
          );
          return null;
        })}

        {/* Action dropdown */}
        <div className="flex items-center justify-end pe-6" onClick={(e) => e.stopPropagation()}>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--surface)] hover:text-[var(--text-primary)] transition-all shrink-0">
                <SVGDots size={14} />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40 bg-[var(--surface)] border border-[var(--border)] shadow-lg rounded-sm p-1">
              <DropdownMenuItem
                onClick={() => onOpenDetails(drv.id)}
                className="text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5"
              >
                <SVGUser size={13} className="text-[var(--text-muted)]" />
                {t.driversPage.profileModalTitle}
              </DropdownMenuItem>

              {drv.accountStatus !== 'PENDING_SETUP' && !readOnly && (
                <DropdownMenuItem onClick={() => onEdit(drv)} className="text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5">
                  <SVGPencil size={13} className="text-[var(--text-muted)]" />
                  {t.driversPage.modifyButton}
                </DropdownMenuItem>
              )}

              {drv.accountStatus === 'PENDING_SETUP' && !readOnly && (
                <>
                  <DropdownMenuItem onClick={() => onResendInvite(drv)} disabled={resendCooldown > 0} className="text-xs font-semibold text-amber-600 hover:text-amber-800 hover:bg-amber-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGSend size={13} className="text-amber-500" />
                    {resendCooldown > 0 ? `${resendCooldown}s` : t.driversPage.resendInviteButton}
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => onCancelInvite(drv)} className="text-xs font-semibold text-red-600 hover:text-red-800 hover:bg-red-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGX size={13} className="text-red-500" />
                    {t.driversPage.cancelInviteButton}
                  </DropdownMenuItem>
                </>
              )}
              {drv.accountStatus === 'ACTIVE' && !readOnly && (
                <>
                  <DropdownMenuItem onClick={() => onSuspend(drv)} className="text-xs font-semibold text-red-600 hover:text-red-800 hover:bg-red-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGBan size={13} className="text-red-500" />
                    {t.driversPage.suspendDriverButton}
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => onForceLogout(drv.id)} className="text-xs font-semibold text-amber-600 hover:text-amber-800 hover:bg-amber-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGLogout size={13} className="text-amber-500" />
                    {t.driversPage.forceLogoutButton}
                  </DropdownMenuItem>
                </>
              )}
              {drv.accountStatus === 'SUSPENDED' && !readOnly && (
                <DropdownMenuItem onClick={() => onActivate(drv)} className="text-xs font-semibold text-emerald-600 hover:text-emerald-800 hover:bg-emerald-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                  <SVGActivity size={13} className="text-emerald-500" />
                  {t.driversPage.activateTooltip}
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
    </div>
  );
}

export function DriverMobileCard({
  drv, t, readOnly, resendCooldown,
  routeNameById, onOpenRoute, onOpenDetails, onEdit, onResendInvite, onCancelInvite, onSuspend, onForceLogout, onActivate,
}: Omit<Props, 'gridCols' | 'rowHeight' | 'orderedColumns' | 'visibleIds'>) {
  const statusConfig = DRIVER_STATUS_COLORS[drv.accountStatus ?? 'PENDING_SETUP'] ?? {
    dot: '#8A8F98', bg: 'rgba(138,143,152,0.08)', text: '#6B7280', ribbon: '#8A8F98',
  };

  return (
    <div
      onClick={() => onOpenDetails(drv.id)}
      className="p-4 rounded-md border border-[var(--border)] bg-[var(--surface)] hover:border-[var(--border-strong)] transition-all flex flex-col gap-3 shadow-xs relative cursor-pointer"
    >
      {/* Account Status Ribbon */}
      <div className="absolute left-0 top-0 bottom-0 w-[4px] rounded-l-md" style={{ backgroundColor: statusConfig.ribbon }} />

      {/* Driver info header */}
      <div className="flex items-start justify-between min-w-0 ps-1">
        <div className="flex items-center gap-3 min-w-0">
          <div className="relative shrink-0 select-none">
            <DriverAvatar name={drv.name} photoUrl={drv.photoUrl} size={36} />
            {drv.accountStatus === 'ACTIVE' && (
              <span
                title={drv.onlineStatus === 'ONLINE' ? (t.driversPage.statusOnline ?? 'En ligne') : (t.driversPage.statusOffline ?? 'Hors ligne')}
                className={cn('absolute bottom-0 right-0 block h-2.5 w-2.5 rounded-full ring-1 ring-[var(--surface)]', drv.onlineStatus === 'ONLINE' ? 'bg-emerald-500' : 'bg-[var(--text-soft)]')}
              />
            )}
          </div>
          <div className="flex flex-col gap-0.5 truncate">
            <span className="text-sm font-[700] text-[var(--text-primary)] truncate">{drv.name}</span>
            {drv.email && <span className="text-2xs font-[500] text-[var(--text-muted)] truncate">{drv.email}</span>}
          </div>
        </div>

        <div className="flex items-center gap-2 shrink-0" onClick={(e) => e.stopPropagation()}>
          <DriverStatusBadge status={drv.accountStatus ?? 'PENDING_SETUP'} size="sm" />
          
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--surface)] hover:text-[var(--text-primary)] transition-all">
                <SVGDots size={14} />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40 bg-[var(--surface)] border border-[var(--border)] shadow-lg rounded-sm p-1">
              <DropdownMenuItem
                onClick={() => onOpenDetails(drv.id)}
                className="text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5"
              >
                <SVGUser size={13} className="text-[var(--text-muted)]" />
                {t.driversPage.profileModalTitle}
              </DropdownMenuItem>

              {drv.accountStatus !== 'PENDING_SETUP' && !readOnly && (
                <DropdownMenuItem onClick={() => onEdit(drv)} className="text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5">
                  <SVGPencil size={13} className="text-[var(--text-muted)]" />
                  {t.driversPage.modifyButton}
                </DropdownMenuItem>
              )}

              {drv.accountStatus === 'PENDING_SETUP' && !readOnly && (
                <>
                  <DropdownMenuItem onClick={() => onResendInvite(drv)} disabled={resendCooldown > 0} className="text-xs font-semibold text-amber-600 hover:text-amber-800 hover:bg-amber-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGSend size={13} className="text-amber-500" />
                    {resendCooldown > 0 ? `${resendCooldown}s` : t.driversPage.resendInviteButton}
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => onCancelInvite(drv)} className="text-xs font-semibold text-red-600 hover:text-red-800 hover:bg-red-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGX size={13} className="text-red-500" />
                    {t.driversPage.cancelInviteButton}
                  </DropdownMenuItem>
                </>
              )}
              {drv.accountStatus === 'ACTIVE' && !readOnly && (
                <>
                  <DropdownMenuItem onClick={() => onSuspend(drv)} className="text-xs font-semibold text-red-600 hover:text-red-800 hover:bg-red-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGBan size={13} className="text-red-500" />
                    {t.driversPage.suspendDriverButton}
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => onForceLogout(drv.id)} className="text-xs font-semibold text-amber-600 hover:text-amber-800 hover:bg-amber-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                    <SVGLogout size={13} className="text-amber-500" />
                    {t.driversPage.forceLogoutButton}
                  </DropdownMenuItem>
                </>
              )}
              {drv.accountStatus === 'SUSPENDED' && !readOnly && (
                <DropdownMenuItem onClick={() => onActivate(drv)} className="text-xs font-semibold text-emerald-600 hover:text-emerald-800 hover:bg-emerald-50/50 gap-2 cursor-pointer rounded px-2.5 py-1.5">
                  <SVGActivity size={13} className="text-emerald-500" />
                  {t.driversPage.activateTooltip}
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      {/* Driver contact & activity */}
      <div className="flex items-center justify-between gap-4 border-t border-[var(--border)] pt-2.5 ps-1">
        <div className="flex items-center gap-1.5">
          <SVGPhone size={10} className="text-[var(--text-muted)]" />
          <span className="text-xs font-[600] text-[var(--text-soft)] font-mono tracking-wide">{drv.phone}</span>
        </div>

        <div className="flex items-center gap-1.5 flex-nowrap min-w-0" onClick={(e) => e.stopPropagation()}>
          <DriverActivity drv={drv} t={t} routeNameById={routeNameById} onOpenRoute={onOpenRoute} />
        </div>
      </div>
    </div>
  );
}
