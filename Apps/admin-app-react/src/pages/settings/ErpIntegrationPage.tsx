import { useEffect, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import { useT } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { canManageSettings, getCurrentRole } from '@/lib/api/auth';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { IconDatabase, IconPlugConnected, IconPlugConnectedX, IconLock, IconAlertTriangle, IconClock, IconCircleCheck, IconCircleDot } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { tw } from '@/lib/ui/typography';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { SectionCard } from '@/components/ui/section-card';

// ── Types ──────────────────────────────────────────────────────────────────
type ErpProvider = 'NONE' | 'ODOO' | 'DUX';
type ConnStatus = 'NOT_CONFIGURED' | 'CONFIGURED' | 'CONNECTED' | 'ERROR';
interface OdooConfig {
  url?: string; db?: string; login?: string; apiKey?: string; reportId?: string;
  // legacy fields tolerated for back-compat (not shown in the UI):
  uid?: number; password?: string;
}
interface ErpSettings {
  activeErpProvider: ErpProvider;
  erpConfiguration: OdooConfig | null;
  // Persisted connection lifecycle (read-only, set by the backend on save/test).
  connectionStatus?: ConnStatus;
  lastTestedAt?: string | null;
  lastConnectedAt?: string | null;
  lastError?: string | null;
  lastTestUid?: string | null;
}
// 'dirty' = the admin edited a field since loading, so any persisted CONNECTED is stale
// until they re-test. It's a client-only overlay on top of the persisted status.
type ConnState = { status: 'idle' | 'ok' | 'fail'; uid?: string };

const EMPTY_ODOO: OdooConfig = { url: '', db: '', login: '', apiKey: '', reportId: 'stock.report_deliveryslip' };

// ── Connection-status pill — the persisted lifecycle, at a glance ────────────
type EffStatus = ConnStatus | 'STALE';

function ConnStatusBadge({ status, uid, sp }: { status: EffStatus; uid?: string; sp: Record<string, string> }) {
  const map: Record<EffStatus, { label: string; icon: React.ReactNode; fg: string; bg: string }> = {
    CONNECTED:      { label: `${sp.statusConnected ?? 'Connecté'}${uid ? ` · uid ${uid}` : ''}`, icon: <IconCircleCheck size={13} />,   fg: 'var(--success)', bg: 'color-mix(in srgb, var(--success) 14%, transparent)' },
    ERROR:          { label: sp.statusError ?? 'Échec de connexion',  icon: <IconPlugConnectedX size={13} />, fg: 'var(--danger)',  bg: 'color-mix(in srgb, var(--danger) 14%, transparent)' },
    CONFIGURED:     { label: sp.statusConfigured ?? 'Configuré · non testé', icon: <IconCircleDot size={13} />, fg: 'var(--warning)', bg: 'color-mix(in srgb, var(--warning) 14%, transparent)' },
    STALE:          { label: sp.statusStale ?? 'Modifié · à re-tester', icon: <IconAlertTriangle size={13} />, fg: 'var(--warning)', bg: 'color-mix(in srgb, var(--warning) 14%, transparent)' },
    NOT_CONFIGURED: { label: sp.statusNotConfigured ?? 'Non configuré', icon: <IconCircleDot size={13} />,   fg: 'var(--text-muted)', bg: 'var(--hover-bg)' },
  };
  const s = map[status];
  return (
    <Badge className="gap-1" style={{ background: s.bg, color: s.fg }}>
      {s.icon} {s.label}
    </Badge>
  );
}

export default function ErpIntegrationPage() {
  const t = useT();
  const sp = t.settingsPage as Record<string, string>;
  const [role, setRole] = useState<'ADMIN' | 'DISPATCHER' | 'MANAGER' | 'UNKNOWN'>('UNKNOWN');
  const [erp, setErp] = useState<ErpSettings | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [conn, setConn] = useState<ConnState>({ status: 'idle' });
  // True once the admin edits any field; means a persisted CONNECTED is stale until re-test.
  const [dirty, setDirty] = useState(false);

  const canManage = canManageSettings(role);

  const fetchErp = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/settings/erp');
      if (res.data) { setErp(res.data); setDirty(false); setConn({ status: 'idle' }); }
    } catch { /* fail safe */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const r = getCurrentRole();
    setRole(r);
    if (r === 'ADMIN') fetchErp();
  }, [fetchErp]);

  const patchConf = (patch: Partial<OdooConfig>) => {
    setDirty(true);
    setConn({ status: 'idle' });
    setErp((prev) => prev ? { ...prev, erpConfiguration: { ...(prev.erpConfiguration ?? {}), ...patch } } : prev);
  };

  const setProvider = (p: ErpProvider) => {
    setDirty(true);
    setConn({ status: 'idle' });
    setErp((prev) => prev ? {
      ...prev,
      activeErpProvider: p,
      erpConfiguration: p === 'NONE' ? null : (prev.erpConfiguration ?? { ...EMPTY_ODOO }),
    } : prev);
  };

  // Run the connection test against the current config. Returns true on success. Persists
  // CONNECTED/ERROR backend-side; the caller decides whether to refetch (we skip the refetch
  // when chaining after a save, so we refetch once at the end instead).
  // `stored` = test the persisted config (used after a save, so there's no masked-secret
  // ambiguity — the exact saved values are verified). Otherwise test the current form values.
  const runTest = async (refetch = true, stored = false): Promise<boolean> => {
    setConn({ status: 'idle' });
    try {
      const res = stored
        ? await api.post('/api/settings/erp/test-stored')
        : await api.post('/api/settings/erp/test', erp);
      setConn({ status: 'ok', uid: res.data?.uid });
      setDirty(false);
      if (refetch) fetchErp();
      return true;
    } catch {
      setConn({ status: 'fail' });
      if (refetch) fetchErp();
      return false;
    }
  };

  // Save = persist the config AND verify it in one click, so the result is "saved + tested"
  // (CONNECTED or ERROR) rather than the limbo "saved but untested" state.
  const handleSave = async () => {
    if (!erp) return;
    setSaving(true);
    try {
      await api.put('/api/settings/erp', erp);
      if (erp.activeErpProvider === 'NONE') {
        showSuccessToast('successErpUpdated');
        fetchErp();
        return;
      }
      // Chained test against the just-saved config (stored = no mask ambiguity).
      setTesting(true);
      const ok = await runTest(false, true);
      if (ok) showSuccessToast(sp.savedAndTested ?? 'Enregistré et connexion vérifiée.');
      else    showErrorToast(new Error(sp.savedTestFailed ?? 'Enregistré, mais le test de connexion a échoué.'));
      fetchErp(); // final persisted state (CONNECTED / ERROR)
    } catch {
      showErrorToast(null, 'errorSaveFailed');
    } finally {
      setTesting(false);
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      const ok = await runTest(true);
      if (ok) showSuccessToast(sp.testSuccess);
      else    showErrorToast(new Error(sp.testFailed));
    } finally {
      setTesting(false);
    }
  };

  const provider = erp?.activeErpProvider ?? 'NONE';
  const conf = erp?.erpConfiguration ?? null;

  // Effective lifecycle state shown to the admin. A live test result (conn) wins; otherwise
  // the persisted status, but a CONNECTED that the admin has since edited shows as STALE.
  const persisted = (erp?.connectionStatus ?? 'NOT_CONFIGURED') as ConnStatus;
  const effectiveStatus: ConnStatus | 'STALE' =
    conn.status === 'ok' ? 'CONNECTED'
    : conn.status === 'fail' ? 'ERROR'
    : (dirty && (persisted === 'CONNECTED' || persisted === 'ERROR')) ? 'STALE'
    : persisted;

  return (
    <div className="h-[calc(100vh-64px)] overflow-y-auto bg-[var(--app-bg)]">
      <div className="max-w-[820px] mx-auto p-6">
        {/* Header */}
        <div className="flex items-start justify-between gap-4 pb-5 mb-6 border-b border-[var(--border)]">
          <div>
            <h1 className={tw.pageTitle}>{tlabel(t.sidebar.items, 'erpIntegration') ?? 'Intégration ERP'}</h1>
            <p className={cn(tw.subtitle, 'mt-0.5')}>
              {provider === 'ODOO' ? sp.erpOdooDesc : provider === 'DUX' ? sp.erpDuxDesc : sp.noErpDesc}
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {!canManage && (
              <Badge variant="outline" className="gap-1 text-[var(--warning)] border-[var(--warning)]/30">
                <IconLock size={12} /> {sp.readOnlyMode}
              </Badge>
            )}
            <ConnStatusBadge status={effectiveStatus} uid={conn.uid ?? erp?.lastTestUid ?? undefined} sp={sp} />
          </div>
        </div>

        {loading && !erp ? (
          <div className="flex items-center justify-center p-20">
            <div className="h-8 w-8 rounded-full border-2 border-[var(--brand)] border-t-transparent animate-spin" />
          </div>
        ) : erp ? (
          <div className="flex flex-col gap-6">
            <SectionCard title={sp.erpProvider}>
              <div className="flex flex-col gap-5">
                <FieldSelect
                  label={sp.erpProvider}
                  value={provider}
                  onChange={(e) => setProvider(e.target.value as ErpProvider)}
                  disabled={!canManage}
                  options={[
                    { value: 'NONE', label: 'NONE' },
                    { value: 'ODOO', label: 'ODOO' },
                    { value: 'DUX', label: 'DUX' },
                  ]}
                />

                {/* ODOO config */}
                {provider === 'ODOO' && conf && (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4 p-4 rounded-lg bg-[var(--app-bg)] border border-dashed border-[var(--border)]">
                    <FieldInput
                      wrapperClassName="md:col-span-2"
                      label={sp.erpUrl}
                      placeholder={sp.erpUrlDesc}
                      value={conf.url ?? ''}
                      onChange={(e) => patchConf({ url: e.target.value })}
                      disabled={!canManage}
                    />
                    <FieldInput label={sp.erpDb} value={conf.db ?? ''} onChange={(e) => patchConf({ db: e.target.value })} disabled={!canManage} />
                    <FieldInput label={sp.erpLogin} value={conf.login ?? ''} onChange={(e) => patchConf({ login: e.target.value })} disabled={!canManage} />
                    <FieldInput
                      wrapperClassName="md:col-span-2"
                      type="password"
                      label={sp.erpApiKey}
                      hint={sp.erpApiKeyHint}
                      placeholder="••••••••"
                      value={conf.apiKey ?? ''}
                      onChange={(e) => patchConf({ apiKey: e.target.value })}
                      disabled={!canManage}
                    />
                    <FieldInput
                      wrapperClassName="md:col-span-2"
                      label={sp.erpReportId}
                      placeholder={t.erpIntegrationPage.reportIdPlaceholder}
                      value={conf.reportId ?? ''}
                      onChange={(e) => patchConf({ reportId: e.target.value })}
                      disabled={!canManage}
                    />
                  </div>
                )}

                {/* DUX placeholder */}
                {provider === 'DUX' && conf && (
                  <div className="grid grid-cols-1 gap-4 p-4 rounded-lg bg-[var(--app-bg)] border border-dashed border-[var(--border)]">
                    <FieldInput
                      label={sp.erpUrl}
                      placeholder={t.erpIntegrationPage.apiUrlPlaceholder}
                      value={conf.url ?? ''}
                      onChange={(e) => patchConf({ url: e.target.value })}
                      disabled={!canManage}
                    />
                    <FieldInput
                      type="password"
                      label={sp.erpApiKey}
                      placeholder="••••••••"
                      value={conf.apiKey ?? ''}
                      onChange={(e) => patchConf({ apiKey: e.target.value })}
                      disabled={!canManage}
                    />
                  </div>
                )}

                {/* Lifecycle banner — makes the connection state explicit and tells the admin
                    what to do next (test, or re-test after edits). */}
                {provider !== 'NONE' && (() => {
                  const tone =
                    effectiveStatus === 'CONNECTED' ? { fg: 'var(--success)', bg: 'color-mix(in srgb, var(--success) 8%, transparent)', icon: <IconCircleCheck size={16} /> }
                    : effectiveStatus === 'ERROR' ? { fg: 'var(--danger)', bg: 'color-mix(in srgb, var(--danger) 8%, transparent)', icon: <IconPlugConnectedX size={16} /> }
                    : { fg: 'var(--warning)', bg: 'color-mix(in srgb, var(--warning) 8%, transparent)', icon: <IconAlertTriangle size={16} /> };
                  const msg =
                    effectiveStatus === 'CONNECTED' ? (sp.lifecycleConnected ?? 'Connexion vérifiée. Cette source est active sur la page Importation.')
                    : effectiveStatus === 'ERROR' ? (erp?.lastError || sp.lifecycleError || 'Le dernier test a échoué — corrigez les identifiants puis re-testez.')
                    : effectiveStatus === 'STALE' ? (sp.lifecycleStale ?? 'Vous avez modifié la configuration. Testez à nouveau avant d’enregistrer.')
                    : effectiveStatus === 'CONFIGURED' ? (sp.lifecycleConfigured ?? 'Configuration enregistrée mais jamais testée. Lancez un test pour confirmer.')
                    : (sp.lifecycleNotConfigured ?? 'Aucune source configurée.');
                  return (
                    <div className="flex items-start gap-2.5 p-3 rounded-lg text-xs" style={{ background: tone.bg, color: tone.fg }}>
                      <span className="shrink-0 mt-px">{tone.icon}</span>
                      <div className="flex flex-col gap-0.5 min-w-0">
                        <span className="font-[600] leading-snug">{msg}</span>
                        {erp?.lastTestedAt && (
                          <span className="inline-flex items-center gap-1 text-2xs opacity-80">
                            <IconClock size={11} /> {sp.lastTestedLabel ?? 'Dernier test'} : {new Date(erp.lastTestedAt).toLocaleString()}
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })()}

                {/* Actions. The standalone Test re-verifies the SAVED config; once the admin
                    edits a field (dirty), they must "Save & test" so the new values are what
                    gets verified — otherwise a masked secret could let stale creds pass. */}
                <div className="flex items-center justify-end gap-2 pt-1">
                  <Button
                    variant="outline" size="sm" onClick={handleTest}
                    disabled={testing || saving || provider === 'NONE' || dirty}
                    title={dirty ? (sp.testDirtyHint ?? 'Enregistrez vos modifications pour les tester') : undefined}
                  >
                    <IconPlugConnected size={15} /> {testing ? sp.erpTesting : sp.testConnection}
                  </Button>
                  {canManage && (
                    <Button size="sm" onClick={handleSave} disabled={saving || testing}>
                      <IconDatabase size={15} /> {saving ? (sp.savingTestingLabel ?? 'Enregistrement & test…') : (sp.saveAndTest ?? sp.saveConfig)}
                    </Button>
                  )}
                </div>
              </div>
            </SectionCard>
          </div>
        ) : (
          <div className="text-center py-20 text-sm text-[var(--text-muted)]">
            {tlabel(t.erpIntegrationPage, 'loadError') ?? 'Aucune configuration ERP disponible.'}
          </div>
        )}
      </div>
    </div>
  );
}
