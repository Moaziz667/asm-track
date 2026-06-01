import { useEffect, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { canManageSettings, getCurrentRole } from '@/lib/auth';
import { AdminUser } from '@/types';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { formatDate } from '@/lib/date';
import {
  IconSettings, IconPlus, IconLock,
  IconMail, IconUser, IconCheck, IconClock, IconX, IconShield,
  IconCpu, IconRouter, IconShieldCheck, IconChevronRight, IconCommand,
  IconFingerprint, IconEye, IconEyeOff
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';

// ── Types & Constants ────────────────────────────────────────────────────────

type SettingSection = 'GENERAL' | 'SLA' | 'IAM';

// ── Sub-components ──────────────────────────────────────────────────────────

function SurgicalSettingCard({ title, children, icon: Icon, description }: { title: string; children: React.ReactNode; icon?: any; description?: string }) {
  return (
    <div className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
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

function TechParam({ label, description, value, unit, onEdit, disabled }: { label: string; description?: string; value: string; unit: string; onEdit?: () => void; disabled?: boolean }) {
  return (
    <div className="flex items-center justify-between py-3 border-b border-[var(--border)] last:border-0 group">
      <div className="flex-1">
        <p className="text-[11px] font-semibold text-[var(--text-primary)]">{label}</p>
        {description && <p className="text-[10px] text-[var(--text-muted)] mt-0.5">{description}</p>}
      </div>
      <div className="flex items-center gap-2">
        <div className="flex items-center gap-1 px-2 py-1 rounded-md" style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}>
          <p className="text-[14px] font-black font-mono text-[var(--text-primary)]">{value}</p>
          <p className="text-[11px] font-semibold text-[var(--text-muted)]">{unit}</p>
        </div>
        {!disabled && onEdit && (
          <button
            type="button"
            className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] opacity-0 group-hover:opacity-100 transition-all"
            onClick={onEdit}
          >
            <IconSettings size={14} />
          </button>
        )}
      </div>
    </div>
  );
}

export default function SettingsPage() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const [section, setSection] = useState<SettingSection>('GENERAL');
  const [role, setRole] = useState<'ADMIN' | 'DISPATCHER' | 'MANAGER' | 'UNKNOWN'>('UNKNOWN');
  const [adminUsers, setAdminUsers] = useState<AdminUser[]>([]);
  const [slaSettings, setSlaSettings] = useState<Record<string, string>>({});
  const [loadingSla, setLoadingSla] = useState(false);
  const [addOpen, setAddOpen] = useState(false);

  // SLA Modal state
  const [slaEditOpen, setSlaEditOpen] = useState(false);
  const [editingSla, setEditingSla] = useState<{ key: string, label: string, value: string } | null>(null);
  const [newSlaValue, setNewSlaValue] = useState('');

  // Modal Form
  const [formName, setFormName] = useState('');
  const [formEmail, setFormEmail] = useState('');
  const [formPassword, setFormPassword] = useState('');
  const [formRole, setFormRole] = useState('ADMIN');
  const [submitting, setSubmitting] = useState(false);
  const [showAdminPass, setShowAdminPass] = useState(false);

  // Company branding
  const [company, setCompany] = useState<{ name: string; supportEmail?: string; address?: string; primaryColor?: string } | null>(null);
  const [companySaving, setCompanySaving] = useState(false);

  const fetchAdminUsers = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/users');
      setAdminUsers(Array.isArray(res.data) ? res.data : res.data.content ?? []);
    } catch { /* fail safe */ }
  }, []);

  const fetchSlaSettings = useCallback(async () => {
    setLoadingSla(true);
    try {
      const res = await api.get('/api/admin/reports/settings');
      setSlaSettings(res.data);
    } catch { /* fail safe */ }
    finally { setLoadingSla(false); }
  }, []);

  const fetchCompany = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/companies/me');
      if (res.data) setCompany({
        name: res.data.name,
        supportEmail: res.data.supportEmail,
        address: res.data.address,
        primaryColor: res.data.primaryColor
      });
    } catch { /* fail safe */ }
  }, []);

  const handleSaveCompany = async () => {
    if (!company) return;
    setCompanySaving(true);
    try {
      const res = await api.put('/api/admin/companies/me', company);
      showSuccessToast('successCompanyUpdated');
      if (res.data) setCompany({
        name: res.data.name,
        supportEmail: res.data.supportEmail,
        address: res.data.address,
        primaryColor: res.data.primaryColor
      });
    } catch (err) {
      showErrorToast(err, 'errorCompanyUpdateFailed');
    } finally {
      setCompanySaving(false);
    }
  };

  useEffect(() => {
    const r = getCurrentRole();
    setRole(r);
    if (r === 'ADMIN') {
      fetchAdminUsers();
    }
    fetchSlaSettings();
    fetchCompany();
  }, [fetchAdminUsers, fetchSlaSettings, fetchCompany]);

  const updateSetting = async (key: string, value: string) => {
    try {
      await api.post('/api/admin/reports/settings', null, { params: { key, value } });
      showSuccessToast('successSlaUpdated', { action: `MAJ : ${key} ➔ ${value} min` });
      fetchSlaSettings();
    } catch {
      showErrorToast(null, 'errorSaveFailed');
    }
  };

  const handleAddUser = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await api.post('/api/admin/users', {
        name: formName, email: formEmail, password: formPassword, role: formRole,
      });
      showSuccessToast('successUserCreated');
      setAddOpen(false);
      setFormName(''); setFormEmail(''); setFormPassword('');
      fetchAdminUsers();
    } catch (err: any) {
      showErrorToast(err, 'errorSaveFailed');
    } finally {
      setSubmitting(false);
    }
  };

  const canManage = canManageSettings(role);
  const [mobileTab, setMobileTab] = useState<'nav' | 'content'>('content');

  const navSections = [
    { id: 'GENERAL', label: t.settingsPage.generalConfig, icon: IconCommand },
    { id: 'SLA', label: t.settingsPage.slaParameters, icon: IconClock },
    { id: 'IAM', label: t.settingsPage.identitiesAccess, icon: IconShieldCheck },
  ];

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Mobile Tab Bar */}
      <div className="lg:hidden flex shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        {([['nav', t.settingsPage.tabSections], ['content', t.settingsPage.tabParameters]] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setMobileTab(tab)}
            className={`flex-1 h-10 text-[11px] font-semibold transition-colors ${
              mobileTab === tab ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]' : 'text-[var(--text-muted)]'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 gap-0" style={{ minHeight: 0, overflow: 'hidden' }}>
        {/* ── Navigation Rail ────────────────── */}
        <div className={`lg:w-[240px] border-r border-[var(--border)] bg-[var(--surface)] shrink-0 flex flex-col ${mobileTab === 'nav' ? 'flex w-full' : 'hidden lg:flex'}`}>
          <div className="p-5 border-b border-[var(--border)]">
            <span className="text-[11px] font-[500] text-[var(--text-muted)] mb-0.5 block">{t.settingsPage.platformNexus}</span>
            <h1 className="text-[18px] font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
              {t.settingsPage.pageTitle} <span className="text-[var(--brand)]">{t.settingsPage.pageTitleBrand}</span>
            </h1>
          </div>

          <div className="flex flex-col gap-1 p-3 flex-1">
            {navSections.map(s => (
              <button
                key={s.id}
                type="button"
                onClick={() => { setSection(s.id as SettingSection); setMobileTab('content'); }}
                className={cn(
                  "px-3 py-2 rounded-md transition-all flex items-center gap-3 group text-left text-[11px] font-[500] border",
                  section === s.id
                    ? "bg-[var(--hover-bg)] text-[var(--text-primary)] border-[var(--border)] font-[500]"
                    : "hover:bg-[var(--hover-bg)]/50 border-transparent text-[var(--text-muted)] hover:border-[var(--border)]"
                )}
              >
                <s.icon
                  size={16}
                  className={cn(
                    section === s.id ? "text-[var(--brand)]" : "text-[var(--text-muted)] group-hover:text-[var(--text-primary)]",
                    "transition-colors"
                  )}
                />
                <span className="text-[11px] font-bold tracking-tight">{s.label}</span>
              </button>
            ))}
          </div>

          <div className="p-5 border-t border-[var(--border)]">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-md flex items-center justify-center border border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
                <IconFingerprint size={16} className="text-[var(--text-muted)]" />
              </div>
              <p className="text-[10px] font-extrabold text-[var(--text-primary)]">{role}</p>
            </div>
          </div>
        </div>

        {/* ── Main Config Slab ────────────────────────── */}
        <div className={`flex-1 flex flex-col overflow-hidden min-w-0 ${mobileTab === 'content' ? 'flex' : 'hidden lg:flex'}`} style={{ background: 'var(--app-bg)' }}>
          <div className="flex items-center justify-between px-6 h-16 sticky top-0 z-10 border-b border-[var(--border)] shrink-0 bg-[var(--surface)]">
            <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.settingsPage.systemAdmin}</p>
            {!canManage && (
              <span
                className="text-[11px] font-semibold px-2 py-0.5 rounded-md border inline-flex items-center gap-1"
                style={{
                  color: '#B05A18',
                  background: 'rgba(212,119,44,0.09)',
                  borderColor: 'rgba(212,119,44,0.15)'
                }}
              >
                <IconLock size={12} />
                {t.settingsPage.readOnlyMode}
              </span>
            )}
          </div>

          <div className="overflow-y-auto flex-1 p-8">
            <div className="max-w-[1000px] mx-auto flex flex-col gap-10">

              {/* SECTION: GENERAL */}
              {section === 'GENERAL' && (
                <div className="flex flex-col gap-8 animate-fade-in">
                  <SurgicalSettingCard title={t.settingsPage.coreService} icon={IconCommand} description={t.settingsPage.coreServiceDesc}>
                    <div className="flex flex-col gap-6">
                      <div className="flex items-center justify-between">
                        <div className="flex flex-col gap-1">
                          <p className="text-[12px] font-bold text-[var(--text-primary)]">{t.settingsPage.systemNotifications}</p>
                          <p className="text-[10px] text-[var(--text-muted)]">{t.settingsPage.systemNotificationsDesc}</p>
                        </div>
                        <input type="checkbox" role="switch" defaultChecked className="w-9 h-5 rounded-full cursor-pointer accent-[var(--text-primary)]" />
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="flex flex-col gap-1">
                          <p className="text-[12px] font-bold text-[var(--text-primary)]">{t.settingsPage.autoArchiving}</p>
                          <p className="text-[10px] text-[var(--text-muted)]">{t.settingsPage.autoArchivingDesc}</p>
                        </div>
                        <input type="checkbox" role="switch" defaultChecked className="w-9 h-5 rounded-full cursor-pointer accent-[var(--text-primary)]" />
                      </div>
                    </div>
                  </SurgicalSettingCard>

                  <SurgicalSettingCard title={t.settingsPage.companyBranding} icon={IconFingerprint}>
                    <div className="flex flex-col gap-6">
                      <div className="grid grid-cols-2 gap-5">
                        <div>
                          <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.instanceName}</label>
                          <input
                            className="w-full h-9 px-3 text-sm rounded-md outline-none"
                            style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                            value={company?.name ?? ''}
                            onChange={(e) => setCompany(prev => prev ? { ...prev, name: e.target.value } : null)}
                            disabled={!canManage || companySaving}
                          />
                        </div>
                        <div>
                          <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.supportContact}</label>
                          <input
                            className="w-full h-9 px-3 text-sm rounded-md outline-none"
                            style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                            value={company?.supportEmail ?? ''}
                            onChange={(e) => setCompany(prev => prev ? { ...prev, supportEmail: e.target.value } : null)}
                            disabled={!canManage || companySaving}
                          />
                        </div>
                      </div>

                      <div className="grid grid-cols-2 gap-5">
                        <div>
                          <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.companyAddress}</label>
                          <input
                            className="w-full h-9 px-3 text-sm rounded-md outline-none"
                            style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                            value={company?.address ?? ''}
                            onChange={(e) => setCompany(prev => prev ? { ...prev, address: e.target.value } : null)}
                            disabled={!canManage || companySaving}
                          />
                        </div>
                        <div>
                          <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.primaryColor}</label>
                          <div className="flex gap-2 items-center">
                            <input
                              type="color"
                              className="w-9 h-9 border-0 rounded-md cursor-pointer"
                              value={company?.primaryColor ?? '#F08734'}
                              onChange={(e) => setCompany(prev => prev ? { ...prev, primaryColor: e.target.value } : null)}
                              disabled={!canManage || companySaving}
                            />
                            <input
                              className="flex-1 h-9 px-3 text-sm rounded-md outline-none"
                              style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                              value={company?.primaryColor ?? '#F08734'}
                              onChange={(e) => setCompany(prev => prev ? { ...prev, primaryColor: e.target.value } : null)}
                              disabled={!canManage || companySaving}
                            />
                          </div>
                        </div>
                      </div>

                      {canManage && (
                        <div className="flex justify-end pt-2">
                          <Button
                            size="sm"
                            onClick={handleSaveCompany}
                            disabled={companySaving}
                            className="rounded-md"
                          >
                            {companySaving ? "Enregistrement..." : (t.settingsPage.saveConfig || "Enregistrer")}
                          </Button>
                        </div>
                      )}
                    </div>
                  </SurgicalSettingCard>
                </div>
              )}

              {/* SECTION: SLA */}
              {section === 'SLA' && (
                <div className="flex flex-col gap-8 animate-fade-in">
                  <div>
                    <h2 className="text-[14px] font-bold text-[var(--text-primary)] mb-1">{t.settingsPage.slaagreement}</h2>
                    <p className="text-[11px] text-[var(--text-muted)] mb-6">{t.settingsPage.slaDesc}</p>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
                    {/* Waiting Time Card */}
                    <div className="rounded-lg overflow-hidden p-5" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
                      <div className="flex items-start justify-between mb-4">
                        <div className="flex items-center gap-2">
                          <div className="w-8 h-8 rounded-md flex items-center justify-center" style={{ background: 'var(--app-bg)' }}>
                            <IconClock size={16} className="text-[var(--brand)]" />
                          </div>
                          <div>
                            <p className="text-[12px] font-bold text-[var(--text-primary)]">{t.settingsPage.waitingTime}</p>
                          </div>
                        </div>
                        {canManage && (
                          <button
                            type="button"
                            onClick={() => {
                              setEditingSla({ key: 'ops.sla.waiting-limit-minutes', label: t.settingsPage.waitingTime, value: slaSettings['ops.sla.waiting-limit-minutes'] || '0' });
                              setNewSlaValue(slaSettings['ops.sla.waiting-limit-minutes'] || '0');
                              setSlaEditOpen(true);
                            }}
                            className="text-[11px] font-semibold px-2 py-1 rounded-md text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors"
                          >
                            {t.actions.edit}
                          </button>
                        )}
                      </div>
                      <p className="text-[10px] text-[var(--text-muted)] mb-4 leading-relaxed">{t.settingsPage.waitingTimeDesc}</p>
                      <div className="flex items-baseline gap-1">
                        <p className="text-[28px] font-black text-[var(--text-primary)]">{slaSettings['ops.sla.waiting-limit-minutes'] || '0'}</p>
                        <p className="text-[11px] font-semibold text-[var(--text-muted)]">MIN</p>
                      </div>
                    </div>

                    {/* Assignment Delay Card */}
                    <div className="rounded-lg overflow-hidden p-5" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
                      <div className="flex items-start justify-between mb-4">
                        <div className="flex items-center gap-2">
                          <div className="w-8 h-8 rounded-md flex items-center justify-center" style={{ background: 'var(--app-bg)' }}>
                            <IconChevronRight size={16} className="text-[var(--brand)]" />
                          </div>
                          <div>
                            <p className="text-[12px] font-bold text-[var(--text-primary)]">{t.settingsPage.assignmentDelay}</p>
                          </div>
                        </div>
                        {canManage && (
                          <button
                            type="button"
                            onClick={() => {
                              setEditingSla({ key: 'ops.sla.assign-limit-minutes', label: t.settingsPage.assignmentDelay, value: slaSettings['ops.sla.assign-limit-minutes'] || '0' });
                              setNewSlaValue(slaSettings['ops.sla.assign-limit-minutes'] || '0');
                              setSlaEditOpen(true);
                            }}
                            className="text-[11px] font-semibold px-2 py-1 rounded-md text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors"
                          >
                            {t.actions.edit}
                          </button>
                        )}
                      </div>
                      <p className="text-[10px] text-[var(--text-muted)] mb-4 leading-relaxed">{t.settingsPage.assignmentDelayDesc}</p>
                      <div className="flex items-baseline gap-1">
                        <p className="text-[28px] font-black text-[var(--text-primary)]">{slaSettings['ops.sla.assign-limit-minutes'] || '0'}</p>
                        <p className="text-[11px] font-semibold text-[var(--text-muted)]">MIN</p>
                      </div>
                    </div>

                    {/* Transit Delay Card */}
                    <div className="rounded-lg overflow-hidden p-5" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
                      <div className="flex items-start justify-between mb-4">
                        <div className="flex items-center gap-2">
                          <div className="w-8 h-8 rounded-md flex items-center justify-center" style={{ background: 'var(--app-bg)' }}>
                            <IconRouter size={16} className="text-[var(--brand)]" />
                          </div>
                          <div>
                            <p className="text-[12px] font-bold text-[var(--text-primary)]">{t.settingsPage.transitDelay}</p>
                          </div>
                        </div>
                        {canManage && (
                          <button
                            type="button"
                            onClick={() => {
                              setEditingSla({ key: 'ops.sla.pickup-limit-minutes', label: t.settingsPage.transitDelay, value: slaSettings['ops.sla.pickup-limit-minutes'] || '0' });
                              setNewSlaValue(slaSettings['ops.sla.pickup-limit-minutes'] || '0');
                              setSlaEditOpen(true);
                            }}
                            className="text-[11px] font-semibold px-2 py-1 rounded-md text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors"
                          >
                            {t.actions.edit}
                          </button>
                        )}
                      </div>
                      <p className="text-[10px] text-[var(--text-muted)] mb-4 leading-relaxed">{t.settingsPage.transitDelayDesc}</p>
                      <div className="flex items-baseline gap-1">
                        <p className="text-[28px] font-black text-[var(--text-primary)]">{slaSettings['ops.sla.pickup-limit-minutes'] || '0'}</p>
                        <p className="text-[11px] font-semibold text-[var(--text-muted)]">MIN</p>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* SECTION: IAM */}
              {section === 'IAM' && (
                <div className="flex flex-col gap-6 animate-fade-in">
                  <div className="flex items-center justify-between">
                    <p className="text-[11px] font-semibold text-[var(--text-muted)]">{t.settingsPage.accessControl}</p>
                    {canManage && (
                      <Button size="sm" onClick={() => setAddOpen(true)} className="rounded-md">
                        <IconPlus size={14} className="mr-1" />
                        {t.settingsPage.newUser}
                      </Button>
                    )}
                  </div>

                  <div className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--border)', background: 'var(--surface)' }}>
                    <table className="w-full border-collapse text-[11px]">
                      <thead style={{ background: 'var(--app-bg)' }}>
                        <tr>
                          <th className="text-[11px] font-semibold text-[var(--text-muted)] py-3 px-4 text-left">{t.settingsPage.actor}</th>
                          <th className="text-[11px] font-semibold text-[var(--text-muted)] py-3 px-4 text-left">{t.settingsPage.authorization}</th>
                          <th className="text-[11px] font-semibold text-[var(--text-muted)] py-3 px-4 text-left">{t.settingsPage.creationDate}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {adminUsers.map(u => (
                          <tr key={u.id} className="border-t border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors">
                            <td className="py-3 px-4">
                              <p className="text-[11px] font-bold text-[var(--text-primary)]">{u.name}</p>
                              <p className="text-[10px] text-[var(--text-muted)]">{u.email}</p>
                            </td>
                            <td className="py-3 px-4">
                              <span
                                className="text-[10px] font-semibold px-2 py-0.5 rounded-md inline-flex items-center gap-1.5 border"
                                style={{
                                  color: u.role === 'ADMIN' ? '#A52B24' : u.role === 'DISPATCHER' ? '#4C56B8' : '#1A7A9A',
                                  background: u.role === 'ADMIN' ? 'rgba(199,55,47,0.09)' : u.role === 'DISPATCHER' ? 'rgba(94,106,210,0.09)' : 'rgba(37,148,184,0.09)',
                                  borderColor: u.role === 'ADMIN' ? 'rgba(199,55,47,0.15)' : u.role === 'DISPATCHER' ? 'rgba(94,106,210,0.15)' : 'rgba(37,148,184,0.15)',
                                }}
                              >
                                <span
                                  className="inline-block w-1 h-1 rounded-full"
                                  style={{
                                    background: u.role === 'ADMIN' ? '#A52B24' : u.role === 'DISPATCHER' ? '#4C56B8' : '#1A7A9A'
                                  }}
                                />
                                {u.role.charAt(0) + u.role.slice(1).toLowerCase()}
                              </span>
                            </td>
                            <td className="py-3 px-4">
                              <p className="text-[11px] text-[var(--text-muted)]">{u.createdAt ? formatDate(u.createdAt) : '--/--/--'}</p>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

            </div>
          </div>
        </div>
      </div>

      {/* ── Add User Modal ─────────────────────────────────────── */}
      <AppModal
        open={addOpen && canManage}
        onClose={() => setAddOpen(false)}
        title={t.settingsPage.iamGovernance}
        size="sm"
        footer={
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => setAddOpen(false)} disabled={submitting}>
              {t.settingsPage.cancelButton}
            </Button>
            <Button size="sm" form="add-user-form" type="submit" disabled={submitting}>
              {submitting && (
                <svg className="animate-spin h-3 w-3 mr-1" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                </svg>
              )}
              {t.settingsPage.initializeAccess}
            </Button>
          </div>
        }
      >
        <form id="add-user-form" onSubmit={handleAddUser} className="flex flex-col gap-4">
          <div>
            <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.fullName}</label>
            <input
              className="w-full h-9 px-3 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
              style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
              placeholder={t.settingsPage.fullNameExample}
              value={formName}
              onChange={e => setFormName(e.currentTarget.value)}
              required
            />
          </div>
          <div>
            <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.loginEmail}</label>
            <input
              type="email"
              className="w-full h-9 px-3 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
              style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
              placeholder={t.settingsPage.loginEmailExample}
              value={formEmail}
              onChange={e => setFormEmail(e.currentTarget.value)}
              required
            />
          </div>
          <div>
            <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.temporaryPassword}</label>
            <div className="relative">
              <input
                type={showAdminPass ? 'text' : 'password'}
                className="w-full h-9 px-3 pr-9 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
                style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                value={formPassword}
                onChange={e => setFormPassword(e.currentTarget.value)}
                required
              />
              <button
                type="button"
                className="absolute right-2 top-1/2 -translate-y-1/2 text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                onClick={() => setShowAdminPass(!showAdminPass)}
              >
                {showAdminPass ? <IconEyeOff size={14} /> : <IconEye size={14} />}
              </button>
            </div>
          </div>
          <div>
            <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-1">{t.settingsPage.profilePrivileges}</label>
            <select
              className="w-full h-9 px-3 text-sm rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
              style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
              value={formRole}
              onChange={e => setFormRole(e.currentTarget.value)}
              disabled={submitting}
            >
              <option value="ADMIN">Admin</option>
              <option value="DISPATCHER">Dispatcher</option>
              <option value="MANAGER">Manager</option>
            </select>
          </div>
        </form>
      </AppModal>

      {/* ── Edit SLA Modal ─────────────────────────────────────── */}
      <AppModal
        open={slaEditOpen}
        onClose={() => setSlaEditOpen(false)}
        title={t.settingsPage.slaThresholdCert}
        size="sm"
        footer={
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => setSlaEditOpen(false)}>{t.settingsPage.cancelButton}</Button>
            <Button size="sm" onClick={() => {
              if (editingSla) updateSetting(editingSla.key, newSlaValue);
              setSlaEditOpen(false);
            }}>
              {t.settingsPage.applyButton}
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-6">
          {/* Description Section */}
          <div className="rounded-lg p-4" style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}>
            <p className="text-[11px] font-semibold text-[var(--text-muted)] mb-2">{t.settingsPage.whatMeasure}</p>
            <p className="text-[12px] text-[var(--text-primary)] leading-relaxed">
              {editingSla?.key === 'ops.sla.waiting-limit-minutes' && t.settingsPage.waitingTimeDesc}
              {editingSla?.key === 'ops.sla.assign-limit-minutes' && t.settingsPage.assignmentDelayDesc}
              {editingSla?.key === 'ops.sla.pickup-limit-minutes' && t.settingsPage.transitDelayDesc}
            </p>
          </div>

          {/* Current Value Section */}
          <div>
            <p className="text-[11px] font-semibold text-[var(--text-muted)] mb-3">{t.settingsPage.currentThreshold}</p>
            <div className="flex items-baseline gap-2 px-4 py-3 rounded-md" style={{ background: 'var(--app-bg)', border: '1px solid var(--border)' }}>
              <p className="text-[28px] font-black text-[var(--text-primary)]">{editingSla?.value}</p>
              <p className="text-[12px] font-semibold text-[var(--text-muted)]">MIN</p>
            </div>
          </div>

          {/* Input Section */}
          <div>
            <label className="block text-[11px] font-semibold text-[var(--text-muted)] mb-2">{t.settingsPage.setNewThreshold}</label>
            <div className="flex gap-2">
              <input
                type="number"
                className="flex-1 h-12 px-3 text-[18px] font-black font-mono rounded-md outline-none focus:ring-1 focus:ring-[var(--brand)]"
                style={{ border: '1px solid var(--border)', background: 'var(--app-bg)', color: 'var(--text-primary)' }}
                value={newSlaValue}
                onChange={(e) => setNewSlaValue(e.currentTarget.value)}
                min="0"
                placeholder="0"
              />
              <div className="flex items-center px-3 rounded-md" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                <p className="text-[12px] font-semibold text-[var(--text-muted)]">MIN</p>
              </div>
            </div>
            <p className="text-[10px] text-[var(--text-muted)] mt-2">{t.settingsPage.slaBreachWarning}</p>
          </div>

          {/* Recommendation Section */}
          <div className="rounded-lg p-4" style={{ background: 'var(--app-bg)', border: '1px dashed var(--border)' }}>
            <p className="text-[11px] font-semibold text-[var(--text-muted)] mb-2">{t.settingsPage.recommendation}</p>
            <p className="text-[10px] text-[var(--text-muted)] leading-relaxed">
              {editingSla?.key === 'ops.sla.waiting-limit-minutes' && t.settingsPage.waitingRec}
              {editingSla?.key === 'ops.sla.assign-limit-minutes' && t.settingsPage.assignmentRec}
              {editingSla?.key === 'ops.sla.pickup-limit-minutes' && t.settingsPage.pickupRec}
            </p>
          </div>
        </div>
      </AppModal>
    </div>
  );
}
