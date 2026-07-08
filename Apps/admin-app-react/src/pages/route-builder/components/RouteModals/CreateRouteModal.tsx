'use client';

import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/i18n/LocaleContext';
import { IconRoute } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { FieldSelect } from '@/components/ui/field';
import { Button } from '@/components/ui/button';
import { DatePickerPopover } from '@/components/ui/DatePickerPopover';
import { useRouteBuilderContext } from '../../hooks/useRouteBuilder';

export function CreateRouteModal() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const rb = useRouteBuilderContext();
  const {
    createOpen,
    setCreateOpen,
    createForm,
    setCreateForm,
    availableDrivers,
    drivers: allDrivers,
    availableVehicles,
    vehicles: allVehicles,
    depots,
    routes,
    availabilityLoaded,
    createRoute,
    creating,
  } = rb;

  // Always show the FULL fleet so busy drivers/vehicles stay visible — just disabled (not selectable).
  // A driver/vehicle is "used" (disabled) when EITHER:
  //  • it's already assigned to a route in the builder — incl. DRAFTs the user just created, which the
  //    /available API ignores (it only flags VALIDATED/IN_PROGRESS), or
  //  • the date/time-aware availability API excluded it.
  const drivers = allDrivers;
  const vehicles = allVehicles;
  const usedDriverIds = new Set(routes.map((r) => r.driverId).filter(Boolean) as string[]);
  const usedVehicleIds = new Set(routes.map((r) => r.vehicleId).filter(Boolean) as string[]);
  const availableDriverIds = new Set(availableDrivers.map((d) => d.id));
  const availableVehicleIds = new Set(availableVehicles.map((v) => v.id));
  const driverDisabled = (d: { id: string }) =>
    usedDriverIds.has(d.id) || (availabilityLoaded && !availableDriverIds.has(d.id));
  const vehicleDisabled = (v: { id: string }) =>
    usedVehicleIds.has(v.id) || (availabilityLoaded && !availableVehicleIds.has(v.id));

  return (
    <AppModal
      opened={createOpen}
      onClose={() => setCreateOpen(false)}
      subtitle={t.routeBuilderPage.createRouteTitle}
      title={t.routeBuilderPage.createRouteTitle}
      size="lg"
      footer={
        <div className="flex items-center justify-end gap-2 w-full">
          <Button variant="outline" size="sm" onClick={() => setCreateOpen(false)}>
            {t.actions.cancel}
          </Button>
          <Button
            size="sm"
            loading={creating}
            onClick={() => void createRoute()}
            className="bg-[var(--brand-orange)] hover:opacity-90 font-semibold"
          >
            <IconRoute size={14} className="mr-1.5" />
            {t.routeBuilderPage.createRouteTitle}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-[var(--text-muted)] select-none">
            {t.placeholders.date}
          </label>
          <DatePickerPopover
            value={createForm.date || null}
            onChange={(val) => {
              setCreateForm((p) => ({ ...p, date: val || '' }));
            }}
            className="w-full !h-[38px] !px-3 bg-[var(--surface)] text-[var(--text-primary)] border-[var(--border)]"
          />
          <span className="text-2xs text-[var(--text-muted)]">{t.routeBuilderPage.routeNameAuto ?? 'N° de tournée attribué automatiquement (R001, R002, …)'}</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FieldSelect
            label={t.routeBuilderPage.assignedDriverLabel}
            placeholder={t.routeBuilderPage.selectPlaceholder}
            options={drivers.map((d) => {
              const busy = driverDisabled(d);
              return { value: d.id, label: `${d.name}${busy ? ` ${t.routeBuilderPage.driverBusy}` : ''}`, disabled: busy };
            })}
            value={createForm.driverId || ''}
            onChange={(e) => {
              const val = e.target.value;
              setCreateForm((p) => ({ ...p, driverId: val }));
            }}
          />
          <FieldSelect
            label={t.routeBuilderPage.vehicleLabel}
            placeholder={t.routeBuilderPage.selectPlaceholder}
            options={vehicles
              .filter((v) => v.active !== false)
              .map((v) => {
                const busy = vehicleDisabled(v);
                return { value: v.id, label: `${v.name} (${v.plate})${busy ? ` ${t.routeBuilderPage.vehicleBusy}` : ''}`, disabled: busy };
              })}
            value={createForm.vehicleId || ''}
            onChange={(e) => {
              const val = e.target.value;
              setCreateForm((p) => ({ ...p, vehicleId: val }));
            }}
          />
        </div>

        <FieldSelect
          label={t.routeBuilderPage.departureDepotLabel}
          placeholder={t.routeBuilderPage.selectDepot}
          options={depots.map((d) => ({ value: d.id, label: d.name }))}
          value={createForm.depotId || ''}
          onChange={(e) => {
            const val = e.target.value;
            setCreateForm((p) => ({ ...p, depotId: val }));
          }}
        />
      </div>
    </AppModal>
  );
}
