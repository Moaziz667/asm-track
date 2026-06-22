

import React, { useCallback, useEffect, useRef, useState, useMemo } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { getCurrentRole, canDispatch } from '@/lib/auth';
import { cn } from '@/lib/utils';
import { IconChevronDown } from '@tabler/icons-react';
import { AppLoader } from '@/components/AppLoader';
import { PageFilterBar } from '@/components/layout/PageFilterBar';

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


type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

// ── Semantic tone (design tokens — never raw hex) ─────────────────────────────
// Each action maps to a tone by *intent* (create=success, delete/fail=danger, …); the colour
// is always a CSS design token so the page stays on-brand in light + dark and passes check:design.
type Tone = 'success' | 'info' | 'danger' | 'warning' | 'brand' | 'neutral';

const TONE_VAR: Record<Tone, string> = {
  success: 'var(--success)',
  info:    'var(--info)',
  danger:  'var(--danger)',
  warning: 'var(--warning)',
  brand:   'var(--brand)',
  neutral: 'var(--text-muted)',
};

/** Map an action code to a semantic tone. Order matters (danger before success for DEACTIVATE). */
function actionTone(action: string): Tone {
  const a = (action || '').toUpperCase();
  if (/(DELETE|FAIL|CANCEL|REJECT|REMOVE|SUSPEND|FORCE_LOGOUT|DEACTIVATE)/.test(a)) return 'danger';
  if (/(REASSIGN|REPLAN|ASSIGN|TOGGLE|HANDOFF|AUTO_OFFLINE|PIN_)/.test(a)) return 'warning';
  if (/(ODOO|ERP|RESYNC|IMPORT)/.test(a)) return 'brand';
  if (/(CREATE|START|ACCEPT|COMPLETE|CONFIRM|ACTIVAT|RESTOCK|ADD_STOP|INVITED)/.test(a)) return 'success';
  if (/(UPDATE|TRANSIT|ARRIVE|REORDER|TRANSFER|REPORT|BRANDING|SETTING)/.test(a)) return 'info';
  return 'neutral';
}

const ROLE_TONE: Record<string, Tone> = { ADMIN: 'info', DISPATCHER: 'brand', DRIVER: 'success', SYSTEM: 'neutral' };
const roleVar = (role: string) => TONE_VAR[ROLE_TONE[(role || '').toUpperCase()] ?? 'neutral'];

/** Deep-link an audit row to the entity it touched (null when there's no page for it). */
function entityHref(log: AuditLog): string | null {
  const id = log.resourceId;
  switch (log.targetEntity) {
    case 'ROUTE':                return id ? `/routes/${id}` : '/routes-table';
    case 'DELIVERY': case 'ORDER': return id ? `/deliveries/${id}` : '/deliveries';
    case 'VEHICLE':              return '/vehicles';
    case 'ZONE':                 return '/zones';
    case 'DEPOT':                return '/depots';
    case 'RMA': case 'RETURN':   return '/returns';
    case 'COMPANY':              return '/settings';
    case 'SLA_SETTINGS':         return '/settings';
    case 'FAILURE_REASON':       return '/failure-reasons';
    default:                     return null;
  }
}

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
  log, isExpanded, onToggle, locale, t,
}: {
  log: AuditLog;
  isExpanded: boolean;
  onToggle: () => void;
  locale: string;
  t: any;
}) {
  const tone = actionTone(log.action);
  const toneColor = TONE_VAR[tone];
  const roleColor = roleVar(log.actorRole);
  const actor = displayActor(log.actorName, log.actorRole);
  const verb = actionVerb(log.action, t);
  const href = entityHref(log);
  const entityText = resourceLabel(log, t);
  let payloadMessage: string | null = null;
  if (log.details) {
    try { const p = JSON.parse(log.details); if (typeof p.message === 'string' && p.message.length > 5) payloadMessage = p.message; } catch { /* not JSON */ }
  }
  const clock = new Date(log.createdAt).toLocaleTimeString(getLocaleFormat(locale), { hour: '2-digit', minute: '2-digit' });

  return (
    <div>
      {/* Dense single-line row — clock · tone dot · "actor verb entity" · role */}
      <div
        className="group flex items-center gap-3 h-9 px-2 rounded-md cursor-pointer transition-colors hover:bg-[var(--hover-bg)]"
        onClick={onToggle}
      >
        <span className="w-11 shrink-0 text-2xs text-[var(--text-muted)] tabular-nums" title={formatTs(log.createdAt, locale)}>{clock}</span>
        <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: toneColor }} />
        <p className="flex-1 min-w-0 truncate text-sm text-[var(--text-secondary)]">
          {payloadMessage ? (
            <span className="text-[var(--text-primary)]">{payloadMessage}</span>
          ) : (
            <><span className="font-[600] text-[var(--text-primary)]">{actor}</span> {verb}</>
          )}
          {entityText && (
            <>
              {' '}
              {href ? (
                <Link to={href} onClick={(e) => e.stopPropagation()} className="font-[500] text-[var(--brand)] hover:underline">{entityText}</Link>
              ) : (
                <span className="font-[500] text-[var(--text-primary)]">{entityText}</span>
              )}
            </>
          )}
        </p>
        <span className="shrink-0 text-3xs font-[700] uppercase tracking-wide hidden sm:inline" style={{ color: roleColor }}>{log.actorRole}</span>
        <IconChevronDown
          size={13}
          className={cn('shrink-0 text-[var(--text-soft)] opacity-0 group-hover:opacity-60 transition-transform', isExpanded && 'rotate-180 !opacity-100')}
        />
      </div>

      {/* Minimal expand — payload + one muted meta line, no card */}
      {isExpanded && (
        <div className="ml-[3.5rem] mb-2 mt-0.5 pl-3 border-l-2 border-[var(--border)] space-y-1.5">
          {log.details && <div className="text-2xs">{formatPayload(log.details, locale)}</div>}
          <p className="text-3xs font-mono text-[var(--text-soft)] break-all">
            {log.action} · {log.ipAddress} · {formatTs(log.createdAt, locale)} · {log.id.slice(0, 8)}
          </p>
        </div>
      )}
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

  // Single search (q → action/actor/resource) + attribute filters, all driving the shared PageFilterBar.
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [filterRole, setFilterRole] = useState('');
  const [filterEntity, setFilterEntity] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');

  const [loading, setLoading] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);

  // Debounce the search box so we don't fire a request per keystroke.
  useEffect(() => {
    const id = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(id);
  }, [search]);

  const fetchLogs = useCallback(async (p: number) => {
    if (abortRef.current) abortRef.current.abort();
    abortRef.current = new AbortController();

    setLoading(true);
    try {
      const params: Record<string, string | number> = { page: p - 1, size: PAGE_SIZE };
      if (debouncedSearch.trim()) params.q = debouncedSearch.trim();
      if (filterRole.trim()) params.actorRole = filterRole.trim();
      if (filterEntity.trim()) params.entity = filterEntity.trim();
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
  }, [debouncedSearch, filterRole, filterEntity, filterFrom, filterTo]);

  useEffect(() => {
    setPage(1);
    fetchLogs(1);
  }, [fetchLogs]);

  // Shared-filter wiring (role + entity selects, from/to dates).
  const filterAttributes = useMemo(() => ([
    { key: 'role', label: t.auditLogsPage.roleLabel, type: 'select' as const, options: [
      { value: 'ADMIN', label: 'ADMIN' }, { value: 'DISPATCHER', label: 'DISPATCHER' },
      { value: 'DRIVER', label: 'DRIVER' }, { value: 'SYSTEM', label: 'SYSTEM' },
    ] },
    { key: 'entity', label: t.auditLogsPage.entityFilterLabel ?? 'Entité', type: 'select' as const, options: [
      { value: 'ROUTE', label: t.auditLogsPage.entities.ROUTE }, { value: 'DELIVERY', label: t.auditLogsPage.entities.DELIVERY },
      { value: 'VEHICLE', label: t.auditLogsPage.entities.VEHICLE }, { value: 'ZONE', label: t.auditLogsPage.entities.ZONE },
      { value: 'DEPOT', label: t.auditLogsPage.entities.DEPOT }, { value: 'RMA', label: t.auditLogsPage.entities.RMA },
      { value: 'COMPANY', label: t.auditLogsPage.entities.COMPANY }, { value: 'ADMIN_USER', label: t.auditLogsPage.entities.ADMIN_USER },
      { value: 'DRIVER', label: t.auditLogsPage.entities.DRIVER },
    ] },
    { key: 'from', label: t.auditLogsPage.fromLabel, type: 'date' as const },
    { key: 'to', label: t.auditLogsPage.toLabel, type: 'date' as const },
  ]), [t]);

  const activeFilters: Record<string, string> = {
    ...(filterRole && { role: filterRole }),
    ...(filterEntity && { entity: filterEntity }),
    ...(filterFrom && { from: filterFrom }),
    ...(filterTo && { to: filterTo }),
  };

  const handleFilterChange = (key: string, value: string | null) => {
    if (key === 'role') setFilterRole(value ?? '');
    if (key === 'entity') setFilterEntity(value ?? '');
    if (key === 'from') setFilterFrom(value ?? '');
    if (key === 'to') setFilterTo(value ?? '');
  };

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
    <div className="h-auto lg:h-[calc(100dvh-56px)] overflow-visible lg:overflow-hidden flex flex-col" style={{ background: 'var(--app-bg)' }}>

      {/* Filter bar — shared PageFilterBar (search + role/entity/date attributes) */}
      <PageFilterBar
        search={search}
        onSearch={setSearch}
        searchPlaceholder={t.auditLogsPage.searchPlaceholder ?? t.auditLogsPage.actionPlaceholder}
        attributes={filterAttributes}
        activeFilters={activeFilters}
        onFilterChange={handleFilterChange}
        onRefresh={() => fetchLogs(page)}
        refreshing={loading}
        extraActions={
          <span className="text-xs font-semibold shrink-0" style={{ color: 'var(--text-muted)' }}>
            {totalElements} {t.auditLogsPage.eventsRecorded}
          </span>
        }
      />

      {/* Activity feed */}
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
          <div className="max-w-4xl mx-auto px-4 py-2">
            {dateGroupOrder.map(dateGroup => {
              const items = groupedLogs[dateGroup];
              if (!items) return null;
              return (
                <div key={dateGroup}>
                  {/* Date section header */}
                  <div className="flex items-center gap-3 pt-5 pb-3 pl-1">
                    <span className="text-2xs font-[800] uppercase tracking-[0.08em] text-[var(--text-soft)]">
                      {getDateGroupLabel(dateGroup)}
                    </span>
                    <div className="flex-1 h-[1px]" style={{ background: 'var(--border)' }} />
                    <span className="text-2xs font-[600] text-[var(--text-soft)] tabular-nums">
                      {items.length}
                    </span>
                  </div>

                  {/* Feed items */}
                  {items.map((log) => (
                    <FeedItem
                      key={log.id}
                      log={log}
                      isExpanded={expandedId === log.id}
                      onToggle={() => setExpandedId(expandedId === log.id ? null : log.id)}
                      locale={locale}
                      t={t}
                    />
                  ))}
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
