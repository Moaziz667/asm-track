import { useEffect, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import { useT } from '@/lib/LocaleContext';
import { canManageSettings, getCurrentRole } from '@/lib/auth';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { IconDatabase, IconPlugConnected, IconPlugConnectedX, IconLock } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { tw } from '@/lib/typography';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { SectionCard } from '@/components/ui/section-card';

// ── Types ──────────────────────────────────────────────────────────────────
type ErpProvider = 'NONE' | 'ODOO' | 'DUX';
interface OdooConfig {
  url?: string; db?: string; login?: string; apiKey?: string; reportId?: string;
  // legacy fields tolerated for back-compat (not shown in the UI):
  uid?: number; password?: string;
}
interface ErpSettings {
  activeErpProvider: ErpProvider;
  erpConfiguration: OdooConfig | null;
}
type ConnState = { status: 'idle' | 'ok' | 'fail'; uid?: string };

const EMPTY_ODOO: OdooConfig = { url: '', db: '', login: '', apiKey: '', reportId: 'stock.report_deliveryslip' };

export default function ErpIntegrationPage() {
  const t = useT();
  const sp = t.settingsPage as Record<string, string>;
  const [role, setRole] = useState<'ADMIN' | 'DISPATCHER' | 'MANAGER' | 'UNKNOWN'>('UNKNOWN');
  const [erp, setErp] = useState<ErpSettings | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [conn, setConn] = useState<ConnState>({ status: 'idle' });

  const canManage = canManageSettings(role);

  const fetchErp = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/settings/erp');
      if (res.data) setErp(res.data);
    } catch { /* fail safe */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const r = getCurrentRole();
    setRole(r);
    if (r === 'ADMIN') fetchErp();
  }, [fetchErp]);

  const patchConf = (patch: Partial<OdooConfig>) =>
    setErp((prev) => prev ? { ...prev, erpConfiguration: { ...(prev.erpConfiguration ?? {}), ...patch } } : prev);

  const setProvider = (p: ErpProvider) =>
    setErp((prev) => prev ? {
      ...prev,
      activeErpProvider: p,
      erpConfiguration: p === 'NONE' ? null : (prev.erpConfiguration ?? { ...EMPTY_ODOO }),
    } : prev);

  const handleSave = async () => {
    if (!erp) return;
    setSaving(true);
    try {
      await api.put('/api/settings/erp', erp);
      showSuccessToast('successErpUpdated');
      fetchErp();
    } catch {
      showErrorToast(null, 'errorSaveFailed');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    setConn({ status: 'idle' });
    try {
      const res = await api.post('/api/settings/erp/test', erp);
      setConn({ status: 'ok', uid: res.data?.uid });
      showSuccessToast(sp.testSuccess);
    } catch {
      setConn({ status: 'fail' });
      showErrorToast(new Error(sp.testFailed));
    } finally {
      setTesting(false);
    }
  };

  const provider = erp?.activeErpProvider ?? 'NONE';
  const conf = erp?.erpConfiguration ?? null;

  return (
    <div className="h-[calc(100vh-64px)] overflow-y-auto bg-[var(--app-bg)]">
      <div className="max-w-[820px] mx-auto p-6">
        {/* Header */}
        <div className="flex items-start justify-between gap-4 pb-5 mb-6 border-b border-[var(--border)]">
          <div>
            <h1 className={tw.pageTitle}>{(t.sidebar.items as any).erpIntegration ?? 'Intégration ERP'}</h1>
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
            {conn.status === 'ok' && (
              <Badge className="gap-1" style={{ background: 'color-mix(in srgb, var(--success) 14%, transparent)', color: 'var(--success)' }}>
                <IconPlugConnected size={13} /> {sp.erpConnected}{conn.uid ? ` · uid ${conn.uid}` : ''}
              </Badge>
            )}
            {conn.status === 'fail' && (
              <Badge variant="destructive" className="gap-1">
                <IconPlugConnectedX size={13} /> {sp.erpConnFailed}
              </Badge>
            )}
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

                {/* Actions */}
                <div className="flex items-center justify-end gap-2 pt-1">
                  <Button variant="outline" size="sm" onClick={handleTest} disabled={testing || saving || provider === 'NONE'}>
                    <IconPlugConnected size={15} /> {testing ? sp.erpTesting : sp.testConnection}
                  </Button>
                  {canManage && (
                    <Button size="sm" onClick={handleSave} disabled={saving || testing}>
                      <IconDatabase size={15} /> {saving ? (sp.savingLabel ?? 'Enregistrement…') : sp.saveConfig}
                    </Button>
                  )}
                </div>
              </div>
            </SectionCard>
          </div>
        ) : (
          <div className="text-center py-20 text-sm text-[var(--text-muted)]">
            {t.erpIntegrationPage.loadError ?? 'Aucune configuration ERP disponible.'}
          </div>
        )}
      </div>
    </div>
  );
}
