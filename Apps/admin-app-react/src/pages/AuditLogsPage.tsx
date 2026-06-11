

import React, { useCallback, useEffect, useRef, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';
import { useLocaleStore } from '@/lib/i18n';
import { useT, getCopy } from '@/lib/LocaleContext';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { cn } from '@/lib/utils';
import {
  IconRefresh, IconChevronDown,
} from '@tabler/icons-react';
import { AppLoader } from '@/components/AppLoader';
import { Button } from '@/components/ui/button';

// ── Types ────────────────────────────────────────────────────────────────────

type AuditLog = {
  id: string;
  actorName: string;
  actorRole: string;
  action: string;
  resourceId: string | null;
  details: string | null;
  ipAddress: string;
  createdAt: string;
};

type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

// ── Action Mapping ────────────────────────────────────────────────────────────

const getActionStyle = (action: string, locale: string, copy?: any) => {
  const actionMap: Record<string, { labelKey: string; color: string }> = {
    CREATE_ROUTE:        { labelKey: 'actionFluxRoute',      color: '#10B981' },
    UPDATE_ROUTE:        { labelKey: 'actionFluxRoute',      color: '#3B82F6' },
    DELETE_ROUTE:        { labelKey: 'actionFluxRoute',      color: '#EF4444' },
    START_ROUTE:         { labelKey: 'actionExecution',      color: '#10B981' },
    ARRIVE_STOP:         { labelKey: 'actionExecution',      color: '#3B82F6' },
    CREATE_VEHICLE:      { labelKey: 'actionFleetManagement',color: '#10B981' },
    UPDATE_VEHICLE:      { labelKey: 'actionFleetManagement',color: '#3B82F6' },
    DELETE_VEHICLE:      { labelKey: 'actionFleetManagement',color: '#EF4444' },
    ASSIGN_VEHICLE:      { labelKey: 'actionAssignment',     color: '#F59E0B' },
    CREATE_ZONE:         { labelKey: 'actionMeshing',        color: '#8B5CF6' },
    UPDATE_ZONE:         { labelKey: 'actionMeshing',        color: '#8B5CF6' },
    DRIVER_ACCEPT:       { labelKey: 'actionMobility',       color: '#10B981' },
    DRIVER_TRANSIT:      { labelKey: 'actionMobility',       color: '#3B82F6' },
    DRIVER_COMPLETE:     { labelKey: 'actionMobility',       color: '#10B981' },
    DRIVER_FAIL:         { labelKey: 'actionMobility',       color: '#EF4444' },
    REASSIGN_DELIVERY:   { labelKey: 'actionIntervention',   color: '#F59E0B' },
    APP_ORDER_CREATED:   { labelKey: 'actionSystem',         color: '#6366F1' },
    ODOO_RECV_ORDER:     { labelKey: 'actionErpSync',        color: '#A855F7' },
    // Driver lifecycle (Enterprise standard audit)
    DRIVER_INVITED:              { labelKey: 'actionDriverInvited',        color: '#3B82F6' },
    DRIVER_ACTIVATED:            { labelKey: 'actionDriverActivated',      color: '#10B981' },
    DRIVER_SUSPENDED:            { labelKey: 'actionDriverSuspended',      color: '#F59E0B' },
    DRIVER_INVITE_RESENT_BY_ADMIN:{ labelKey: 'actionDriverInviteResent',  color: '#8B5CF6' },
    DRIVER_INVITE_CANCELLED:     { labelKey: 'actionDriverInviteCancelled',color: '#EF4444' },
    DRIVER_UPDATED:              { labelKey: 'actionDriverUpdated',        color: '#6366F1' },
    DRIVER_PASSWORD_RESET:       { labelKey: 'actionDriverPasswordReset',  color: '#A855F7' },
    DRIVER_BULK_IMPORTED:        { labelKey: 'actionDriverBulkImported',   color: '#0891B2' },
    DRIVER_AUTO_OFFLINED:        { labelKey: 'actionDriverSuspended',      color: '#9CA3AF' },
    DRIVER_FORCE_LOGOUT:         { labelKey: 'actionDriverForceLogout',    color: '#EF4444' },
    CREATE_ADMIN_USER:           { labelKey: 'actionCreateAdminUser',      color: '#10B981' },
    TOGGLE_ADMIN_USER_STATUS:    { labelKey: 'actionToggleAdminUserStatus', color: '#F59E0B' },
    UPDATE_ADMIN_USER:           { labelKey: 'actionUpdateAdminUser',      color: '#3B82F6' },
    RESET_ADMIN_USER_PASSWORD:   { labelKey: 'actionResetAdminUserPassword',color: '#A855F7' },
    FORCE_LOGOUT_ADMIN_USER:     { labelKey: 'actionForceLogoutAdminUser',  color: '#EF4444' },
  };

  const resolvedCopy = copy || getCopy(locale as any);
  const meta = actionMap[action] ?? { labelKey: 'actionSystemAudit', color: '#71717A' };
  return { label: resolvedCopy.auditLogsPage[meta.labelKey as keyof typeof resolvedCopy.auditLogsPage] as string, color: meta.color };
};

const getLocaleFormat = (locale: string): string => {
  const map: Record<string, string> = { fr: 'fr-FR', en: 'en-US', ar: 'ar-SA' };
  return map[locale] || 'fr-FR';
};

function formatTs(ts: string, locale: string = 'fr') {
  const d = new Date(ts);
  return d.toLocaleString(getLocaleFormat(locale), {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function formatTime(ts: string, locale: string = 'fr') {
  const d = new Date(ts);
  return d.toLocaleString(getLocaleFormat(locale), {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function getPayloadKeyLabel(key: string, locale: string): string {
  const keyMap: Record<string, Record<string, string>> = {
    fr: {
      valeur: 'Valeur', value: 'Valeur',
      action: 'Action', message: 'Message',
      parametre: 'Paramètre', parameter: 'Paramètre',
      ancienneValeur: 'Ancienne Valeur', oldValue: 'Ancienne Valeur',
      nouvelleValeur: 'Nouvelle Valeur', newValue: 'Nouvelle Valeur',
      raison: 'Raison', reason: 'Raison',
      statut: 'Statut', status: 'Statut',
      description: 'Description', nom: 'Nom', name: 'Nom',
    },
    en: {
      valeur: 'Value', value: 'Value',
      action: 'Action', message: 'Message',
      parametre: 'Parameter', parameter: 'Parameter',
      ancienneValeur: 'Old Value', oldValue: 'Old Value',
      nouvelleValeur: 'New Value', newValue: 'New Value',
      raison: 'Reason', reason: 'Reason',
      statut: 'Status', status: 'Status',
      description: 'Description', nom: 'Name', name: 'Name',
    },
    ar: {
      valeur: 'القيمة', value: 'القيمة',
      action: 'الإجراء', message: 'الرسالة',
      parametre: 'المعامل', parameter: 'المعامل',
      ancienneValeur: 'القيمة السابقة', oldValue: 'القيمة السابقة',
      nouvelleValeur: 'القيمة الجديدة', newValue: 'القيمة الجديدة',
      raison: 'السبب', reason: 'السبب',
      statut: 'الحالة', status: 'الحالة',
      description: 'الوصف', nom: 'الاسم', name: 'الاسم',
    },
  };
  return keyMap[locale]?.[key] || key;
}

function getDateGroup(ts: string): { label: string; sort: number } {
  const d = new Date(ts);
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  const weekAgo = new Date(today);
  weekAgo.setDate(weekAgo.getDate() - 7);

  const dateStr = d.toLocaleDateString('fr-FR');
  const todayStr = today.toLocaleDateString('fr-FR');
  const yesterdayStr = yesterday.toLocaleDateString('fr-FR');

  if (dateStr === todayStr) return { label: 'Today', sort: 0 };
  if (dateStr === yesterdayStr) return { label: 'Yesterday', sort: 1 };
  if (d >= weekAgo) return { label: 'Earlier this week', sort: 2 };
  return { label: 'Older', sort: 3 };
}

function formatPayload(details: string | null, locale: string = 'fr'): React.ReactNode {
  if (!details) return null;
  try {
    const parsed = JSON.parse(details);
    return (
      <div className="space-y-1">
        {Object.entries(parsed).map(([key, value]) => (
          <div key={key} className="flex items-start gap-2">
            <span className="text-[9px] font-[600] text-[var(--text-muted)] min-w-fit">{getPayloadKeyLabel(key, locale)}:</span>
            <span className="text-[9px] font-mono text-[var(--text-primary)] break-all">{String(value)}</span>
          </div>
        ))}
      </div>
    );
  } catch {
    return (
      <pre className="text-[9px] font-mono text-[var(--text-muted)] whitespace-pre-wrap break-words">
        {details}
      </pre>
    );
  }
}

// ── Pagination ────────────────────────────────────────────────────────────────

function SimplePagination({ total, value, onChange }: { total: number; value: number; onChange: (p: number) => void }) {
  const pages = Array.from({ length: total }, (_, i) => i + 1);
  const visible = pages.filter(p => p === 1 || p === total || Math.abs(p - value) <= 2);

  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        onClick={() => onChange(value - 1)}
        disabled={value === 1}
        className="w-7 h-7 flex items-center justify-center rounded border text-xs font-bold disabled:opacity-30 hover:bg-[var(--hover-bg)] border-[var(--border)] text-[var(--text-muted)]"
      >
        ‹
      </button>
      {visible.map((p, idx, arr) => (
        <React.Fragment key={p}>
          {idx > 0 && arr[idx - 1] !== p - 1 && (
            <span className="text-xs text-[var(--text-muted)] px-1">…</span>
          )}
          <button
            type="button"
            onClick={() => onChange(p)}
            className={cn(
              'w-7 h-7 flex items-center justify-center rounded border text-xs font-bold',
              p === value
                ? 'bg-[var(--brand)] text-white border-[var(--brand)]'
                : 'border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]'
            )}
          >
            {p}
          </button>
        </React.Fragment>
      ))}
      <button
        type="button"
        onClick={() => onChange(value + 1)}
        disabled={value === total}
        className="w-7 h-7 flex items-center justify-center rounded border text-xs font-bold disabled:opacity-30 hover:bg-[var(--hover-bg)] border-[var(--border)] text-[var(--text-muted)]"
      >
        ›
      </button>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function AuditLogsPage() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const navigate = useNavigate();

  useEffect(() => {
    const r = getCurrentRole();
    if (r !== 'UNKNOWN' && !canDispatch(r)) {
      navigate('/dashboard', { replace: true });
    }
  }, [navigate]);

  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(1);
  const PAGE_SIZE = 50;

  const [filterAction, setFilterAction] = useState('');
  const [filterActor, setFilterActor] = useState('');
  const [filterRole, setFilterRole] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');

  const [loading, setLoading] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);

  const fetchLogs = useCallback(async (p: number) => {
    if (abortRef.current) abortRef.current.abort();
    abortRef.current = new AbortController();

    setLoading(true);
    try {
      const params: Record<string, string | number> = { page: p - 1, size: PAGE_SIZE };
      if (filterAction.trim()) params.action = filterAction.trim();
      if (filterActor.trim()) params.actor = filterActor.trim();
      if (filterRole.trim()) params.actorRole = filterRole.trim();
      if (filterFrom) params.from = filterFrom + 'T00:00:00';
      if (filterTo) params.to = filterTo + 'T23:59:59';

      const res = await api.get('/api/admin/audit', { params, signal: abortRef.current.signal });
      const data: Page<AuditLog> = res.data;
      setLogs(data.content);
      setTotalElements(data.totalElements);
      setTotalPages(data.totalPages);
    } catch (e: any) {
      if (e?.name !== 'AbortError') showErrorToast(e, 'errorDataLoadFailed');
    } finally {
      setLoading(false);
    }
  }, [filterAction, filterActor, filterRole, filterFrom, filterTo]);

  useEffect(() => {
    setPage(1);
    fetchLogs(1);
  }, [fetchLogs]);

  const handleReset = () => {
    setFilterAction('');
    setFilterActor('');
    setFilterRole('');
    setFilterFrom('');
    setFilterTo('');
  };

  const inputCls = "h-8 w-full px-2 text-xs rounded-[2px] border border-[var(--border)] bg-[var(--surface)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-[var(--brand)]";
  const labelCls = "block text-2xs font-[600] text-[var(--text-muted)] mb-1";

  // Group logs by date
  const groupedLogs = useMemo(() => {
    const groups: Record<string, AuditLog[]> = {};
    logs.forEach(log => {
      const { label } = getDateGroup(log.createdAt);
      if (!groups[label]) groups[label] = [];
      groups[label].push(log);
    });
    return groups;
  }, [logs]);

  const getDateGroupLabel = (group: string): string => {
    const labels: Record<string, string> = {
      'Today': t.auditLogsPage.dateToday,
      'Yesterday': t.auditLogsPage.dateYesterday,
      'Earlier this week': t.auditLogsPage.dateEarlierWeek,
      'Older': t.auditLogsPage.dateOlder,
    };
    return labels[group] || group;
  };

  const dateGroupOrder = ['Today', 'Yesterday', 'Earlier this week', 'Older'];

  return (
    <div className="h-[calc(100vh-64px)] overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>

      {/* Filter bar (title removed) */}
      <div className="px-4 py-2.5 shrink-0" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
        <div className="flex items-center gap-3 mb-2">
          <span className="text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>{totalElements} {t.auditLogsPage.eventsRecorded}</span>
          <button
            type="button"
            onClick={() => fetchLogs(page)}
            disabled={loading}
            className="ml-auto w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] disabled:opacity-50 transition-colors"
          >
            <IconRefresh size={13} />
          </button>
        </div>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-2 items-end">
          <div>
            <label className={labelCls}>{t.auditLogsPage.actionLabel}</label>
            <input className={inputCls} placeholder={t.auditLogsPage.actionPlaceholder} value={filterAction} onChange={e => setFilterAction(e.target.value)} />
          </div>
          <div>
            <label className={labelCls}>{t.auditLogsPage.actorLabel}</label>
            <input className={inputCls} placeholder={t.auditLogsPage.actorPlaceholder} value={filterActor} onChange={e => setFilterActor(e.target.value)} />
          </div>
          <div>
            <label className={labelCls}>{t.auditLogsPage.roleLabel}</label>
            <select className={inputCls} value={filterRole} onChange={e => setFilterRole(e.target.value)}>
              <option value="">{t.auditLogsPage.allRoles}</option>
              <option value="ADMIN">ADMIN</option>
              <option value="DISPATCHER">DISPATCHER</option>
              <option value="DRIVER">DRIVER</option>
              <option value="SYSTEM">SYSTEM</option>
            </select>
          </div>
          <div>
            <label className={labelCls}>{t.auditLogsPage.fromLabel}</label>
            <input type="date" className={inputCls} value={filterFrom} onChange={e => setFilterFrom(e.target.value)} />
          </div>
          <div>
            <label className={labelCls}>{t.auditLogsPage.toLabel}</label>
            <input type="date" className={inputCls} value={filterTo} onChange={e => setFilterTo(e.target.value)} />
          </div>
          <div>
            <Button variant="ghost" size="sm" onClick={handleReset} className="h-8 text-red-600 hover:text-red-600 hover:bg-red-50 text-2xs font-bold w-full">
              {t.auditLogsPage.resetButton}
            </Button>
          </div>
        </div>
      </div>

      {/* Timeline */}
      <div className="flex-1 overflow-auto" style={{ background: 'var(--surface)' }}>
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <AppLoader centered height="200px" size="sm" label={t.auditLogsPage.loadingLogs} />
          </div>
        ) : logs.length === 0 ? (
          <div className="flex items-center justify-center h-64">
            <p className="text-xs font-bold text-[var(--text-muted)]">{t.auditLogsPage.noLogs}</p>
          </div>
        ) : (
          <div className="px-6 py-4">
            {dateGroupOrder.map(dateGroup => {
              const items = groupedLogs[dateGroup];
              if (!items) return null;
              return (
                <div key={dateGroup} className="mb-6">
                  <p className="text-2xs font-[700] text-[var(--text-muted)] uppercase tracking-wide mb-3">{getDateGroupLabel(dateGroup)}</p>
                  <div className="space-y-0 border-l border-[var(--border)]">
                    {items.map((log, idx) => {
                      const meta = getActionStyle(log.action, locale, t);
                      const isExpanded = expandedId === log.id;
                      const isLast = idx === items.length - 1;

                      return (
                        <div key={log.id}>
                          {/* Timeline Item */}
                          <div
                            className="pl-4 py-2.5 pr-3 border-b border-[var(--border)] cursor-pointer transition-colors hover:bg-[var(--app-bg)] group"
                            onClick={() => setExpandedId(isExpanded ? null : log.id)}
                          >
                            <div className="flex items-start gap-3">
                              {/* Left semantic indicator */}
                              <div className="flex flex-col items-center gap-2 pt-0.5">
                                <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: meta.color }} />
                                {!isLast && <div className="w-px h-8 bg-[var(--border)]" />}
                              </div>

                              {/* Content */}
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2 mb-1">
                                  <span className="text-xs font-[700] font-mono text-[var(--text-primary)]">{meta.label}</span>
                                  <span className="text-2xs font-[500] text-[var(--text-muted)]">{t.auditLogsPage.byActor} {log.actorName}</span>
                                  <span className="text-[9px] font-[500] px-1.5 py-0.5 rounded-[2px] border border-[var(--border)] text-[var(--text-muted)]">{log.actorRole}</span>
                                </div>
                                <div className="flex items-center gap-4 mb-1">
                                  <span className="text-2xs font-[500] font-mono text-[var(--text-muted)]">{formatTime(log.createdAt, locale)}</span>
                                  {log.resourceId && (
                                    <span className="text-[9px] font-mono text-[var(--text-muted)] px-1.5 py-0.5 rounded-[2px]" style={{ background: 'var(--app-bg)' }}>
                                      {log.resourceId.slice(0, 14)}
                                    </span>
                                  )}
                                  <span className="text-[9px] font-mono text-[var(--text-muted)]">{log.ipAddress}</span>
                                </div>
                                <p className="text-2xs text-[var(--text-muted)]">{meta.label}</p>
                              </div>

                              {/* Expand indicator */}
                              <div className={cn('flex-shrink-0 text-[var(--text-muted)] transition-transform', isExpanded && 'rotate-180')}>
                                <IconChevronDown size={14} />
                              </div>
                            </div>

                            {/* Expanded Details */}
                            {isExpanded && (
                              <div className="mt-3 pt-3 border-t border-[var(--border)] ml-3">
                                <div className="grid grid-cols-2 gap-4 mb-3">
                                  <div>
                                    <p className="text-2xs font-[600] text-[var(--text-muted)] mb-1">{t.auditLogsPage.eventId}</p>
                                    <p className="text-2xs font-mono text-[var(--text-primary)]" title={log.id}>{log.id.slice(0, 8)}</p>
                                    <p className="text-[9px] text-[var(--text-muted)] mt-0.5">{t.auditLogsPage.fullId} {log.id}</p>
                                  </div>
                                  <div>
                                    <p className="text-2xs font-[600] text-[var(--text-muted)] mb-1">{t.auditLogsPage.engineCategory}</p>
                                    <span className="text-2xs font-[600] px-2 py-1 rounded-[2px] inline-block" style={{ background: 'var(--app-bg)', color: 'var(--text-primary)' }}>
                                      {meta.label}
                                    </span>
                                  </div>
                                </div>
                                {log.details && (
                                  <div>
                                    <p className="text-2xs font-[600] text-[var(--text-muted)] mb-1">{t.auditLogsPage.payloadDetails}</p>
                                    <div className="text-[9px] p-2 rounded-[2px] bg-[var(--app-bg)] border border-[var(--border)] max-h-32 overflow-y-auto">
                                      {formatPayload(log.details, locale)}
                                    </div>
                                  </div>
                                )}
                              </div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Pagination */}
      {!loading && totalPages > 1 && (
        <div className="p-3 border-t border-[var(--border)] flex items-center justify-center" style={{ background: 'var(--surface)' }}>
          <SimplePagination
            total={totalPages}
            value={page}
            onChange={(p) => { setPage(p); fetchLogs(p); }}
          />
        </div>
      )}
    </div>
  );
}

