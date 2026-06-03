'use client';

import { IconSearch, IconPackage, IconInfoCircle, IconGripVertical, IconAlertTriangle, IconLock } from '@tabler/icons-react';
import { useDraggable } from '@dnd-kit/core';
import { DroppableZone } from './DroppableZone';
import { DeliveryOption } from '../types';
import { resolveOrderRef, shortId } from '@/lib/utils';
import { showErrorToast } from '@/lib/toast-service';

/** A delivery whose ERP warehouse has no synced depot cannot be routed (no source depot). */
export const isUnmappedDelivery = (d: DeliveryOption): boolean =>
  Boolean(d.warehouseCode) && !d.sourceDepotId;
import { Table, TableHeader, TableBody, TableHead, TableRow, TableCell } from '@/components/ui/table';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { FieldSelect } from '@/components/ui/field';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { useRouteBuilderContext } from '../hooks/useRouteBuilder';

function DraggableOrderRow({
  delivery,
  isSelected,
  onToggle,
}: {
  delivery: DeliveryOption;
  isSelected: boolean;
  onToggle: (id: string) => void;
}) {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const unmapped = isUnmappedDelivery(delivery);
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `order:${delivery.id}`,
    data: { type: 'order', deliveryId: delivery.id, source: 'orders-table' },
    disabled: unmapped,
  });

  return (
    <TableRow
      style={{
        opacity: isDragging ? 0.4 : unmapped ? 0.6 : 1,
        cursor: 'default',
      }}
      onClick={() => unmapped ? showErrorToast(null, t.routeBuilderPage.unmappedDepotChip) : onToggle(delivery.id)}
      className={`border-b border-[var(--border)] hover:bg-[var(--surface-2)] transition-colors ${
        isDragging || isSelected ? 'bg-[var(--surface-2)]' : ''
      }`}
    >
      {/* Grip handle — only this cell initiates drag (disabled for unmapped rows) */}
      <TableCell
        ref={unmapped ? undefined : setNodeRef}
        {...(unmapped ? {} : attributes)}
        {...(unmapped ? {} : listeners)}
        className={`w-7 text-[var(--text-muted)] pl-2 pr-1 ${unmapped ? 'cursor-not-allowed' : 'cursor-grab active:cursor-grabbing'}`}
        onClick={(e) => e.stopPropagation()}
        aria-label={`${locale === 'ar' ? 'نقل' : locale === 'en' ? 'Move' : 'Déplacer'} ${delivery.clientName ?? delivery.id.slice(0, 8)}`}
      >
        <div className="flex items-center justify-center w-4 h-4">
          {unmapped ? <IconLock size={12} /> : <IconGripVertical size={13} />}
        </div>
      </TableCell>
      <TableCell className="w-9 px-1" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-center">
          <input
            type="checkbox"
            checked={isSelected}
            disabled={unmapped}
            onChange={() => onToggle(delivery.id)}
            className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 focus:ring-offset-0 cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
            aria-label={`${locale === 'ar' ? 'اختر' : locale === 'en' ? 'Select' : 'Sélectionner'} ${delivery.id}`}
          />
        </div>
      </TableCell>
      <TableCell>
        <span className="font-mono text-xs font-bold text-[var(--brand-orange)]">
          {resolveOrderRef(delivery)}
        </span>
      </TableCell>
      <TableCell>
        <span className="font-mono text-[10px] text-[var(--text-muted)]">
          #{shortId(delivery.id)}
        </span>
      </TableCell>
      <TableCell className="max-w-[220px] font-medium text-[var(--text-strong)]">
        <div className="flex items-center gap-1.5 min-w-0">
          <span className="truncate">{delivery.clientName || '—'}</span>
          {delivery.warehouseCode && !delivery.sourceDepotId && (
            <Tooltip>
              <TooltipTrigger asChild>
                <span
                  className="inline-flex items-center gap-1 shrink-0 rounded-[2px] px-1 py-0.5 text-[9px] font-bold cursor-help"
                  style={{ color: '#B45309', background: 'rgba(217,119,6,0.12)' }}
                >
                  <IconAlertTriangle size={10} />
                  {delivery.warehouseCode}
                </span>
              </TooltipTrigger>
              <TooltipContent className="max-w-xs">{t.routeBuilderPage.unmappedDepotChip}</TooltipContent>
            </Tooltip>
          )}
        </div>
      </TableCell>
      <TableCell className="max-w-[240px]">
        <div className="flex items-center gap-1.5 min-w-0">
          <span className="text-xs text-[var(--text-soft)] truncate flex-1">
            {delivery.itemsSummary || (delivery.totalQuantity ? (delivery.totalQuantity === 1 ? t.routeBuilderPage.articlesCountSingular.replace('{count}', '1') : t.routeBuilderPage.articlesCountPlural.replace('{count}', String(delivery.totalQuantity))) : '—')}
          </span>
          {delivery.itemsSummary && (
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="inline-flex cursor-help text-[var(--text-muted)] shrink-0">
                  <IconInfoCircle size={13} />
                </span>
              </TooltipTrigger>
              <TooltipContent className="max-w-xs">{delivery.itemsSummary}</TooltipContent>
            </Tooltip>
          )}
        </div>
      </TableCell>
      <TableCell className="text-right font-mono font-semibold text-[var(--text-strong)]">
        {delivery.totalWeightKg?.toFixed(1) ?? '0.0'} kg
      </TableCell>
      <TableCell className="text-right text-[var(--text-soft)]">
        {delivery.createdAt
          ? new Date(delivery.createdAt).toLocaleDateString(locale === 'ar' ? 'ar-EG' : locale === 'en' ? 'en-US' : 'fr-FR', { day: '2-digit', month: '2-digit' })
          : '—'}
      </TableCell>
    </TableRow>
  );
}

interface OrdersTableProps {
  targetRouteId: string | null;
  setTargetRouteId: (id: string | null) => void;
}

export function OrdersTable({
  targetRouteId,
  setTargetRouteId,
}: OrdersTableProps) {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const rb = useRouteBuilderContext();
  const {
    filteredDeliveries,
    deliverySearch,
    setDeliverySearch,
    orderQuickView,
    setOrderQuickView,
    selectedOrderIds,
    setSelectedOrderIds,
    batchAssigning,
    assignSelectedToActiveRoute,
    routes,
  } = rb;

  const toggleSelection = (id: string) => {
    if (selectedOrderIds.includes(id)) {
      setSelectedOrderIds(selectedOrderIds.filter((x) => x !== id));
    } else {
      setSelectedOrderIds([...selectedOrderIds, id]);
    }
  };

  // Only mapped deliveries (with a resolved source depot) can be routed.
  const routableDeliveries = filteredDeliveries.filter((d) => !isUnmappedDelivery(d));
  const allSelected =
    routableDeliveries.length > 0 && selectedOrderIds.length === routableDeliveries.length;

  const toggleAll = () => {
    if (allSelected) setSelectedOrderIds([]);
    else setSelectedOrderIds(routableDeliveries.map((d) => d.id));
  };

  return (
    <div className="flex flex-col h-full bg-[var(--surface-1)]">
      {/* Toolbar */}
      <div className="flex justify-between items-center gap-3 px-4 py-2 border-b border-[var(--border)] shrink-0 bg-[var(--surface-1)]">
        <div className="flex items-center gap-2 flex-1">
          <div className="relative flex items-center max-w-sm">
            <span className="absolute left-2.5 text-[var(--text-muted)] pointer-events-none">
              <IconSearch size={13} />
            </span>
            <input
              type="text"
              placeholder={t.routeBuilderPage.searchClientRefId}
              value={deliverySearch}
              onChange={(e) => setDeliverySearch(e.currentTarget.value)}
              className="w-full h-8 pl-8 pr-3 rounded bg-[var(--surface-2)] border border-[var(--border)] text-xs text-[var(--text-strong)] placeholder:text-[var(--text-soft)] focus:outline-none focus:border-[var(--brand)]"
            />
          </div>
          <div className="flex items-center gap-0.5 ml-1">
            {([
              { key: 'all', label: t.routeBuilderPage.quickViewAll },
              { key: 'today', label: t.routeBuilderPage.quickViewToday },
              { key: 'thisWeek', label: t.routeBuilderPage.quickViewThisWeek },
            ] as const).map(({ key, label }) => (
              <button
                key={key}
                onClick={() => setOrderQuickView(key)}
                className={`px-1.5 py-1 text-[11px] font-semibold rounded transition-colors ${
                  orderQuickView === key
                    ? 'bg-[var(--brand-orange)] text-white'
                    : 'text-[var(--text-soft)] hover:text-[var(--text-strong)] hover:bg-[var(--surface-2)]'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {selectedOrderIds.length > 0 && (
          <div className="flex items-center gap-2">
            <FieldSelect
              options={routes.map((r) => ({ value: r.id, label: r.name }))}
              value={targetRouteId || ''}
              onChange={(e) => setTargetRouteId(e.target.value || null)}
              placeholder={t.routeBuilderPage.selectRoutePlaceholder}
              wrapperClassName="w-48 !gap-0"
              className="!h-8 !py-1 text-xs"
            />
            <Button
              size="sm"
              onClick={() => void assignSelectedToActiveRoute(targetRouteId ?? undefined)}
              loading={batchAssigning}
              disabled={!targetRouteId}
              className="bg-[var(--brand-orange)] hover:opacity-90 text-white font-semibold h-8 text-xs border-transparent"
            >
              {t.routeBuilderPage.assignButton.replace('{count}', String(selectedOrderIds.length))}
            </Button>
          </div>
        )}
      </div>

      {/* Grid container */}
      <div className="flex-1 overflow-y-auto min-h-0">
        {filteredDeliveries.length === 0 ? (
          <div className="h-44 flex flex-col items-center justify-center gap-2 p-6">
            <IconPackage size={28} className="text-[var(--text-muted)]" />
            <span className="text-xs text-[var(--text-muted)]">{t.routeBuilderPage.emptyOrdersState}</span>
          </div>
        ) : (
          <DroppableZone id="unscheduled-drop" className="h-full">
            <Table>
              <TableHeader className="bg-[var(--surface-2)] sticky top-0 z-10">
                <TableRow className="border-b border-[var(--border)]">
                  <TableHead className="w-7 p-0" />
                  <TableHead className="w-9 px-1">
                    <div className="flex items-center justify-center">
                      <input
                        type="checkbox"
                        checked={allSelected}
                        onChange={toggleAll}
                        className="w-3.5 h-3.5 rounded border-[var(--border)] bg-[var(--surface)] text-[var(--brand-orange)] focus:ring-0 focus:ring-offset-0 cursor-pointer"
                        aria-label={t.routeBuilderPage.selectAll}
                      />
                    </div>
                  </TableHead>
                  <TableHead className="w-[100px] text-[10px] font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerIdErp}</TableHead>
                  <TableHead className="w-[100px] text-[10px] font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerIdAsm}</TableHead>
                  <TableHead className="text-[10px] font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerClient}</TableHead>
                  <TableHead className="text-[10px] font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerArticles}</TableHead>
                  <TableHead className="w-24 text-right text-[10px] font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerWeight}</TableHead>
                  <TableHead className="w-24 text-right text-[10px] font-bold text-[var(--text-soft)] uppercase tracking-wider">{t.routeBuilderPage.headerDate}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredDeliveries.map((delivery) => (
                  <DraggableOrderRow
                    key={delivery.id}
                    delivery={delivery}
                    isSelected={selectedOrderIds.includes(delivery.id)}
                    onToggle={toggleSelection}
                  />
                ))}
              </TableBody>
            </Table>
          </DroppableZone>
        )}
      </div>
    </div>
  );
}
