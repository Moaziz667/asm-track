'use client';

import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { IconCalendar, IconDeviceFloppy } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { Button } from '@/components/ui/button';
import { useRouteBuilderContext } from '../../hooks/useRouteBuilder';

export function SettingsModal() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const rb = useRouteBuilderContext();
  const {
    settingsOpen,
    selectedRoute,
    setSettingsOpen,
    settingsForm,
    setSettingsForm,
    drivers,
    vehicles,
    depots,
    isVehicleBusy,
    saveRouteSettings,
    savingSettings,
  } = rb;

  if (!selectedRoute) return null;

  return (
    <AppModal
      opened={settingsOpen}
      onClose={() => setSettingsOpen(false)}
      subtitle={`${t.routeBuilderPage.settingsSubtitlePrefix} — ${selectedRoute.name}`}
      title={t.routeBuilderPage.settingsTitle}
      size="lg"
      footer={
        <div className="w-full flex items-center justify-between">
          <div className="flex items-center gap-1.5 text-xs text-[var(--text-muted)] font-mono font-medium">
            <IconCalendar size={13} />
            <span>{t.routeBuilderPage.settingsWarning}</span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => setSettingsOpen(false)}>
              {t.actions.cancel}
            </Button>
            <Button
              size="sm"
              loading={savingSettings}
              onClick={() => void saveRouteSettings()}
              className="bg-[var(--brand-orange)] hover:opacity-90 font-semibold"
            >
              <IconDeviceFloppy size={14} className="mr-1.5" />
              {t.actions.save}
            </Button>
          </div>
        </div>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FieldInput
            label={t.routeBuilderPage.routeNameReadOnly}
            value={selectedRoute.name}
            readOnly
            disabled
          />
          <FieldInput
            type="date"
            label={t.routeBuilderPage.operationDateLabel}
            value={settingsForm.date}
            onChange={(e) => {
              const val = e.currentTarget.value;
              setSettingsForm((p) => ({ ...p, date: val }));
            }}
          />
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FieldSelect
            label={t.routeBuilderPage.assignedDriverLabel}
            placeholder={t.routeBuilderPage.selectPlaceholder}
            options={drivers.map((d) => ({ value: d.id, label: d.name }))}
            value={settingsForm.driverId || ''}
            onChange={(e) => {
              const val = e.target.value;
              setSettingsForm((p) => ({ ...p, driverId: val }));
            }}
          />
          <FieldSelect
            label={t.routeBuilderPage.vehicleLabel}
            placeholder={t.routeBuilderPage.selectPlaceholder}
            options={vehicles
              .filter((v) => v.active !== false)
              .map((v) => ({
                value: v.id,
                label: `${v.name} (${v.plate})${v.id !== selectedRoute.vehicleId && isVehicleBusy(v) ? t.routeBuilderPage.vehicleBusy : ''}`,
                disabled: v.id !== selectedRoute.vehicleId && isVehicleBusy(v),
              }))}
            value={settingsForm.vehicleId || ''}
            onChange={(e) => {
              const val = e.target.value;
              setSettingsForm((p) => ({ ...p, vehicleId: val }));
            }}
          />
        </div>

        <FieldSelect
          label={t.routeBuilderPage.logisticsDepotLabel}
          placeholder={t.routeBuilderPage.selectPlaceholder}
          options={depots.map((d) => ({ value: d.id, label: d.name }))}
          value={settingsForm.depotId || ''}
          onChange={(e) => {
            const val = e.target.value;
            setSettingsForm((p) => ({ ...p, depotId: val }));
          }}
        />
      </div>
    </AppModal>
  );
}
