import { useEffect, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import { useT } from '@/lib/LocaleContext';
import { canManageSettings, getCurrentRole } from '@/lib/auth';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { IconDatabase } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';

function SurgicalSettingCard({ title, children, icon: Icon, description }: { title: string; children: React.ReactNode; icon?: any; description?: string }) {
  return (
    <div className="rounded-[2px] overflow-hidden animate-fade-in" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
      <div className="px-5 py-4 border-b border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
        <div className="flex items-center justify-between">
          <div className="flex flex-col gap-0.5">
            <p className="text-[11px] font-semibold text-[var(--text-primary)]">{title}</p>
            {description && <p className="text-[11px] font-semibold text-[var(--text-muted)]">{description}</p>}
          </div>
          {Icon && <Icon size={16} className="text-[var(--border)]" />}
        </div>
      </div>
      <div className="p-5">{children}</div>
    </div>
  );
}

export default function ErpIntegrationPage() {
  const t = useT();
  const [role, setRole] = useState<'ADMIN' | 'DISPATCHER' | 'MANAGER' | 'UNKNOWN'>('UNKNOWN');

  // ERP Integration states
  const [erpSettings, setErpSettings] = useState<any>(null);
  const [erpLoading, setErpLoading] = useState(false);
  const [erpSaving, setErpSaving] = useState(false);
  const [erpTesting, setErpTesting] = useState(false);

  const fetchErpSettings = useCallback(async () => {
    setErpLoading(true);
    try {
      const res = await api.get('/api/settings/erp');
      if (res.data) setErpSettings(res.data);
    } catch { /* fail safe */ }
    finally { setErpLoading(false); }
  }, []);

  useEffect(() => {
    const r = getCurrentRole();
    setRole(r);
    if (r === 'ADMIN') {
      fetchErpSettings();
    }
  }, [fetchErpSettings]);

  const handleSaveErp = async () => {
    setErpSaving(true);
    try {
      await api.put('/api/settings/erp', erpSettings);
      showSuccessToast('successErpUpdated');
      fetchErpSettings();
    } catch {
      showErrorToast(null, 'errorSaveFailed');
    } finally {
      setErpSaving(false);
    }
  };

  const handleTestConnection = async () => {
    setErpTesting(true);
    try {
      await api.post('/api/settings/erp/test', erpSettings);
      showSuccessToast(t.settingsPage.testSuccess);
    } catch {
      showErrorToast(new Error(t.settingsPage.testFailed));
    } finally {
      setErpTesting(false);
    }
  };

  const canManage = canManageSettings(role);

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', background: 'var(--app-bg)' }} className="flex flex-col">
      {/* ── Sticky Header ── */}
      <div
        className="sticky top-0 z-20 min-h-16 h-auto lg:h-16 py-4 lg:py-0 flex items-center shrink-0"
        style={{ background: 'var(--surface)', borderBottom: '1px solid var(--border)' }}
      >
        <div className="px-6 w-full flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4 sm:gap-8">
            <div>
              <p className="text-[11px] font-[600] text-[var(--text-muted)] mb-1">
                {t.settingsPage.systemAdmin}
              </p>
              <p className="text-[18px] font-bold" style={{ color: 'var(--text-primary)' }}>
                {t.settingsPage.integrationConfig} <span style={{ color: 'var(--brand)' }}>ERP</span>
              </p>
            </div>
            <div className="hidden sm:block w-px h-6 bg-[var(--border)]" />
            <p className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
              Configuration de la synchronisation de données
            </p>
          </div>

          <div className="flex items-center gap-2">
            {!canManage && (
              <span className="text-[11px] font-semibold px-2 py-1 rounded-none" style={{ color: 'var(--brand)', background: 'var(--hover-bg)', border: '1px solid var(--border-strong)' }}>
                {t.settingsPage.readOnlyMode}
              </span>
            )}
            <Button size="sm" variant="outline" onClick={handleTestConnection} disabled={erpTesting || erpSaving || !erpSettings} className="rounded-[2px]">
              {erpTesting && (
                <svg className="animate-spin h-3 w-3 mr-2" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                </svg>
              )}
              {t.settingsPage.testConnection}
            </Button>
            {canManage && erpSettings && (
              <Button size="sm" onClick={handleSaveErp} disabled={erpSaving || erpTesting} className="rounded-[2px]">
                {erpSaving && (
                  <svg className="animate-spin h-3 w-3 mr-2" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                  </svg>
                )}
                {t.settingsPage.saveConfig}
              </Button>
            )}
          </div>
        </div>
      </div>

      {/* ── Content Container ── */}
      <div className="flex-1 overflow-y-auto p-8">
        <div className="max-w-[800px] mx-auto flex flex-col gap-6">
          {erpLoading && !erpSettings ? (
            <div className="flex items-center justify-center p-20">
              <svg className="animate-spin h-8 w-8 text-[var(--brand)]" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
              </svg>
            </div>
          ) : erpSettings ? (
            <div className="flex flex-col gap-6">
              <SurgicalSettingCard
                title={t.settingsPage.erpProvider}
                icon={IconDatabase}
                description={erpSettings.activeErpProvider === 'ODOO' ? t.settingsPage.erpOdooDesc : (erpSettings.activeErpProvider === 'DUX' ? t.settingsPage.erpDuxDesc : t.settingsPage.noErpDesc)}
              >
                <div className="flex flex-col gap-6">
                  <div>
                    <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-2">{t.settingsPage.erpProvider}</label>
                    <select
                      className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                      style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                      value={erpSettings.activeErpProvider || 'NONE'}
                      onChange={e => {
                        const newProv = e.target.value;
                        setErpSettings({
                          ...erpSettings,
                          activeErpProvider: newProv,
                          erpConfiguration: newProv !== 'NONE' ? (erpSettings.erpConfiguration || { url: '', db: '', uid: '', password: '', apiKey: '' }) : null
                        });
                      }}
                      disabled={!canManage}
                    >
                      <option value="NONE">NONE</option>
                      <option value="ODOO">ODOO</option>
                      <option value="DUX">DUX</option>
                    </select>
                  </div>

                  {erpSettings.activeErpProvider === 'ODOO' && erpSettings.erpConfiguration && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-5 mt-2 p-5 rounded-[2px]" style={{ background: 'var(--hover-bg)', border: '1px dashed var(--border)' }}>
                      <div className="md:col-span-2">
                        <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.erpUrl}</label>
                        <input
                          className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                          style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                          placeholder={t.settingsPage.erpUrlDesc}
                          value={erpSettings.erpConfiguration.url || ''}
                          onChange={e => setErpSettings({...erpSettings, erpConfiguration: {...erpSettings.erpConfiguration, url: e.target.value}})}
                          disabled={!canManage}
                        />
                      </div>
                      <div>
                        <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.erpDb}</label>
                        <input
                          className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                          style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                          value={erpSettings.erpConfiguration.db || ''}
                          onChange={e => setErpSettings({...erpSettings, erpConfiguration: {...erpSettings.erpConfiguration, db: e.target.value}})}
                          disabled={!canManage}
                        />
                      </div>
                      <div>
                        <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.erpUid}</label>
                        <input
                          type="number"
                          className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                          style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                          value={erpSettings.erpConfiguration.uid || ''}
                          onChange={e => setErpSettings({...erpSettings, erpConfiguration: {...erpSettings.erpConfiguration, uid: parseInt(e.target.value) || 0}})}
                          disabled={!canManage}
                        />
                      </div>
                      <div className="md:col-span-2">
                        <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.erpPassword}</label>
                        <input
                          type="password"
                          className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                          style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                          placeholder="********"
                          value={erpSettings.erpConfiguration.password || ''}
                          onChange={e => setErpSettings({...erpSettings, erpConfiguration: {...erpSettings.erpConfiguration, password: e.target.value}})}
                          disabled={!canManage}
                        />
                      </div>
                    </div>
                  )}

                  {erpSettings.activeErpProvider === 'DUX' && erpSettings.erpConfiguration && (
                    <div className="grid grid-cols-1 gap-5 mt-2 p-5 rounded-[2px]" style={{ background: 'var(--hover-bg)', border: '1px dashed var(--border)' }}>
                      <div>
                        <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.erpUrl}</label>
                        <input
                          className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                          style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                          placeholder="e.g. https://api.dux.com/v1"
                          value={erpSettings.erpConfiguration.url || ''}
                          onChange={e => setErpSettings({...erpSettings, erpConfiguration: {...erpSettings.erpConfiguration, url: e.target.value}})}
                          disabled={!canManage}
                        />
                      </div>
                      <div>
                        <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">API Key (Placeholder)</label>
                        <input
                          type="password"
                          className="w-full h-9 px-3 text-sm rounded-[2px] outline-none focus:ring-1 focus:ring-[var(--brand)]"
                          style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                          placeholder="********"
                          value={erpSettings.erpConfiguration.apiKey || ''}
                          onChange={e => setErpSettings({...erpSettings, erpConfiguration: {...erpSettings.erpConfiguration, apiKey: e.target.value}})}
                          disabled={!canManage}
                        />
                      </div>
                    </div>
                  )}
                </div>
              </SurgicalSettingCard>
            </div>
          ) : (
            <div className="text-center py-20 text-[var(--text-muted)] text-[12px]">
              Aucune configuration ERP disponible ou impossible de charger les données.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
