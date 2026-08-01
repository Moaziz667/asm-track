import { Link } from 'react-router-dom';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { STATUS_COLORS } from '@/components/data-display/StatusBadge';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { PriorityDot } from '@/components/data-display/PriorityDot';
import { IconMapPin, IconFileText, IconRoute, IconX, IconLink } from '@tabler/icons-react';
import { cn, resolveOrderRef, shortId } from '@/lib/utils';
import { getDayBucket } from '@/lib/sla';
import { showSuccessToast } from '@/lib/ui/toast-service';
import type { useT } from '@/lib/i18n/LocaleContext';
import type { ColumnDef } from '@/hooks/useColumnSettings';
import { DELIVERY_ROW_H } from './constants';
import { Spinner } from './helpers';
import { formatAddress } from './format';
import type { DeliveryRow } from './types';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';

const LOCKED = ['PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED'];

interface Props {
  item: DeliveryRow;
  density: 'compact' | 'comfortable' | 'spacious';
  orderedColumns: ColumnDef[];
  visibleIds: Set<string>;
  dateTag: string;
  downloadingBl: Set<string>;
  t: ReturnType<typeof useT>;
  onRowClick: (rowId: string) => void;
  onPin: (item: DeliveryRow) => void;
  onDownloadBl: (rowId: string, ref: string) => void;
  onOpenRoute: (item: DeliveryRow) => void;
  onCancel: (item: DeliveryRow) => void;
}

export function DeliveryTableRow({
  item, density, orderedColumns, visibleIds, dateTag, downloadingBl, t,
  onRowClick, onPin, onDownloadBl, onOpenRoute, onCancel,
}: Props) {
  return (
    <tr
      className={cn('hover:bg-[var(--hover-bg)] transition-all group cursor-pointer', DELIVERY_ROW_H[density])}
      onClick={() => onRowClick(item.rowId)}
    >
      {/* Vertical ribbon — SLA-dominant: overdue=red, at-risk=amber on a pending delivery;
          otherwise the status colour. */}
      <td className="p-0">
        {(() => {
          const pending = !['DELIVERED', 'CANCELLED', 'FAILED'].includes((item.status ?? '').toUpperCase());
          const bucket = getDayBucket(item.scheduledAt);
          const h = (item.slaHealth && item.slaHealth !== 'NONE')
            ? item.slaHealth : item.slaWorstHealth;
          const ribbon =
            (h === 'BREACHED' || h === 'LATE' || (pending && bucket === 'overdue')) ? 'var(--danger)'
            : (h === 'AT_RISK' || (pending && bucket === 'today')) ? 'var(--warning)'
            : STATUS_COLORS[(item.status ?? '').toUpperCase()] || 'var(--border)';
          return <div className="w-[3px] h-10 rounded-r-[2px]" style={{ backgroundColor: ribbon }} />;
        })()}
      </td>

      {orderedColumns.map(col => {
        if (!visibleIds.has(col.id)) return null;
        if (col.id === 'ref') {
          const isReturn = (item as { kind?: string }).kind === 'RETURN_PICKUP';
          const rmaNumber = (item as { rmaNumber?: string }).rmaNumber;
          const primaryRef = isReturn && rmaNumber ? rmaNumber : resolveOrderRef(item);
          return (
          <td key="ref" className="px-6">
            <div className="flex flex-col gap-0">
              <Link to={`/deliveries/${item.rowId ?? item.id}`} onClick={(e) => e.stopPropagation()} style={{ textDecoration: 'none' }}>
                <span className="text-xs font-[700] font-mono tabular-nums hover:text-[var(--brand)] transition-colors" style={{ color: 'var(--brand)', cursor: 'pointer' }}>
                  {primaryRef}
                </span>
              </Link>
              <span className="text-xs font-[600] text-[var(--text-muted)] uppercase font-mono tracking-tighter opacity-70">
                {isReturn && rmaNumber ? resolveOrderRef(item) : `#${shortId(item.rowId)}`}
              </span>
            </div>
          </td>
          );
        }
        if (col.id === 'client') return (
          <td key="client" className="px-6">
            <div className="flex flex-col gap-0.5 max-w-[400px]">
              <span className="flex items-center gap-1.5 min-w-0">
                <span className="text-xs font-[600] text-[var(--text-primary)] line-clamp-1 group-hover:underline decoration-[var(--brand)]/20">
                  {item.clientName || t.deliveriesPage.unknownClientName}
                </span>
                {(item as { kind?: string }).kind === 'RETURN_PICKUP' && (
                  <StatusBadge status="RETURN_PICKUP" label={t.deliveriesPage.returnPickupBadge ?? 'Retour'} size="sm" />
                )}
              </span>
              <div className="flex items-center gap-1 flex-nowrap">
                <IconMapPin size={10} className="text-[var(--text-muted)]" />
                {/* `truncate` and `line-clamp-1` were both set: two mechanisms for one job, the
                    last one winning. And the missing-address case borrowed the "geocoding in
                    progress" string, so a delivery with no address announced work that was not
                    happening. The title carries the full line, which the cell cannot show. */}
                {/* Displayed cleaned, kept whole in the title: the stored value is what was
                    geocoded, and someone chasing a bad pin needs to see exactly that. */}
                <span
                  className="text-2xs font-[500] text-[var(--text-soft)] truncate"
                  title={item.dropoffAddress || undefined}
                >
                  {formatAddress(item.dropoffAddress) || t.deliveriesPage.missingAddress}
                </span>
              </div>
            </div>
          </td>
        );
        if (col.id === 'scheduled') return (
          <td key="scheduled" className="px-6">
            {(() => {
              if (!item.scheduledAt) {
                return <span className="text-xs text-[var(--text-muted)] italic">{t.deliveriesPage.unscheduled}</span>;
              }
              const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
              const bucket = getDayBucket(item.scheduledAt);
              // The pill was written and never worn: three classes were built — text, background,
              // border — and the render used colorClass.split(' ')[0], keeping only the text
              // colour. The background classes were raw Tailwind (bg-red-50), which would have
              // been a near-white block in dark mode had they ever applied. Rebuilt on the tokens,
              // tinted from the same hue, so it works in both themes.
              let tone = 'var(--text-soft)';
              if (isPending) {
                if (bucket === 'overdue') tone = 'var(--danger)';
                else if (bucket === 'today') tone = 'var(--warning)';
                else tone = 'var(--info)';
              }
              return (
                <div className="inline-flex items-center gap-1.5 flex-wrap">
                  <span
                    className="text-xs font-[600] px-2 py-0.5 rounded-full border whitespace-nowrap"
                    style={{
                      color: tone,
                      backgroundColor: isPending ? `color-mix(in srgb, ${tone} 10%, transparent)` : 'transparent',
                      borderColor: isPending ? `color-mix(in srgb, ${tone} 25%, transparent)` : 'var(--border)',
                    }}
                  >
                    {new Date(item.scheduledAt).toLocaleDateString(dateTag)}
                    {/* `me-` not `mr-`: in Arabic the margin belongs on the other side. */}
                    <span className="me-0.5">,</span>
                    {new Date(item.scheduledAt).toLocaleTimeString(dateTag, { hour: '2-digit', minute: '2-digit' })}
                  </span>
                  {item.rescheduledAt && (
                    <StatusBadge status="RESCHEDULED" size="sm" label={t.deliveryPage.rescheduledBadge} />
                  )}
                </div>
              );
            })()}
          </td>
        );
        if (col.id === 'status') return (
          <td key="status" className="px-6">
            <div className="flex items-center gap-1.5 flex-wrap">
              <StatusBadge status={item.status} size="sm" />
              <SlaHealthBadge health={item.slaHealth} />
            </div>
          </td>
        );
        if (col.id === 'driver') return (
          <td key="driver" className="px-6">
            <div className="flex items-center gap-1.5 justify-center">
              {item.driverName ? (
                <>
                  <DriverAvatarById driverId={item.driverId} name={item.driverName} size={20} />
                  <span className="text-xs font-[600] text-[var(--text-soft)] truncate max-w-[100px]" title={item.driverName}>{item.driverName}</span>
                </>
              ) : (
                <span className="text-xs font-[400] text-[var(--text-muted)]">{t.deliveriesPage.notAssigned}</span>
              )}
            </div>
          </td>
        );
          if (col.id === 'zone') return (
          <td key="zone" className="px-6">
            <div className="flex justify-center">
              {item.zoneName ? (
                <span
                  className="text-xs font-[500] px-2 py-0.5 rounded-full"
                  style={{
                    color: item.zoneColor || 'var(--text-muted)',
                    // Appending "12" assumed a six-digit hex. A three-digit one, or an rgb()
                    // value, produced an invalid colour and the chip lost its background.
                    backgroundColor: item.zoneColor
                      ? `color-mix(in srgb, ${item.zoneColor} 12%, transparent)`
                      : 'color-mix(in srgb, var(--text-soft) 12%, transparent)',
                  }}
                >
                  {item.zoneName}
                </span>
              ) : (
                <span className="text-xs text-[var(--text-muted)]">—</span>
              )}
            </div>
          </td>
        );
        return null;
      })}

      <td className="px-6">
        <div className="flex items-center gap-1.5 justify-end flex-wrap">
          {(() => {
            const locked = LOCKED.includes(item.status);
            return (
              <>
                {!item.dropoffPinned ? (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                        style={{ color: 'var(--brand)' }}
                        onClick={(e) => { e.stopPropagation(); onPin(item); }}
                      >
                        <IconMapPin size={14} />
                      </button>
                    </TooltipTrigger>
                    <TooltipContent>{t.deliveriesPage.tooltipPinLocation}</TooltipContent>
                  </Tooltip>
                ) : !locked ? (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                        style={{ color: 'var(--brand)' }}
                        onClick={(e) => { e.stopPropagation(); onPin(item); }}
                      >
                        <IconMapPin size={15} />
                      </button>
                    </TooltipTrigger>
                    <TooltipContent>{t.deliveriesPage.tooltipRepin}</TooltipContent>
                  </Tooltip>
                ) : null}

                {item.blNumber && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                        style={{ color: 'var(--brand)' }}
                        onClick={(e) => { e.stopPropagation(); window.open(`/admin/deliveries/${item.id}/bon-livraison`, '_blank'); }}
                      >
                        <IconFileText size={14} />
                      </button>
                    </TooltipTrigger>
                    <TooltipContent>{t.deliveryPage.viewBL}</TooltipContent>
                  </Tooltip>
                )}

                {item.routeName && (
                  <button
                    type="button"
                    className="h-7 px-3 bg-[var(--surface)] border border-[var(--border)] rounded-xs flex items-center gap-2 hover:bg-[var(--hover-bg)] hover:border-[var(--border-strong)] transition-all text-[var(--text-primary)] font-[500]"
                    onClick={(e) => { e.stopPropagation(); onOpenRoute(item); }}
                  >
                    <IconRoute size={12} />
                    <span className="text-2xs truncate max-w-[80px]">{item.routeName}</span>
                  </button>
                )}
              </>
            );
          })()}
          {item.status === 'UNSCHEDULED' && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  className="w-7 h-7 flex items-center justify-center rounded-xs border border-red-300 hover:bg-red-50 transition-colors"
                  onClick={(e) => { e.stopPropagation(); onCancel(item); }}
                >
                  <IconX size={16} className="text-[var(--danger)]" />
                </button>
              </TooltipTrigger>
              <TooltipContent>{t.deliveriesPage.tooltipCancel}</TooltipContent>
            </Tooltip>
          )}
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                className="w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors disabled:opacity-50"
                disabled={downloadingBl.has(item.rowId)}
                onClick={(e) => { e.stopPropagation(); onDownloadBl(item.rowId, resolveOrderRef(item)); }}
              >
                {downloadingBl.has(item.rowId) ? <Spinner className="h-3 w-3" /> : <IconFileText size={14} />}
              </button>
            </TooltipTrigger>
            <TooltipContent>{t.deliveriesPage.tooltipDownloadBL}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                className={`w-7 h-7 flex items-center justify-center rounded-xs border border-[var(--border)] text-[var(--text-soft)] transition-colors ${item.status === 'UNSCHEDULED' ? 'opacity-40 cursor-not-allowed' : 'hover:bg-[var(--hover-bg)]'}`}
                onClick={(e) => {
                  e.stopPropagation();
                  // Tracking has no live driver/route until the delivery is dispatched — don't hand out an empty link.
                  if (item.status === 'UNSCHEDULED') return;
                  const url = `${window.location.origin}/track/${item.rowId}`;
                  navigator.clipboard.writeText(url);
                  showSuccessToast(t.deliveriesPage.trackingCopied);
                }}
              >
                <IconLink size={14} />
              </button>
            </TooltipTrigger>
            <TooltipContent>{item.status === 'UNSCHEDULED' ? t.deliveriesPage.trackingLinkUnavailable : t.deliveriesPage.trackingLink}</TooltipContent>
          </Tooltip>
        </div>
      </td>
    </tr>
  );
}

export function DeliveryMobileCard({
  item, dateTag, downloadingBl, t,
  onRowClick, onPin, onDownloadBl, onOpenRoute, onCancel,
}: Omit<Props, 'density' | 'orderedColumns' | 'visibleIds'>) {
  const locked = LOCKED.includes(item.status);
  return (
    <div
      onClick={() => onRowClick(item.rowId)}
      className="p-4 rounded-md border border-[var(--border)] bg-[var(--surface)] hover:border-[var(--border-strong)] transition-all flex flex-col gap-3 shadow-xs relative"
    >
      {/* SLA Ribbon Indicator on the left border */}
      {(() => {
        const pending = !['DELIVERED', 'CANCELLED', 'FAILED'].includes((item.status ?? '').toUpperCase());
        const bucket = getDayBucket(item.scheduledAt);
        const h = (item.slaHealth && item.slaHealth !== 'NONE')
          ? item.slaHealth : item.slaWorstHealth;
        const ribbon =
          (h === 'BREACHED' || h === 'LATE' || (pending && bucket === 'overdue')) ? 'var(--danger)'
          : (h === 'AT_RISK' || (pending && bucket === 'today')) ? 'var(--warning)'
          : STATUS_COLORS[(item.status ?? '').toUpperCase()] || 'var(--border)';
        return <div className="absolute left-0 top-0 bottom-0 w-[4px] rounded-l-md" style={{ backgroundColor: ribbon }} />;
      })()}

      {/* Header Row: Ref & Status */}
      <div className="flex items-center justify-between min-w-0 ps-1">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-xs font-bold font-mono text-[var(--brand)] truncate">
            {(item as { kind?: string }).kind === 'RETURN_PICKUP' && (item as { rmaNumber?: string }).rmaNumber
              ? (item as { rmaNumber?: string }).rmaNumber
              : resolveOrderRef(item)}
          </span>
          <span className="text-3xs font-[600] text-[var(--text-muted)] font-mono">
            {(item as { kind?: string }).kind === 'RETURN_PICKUP' && (item as { rmaNumber?: string }).rmaNumber
              ? resolveOrderRef(item)
              : `#${shortId(item.rowId)}`}
          </span>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <StatusBadge status={item.status} size="sm" />
          <SlaHealthBadge health={item.slaHealth} />
        </div>
      </div>

      {/* Client & Address Info */}
      <div className="flex flex-col gap-1 ps-1">
        <span className="flex items-center gap-1.5 text-xs font-bold text-[var(--text-primary)]">
          <PriorityDot priority={item.priority} label={t.deliveryPage.priorityHigh} />
          {item.clientName || t.deliveriesPage.unknownDriver}
        </span>
        <div className="flex items-center gap-1">
          <IconMapPin size={11} className="text-[var(--text-muted)] shrink-0" />
          {/* The mobile card carried the same two faults as the table row: the geocoding-in-progress
              string standing in for a missing address, and the raw geocoded path printed whole. */}
          <span className="text-2xs text-[var(--text-soft)] truncate" title={item.dropoffAddress || undefined}>
            {formatAddress(item.dropoffAddress) || t.deliveriesPage.missingAddress}
          </span>
        </div>
      </div>

      {/* Driver, Zone & Date */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[var(--border)] pt-2.5 ps-1">
        <div className="flex items-center gap-2">
          {item.driverName ? (
            <div className="flex items-center gap-1">
              <DriverAvatarById driverId={item.driverId} name={item.driverName} size={18} />
              <span className="text-2xs font-[600] text-[var(--text-soft)]">{item.driverName}</span>
            </div>
          ) : (
            <span className="text-2xs text-[var(--text-muted)]">{t.deliveriesPage.notAssigned}</span>
          )}

          {item.zoneName ? (
            <span
              className="text-2xs font-[500] px-1.5 py-0.5 rounded-full"
              style={{
                color: item.zoneColor || 'var(--text-muted)',
                backgroundColor: item.zoneColor ? `${item.zoneColor}12` : 'rgba(161,161,170,0.10)',
              }}
            >
              {item.zoneName}
            </span>
          ) : (
            <span className="text-2xs text-[var(--text-muted)]">—</span>
          )}
        </div>

        {item.scheduledAt && (
          <span className="text-2xs font-bold text-[var(--text-soft)]">
            {new Date(item.scheduledAt).toLocaleDateString(dateTag, { month: 'short', day: 'numeric' })}
            <span className="mx-0.5">@</span>
            {new Date(item.scheduledAt).toLocaleTimeString(dateTag, { hour: '2-digit', minute: '2-digit' })}
          </span>
        )}
      </div>

      {/* Actions Strip */}
      <div className="flex items-center gap-1.5 justify-end border-t border-[var(--border)] pt-2.5" onClick={(e) => e.stopPropagation()}>
        {!item.dropoffPinned ? (
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)]"
            style={{ color: 'var(--brand)' }}
            onClick={() => onPin(item)}
            title={t.deliveriesPage.tooltipPinLocation}
          >
            <IconMapPin size={13} />
          </button>
        ) : !locked ? (
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] hover:bg-[var(--hover-bg)]"
            style={{ color: 'var(--brand)' }}
            onClick={() => onPin(item)}
            title={t.deliveriesPage.tooltipRepin}
          >
            <IconMapPin size={13} />
          </button>
        ) : null}

        {item.routeName && (
          <button
            type="button"
            className="h-7 px-2 bg-[var(--surface)] border border-[var(--border)] rounded flex items-center gap-1 hover:bg-[var(--hover-bg)]"
            onClick={() => onOpenRoute(item)}
          >
            <IconRoute size={11} className="text-[var(--text-soft)]" />
            <span className="text-3xs font-[600] text-[var(--text-soft)] truncate max-w-[70px]">{item.routeName}</span>
          </button>
        )}

        {item.status === 'UNSCHEDULED' && (
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-red-200 hover:bg-red-50"
            onClick={() => onCancel(item)}
            title={t.deliveriesPage.tooltipCancel}
          >
            <IconX size={14} className="text-[var(--danger)]" />
          </button>
        )}

        <button
          type="button"
          className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-soft)] hover:bg-[var(--hover-bg)]"
          disabled={downloadingBl.has(item.rowId)}
          onClick={() => onDownloadBl(item.rowId, resolveOrderRef(item))}
          title={t.deliveriesPage.tooltipDownloadBL}
        >
          {downloadingBl.has(item.rowId) ? <Spinner className="h-3 w-3" /> : <IconFileText size={13} />}
        </button>

        <button
          type="button"
          className={`w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-soft)] ${item.status === 'UNSCHEDULED' ? 'opacity-40 cursor-not-allowed' : 'hover:bg-[var(--hover-bg)]'}`}
          onClick={() => {
            if (item.status === 'UNSCHEDULED') return;
            const url = `${window.location.origin}/track/${item.rowId}`;
            navigator.clipboard.writeText(url);
            showSuccessToast(t.deliveriesPage.trackingCopied);
          }}
          title={item.status === 'UNSCHEDULED' ? t.deliveriesPage.trackingLinkUnavailable : t.deliveriesPage.trackingLink}
        >
          <IconLink size={13} />
        </button>
      </div>
    </div>
  );
}
