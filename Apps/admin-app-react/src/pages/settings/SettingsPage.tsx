import { useEffect, useState, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useT } from '@/lib/i18n/LocaleContext';
import { canManageSettings, getCurrentRole } from '@/lib/api/auth';
import { AdminUser } from '@/types';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { applyFieldError } from '@/lib/utils/form-errors';
import { formatDate } from '@/lib/utils/date';
import { TablePagination } from '@/components/data-display/TablePagination';
import {
  IconPlus, IconLock, IconClock, IconShieldCheck, IconChevronRight,
  IconFingerprint, IconEye, IconEyeOff, IconDotsVertical, IconPencil, IconBan, IconLogout,
  IconHourglass, IconAlertTriangle, IconArrowBackUp, IconInfoCircle, IconRouter,
  IconBuildingStore, IconCheck, IconSettings, IconRoute, IconChartBar, IconCircleCheck,
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { tw } from '@/lib/ui/typography';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { SectionCard } from '@/components/ui/section-card';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';

// ── Types ──────────────────────────────────────────────────────────────────
type SettingSection = 'COMPANY' | 'SLA' | 'IAM';

// SLA threshold cards — each maps a settings key to its label/description/default + icon.
const SLA_CARDS: { key: string; icon: typeof IconClock; def: string;
  label: keyof TSettings; desc: keyof TSettings }[] = [
  { key: 'ops.sla.assign-leadtime-minutes', icon: IconClock,        def: '120', label: 'waitingTime',     desc: 'waitingTimeDesc' },
  { key: 'ops.sla.assign-limit-minutes',    icon: IconChevronRight,  def: '0',   label: 'assignmentDelay', desc: 'assignmentDelayDesc' },
  { key: 'ops.sla.pickup-limit-minutes',    icon: IconRouter,        def: '0',   label: 'transitDelay',    desc: 'transitDelayDesc' },
  { key: 'ops.sla.waiting-limit-minutes',   icon: IconHourglass,     def: '15',  label: 'waitingLimit',    desc: 'waitingLimitDesc' },
  { key: 'ops.sla.at-risk-window-minutes',  icon: IconAlertTriangle, def: '30',  label: 'atRiskWindow',    desc: 'atRiskWindowDesc' },
  { key: 'ops.sla.replan-grace-minutes',    icon: IconArrowBackUp,   def: '60',  label: 'replanGrace',     desc: 'replanGraceDesc' },
];
// Per-SLA recommendation copy key (shown in the edit modal).
const SLA_REC: Record<string, keyof TSettings> = {
  'ops.sla.assign-leadtime-minutes': 'waitingRec',
  'ops.sla.assign-limit-minutes': 'assignmentRec',
  'ops.sla.pickup-limit-minutes': 'pickupRec',
  'ops.sla.waiting-limit-minutes': 'waitingLimitRec',
  'ops.sla.at-risk-window-minutes': 'atRiskWindowRec',
  'ops.sla.replan-grace-minutes': 'replanGraceRec',
};
type TSettings = Record<string, string>;

const ROLE_TONE: Record<string, string> = {
  ADMIN: 'var(--danger)', DISPATCHER: 'var(--brand)', MANAGER: 'var(--info)',
};
const ROLE_ICON: Record<string, typeof IconShieldCheck> = {
  ADMIN: IconShieldCheck, DISPATCHER: IconRoute, MANAGER: IconChartBar,
};

export default function SettingsPage() {
  const t = useT();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const sp = t.settingsPage as TSettings;
  const [section, setSection] = useState<SettingSection>('COMPANY');
  const [role, setRole] = useState<'ADMIN' | 'DISPATCHER' | 'MANAGER' | 'UNKNOWN'>('UNKNOWN');
  const [adminUsers, setAdminUsers] = useState<AdminUser[]>([]);
  const [userPage, setUserPage] = useState(0);
  const [userPageSize, setUserPageSize] = useState(25);
  // Client-side pagination for the admin-users table (bounded reference data).
  const userTotalPages = Math.max(1, Math.ceil(adminUsers.length / userPageSize));
  const userSafePage = Math.min(userPage, userTotalPages - 1);
  const pageUsers = useMemo(
    () => adminUsers.slice(userSafePage * userPageSize, userSafePage * userPageSize + userPageSize),
    [adminUsers, userSafePage, userPageSize],
  );
  const [slaSettings, setSlaSettings] = useState<Record<string, string>>({});
  const [addOpen, setAddOpen] = useState(false);

  // SLA edit modal
  const [slaEditOpen, setSlaEditOpen] = useState(false);
  const [editingSla, setEditingSla] = useState<{ key: string; label: string; value: string } | null>(null);
  const [newSlaValue, setNewSlaValue] = useState('');

  const [submitting, setSubmitting] = useState(false);
  const [showAdminPass, setShowAdminPass] = useState(false);

  // Add-user form (RHF + zod)
  const addUserSchema = useMemo(() => z.object({
    name: z.string().trim().min(1, t.validation.required),
    email: z.string().trim().min(1, t.validation.required).email(t.validation.invalidEmail),
    password: z.string().min(6, t.validation.minLength),
    role: z.string(),
  }), [t]);
  type AddUserForm = z.infer<typeof addUserSchema>;
  const addForm = useForm<AddUserForm>({
    resolver: zodResolver(addUserSchema),
    defaultValues: { name: '', email: '', password: '', role: 'ADMIN' },
  });

  // Edit-user form (RHF + zod)
  const [editOpen, setEditOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<AdminUser | null>(null);
  const editUserSchema = useMemo(() => z.object({
    name: z.string().trim().min(1, t.validation.required),
    email: z.string().trim().min(1, t.validation.required).email(t.validation.invalidEmail),
    role: z.string(),
  }), [t]);
  type EditUserForm = z.infer<typeof editUserSchema>;
  const editForm = useForm<EditUserForm>({
    resolver: zodResolver(editUserSchema),
    defaultValues: { name: '', email: '', role: 'ADMIN' },
  });

  // Company branding
  const [company, setCompany] = useState<{ name: string; supportEmail?: string; address?: string; city?: string; phone?: string; taxId?: string; registrationNumber?: string; primaryColor?: string } | null>(null);
  const [companySaving, setCompanySaving] = useState(false);

  const fetchAdminUsers = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/users');
      setAdminUsers(Array.isArray(res.data) ? res.data : res.data.content ?? []);
    } catch { /* fail safe */ }
  }, []);

  const fetchSlaSettings = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/reports/settings');
      setSlaSettings(res.data);
    } catch { /* fail safe */ }
  }, []);

  const fetchCompany = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/companies/me');
      if (res.data) setCompany({
        name: res.data.name, supportEmail: res.data.supportEmail,
        address: res.data.address, city: res.data.city, phone: res.data.phone,
        taxId: res.data.taxId, registrationNumber: res.data.registrationNumber,
        primaryColor: res.data.primaryColor,
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
        name: res.data.name, supportEmail: res.data.supportEmail,
        address: res.data.address, city: res.data.city, phone: res.data.phone,
        taxId: res.data.taxId, registrationNumber: res.data.registrationNumber,
        primaryColor: res.data.primaryColor,
      });
    } catch (err) {
      showErrorToast(err, 'errorCompanyUpdateFailed');
    } finally {
      setCompanySaving(false);
    }
  };

  const handleSyncCompanyFromErp = async () => {
    setCompanySaving(true);
    try {
      const res = await api.post('/api/admin/companies/me/sync-erp');
      if (res.data) setCompany({
        name: res.data.name, supportEmail: res.data.supportEmail,
        address: res.data.address, city: res.data.city, phone: res.data.phone,
        taxId: res.data.taxId, registrationNumber: res.data.registrationNumber,
        primaryColor: res.data.primaryColor,
      });
      showSuccessToast('successCompanySynced');
    } catch (err) {
      showErrorToast(err, 'errorCompanySyncFailed');
    } finally {
      setCompanySaving(false);
    }
  };


  useEffect(() => {
    const r = getCurrentRole();
    setRole(r);
    if (r === 'ADMIN') fetchAdminUsers();
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

  const handleAddUser = addForm.handleSubmit(async (data) => {
    setSubmitting(true);
    try {
      await api.post('/api/admin/users', data);
      showSuccessToast('successUserCreated');
      setAddOpen(false);
      addForm.reset();
      fetchAdminUsers();
    } catch (err) {
      // Taken email → under the field; the toast self-suppresses for this code.
      applyFieldError(err, addForm.setError, { USER_EMAIL_EXISTS: { field: 'email', message: t.validation.emailTaken } });
      showErrorToast(err, 'errorSaveFailed');
    } finally {
      setSubmitting(false);
    }
  });

  const handleEditUser = editForm.handleSubmit(async (data) => {
    if (!editingUser) return;
    setSubmitting(true);
    try {
      await api.put(`/api/admin/users/${editingUser.id}`, data);
      showSuccessToast('successUserUpdated');
      setEditOpen(false);
      setEditingUser(null);
      fetchAdminUsers();
      // If the edited user is the signed-in one, refresh the live profile so the top bar updates now.
      queryClient.invalidateQueries({ queryKey: ['me'] });
    } catch (err) {
      applyFieldError(err, editForm.setError, { USER_EMAIL_EXISTS: { field: 'email', message: t.validation.emailTaken } });
      showErrorToast(err, 'errorUserUpdateFailed');
    } finally {
      setSubmitting(false);
    }
  });

  const handleToggleStatus = async (user: AdminUser) => {
    try {
      await api.patch(`/api/admin/users/${user.id}/status`, { active: !user.active });
      showSuccessToast('successUserUpdated');
      fetchAdminUsers();
    } catch (err) {
      showErrorToast(err, 'errorUserUpdateFailed');
    }
  };

  const handleResetPassword = async (user: AdminUser) => {
    try {
      await api.post(`/api/admin/users/${user.id}/reset-password-email`);
      showSuccessToast('successUserPasswordResetEmail');
    } catch (err) {
      showErrorToast(err, 'errorUserPasswordResetEmailFailed');
    }
  };

  const handleForceLogout = async (user: AdminUser) => {
    try {
      await api.post(`/api/admin/users/${user.id}/logout`);
      showSuccessToast('successUserForceLogout');
    } catch (err) {
      showErrorToast(err, 'errorUserForceLogoutFailed');
    }
  };

  const canManage = canManageSettings(role);
  const [mobileTab, setMobileTab] = useState<'nav' | 'content'>('content');

  const navSections = [
    { id: 'COMPANY', label: sp.companyBranding, icon: IconBuildingStore },
    { id: 'SLA', label: sp.slaParameters, icon: IconClock },
    { id: 'IAM', label: sp.identitiesAccess, icon: IconShieldCheck },
  ];

  const sectionMeta: Record<SettingSection, { title: string; subtitle: string }> = {
    COMPANY: { title: sp.companyBranding, subtitle: sp.companyBrandingDesc ?? '' },
    SLA: { title: sp.slaagreement, subtitle: sp.slaDesc },
    IAM: { title: sp.identitiesAccess, subtitle: sp.accessControl },
  };

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col bg-[var(--app-bg)]">
      {/* Mobile tab bar */}
      <div className="lg:hidden flex shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        {([['nav', sp.tabSections], ['content', sp.tabParameters]] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setMobileTab(tab)}
            className={cn('flex-1 h-10 text-xs font-semibold transition-colors',
              mobileTab === tab ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]' : 'text-[var(--text-muted)]')}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* ── Navigation rail ── */}
        <div className={cn('lg:w-[240px] border-r border-[var(--border)] bg-[var(--surface)] shrink-0 flex flex-col',
          mobileTab === 'nav' ? 'flex w-full' : 'hidden lg:flex')}>
          <div className="flex flex-col gap-1 p-3 flex-1">
            {navSections.map((s) => (
              <button
                key={s.id}
                type="button"
                onClick={() => { setSection(s.id as SettingSection); setMobileTab('content'); }}
                className={cn(
                  'px-3 py-2 rounded-md transition-all flex items-center gap-3 text-left text-sm border',
                  section === s.id
                    ? 'bg-[var(--hover-bg)] text-[var(--text-primary)] border-[var(--border)] font-semibold'
                    : 'border-transparent text-[var(--text-muted)] hover:bg-[var(--hover-bg)]/50 hover:border-[var(--border)]',
                )}
              >
                <s.icon size={16} className={cn(section === s.id ? 'text-[var(--brand)]' : 'text-[var(--text-muted)]')} />
                <span>{s.label}</span>
              </button>
            ))}

          </div>

          <div className="p-4 border-t border-[var(--border)]">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-md flex items-center justify-center border border-[var(--border)] bg-[var(--app-bg)]">
                <IconFingerprint size={16} className="text-[var(--text-muted)]" />
              </div>
              <p className="text-xs font-bold text-[var(--text-primary)]">{role}</p>
            </div>
          </div>
        </div>

        {/* ── Content ── */}
        <div className={cn('flex-1 flex flex-col overflow-hidden min-w-0 bg-[var(--app-bg)]',
          mobileTab === 'content' ? 'flex' : 'hidden lg:flex')}>
          <div className="overflow-y-auto flex-1 p-6">
            <div className="max-w-[1000px] mx-auto">
              {/* Header */}
              <div className="flex items-start justify-between gap-4 pb-5 mb-6 border-b border-[var(--border)]">
                <div>
                  <h1 className={tw.pageTitle}>{sectionMeta[section].title}</h1>
                  {sectionMeta[section].subtitle && <p className={cn(tw.subtitle, 'mt-0.5')}>{sectionMeta[section].subtitle}</p>}
                </div>
                {!canManage && (
                  <Badge variant="outline" className="gap-1 text-[var(--warning)] border-[var(--warning)]/30 shrink-0">
                    <IconLock size={12} /> {sp.readOnlyMode}
                  </Badge>
                )}
              </div>

              {/* SECTION: COMPANY */}
              {section === 'COMPANY' && (
                <SectionCard>
                  <div className="flex flex-col gap-5">
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <FieldInput
                        label={sp.instanceName}
                        value={company?.name ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, name: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                      <FieldInput
                        label={sp.supportContact}
                        value={company?.supportEmail ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, supportEmail: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <FieldInput
                        label={sp.companyAddress}
                        value={company?.address ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, address: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                      <div>
                        <label className={cn(tw.label, 'block mb-1.5')}>{sp.primaryColor}</label>
                        <div className="flex items-center gap-2">
                          <input
                            type="color"
                            className="w-10 h-9 border border-[var(--border)] rounded-md cursor-pointer bg-[var(--app-bg)]"
                            value={company?.primaryColor ?? '#F08734'}
                            onChange={(e) => setCompany((p) => p ? { ...p, primaryColor: e.target.value } : null)}
                            disabled={!canManage || companySaving}
                          />
                          <FieldInput
                            wrapperClassName="flex-1"
                            value={company?.primaryColor ?? '#F08734'}
                            onChange={(e) => setCompany((p) => p ? { ...p, primaryColor: e.target.value } : null)}
                            disabled={!canManage || companySaving}
                          />
                        </div>
                      </div>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <FieldInput
                        label={sp.companyCity}
                        value={company?.city ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, city: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                      <FieldInput
                        label={sp.companyPhone}
                        value={company?.phone ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, phone: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <FieldInput
                        label={sp.companyTaxId}
                        value={company?.taxId ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, taxId: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                      <FieldInput
                        label={sp.companyRegistration}
                        value={company?.registrationNumber ?? ''}
                        onChange={(e) => setCompany((p) => p ? { ...p, registrationNumber: e.target.value } : null)}
                        disabled={!canManage || companySaving}
                      />
                    </div>

                    {canManage && (
                      <div className="flex justify-between items-center pt-1 gap-2">
                        <Button size="sm" variant="outline" onClick={handleSyncCompanyFromErp} disabled={!canManage || companySaving} title={sp.syncFromErpHint}>
                          {sp.syncFromErp ?? "Synchroniser depuis l'ERP"}
                        </Button>
                        <Button size="sm" onClick={handleSaveCompany} disabled={companySaving}>
                          {companySaving ? (sp.savingLabel ?? 'Enregistrement…') : (sp.saveConfig ?? 'Enregistrer')}
                        </Button>
                      </div>
                    )}
                  </div>
                </SectionCard>
              )}

              {/* SECTION: SLA */}
              {section === 'SLA' && (
                <div className="flex flex-col gap-6">
                  {/* Guide */}
                  <SectionCard padding contentClassName="p-5">
                    <div className="flex items-center gap-2 mb-2">
                      <IconInfoCircle size={16} className="text-[var(--brand)]" />
                      <p className={tw.cardTitle}>{sp.slaGuideTitle}</p>
                    </div>
                    <p className="text-xs text-[var(--text-muted)] leading-relaxed mb-4">{sp.slaGuideIntro}</p>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                      <div>
                        <p className={cn(tw.labelSm, 'mb-2')}>{sp.slaGuidePhasesHeading}</p>
                        <ul className="flex flex-col gap-1.5 text-xs leading-relaxed text-[var(--text-primary)]">
                          {[['slaPhasePlanning', 'slaPhasePlanningD'], ['slaPhaseAssignment', 'slaPhaseAssignmentD'],
                            ['slaPhaseDeparture', 'slaPhaseDepartureD'], ['slaPhaseDelivery', 'slaPhaseDeliveryD'],
                            ['slaPhaseHandoff', 'slaPhaseHandoffD'], ['slaPhaseTerminal', 'slaPhaseTerminalD']].map(([k, d]) => (
                            <li key={k}><span className="font-semibold">{sp[k]}</span> — {sp[d]}</li>
                          ))}
                        </ul>
                      </div>
                      <div>
                        <p className={cn(tw.labelSm, 'mb-2')}>{sp.slaHealthHeading}</p>
                        <ul className="flex flex-col gap-2 text-xs leading-relaxed text-[var(--text-primary)]">
                          {[['ON_TRACK', 'slaHealthOnTrack'], ['AT_RISK', 'slaHealthAtRisk'],
                            ['BREACHED', 'slaHealthBreached'], ['MET', 'slaHealthMet'],
                            ['LATE', 'slaHealthLate']].map(([status, k]) => {
                            const full = sp[k] ?? '';
                            const dash = full.indexOf('—');
                            const explanation = dash >= 0 ? full.slice(dash + 1).trim() : full;
                            const label = (t.slaTimeline as Record<string, any>)?.health?.[status] ?? status;
                            return (
                              <li key={status} className="flex items-start gap-2">
                                <StatusBadge status={status} label={label} size="sm" />
                                <span className="text-[var(--text-muted)]">{explanation}</span>
                              </li>
                            );
                          })}
                        </ul>
                      </div>
                    </div>
                    <p className="text-xs text-[var(--text-muted)] leading-relaxed mt-4 pt-3 border-t border-dashed border-[var(--border)]">{sp.slaGuideNote}</p>
                  </SectionCard>

                  {/* Threshold cards */}
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    {SLA_CARDS.map((c) => (
                      <div key={c.key} className="card p-5">
                        <div className="flex items-start justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <div className="w-8 h-8 rounded-md flex items-center justify-center bg-[var(--app-bg)]">
                              <c.icon size={16} className="text-[var(--brand)]" />
                            </div>
                            <p className={tw.cardTitle}>{sp[c.label]}</p>
                          </div>
                          {canManage && (
                            <Button
                              variant="ghost" size="sm"
                              className="h-7 px-2 text-xs text-[var(--brand)]"
                              onClick={() => {
                                const v = slaSettings[c.key] ?? c.def;
                                setEditingSla({ key: c.key, label: sp[c.label], value: v });
                                setNewSlaValue(v);
                                setSlaEditOpen(true);
                              }}
                            >
                              <IconSettings size={13} /> {t.actions.edit}
                            </Button>
                          )}
                        </div>
                        <p className="text-xs text-[var(--text-muted)] mb-4 leading-relaxed">{sp[c.desc]}</p>
                        <div className="flex items-baseline gap-1">
                          <p className="font-mono text-3xl font-semibold tabular-nums text-[var(--text-primary)]">{slaSettings[c.key] ?? c.def}</p>
                          <p className="text-xs font-semibold text-[var(--text-muted)]">MIN</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* SECTION: IAM */}
              {section === 'IAM' && (
                <div className="flex flex-col gap-4">
                  <div className="flex items-center justify-end">
                    {canManage && (
                      <Button size="sm" onClick={() => { addForm.reset(); setAddOpen(true); }} className="gap-1.5">
                        <IconPlus size={15} /> {sp.newUser}
                      </Button>
                    )}
                  </div>
                  <div className="rounded-xl border border-[var(--border)] bg-[var(--app-bg)] overflow-hidden">
                    <Table>
                      <TableHeader>
                        <TableRow className="bg-[var(--app-bg)] hover:bg-[var(--app-bg)]">
                          <TableHead className="text-2xs font-bold tracking-wider text-[var(--text-muted)]">{sp.actor}</TableHead>
                          <TableHead className="text-2xs font-bold tracking-wider text-[var(--text-muted)]">{sp.authorization}</TableHead>
                          <TableHead className="text-2xs font-bold tracking-wider text-[var(--text-muted)]">{sp.statusLabel}</TableHead>
                          <TableHead className="text-2xs font-bold tracking-wider text-[var(--text-muted)]">{sp.creationDate}</TableHead>
                          <TableHead />
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {pageUsers.map((u) => {
                          const RoleIcon = ROLE_ICON[u.role] ?? IconShieldCheck;
                          return (
                            <TableRow key={u.id} className="border-b border-[var(--border)] last:border-0">
                              <TableCell>
                                <p className="text-sm font-semibold text-[var(--text-primary)]">{u.name}</p>
                                <p className="text-xs text-[var(--text-muted)]">{u.email}</p>
                              </TableCell>
                              <TableCell>
                                <span className="inline-flex items-center gap-1.5 text-xs font-semibold"
                                      style={{ color: ROLE_TONE[u.role] ?? 'var(--text-muted)' }}>
                                  <RoleIcon size={13} />
                                  {u.role.charAt(0) + u.role.slice(1).toLowerCase()}
                                </span>
                              </TableCell>
                              <TableCell>
                                <span className="inline-flex items-center gap-1.5 text-xs font-semibold"
                                      style={{ color: u.active ? 'var(--success)' : 'var(--danger)' }}>
                                  {u.active
                                    ? <IconCircleCheck size={13} />
                                    : <IconBan size={13} />}
                                   {u.active ? (t.driversPage.statusActive ?? 'Active') : (t.driversPage.statusInactive ?? 'Inactive')}
                                </span>
                              </TableCell>
                              <TableCell className="text-xs text-[var(--text-muted)] tabular-nums">{u.createdAt ? formatDate(u.createdAt) : '—'}</TableCell>
                              <TableCell>
                                {canManage && (
                                  <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                      <button type="button" className="w-7 h-7 inline-flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--app-bg)] hover:text-[var(--text-primary)] transition-all">
                                        <IconDotsVertical size={14} />
                                      </button>
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="end" className="w-48 bg-[var(--surface)] border border-[var(--border)] shadow-lg rounded-md p-1">
                                      <DropdownMenuItem
                                        onClick={() => { setEditingUser(u); editForm.reset({ name: u.name, email: u.email, role: u.role }); setEditOpen(true); }}
                                        className="text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] gap-2 cursor-pointer rounded px-2.5 py-1.5">
                                        <IconPencil size={13} /> {t.driversPage.modifyButton ?? 'Edit'}
                                      </DropdownMenuItem>
                                      <DropdownMenuItem
                                        onClick={() => handleToggleStatus(u)}
                                        className="text-xs font-semibold gap-2 cursor-pointer rounded px-2.5 py-1.5"
                                        style={{ color: u.active ? 'var(--danger)' : 'var(--success)' }}>
                                        {u.active ? <><IconBan size={13} /> {t.driversPage.suspendDriverButton ?? 'Suspend'}</>
                                                  : <><IconCheck size={13} /> {t.driversPage.activateTooltip ?? 'Activate'}</>}
                                      </DropdownMenuItem>
                                      <DropdownMenuItem
                                        onClick={() => handleResetPassword(u)}
                                        className="text-xs font-semibold gap-2 cursor-pointer rounded px-2.5 py-1.5"
                                        style={{ color: 'var(--warning)' }}>
                                        <IconLock size={13} /> {sp.resetPassword}
                                      </DropdownMenuItem>
                                      {u.active && (
                                        <DropdownMenuItem
                                          onClick={() => handleForceLogout(u)}
                                          className="text-xs font-semibold gap-2 cursor-pointer rounded px-2.5 py-1.5"
                                          style={{ color: 'var(--danger)' }}>
                                          <IconLogout size={13} /> {t.driversPage.forceLogoutButton ?? 'Force Logout'}
                                        </DropdownMenuItem>
                                      )}
                                    </DropdownMenuContent>
                                  </DropdownMenu>
                                )}
                              </TableCell>
                            </TableRow>
                          );
                        })}
                      </TableBody>
                    </Table>
                    <TablePagination
                      page={userSafePage}
                      totalPages={userTotalPages}
                      totalElements={adminUsers.length}
                      size={userPageSize}
                      onPageChange={setUserPage}
                      onSizeChange={(s) => { setUserPageSize(s); setUserPage(0); }}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* ── Add User Modal ── */}
      <AppModal
        open={addOpen && canManage}
        onClose={() => setAddOpen(false)}
        title={sp.iamGovernance}
        size="sm"
        footer={
          <div className="flex gap-2 justify-end">
            <Button variant="ghost" size="sm" onClick={() => setAddOpen(false)} disabled={submitting}>{sp.cancelButton}</Button>
            <Button size="sm" form="add-user-form" type="submit" disabled={submitting}>{sp.initializeAccess}</Button>
          </div>
        }
      >
        <form id="add-user-form" onSubmit={handleAddUser} className="flex flex-col gap-4">
          <FieldInput label={sp.fullName} placeholder={sp.fullNameExample} required {...addForm.register('name')} error={addForm.formState.errors.name?.message} />
          <FieldInput type="email" label={sp.loginEmail} placeholder={sp.loginEmailExample} required {...addForm.register('email')} error={addForm.formState.errors.email?.message} />
          <FieldInput
            type={showAdminPass ? 'text' : 'password'}
            label={sp.temporaryPassword}
            required
            {...addForm.register('password')}
            error={addForm.formState.errors.password?.message}
            rightSection={
              <button type="button" className="text-[var(--text-muted)] hover:text-[var(--text-primary)]" onClick={() => setShowAdminPass(!showAdminPass)}>
                {showAdminPass ? <IconEyeOff size={14} /> : <IconEye size={14} />}
              </button>
            }
          />
          <FieldSelect
            label={sp.profilePrivileges}
            {...addForm.register('role')}
            disabled={submitting}
            options={[{ value: 'ADMIN', label: 'Admin' }, { value: 'DISPATCHER', label: 'Dispatcher' }, { value: 'MANAGER', label: 'Manager' }]}
          />
        </form>
      </AppModal>

      {/* ── Edit User Modal ── */}
      <AppModal
        open={editOpen && canManage}
        onClose={() => setEditOpen(false)}
        title={sp.modifyUserTitle ?? 'Edit User'}
        size="sm"
        footer={
          <div className="flex gap-2 justify-end">
            <Button variant="ghost" size="sm" onClick={() => setEditOpen(false)} disabled={submitting}>{sp.cancelButton}</Button>
            <Button size="sm" form="edit-user-form" type="submit" disabled={submitting}>{sp.updateAccess ?? 'Update Access'}</Button>
          </div>
        }
      >
        <form id="edit-user-form" onSubmit={handleEditUser} className="flex flex-col gap-4">
          <FieldInput label={sp.fullName} placeholder={sp.fullNameExample} required {...editForm.register('name')} error={editForm.formState.errors.name?.message} />
          <FieldInput type="email" label={sp.loginEmail} placeholder={sp.loginEmailExample} required {...editForm.register('email')} error={editForm.formState.errors.email?.message} />
          <FieldSelect
            label={sp.profilePrivileges}
            {...editForm.register('role')}
            disabled={submitting}
            options={[{ value: 'ADMIN', label: 'Admin' }, { value: 'DISPATCHER', label: 'Dispatcher' }, { value: 'MANAGER', label: 'Manager' }]}
          />
        </form>
      </AppModal>

      {/* ── Edit SLA Modal ── */}
      <AppModal
        open={slaEditOpen}
        onClose={() => setSlaEditOpen(false)}
        title={sp.slaThresholdCert}
        size="sm"
        footer={
          <div className="flex gap-2 justify-end">
            <Button variant="ghost" size="sm" onClick={() => setSlaEditOpen(false)}>{sp.cancelButton}</Button>
            <Button size="sm" onClick={() => { if (editingSla) updateSetting(editingSla.key, newSlaValue); setSlaEditOpen(false); }}>{sp.applyButton}</Button>
          </div>
        }
      >
        <div className="flex flex-col gap-5">
          <div className="rounded-lg p-4 bg-[var(--app-bg)] border border-[var(--border)]">
            <p className={cn(tw.label, 'mb-2')}>{sp.whatMeasure}</p>
            <p className="text-sm text-[var(--text-primary)] leading-relaxed">
              {editingSla && SLA_CARDS.find((c) => c.key === editingSla.key) && sp[SLA_CARDS.find((c) => c.key === editingSla.key)!.desc]}
            </p>
          </div>

          <div>
            <p className={cn(tw.label, 'mb-2')}>{sp.currentThreshold}</p>
            <div className="flex items-baseline gap-2 px-4 py-3 rounded-md bg-[var(--app-bg)] border border-[var(--border)]">
              <p className="font-mono text-3xl font-semibold tabular-nums text-[var(--text-primary)]">{editingSla?.value}</p>
              <p className="text-xs font-semibold text-[var(--text-muted)]">MIN</p>
            </div>
          </div>

          <div>
            <label className={cn(tw.label, 'block mb-2')}>{sp.setNewThreshold}</label>
            <div className="flex gap-2">
              <FieldInput
                type="number"
                wrapperClassName="flex-1"
                className="h-12 text-lg font-mono font-semibold"
                value={newSlaValue}
                onChange={(e) => setNewSlaValue(e.target.value)}
                min={0}
                placeholder="0"
              />
              <div className="flex items-center px-3 rounded-md border border-[var(--border)] bg-[var(--app-bg)] h-12">
                <p className="text-xs font-semibold text-[var(--text-muted)]">MIN</p>
              </div>
            </div>
            <p className="text-xs text-[var(--text-muted)] mt-2">{sp.slaBreachWarning}</p>
          </div>

          <div className="rounded-lg p-4 bg-[var(--app-bg)] border border-dashed border-[var(--border)]">
            <p className={cn(tw.label, 'mb-2')}>{sp.recommendation}</p>
            <p className="text-xs text-[var(--text-muted)] leading-relaxed">
              {editingSla && SLA_REC[editingSla.key] && sp[SLA_REC[editingSla.key]]}
            </p>
          </div>
        </div>
      </AppModal>
    </div>
  );
}
