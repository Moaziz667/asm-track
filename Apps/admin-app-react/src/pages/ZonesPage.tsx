

import { useCallback, useEffect, useMemo, useState, Suspense } from 'react';
import { lazy as dynamic } from 'react';
import { z } from 'zod';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { useLocaleStore } from '@/lib/i18n';
import { useT, getCopy } from '@/lib/LocaleContext';
import { AddButton } from '@/components/ui/AddButton';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useDensity } from '@/hooks/useDensity';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import type { ColumnDef } from '@/hooks/useColumnSettings';
import {
  IconAlertTriangle, IconMapPin, IconPencil, IconPlus,
  IconRefresh, IconScan, IconTrash, IconX, IconWorld,
  IconLayoutDashboard, IconPoint
} from '@tabler/icons-react';
import { isReadOnlyRole, getCurrentRole } from '@/lib/auth';
import type { Zone } from '@/types';
import { cn } from '@/lib/utils';
import type { CoordMap } from '@/components/ZoneSelectorMap';
import { AppModal } from '@/components/overlays/AppModal';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { Button } from '@/components/ui/button';

const ZONE_TABLE_COLUMNS: ColumnDef[] = [
  { id: 'designation', label: 'Désignation', pinned: true },
  { id: 'coverage',    label: 'Couverture postale' },
  { id: 'density',     label: 'Codes' },
  { id: 'status',      label: 'Statut' },
];
import {
  useZones,
  useCreateZone,
  useUpdateZone,
  useDeleteZone,
  useSyncZones
} from '@/hooks/useZones';

const ZoneSelectorMap = dynamic(() => import('@/components/ZoneSelectorMap'));

const ZONE_COLORS = [
  '#5E6AD2', '#C7372F', '#4CAF82', '#7B6FCC', '#C4881A',
  '#2594B8', '#D45E8B', '#8CB83E', '#D4772C', '#2E8B83',
  '#6A5ACD', '#A0522D',
];

const emptyForm = {
  name: '',
  color: '#5E6AD2',
  description: '',
  postalCodes: [] as string[],
  isActive: true,
  geometry: undefined as string | undefined,
};

export default function ZonesPage() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const role = getCurrentRole();
  const readOnly = isReadOnlyRole(role);

  const { density, setDensity } = useDensity('zones', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } = useColumnSettings('zones', ZONE_TABLE_COLUMNS);

  const ZONE_ROW_H: Record<typeof density, string> = {
    compact:     'h-9',
    comfortable: 'h-12',
    spacious:    'h-16',
  };

  // TanStack Query Hooks
  const { data: zones = [], isLoading: loading, refetch: fetchZones } = useZones();
  const createZoneMutation = useCreateZone();
  const updateZoneMutation = useUpdateZone();
  const deleteZoneMutation = useDeleteZone();
  const syncZonesMutation = useSyncZones();

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(emptyForm);
  // zod validation for the single text input; postalCodes/geometry are map-driven
  // (imperative) so their checks stay as pre-submit guards below, not RHF fields.
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const nameSchema = useMemo(() => z.string().trim().min(1, t.validation.required), [t]);
  const [knownCoords, setKnownCoords] = useState<CoordMap>({});
  const [manualCode, setManualCode] = useState('');
  const [addingManual, setAddingManual] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Zone | null>(null);

  const openCreate = () => {
    if (readOnly) return;
    const usedColors = new Set(zones.map(z => z.color));
    const autoColor = ZONE_COLORS.find(c => !usedColors.has(c)) ?? ZONE_COLORS[0];
    setEditingId(null);
    setForm({ ...emptyForm, color: autoColor });
    setKnownCoords({});
    setManualCode('');
    setNameError(undefined);
    setEditorOpen(true);
  };

  const openEdit = (zone: Zone) => {
    if (readOnly) return;
    setNameError(undefined);
    setEditingId(zone.id);
    setForm({
      name: zone.name,
      color: zone.color ?? '#2563eb',
      description: zone.description ?? '',
      postalCodes: zone.postalCodes ?? [],
      isActive: zone.isActive,
      geometry: zone.geometry,
    });
    setKnownCoords({});
    setManualCode('');
    setEditorOpen(true);
  };

  const conflictCodes = useMemo(() => {
    const otherCodes = new Set(
      zones.filter(z => z.id !== editingId).flatMap(z => z.postalCodes ?? [])
    );
    return form.postalCodes.filter(c => otherCodes.has(c));
  }, [zones, form.postalCodes, editingId]);

  const addManualCode = async () => {
    const code = manualCode.trim();
    if (!code) return;
    if (form.postalCodes.includes(code)) {
      return showErrorToast(null, 'errorZoneCodeAlreadyAdded');
    }
    setAddingManual(true);
    try {
      const res = await fetch(`https://nominatim.openstreetmap.org/search?format=json&postalcode=${encodeURIComponent(code)}&country=Tunisia&limit=1`);
      const data = await res.json();
      if (data?.[0]) {
        const lat = parseFloat(data[0].lat);
        const lng = parseFloat(data[0].lon);
        const name = data[0].display_name?.split(',')[0]?.trim();
        setKnownCoords(prev => ({ ...prev, [code]: { lat, lng, name } }));
        setForm(p => ({ ...p, postalCodes: [...p.postalCodes, code] }));
        setManualCode('');
      } else {
        showErrorToast(null, 'errorZoneCodeLookupFailed');
      }
    } catch (err) {
      showErrorToast(err, 'errorNetworkError');
    }
    finally { setAddingManual(false); }
  };

  const togglePostalCode = (code: string) => {
    setForm((p) => ({
      ...p,
      postalCodes: p.postalCodes.includes(code)
        ? p.postalCodes.filter((c) => c !== code)
        : [...p.postalCodes, code],
    }));
  };

  const save = async () => {
    const nameCheck = nameSchema.safeParse(form.name);
    if (!nameCheck.success) {
      setNameError(nameCheck.error.issues[0].message);
      return;
    }
    setNameError(undefined);
    if (form.postalCodes.length === 0) {
      return showErrorToast(null, 'errorZoneMinPostalCodesRequired');
    }
    if (conflictCodes.length > 0) {
      return showErrorToast(null, 'errorZoneConflictingCodes');
    }

    const payload = {
      name: form.name.trim(),
      color: form.color || null,
      description: form.description.trim() || null,
      cities: [],
      postalCodes: form.postalCodes,
      isActive: form.isActive,
      geometry: form.geometry,
    };
    try {
      if (editingId) {
        await updateZoneMutation.mutateAsync({ id: editingId, payload });
      } else {
        await createZoneMutation.mutateAsync(payload);
      }
      setEditorOpen(false);
    } catch (err: any) {
      // Errors are handled by query mutation callbacks
    }
  };

  const syncZones = async () => {
    try {
      await syncZonesMutation.mutateAsync();
    } catch (err) {
      // Errors are handled by query mutation callbacks
    }
  };

  const doDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteZoneMutation.mutateAsync(deleteTarget.id);
      setDeleteTarget(null);
    } catch (err) {
      // Errors are handled by query mutation callbacks
    }
  };

  const saving = createZoneMutation.isPending || updateZoneMutation.isPending;
  const syncing = syncZonesMutation.isPending;
  const deleting = deleteZoneMutation.isPending;

  const stats = useMemo(() => {
    const active = zones.filter(z => z.isActive).length;
    const codes = zones.reduce((acc, z) => acc + (z.postalCodes?.length || 0), 0);
    return { total: zones.length, active, codes };
  }, [zones]);

  return (
    <>
    <div className="flex flex-col overflow-visible lg:overflow-hidden h-auto lg:h-[calc(100dvh-56px)]" style={{ background: 'var(--app-bg)' }}>

      {/* ── Compact Action Bar ── */}
      <div
        className="flex items-center gap-3 px-4 h-11 shrink-0"
        style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}
      >
        <div className="flex items-center gap-3">
          <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
            {t.zonesPage.activeSectors}: <span className="font-mono font-bold" style={{ color: 'var(--text-primary)' }}>{stats.active}/{stats.total}</span>
          </span>
          <span className="w-px h-3 bg-[var(--border)]" />
          <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
            {t.zonesPage.postalPoints}: <span className="font-mono font-bold" style={{ color: 'var(--text-primary)' }}>{stats.codes}</span>
          </span>
        </div>
        <div className="ml-auto flex items-center gap-2">
          {!readOnly && (
            <AddButton label={t.zonesPage.newZoneButton} onClick={openCreate} />
          )}
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => syncZones()}
            disabled={syncing}
            title={t.zonesPage.syncTooltip}
            style={{ background: 'var(--app-bg)' }}
          >
            <IconRefresh size={14} style={{ color: 'var(--brand)' }} className={syncing ? 'animate-spin' : ''} />
          </button>
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
            onClick={() => fetchZones()}
            disabled={loading}
            style={{ background: 'var(--app-bg)' }}
          >
            <IconRefresh size={14} className={loading ? 'animate-spin' : ''} />
          </button>
          <DisplaySettingsDropdown
            columns={orderedColumns}
            visibleIds={visibleIds}
            onToggle={toggleColumn}
            onReorder={moveColumn}
            onReset={resetColumns}
            density={density}
            onDensityChange={setDensity}
          />
        </div>
      </div>

      {/* ── Registry ── */}
      <div className="flex flex-1 overflow-hidden min-w-0" style={{ background: 'var(--app-bg)' }}>
        <div className="overflow-y-auto flex-1">
            {loading ? (
              <div className="flex items-center justify-center h-[400px]">
                <svg className="animate-spin h-8 w-8" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                </svg>
              </div>
            ) : zones.length === 0 ? (
              <div className="flex flex-col items-center py-[120px] gap-2">
                <IconWorld size={48} style={{ color: 'var(--border)' }} />
                <p className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.noZones}</p>
              </div>
            ) : (
            <div className="overflow-x-auto">
              <table className="w-full border-collapse min-w-[800px]">
                <thead className="sticky top-0 z-10" style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}>
                  <tr style={{ borderBottom: '1px solid var(--border)' }}>
                    <th className="w-2 p-0" style={{ background: 'var(--app-bg)' }}></th>
                    {orderedColumns.map(col => visibleIds.has(col.id) && (
                      <th key={col.id} className="text-xs font-semibold py-3 text-left px-4" style={{ color: 'var(--text-muted)', background: 'var(--app-bg)' }}>
                        {col.label}
                      </th>
                    ))}
                    <th className="w-24" style={{ background: 'var(--app-bg)' }}></th>
                  </tr>
                </thead>
                <tbody>
                  {zones.map((zone) => {
                    const displayed = zone.postalCodes?.slice(0, 10) ?? [];
                    const extra = (zone.postalCodes?.length ?? 0) - 10;
                    return (
                      <tr key={zone.id} className={cn('group transition-colors hover:bg-[var(--hover-bg)]', ZONE_ROW_H[density])} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td className="p-0">
                          <div className="w-[3px] h-6 rounded-r-md" style={{ background: zone.color }} />
                        </td>
                        {orderedColumns.map(col => {
                          if (!visibleIds.has(col.id)) return null;
                          if (col.id === 'designation') return (
                            <td key="designation" className="px-4 py-2">
                              <div className="flex items-center gap-3">
                                <div className="w-3 h-3 rounded-full shrink-0" style={{ background: zone.color ?? 'var(--text-muted)' }} />
                                <div>
                                  <p className="text-xs font-bold" style={{ color: 'var(--text-primary)' }}>{zone.name}</p>
                                  {zone.description && <p className="text-2xs italic truncate max-w-[200px]" style={{ color: 'var(--text-muted)' }}>{zone.description}</p>}
                                </div>
                              </div>
                            </td>
                          );
                          if (col.id === 'coverage') return (
                            <td key="coverage" className="px-4 py-2">
                              <div className="flex flex-wrap gap-1 max-w-[400px]">
                                {displayed.map((pc) => (
                                  <span key={pc} className="text-2xs font-bold px-1.5 py-0.5 rounded-md" style={{ border: '1px solid var(--border)', color: 'var(--text-muted)' }}>
                                    {pc}
                                  </span>
                                ))}
                                {extra > 0 && <span className="text-xs font-semibold font-mono" style={{ color: 'var(--brand)' }}>{t.zonesPage.extraCodes.replace('{count}', extra.toString())}</span>}
                              </div>
                            </td>
                          );
                          if (col.id === 'density') return (
                            <td key="density" className="px-4 py-2 text-center">
                              <p className="text-xs font-extrabold font-mono" style={{ color: 'var(--text-primary)' }}>{zone.postalCodes?.length || 0}</p>
                            </td>
                          );
                          if (col.id === 'status') return (
                            <td key="status" className="px-4 py-2 text-center">
                              <StatusBadge
                                status={zone.isActive ? 'ACTIVE' : 'INACTIVE'}
                                label={zone.isActive ? t.zonesPage.statusOperational : t.zonesPage.statusInactive}
                                size="sm"
                              />
                            </td>
                          );
                          return null;
                        })}
                        <td className="px-4 py-3">
                          {!readOnly && (
                            <div className="flex gap-1 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                              <button
                                type="button"
                                className="w-7 h-7 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
                                onClick={() => openEdit(zone)}
                              >
                                <IconPencil size={14} />
                              </button>
                              <button
                                type="button"
                                className="w-7 h-7 flex items-center justify-center rounded-md border border-[var(--border)] text-red-500 hover:bg-red-50 transition-colors"
                                onClick={() => setDeleteTarget(zone)}
                              >
                                <IconTrash size={14} />
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            )}
          </div>
        </div>
      </div>

      {/* ── Delete Confirm Modal ─────────────────────────────────────────────── */}
      <ConfirmModal
        open={deleteTarget !== null}
        title={t.zonesPage.deleteTitle}
        description={deleteTarget ? t.zonesPage.deleteDescription.replace('{zoneName}', deleteTarget.name) : ''}
        confirmLabel={t.zonesPage.deleteButton}
        cancelLabel={t.zonesPage.deleteCancel}
        variant="danger"
        loading={deleting}
        onConfirm={doDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* ── Technical Sector Certifier ─────────────────────── */}
      <AppModal
        open={editorOpen}
        onClose={() => setEditorOpen(false)}
        title={t.zonesPage.modalTitle}
        subtitle={t.zonesPage.modalSubtitle}
        size="xl"
        className="max-w-[95vw] max-h-[90dvh]"
        footer={
          <div className="flex items-center justify-end gap-2">
            <button
              type="button"
              className="px-4 h-8 text-xs font-semibold rounded-md hover:bg-[var(--hover-bg)] transition-colors"
              style={{ color: 'var(--text-muted)' }}
              onClick={() => setEditorOpen(false)}
            >
              {t.zonesPage.cancelButton}
            </button>
            <Button
              className="min-w-[100px] h-8 font-semibold text-xs rounded-md"
              onClick={save}
              disabled={saving}
            >
              {saving ? (
                <svg className="animate-spin h-4 w-4 mr-1" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                </svg>
              ) : null}
              {t.zonesPage.saveButton}
            </Button>
          </div>
        }
      >
        <div className="flex flex-col lg:flex-row gap-0" style={{ height: 'calc(90vh - 140px)' }}>
          {/* Config Rail */}
          <div className="w-full lg:w-[340px] shrink-0 h-[40%] lg:h-full overflow-y-auto flex flex-col gap-0 border-b lg:border-b-0 lg:border-r border-[var(--border)]">
            <div className="p-6 flex flex-col gap-6">
              {/* Name */}
              <div>
                <label className="block text-xs font-semibold mb-2" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.sectorNameLabel}</label>
                <input
                  className="w-full h-9 px-3 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
                  style={{ background: 'var(--app-bg)', border: `1px solid ${nameError ? 'var(--danger)' : 'var(--border)'}`, color: 'var(--text-primary)' }}
                  placeholder={t.zonesPage.sectorNamePlaceholder}
                  value={form.name}
                  onChange={(e) => { setForm((p) => ({ ...p, name: e.target.value })); if (nameError) setNameError(undefined); }}
                  aria-invalid={!!nameError || undefined}
                  required
                />
                {nameError && <p className="text-xs text-[var(--danger)] mt-1" role="alert">{nameError}</p>}
              </div>

              {/* Color picker */}
              <div>
                <label className="block text-xs font-semibold mb-2" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.zoneColorLabel}</label>
                <div className="flex flex-wrap gap-1.5 mb-2">
                  {ZONE_COLORS.map(c => (
                    <button
                      key={c}
                      type="button"
                      className={cn("w-6 h-6 rounded-md transition-transform hover:scale-110", form.color === c ? 'ring-2 ring-offset-1 ring-[var(--text-primary)]' : '')}
                      style={{ background: c }}
                      onClick={() => setForm(p => ({ ...p, color: c }))}
                    />
                  ))}
                </div>
                <input
                  type="color"
                  className="w-full h-8 rounded-md cursor-pointer"
                  style={{ border: '1px solid var(--border)' }}
                  value={form.color}
                  onChange={e => setForm(p => ({ ...p, color: e.target.value }))}
                />
              </div>

              {/* Description */}
              <div>
                <label className="block text-xs font-semibold mb-2" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.operationalNotesLabel}</label>
                <textarea
                  rows={3}
                  className="w-full px-3 py-2 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)] resize-none"
                  style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                  placeholder={t.zonesPage.operationalNotesPlaceholder}
                  value={form.description}
                  onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))}
                />
              </div>

              {/* Active toggle */}
              <div className="flex items-center justify-between p-3 rounded-lg" style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}>
                <p className="text-xs font-bold" style={{ color: 'var(--text-primary)' }}>{t.zonesPage.dispatchAvailability}</p>
                <input
                  type="checkbox"
                  role="switch"
                  checked={form.isActive}
                  onChange={(e) => {
                    const checked = e.target.checked;
                    setForm((p) => ({ ...p, isActive: checked }));
                  }}
                  className="w-9 h-5 rounded-full cursor-pointer accent-[var(--brand)]"
                />
              </div>

              {/* Postal codes */}
              <div className="flex flex-col gap-2">
                <p className="text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.postalCoverageLabel}</p>
                <div className="flex gap-2">
                  <input
                    className="flex-1 h-9 px-3 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
                    style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                    placeholder={t.zonesPage.postalCodePlaceholder}
                    value={manualCode}
                    onChange={e => setManualCode(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && addManualCode()}
                  />
                  <button
                    type="button"
                    className="w-9 h-9 flex items-center justify-center rounded-md border border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
                    onClick={addManualCode}
                    disabled={addingManual}
                  >
                    {addingManual
                      ? <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/></svg>
                      : <IconPlus size={14} />}
                  </button>
                </div>

                {conflictCodes.length > 0 && (
                  <div className="p-2 rounded-lg" style={{ border: '1px solid rgba(255,87,34,0.3)', background: 'var(--app-bg)' }}>
                    <p className="text-xs font-semibold mb-1" style={{ color: 'var(--brand)' }}>{t.zonesPage.conflictsDetected}</p>
                    <p className="text-2xs mb-2" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.conflictWarning}</p>
                    <button
                      type="button"
                      className="w-full h-6 text-xs font-semibold rounded-md bg-[var(--brand)] text-white"
                      onClick={() => setForm(p => ({ ...p, postalCodes: p.postalCodes.filter(c => !conflictCodes.includes(c)) }))}
                    >
                      {t.zonesPage.removeConflicts}
                    </button>
                  </div>
                )}

                <div className="p-3 rounded-lg flex items-center justify-between" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                  <p className="text-xl font-black font-mono" style={{ color: 'var(--text-primary)' }}>{form.postalCodes.length}</p>
                  <IconScan size={18} style={{ color: 'var(--brand)' }} />
                </div>
                <div className="flex items-center justify-between">
                  <p className="text-2xs" style={{ color: 'var(--text-muted)' }}>{t.zonesPage.activePostalPoints}</p>
                  {form.postalCodes.length > 0 && (
                    <button
                      type="button"
                      onClick={() => setForm(p => ({ ...p, postalCodes: [] }))}
                      className="text-2xs font-bold text-[var(--text-muted)] hover:text-red-500 transition-colors"
                    >
                      Désélectionner tout
                    </button>
                  )}
                </div>

                <div className="max-h-[200px] overflow-y-auto">
                  <div className="flex flex-wrap gap-1">
                    {form.postalCodes.map(pc => (
                      <span key={pc} className="inline-flex items-center gap-1 text-2xs font-bold px-1.5 py-0.5 rounded-md" style={{ border: '1px solid var(--border)', color: 'var(--text-muted)' }}>
                        {pc}
                        <button type="button" aria-label={`${t.actions?.remove ?? 'Retirer'} ${pc}`} className="hover:text-red-500 transition-colors" onClick={() => togglePostalCode(pc)}>
                          <IconX size={8} />
                        </button>
                      </span>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Map Selector */}
          <div className="flex-1 h-[60%] lg:h-full relative" style={{ background: 'var(--app-bg)' }}>
            <Suspense fallback={
              <div className="flex items-center justify-center h-[600px]"
                style={{ background: 'var(--app-bg)', border: '1px dashed var(--border)', borderRadius: 2 }}>
                <div className="flex flex-col items-center gap-2">
                  <svg className="animate-spin h-4 w-4 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                  </svg>
                  <span className="text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
                    {getCopy(locale).zonesPage.mapInitializing}
                  </span>
                </div>
              </div>
            }>
              <ZoneSelectorMap
              selectedCodes={form.postalCodes}
              geometry={form.geometry}
              color={form.color}
              zoneName={form.name || 'Zone sans nom'}
              externalCoords={knownCoords}
              onGeometryChange={(geo) => setForm(p => ({ ...p, geometry: geo }))}
              onPostalCodesChange={(codes) => setForm(p => ({ ...p, postalCodes: codes }))}
              onCoordsFound={(coords) => setKnownCoords(prev => ({ ...prev, ...coords }))}
            />
            </Suspense>
          </div>
        </div>
      </AppModal>
    </>
  );
}

