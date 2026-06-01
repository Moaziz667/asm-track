

import { useCallback, useEffect, useState } from 'react';
import { lazy as dynamic } from 'react';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { useLocaleStore } from '@/lib/i18n';
import { useDepots, useCreateDepot, useUpdateDepot, useDeleteDepot } from '@/hooks/useDepots';
import { useT } from '@/lib/LocaleContext';
import {
  IconCurrentLocation, IconMapPin, IconPencil, IconPlus,
  IconRefresh, IconTrash, IconBuildingWarehouse,
  IconCircleFilled, IconPoint, IconWorld, IconLayoutDashboard
} from '@tabler/icons-react';
import { isReadOnlyRole, getCurrentRole } from '@/lib/auth';
import type { Depot } from '@/types';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { AppModal } from '@/components/overlays/AppModal';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';

// ─── Map Components (SSR Disabled) ───────────────────────────────────────────

const DepotPinMap = dynamic(() => import('@/components/DepotPinMap'));

const DepotsOverviewMap = dynamic(() => import('@/components/DepotsOverviewMap'));

// ─── Types ────────────────────────────────────────────────────────────────────

type DepotForm = {
  name: string;
  address: string;
  latitude: string;
  longitude: string;
  isActive: boolean;
};

const EMPTY_FORM: DepotForm = { name: '', address: '', latitude: '', longitude: '', isActive: true };

// ─── Spinner ──────────────────────────────────────────────────────────────────

function Spinner({ size = 24 }: { size?: number }) {
  return (
    <svg className="animate-spin text-[var(--brand)]" width={size} height={size} viewBox="0 0 24 24" fill="none">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
    </svg>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function DepotsPage() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const role = getCurrentRole();
  const readOnly = isReadOnlyRole(role);

  const { data: depots = [], isLoading: loading, refetch: fetchDepots } = useDepots();
  const createMutation = useCreateDepot();
  const updateMutation = useUpdateDepot();
  const deleteMutation = useDeleteDepot();

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [geocoding, setGeocoding] = useState(false);
  const [form, setForm] = useState<DepotForm>(EMPTY_FORM);

  // Confirm delete state
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Depot | null>(null);

  const openCreate = () => {
    if (readOnly) return;
    setEditingId(null);
    setForm(EMPTY_FORM);
    setEditorOpen(true);
  };

  const openEdit = (depot: Depot) => {
    if (readOnly) return;
    setEditingId(depot.id);
    setForm({
      name: depot.name,
      address: depot.address ?? '',
      latitude: String(depot.latitude),
      longitude: String(depot.longitude),
      isActive: depot.isActive,
    });
    setEditorOpen(true);
  };

  const geocodeAddress = useCallback(async () => {
    const addr = form.address.trim();
    if (!addr) {
      return showErrorToast(null, 'errorDepotAddressRequired');
    }
    setGeocoding(true);
    try {
      const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${encodeURIComponent(addr)}`;
      const res = await fetch(url);
      const data = await res.json();
      if (data && data[0]) {
        setForm(p => ({ ...p, latitude: Number(data[0].lat).toFixed(7), longitude: Number(data[0].lon).toFixed(7) }));
        showSuccessToast('successDepotGeolocate');
      } else {
        showErrorToast(null, 'errorDepotGeolocateFailed');
      }
    } catch (err: any) {
      showErrorToast(err, 'errorDepotGeolocateFailed');
    } finally {
      setGeocoding(false);
    }
  }, [form.address]);

  const reverseGeocode = useCallback(async (lat: number, lng: number) => {
    setGeocoding(true);
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lng}`;
      const res = await fetch(url);
      const data = await res.json();
      if (data && data.display_name) {
        setForm(p => ({ ...p, address: data.display_name }));
        showSuccessToast('successDepotSync');
      }
    } catch (err: any) {
      showErrorToast(err, 'errorDepotSyncFailed');
    } finally {
      setGeocoding(false);
    }
  }, []);

  const save = async () => {
    if (!form.name.trim()) {
      return showErrorToast(null, 'errorDepotNameRequired');
    }
    try {
      const payload = {
        name: form.name.trim(),
        address: form.address.trim() || undefined,
        latitude: parseFloat(form.latitude),
        longitude: parseFloat(form.longitude),
        isActive: form.isActive,
      };
      if (editingId) {
        await updateMutation.mutateAsync({ id: editingId, payload });
      } else {
        await createMutation.mutateAsync(payload);
      }
      setEditorOpen(false);
    } catch {
      // toast shown by mutation hook
    }
  };

  const deleteDepot = (depot: Depot) => {
    setPendingDelete(depot);
    setConfirmOpen(true);
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    try {
      await deleteMutation.mutateAsync(pendingDelete.id);
      setConfirmOpen(false);
      setPendingDelete(null);
    } catch {
      // toast shown by mutation hook
    }
  };

  const saving = createMutation.isPending || updateMutation.isPending;
  const confirmDeleting = deleteMutation.isPending;

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', background: 'var(--app-bg)' }}>

      {/* ── Header ── */}
      <div
        className="sticky top-0 z-20 min-h-16 h-auto lg:h-16 py-4 lg:py-0 flex items-center"
        style={{ background: 'var(--surface)', borderBottom: '1px solid var(--border)' }}
      >
        <div className="px-6 w-full flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4 sm:gap-8">
            <div>
              <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.depotsPage.pageSubtitle}</span>
              <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
                {t.depotsPage.pageTitle} <span className="text-[var(--brand)]">{t.depotsPage.pageTitleBrand}</span>
              </h1>
            </div>
            <div className="hidden sm:block w-px h-6 bg-[var(--border)]" />
            <p className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
              {t.depotsPage.depotsCount.replace('{count}', depots.length.toString())}
            </p>
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto justify-between sm:justify-start">
            {!readOnly && (
              <Button
                className="font-bold text-[11px] rounded-md"
                style={{ background: 'var(--brand)', color: 'white', border: 'none' }}
                onClick={openCreate}
              >
                <IconPlus size={14} />
                {t.depotsPage.newHubButton}
              </Button>
            )}
            <button
              type="button"
              onClick={() => fetchDepots()}
              className="w-9 h-9 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
              style={{ background: 'var(--app-bg)' }}
            >
              {loading ? <Spinner size={18} /> : <IconRefresh size={18} style={{ color: 'var(--text-muted)' }} />}
            </button>
          </div>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="overflow-y-auto" style={{ height: 'calc(100vh - 128px)' }}>
        <div className="flex flex-col gap-8 max-w-[1400px] mx-auto p-4 md:p-8">

          {/* ── Overview Map ── */}
          <div
            className="rounded-lg overflow-hidden shadow-sm"
            style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}
          >
            {/* Map header */}
            <div
              className="px-5 py-3 flex items-center justify-between"
              style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
            >
              <div className="flex items-center gap-2">
                <IconWorld size={16} style={{ color: 'var(--brand)' }} />
                <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
                  {t.depotsPage.mapTitle}
                </span>
              </div>
            </div>

            {/* Map + legend overlay */}
            <div className="relative">
              <DepotsOverviewMap depots={depots} height={400} />
              <div className="absolute bottom-4 left-4 z-[1000]">
                <div
                  className="px-3 py-2 rounded-md opacity-90"
                  style={{
                    border: '1px solid var(--border)',
                    background: 'var(--surface)',
                    backdropFilter: 'blur(4px)',
                  }}
                >
                  <div className="flex items-center gap-4">
                    <div>
                      <p className="text-[11px] font-[600]" style={{ color: 'var(--text-muted)' }}>
                        {t.depotsPage.totalHubs}
                      </p>
                      <p className="text-[13px] font-[600] font-mono" style={{ color: 'var(--text-primary)' }}>
                        {depots.length}
                      </p>
                    </div>
                    <div className="w-px h-5 bg-[var(--border)]" />
                    <div>
                      <p className="text-[11px] font-[600]" style={{ color: 'var(--text-muted)' }}>
                        {t.depotsPage.operationalHubs}
                      </p>
                      <p className="text-[13px] font-[600] font-mono text-[#2D8A5E]">
                        {depots.filter(d => d.isActive).length}
                      </p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* ── Registry ── */}
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-2 mb-1">
              <IconLayoutDashboard size={14} style={{ color: 'var(--brand)' }} />
              <span className="text-[11px] font-[600]" style={{ color: 'var(--text-muted)' }}>
                {t.depotsPage.registryTitle}
              </span>
            </div>

            {loading ? (
              <div className="flex items-center justify-center h-[200px]">
                <Spinner size={28} />
              </div>
            ) : depots.length === 0 ? (
              <div
                className="rounded-lg p-[60px] flex flex-col items-center gap-2"
                style={{ border: '1px dashed var(--border)', background: 'var(--surface)' }}
              >
                <IconBuildingWarehouse size={40} style={{ color: 'var(--border)' }} />
                <p className="text-[11px] font-bold" style={{ color: 'var(--text-muted)' }}>
                  {t.depotsPage.noDepots}
                </p>
              </div>
            ) : (
              <div
                className="rounded-lg overflow-hidden shadow-sm"
                style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}
              >
                <div className="overflow-x-auto">
                  <div className="min-w-[800px] lg:min-w-0">
                    {/* Table header */}
                    <div
                      className="grid grid-cols-[2fr_3fr_2fr_1fr_80px] px-4 py-3"
                      style={{ background: 'var(--app-bg)', borderBottom: '1px solid var(--border)' }}
                    >
                      {[t.depotsPage.headerDesignation, t.depotsPage.headerLocation, t.depotsPage.headerCoordinates, t.depotsPage.headerStatus, ''].map((h, i) => (
                        <div
                          key={i}
                          className={cn(
                             'text-[11px] font-[600]',
                             i === 2 || i === 3 ? 'text-center' : '',
                             i === 4 ? 'text-right' : ''
                          )}
                          style={{ color: 'var(--text-muted)' }}
                        >
                          {h}
                        </div>
                      ))}
                    </div>

                    {/* Table rows */}
                    {depots.map((depot) => (
                      <div
                        key={depot.id}
                        className="grid grid-cols-[2fr_3fr_2fr_1fr_80px] px-4 py-3 items-center group transition-colors hover:bg-[var(--hover-bg)]"
                        style={{ borderBottom: '1px solid var(--border)' }}
                      >
                        {/* Name */}
                        <div className="flex items-center gap-3">
                          <div
                            className="w-[30px] h-[30px] flex items-center justify-center rounded-md"
                            style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}
                          >
                            <IconBuildingWarehouse size={14} style={{ color: 'var(--brand)' }} />
                          </div>
                          <p className="text-[11px] font-bold" style={{ color: 'var(--text-primary)' }}>
                            {depot.name}
                          </p>
                        </div>

                        {/* Address */}
                        <p
                          className="text-[11px] max-w-[300px] truncate"
                          style={{ color: 'var(--text-muted)' }}
                        >
                          {depot.address || '—'}
                        </p>

                        {/* Coordinates */}
                        <div className="flex justify-center">
                          <span
                            className="text-[11px] font-bold font-mono px-2 py-1 rounded-md inline-block"
                            style={{ color: 'var(--text-primary)', background: 'var(--app-bg)' }}
                          >
                            {depot.latitude.toFixed(5)}, {depot.longitude.toFixed(5)}
                          </span>
                        </div>

                        {/* Status badge */}
                        <div className="flex justify-center">
                          <span
                            className="text-[11px] font-bold px-2 py-0.5 rounded-md flex items-center gap-1.5 border"
                            style={{
                              color: depot.isActive ? '#2D8A5E' : '#6B7280',
                              background: depot.isActive ? 'rgba(76,175,130,0.09)' : 'rgba(138,143,152,0.08)',
                              borderColor: depot.isActive ? 'rgba(76,175,130,0.15)' : 'rgba(138,143,152,0.15)',
                            }}
                          >
                            <span
                              className="inline-block w-1.5 h-1.5 rounded-full"
                              style={{ background: depot.isActive ? '#2D8A5E' : '#6B7280' }}
                            />
                            {depot.isActive ? t.depotsPage.statusOperational : t.depotsPage.statusInactive}
                          </span>
                        </div>

                        {/* Actions */}
                        <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                          {!readOnly && (
                            <>
                              <button
                                type="button"
                                onClick={() => openEdit(depot)}
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
                              >
                                <IconPencil size={14} />
                              </button>
                              <button
                                type="button"
                                onClick={() => deleteDepot(depot)}
                                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[#EF4444] hover:bg-[var(--hover-bg)] transition-colors"
                              >
                                <IconTrash size={14} />
                              </button>
                            </>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── Config Modal ── */}
      <AppModal
        open={editorOpen}
        onClose={() => setEditorOpen(false)}
        size="lg"
        title={t.depotsPage.modalTitle}
        subtitle={t.depotsPage.modalSubtitle}
        footer={
          <>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setEditorOpen(false)}
              className="text-[11px] font-semibold rounded-[2px]"
            >
              {t.depotsPage.cancelButton}
            </Button>
            <Button
              size="sm"
              onClick={save}
              disabled={saving}
              className="text-[11px] font-semibold rounded-[2px]"
              style={{ background: 'var(--brand)', color: '#fff', border: 'none' }}
            >
              {saving && (
                <svg className="animate-spin -ml-0.5 mr-1.5 h-3 w-3 text-white" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
                </svg>
              )}
              {editingId ? t.depotsPage.updateButton : t.depotsPage.saveButton}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-4">
            <FieldInput
              label={t.depotsPage.nameLabel}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder={t.depotsPage.nameExample}
              required
            />
            <FieldInput
              label={t.depotsPage.addressLabel}
              value={form.address}
              onChange={(e) => setForm({ ...form, address: e.target.value })}
              placeholder={t.depotsPage.addressPlaceholder}
              rightSection={
                <button
                  type="button"
                  onClick={geocodeAddress}
                  disabled={geocoding}
                  className="flex items-center justify-center text-[var(--brand)] transition-colors disabled:opacity-50"
                >
                  {geocoding
                    ? <Spinner size={14} />
                    : <IconCurrentLocation size={14} />
                  }
                </button>
              }
            />
            <FieldInput
              label={t.depotsPage.latitudeLabel}
              value={form.latitude}
              readOnly
              className="font-mono text-[11px]"
            />
            <FieldInput
              label={t.depotsPage.longitudeLabel}
              value={form.longitude}
              readOnly
              className="font-mono text-[11px]"
            />
          </div>

          {/* Map pin selector */}
          <div>
            <p className="text-[11px] font-[600] mb-2" style={{ color: 'var(--text-muted)' }}>
              {t.depotsPage.geometricAdjustment}
            </p>
            <div
              className="rounded-[2px] overflow-hidden"
              style={{ height: 300, border: '1px solid var(--border)', background: 'var(--app-bg)' }}
            >
              <DepotPinMap
                lat={form.latitude ? parseFloat(form.latitude) : undefined}
                lng={form.longitude ? parseFloat(form.longitude) : undefined}
                onPick={(lat, lng) => {
                  setForm(p => ({ ...p, latitude: lat.toFixed(7), longitude: lng.toFixed(7) }));
                  reverseGeocode(lat, lng);
                }}
                height={300}
              />
            </div>
          </div>

          {/* Active toggle */}
          <div
            className="flex items-center justify-between px-3 py-3 rounded-lg"
            style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}
          >
            <p className="text-[11px] font-bold" style={{ color: 'var(--text-primary)' }}>
              {t.depotsPage.operationalAvailability}
            </p>
            <button
              type="button"
              role="switch"
              aria-checked={form.isActive}
              onClick={() => setForm(p => ({ ...p, isActive: !p.isActive }))}
              className={cn(
                'relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none',
                form.isActive ? 'bg-[var(--brand)]' : 'bg-[var(--border)]'
              )}
            >
              <span
                className={cn(
                  'inline-block h-3.5 w-3.5 rounded-full bg-white shadow transition-transform',
                  form.isActive ? 'translate-x-4' : 'translate-x-1'
                )}
              />
            </button>
          </div>
        </div>
      </AppModal>

      {/* ── Delete Confirm ── */}
      <ConfirmModal
        open={confirmOpen}
        title={t.depotsPage.deleteTitle}
        description={
          pendingDelete
            ? t.depotsPage.deleteDescription.replace('{hubName}', pendingDelete.name)
            : ''
        }
        confirmLabel={t.depotsPage.deleteButton}
        cancelLabel={t.depotsPage.deleteCancel}
        variant="danger"
        loading={confirmDeleting}
        onConfirm={confirmDelete}
        onCancel={() => { setConfirmOpen(false); setPendingDelete(null); }}
      />
    </div>
  );
}

