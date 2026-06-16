

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
  targetEntity?: string | null;
  resourceId: string | null;
  details: string | null;
  ipAddress: string;
  createdAt: string;
};

// ── Human-readable rendering helpers ──────────────────────────────────────────

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Clean the actor for display: a raw UUID becomes "Système", an email keeps its local part,
 *  otherwise the name as-is. So the actor column never shows machine garbage. */
function displayActor(name: string | undefined, role: string): string {
  const n = (name ?? '').trim();
  if (!n) return role || 'Système';
  if (UUID_RE.test(n)) return role === 'SYSTEM' ? 'Système' : (role || 'Système');
  if (n.includes('@')) return n.split('@')[0]; // email → local part
  return n;
}

/** Verb phrase for an action code, e.g. "a activé un compte". Falls back to a humanized code
 *  ("a effectué TOGGLE_ADMIN_USER_STATUS") rather than a meaningless "Audit Système". */
function actionVerb(action: string, t: any): string {
  const verbs = t.auditLogsPage?.verbs ?? {};
  if (verbs[action]) return verbs[action];
  // Humanize the raw code as a last resort.
  const human = action.replace(/_/g, ' ').toLowerCase();
  return `${t.auditLogsPage?.didAction ?? 'a effectué'} ${human}`;
}

/** A short, human resource label — prefers a name from the payload, else "ENTITY a1b2c3c4". */
function resourceLabel(log: AuditLog, t: any): string | null {
  // Try to pull a friendly name/ref out of the JSON details.
  if (log.details) {
    try {
      const p = JSON.parse(log.details);
      const named = p.name || p.label || p.ref || p.orderRef || p.email || p.plate;
      if (named) return String(named);
    } catch { /* not JSON */ }
  }
  if (!log.resourceId) return null;
  const ent = log.targetEntity ? (t.auditLogsPage?.entities?.[log.targetEntity] ?? log.targetEntity) : '';
  const shortId = log.resourceId.slice(0, 8);
  return ent ? `${ent} ${shortId}` : shortId;
}

/** Payload-first headline: if the details JSON has a "message" field, use that as the
 *  primary headline. Otherwise fall back to verb + resource composition. */
function getHeadline(log: AuditLog, t: any): { primary: string; secondary: string | null } {
  // Priority 1: payload "message" field — the most human-readable text
  if (log.details) {
    try {
      const p = JSON.parse(log.details);
      if (p.message && typeof p.message === 'string' && p.message.length > 5) {
        return { primary: p.message, secondary: null };
      }
    } catch { /* not JSON */ }
  }
  // Priority 2: composed verb + resource (existing logic)
  const verb = actionVerb(log.action, t);
  const res = resourceLabel(log, t);
  return { primary: verb, secondary: res };
}

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

// Stable per-role accent for the actor avatar/role label. Keeps the *person* who acted
// visually distinct (admin vs dispatcher vs driver) — the headline of each audit row.
const getRoleColor = (role: string): string => {
  switch ((role || '').toUpperCase()) {
    case 'ADMIN':      return '#3B82F6';
    case 'DISPATCHER': return '#8B5CF6';
    case 'DRIVER':     return '#10B981';
    case 'SYSTEM':     return '#71717A';
    default:           return '#6366F1';
  }
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

/** Human relative time: "à l'instant", "il y a 5 min", "il y a 2 h", "il y a 3 j", else a date. */
function relativeTime(ts: string, t: any): string {
  const r = t.auditLogsPage?.relative ?? {};
  const diffMs = Date.now() - new Date(ts).getTime();
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return r.now ?? "à l'instant";
  if (mins < 60) return (r.minutes ?? 'il y a {n} min').replace('{n}', String(mins));
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return (r.hours ?? 'il y a {n} h').replace('{n}', String(hrs));
  const days = Math.floor(hrs / 24);
  if (days < 7) return (r.days ?? 'il y a {n} j').replace('{n}', String(days));
  return new Date(ts).toLocaleDateString();
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
            <span className="text-2xs font-[600] text-[var(--text-muted)] min-w-fit">{getPayloadKeyLabel(key, locale)}:</span>
            <span className="text-2xs font-mono text-[var(--text-primary)] break-all">{String(value)}</span>
          </div>
        ))}
      </div>
    );
  } catch {
    return (
      <pre className="text-2xs font-mono text-[var(--text-muted)] whitespace-pre-wrap break-words">
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

// ── Feed Item ─────────────────────────────────────────────────────────────────

function FeedItem({
  log, isExpanded, isLast, onToggle, locale, t,
}: {
  log: AuditLog;
  isExpanded: boolean;
  isLast: boolean;
  onToggle: () => void;
  locale: string;
  t: any;
}) {
  const meta = getActionStyle(log.action, locale, t);
  const roleColor = getRoleColor(log.actorRole);
  const actor = displayActor(log.actorName, log.actorRole);
  const headline = getHeadline(log, t);

  return (
    <div className="relative flex gap-0">
      {/* Timeline column — dot + vertical line */}
      <div className="flex flex-col items-center shrink-0 w-8 pt-[2px]">
        {/* Action dot */}
        <div
          className="w-[10px] h-[10px] rounded-full shrink-0 mt-[5px] ring-[3px]"
          style={{
            background: meta.color,
            boxShadow: `0 0 0 3px ${meta.color}18`,
          }}
        />
        {/* Vertical connector */}
        {!isLast && (
          <div
            className="flex-1 w-[1.5px] mt-1"
            style={{ background: 'var(--border)' }}
          />
        )}
      </div>

      {/* Feed content */}
      <div className="flex-1 min-w-0 pb-5">
        {/* Main clickable area */}
        <div
          className="group rounded-lg px-3 py-2.5 -ml-1 cursor-pointer transition-colors hover:bg-[var(--hover-bg)]"
          onClick={onToggle}
        >
          {/* Row 1: Headline + timestamp */}
          <div className="flex items-start justify-between gap-3">
            <p className="text-[13px] font-[600] text-[var(--text-primary)] leading-snug min-w-0">
              {headline.primary}
              {headline.secondary && (
                <span className="text-[var(--text-muted)] font-[400]"> · {headline.secondary}</span>
              )}
            </p>
            <span
              className="text-2xs text-[var(--text-muted)] whitespace-nowrap shrink-0 mt-[2px]"
              title={formatTs(log.createdAt, locale)}
            >
              {relativeTime(log.createdAt, t)}
            </span>
          </div>

          {/* Row 2: Actor + category badge */}
          <div className="flex items-center gap-2 mt-1.5">
            {/* Actor pill */}
            <span className="inline-flex items-center gap-1.5">
              <span
                className="w-[18px] h-[18px] rounded-full flex items-center justify-center text-[8px] font-[700] shrink-0"
                style={{ background: `${roleColor}1A`, color: roleColor }}
              >
                {actor.trim().split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase() || '?'}
              </span>
              <span className="text-2xs text-[var(--text-muted)]">
                <span className="font-[600]">{actor}</span>
                <span className="mx-1 opacity-40">·</span>
                <span className="font-[700] uppercase tracking-wide" style={{ color: roleColor, fontSize: '9px' }}>{log.actorRole}</span>
              </span>
            </span>

            {/* Category badge */}
            <span
              className="text-[9px] font-[600] uppercase tracking-wider px-1.5 py-[1px] rounded-sm"
              style={{
                background: `${meta.color}12`,
                color: meta.color,
              }}
            >
              {meta.label}
            </span>

            {/* Expand indicator */}
            <IconChevronDown
              size={12}
              className={cn(
                'ml-auto text-[var(--text-soft)] opacity-0 group-hover:opacity-100 transition-all duration-200',
                isExpanded && 'rotate-180 opacity-100'
              )}
            />
          </div>
        </div>

        {/* Expanded details panel */}
        <div
          className={cn(
            'overflow-hidden transition-all duration-300 ease-in-out',
            isExpanded ? 'max-h-[500px] opacity-100' : 'max-h-0 opacity-0'
          )}
        >
          <div className="mx-2 mt-1 mb-1 rounded-lg border border-[var(--border)] overflow-hidden" style={{ background: 'var(--app-bg)' }}>
            <div className="px-3 py-2.5 space-y-3">
              {/* Metadata grid */}
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
                <div>
                  <p className="text-[9px] font-[700] uppercase tracking-wider text-[var(--text-soft)] mb-0.5">{t.auditLogsPage.eventId}</p>
                  <p className="text-2xs font-mono text-[var(--text-muted)] break-all select-all">{log.id}</p>
                </div>
                <div>
                  <p className="text-[9px] font-[700] uppercase tracking-wider text-[var(--text-soft)] mb-0.5">{t.auditLogsPage.engineCategory}</p>
                  <span className="inline-flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full" style={{ background: meta.color }} />
                    <span className="text-2xs font-[600] text-[var(--text-primary)]">{meta.label}</span>
                  </span>
                </div>
                <div>
                  <p className="text-[9px] font-[700] uppercase tracking-wider text-[var(--text-soft)] mb-0.5">{t.auditLogsPage.colIp}</p>
                  <p className="text-2xs font-mono text-[var(--text-muted)]">{log.ipAddress}</p>
                </div>
                <div>
                  <p className="text-[9px] font-[700] uppercase tracking-wider text-[var(--text-soft)] mb-0.5">{t.auditLogsPage.colTime}</p>
                  <p className="text-2xs font-mono text-[var(--text-muted)]">{formatTs(log.createdAt, locale)}</p>
                </div>
              </div>

              {/* Payload details */}
              {log.details && (
                <div>
                  <p className="text-[9px] font-[700] uppercase tracking-wider text-[var(--text-soft)] mb-1">{t.auditLogsPage.payloadDetails}</p>
                  <div className="text-2xs p-2 rounded-xs bg-[var(--surface)] border border-[var(--border)] max-h-40 overflow-y-auto">
                    {formatPayload(log.details, locale)}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
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

  const inputCls = "h-8 w-full px-2 text-xs rounded-xs border border-[var(--border)] bg-[var(--surface)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-[var(--brand)]";
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

  // Flatten all logs in order to determine "last" item for timeline connector
  const allLogsFlat = useMemo(() => {
    const flat: AuditLog[] = [];
    dateGroupOrder.forEach(dg => {
      if (groupedLogs[dg]) flat.push(...groupedLogs[dg]);
    });
    return flat;
  }, [groupedLogs]);

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] overflow-visible lg:overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>

      {/* Filter bar */}
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

      {/* Activity feed */}
      <div className="flex-1 overflow-auto" style={{ background: 'var(--app-bg)' }}>
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <AppLoader centered height="200px" size="sm" label={t.auditLogsPage.loadingLogs} />
          </div>
        ) : logs.length === 0 ? (
          <div className="flex items-center justify-center h-64">
            <p className="text-xs font-bold text-[var(--text-muted)]">{t.auditLogsPage.noLogs}</p>
          </div>
        ) : (
          <div className="max-w-4xl mx-auto px-4 py-2">
            {dateGroupOrder.map(dateGroup => {
              const items = groupedLogs[dateGroup];
              if (!items) return null;
              return (
                <div key={dateGroup}>
                  {/* Date section header */}
                  <div className="flex items-center gap-3 pt-5 pb-3 pl-1">
                    <span className="text-[10px] font-[800] uppercase tracking-[0.08em] text-[var(--text-soft)]">
                      {getDateGroupLabel(dateGroup)}
                    </span>
                    <div className="flex-1 h-[1px]" style={{ background: 'var(--border)' }} />
                    <span className="text-[10px] font-[600] text-[var(--text-soft)] tabular-nums">
                      {items.length}
                    </span>
                  </div>

                  {/* Feed items */}
                  {items.map((log, idx) => {
                    const isLastInGroup = idx === items.length - 1;
                    // Check if this is truly the last item across all groups
                    const isLastOverall = allLogsFlat[allLogsFlat.length - 1]?.id === log.id;

                    return (
                      <FeedItem
                        key={log.id}
                        log={log}
                        isExpanded={expandedId === log.id}
                        isLast={isLastOverall}
                        onToggle={() => setExpandedId(expandedId === log.id ? null : log.id)}
                        locale={locale}
                        t={t}
                      />
                    );
                  })}
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
