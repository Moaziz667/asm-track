'use client';

import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { IconRoute } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { FieldInput, FieldSelect } from '@/components/ui/field';
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
    createRoute,
    creating,
  } = rb;

  const drivers = availableDrivers.length ? availableDrivers : allDrivers;
  const vehicles = availableVehicles.length ? availableVehicles : allVehicles;

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
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FieldInput
            label={t.routeBuilderPage.routeNameLabel}
            placeholder={t.placeholders.searchGeneric}
            value={createForm.name}
            onChange={(e) => {
              const val = e.currentTarget.value;
              setCreateForm((p) => ({ ...p, name: val }));
            }}
          />
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
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FieldSelect
            label={t.routeBuilderPage.assignedDriverLabel}
            placeholder={t.routeBuilderPage.selectPlaceholder}
            options={drivers.map((d) => ({ value: d.id, label: d.name }))}
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
              .map((v) => ({ value: v.id, label: `${v.name} (${v.plate})` }))}
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
