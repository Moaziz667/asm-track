import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  IconCheck,
  IconSearch,
  IconRefresh,
  IconBellOff,
  IconChevronRight,
  IconAlertTriangle,
  IconAlertCircle,
  IconInfoCircle,
} from '@tabler/icons-react';

import {
  getLocalizedNotif,
  useNotifications,
} from '@/components/AlertsProvider';
import { SeverityIcon } from '@/components/AlertBell';

import { usePageBreadcrumb } from '@/lib/ui/breadcrumb';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/i18n/LocaleContext';
import { cn } from '@/lib/utils';
import { toast } from '@/lib/ui/toast';
import { notifDestination } from '@/lib/api/dispatch-link';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { TablePagination } from '@/components/data-display/TablePagination';

type Filter = 'all' | 'unread' | 'critical' | 'warning' | 'info';


function localeTag(locale: string) {
  return locale === 'fr' ? 'fr-FR' : locale === 'ar' ? 'ar' : 'en-US';
}


/**
 * Relative time, outside the component.
 *
 * These four strings used to be ternaries on `locale` inside the render, alongside a second
 * translation table that redefined words the copy files already carried — "Aujourd'hui",
 * "Tout marquer lu". Two mechanisms in one file means a translator fixes one and misses the other,
 * and the parity test only ever saw half of them.
 *
 * It lives at module scope because it closes over nothing that changes: reading the clock during
 * render is precisely what `react-hooks/purity` flags, and a pure helper taking its copy as an
 * argument has no reason to be a closure.
 */
function relativeTime(
  ts: number,
  copy: { justNow: string; minutesAgo: string; hoursAgo: string; daysAgo: string },
  tag: string,
) {
  const diff = Math.floor((Date.now() - ts) / 1000);
  if (diff < 60) return copy.justNow;
  if (diff < 3600) return copy.minutesAgo.replace('{n}', String(Math.floor(diff / 60)));
  if (diff < 86400) return copy.hoursAgo.replace('{n}', String(Math.floor(diff / 3600)));
  if (diff < 7 * 86400) return copy.daysAgo.replace('{n}', String(Math.floor(diff / 86400)));
  return new Date(ts).toLocaleDateString(tag, { day: '2-digit', month: 'short' });
}

export default function NotificationsPage() {
  const t = useT();
  const copy = t.notificationsPage;
  const { locale } = useLocaleStore();
  const tag = localeTag(locale);
  const navigate = useNavigate();

  const { notifications, unreadCount, markRead, markAllRead, refresh } = useNotifications();

  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);

  usePageBreadcrumb([{ label: copy.title }]);

  const counts = useMemo(() => ({
    all: notifications.length,
    unread: notifications.filter(n => !n.read).length,
    critical: notifications.filter(n => n.severity === 'critical').length,
    warning: notifications.filter(n => n.severity === 'warning').length,
    info: notifications.filter(n => n.severity === 'info').length,
  }), [notifications]);

  const filtered = useMemo(() => {
    let list = notifications;
    if (filter === 'unread') list = list.filter(n => !n.read);
    else if (filter !== 'all') list = list.filter(n => n.severity === filter);
    const q = query.trim().toLowerCase();
    if (q) {
      list = list.filter(n =>
        [n.orderId, n.driverName, n.routeName, n.clientName, n.event,
         getLocalizedNotif(n, locale).title, getLocalizedNotif(n, locale).message]
          .some(v => String(v ?? '').toLowerCase().includes(q)));
    }
    return list;
  }, [notifications, filter, query, locale]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const paginatedItems = useMemo(
    () => filtered.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [filtered, safePage, pageSize],
  );

  const groups = useMemo(() => {
    const todayStart = new Date().setHours(0, 0, 0, 0);
    return [
      { key: 'today', label: copy.groups.today, items: paginatedItems.filter(n => n.timestamp >= todayStart) },
      { key: 'yesterday', label: copy.groups.yesterday, items: paginatedItems.filter(n => {
        const yesterdayStart = new Date();
        yesterdayStart.setDate(yesterdayStart.getDate() - 1);
        yesterdayStart.setHours(0, 0, 0, 0);
        return n.timestamp >= yesterdayStart.getTime() && n.timestamp < todayStart;
      })},
      { key: 'older', label: copy.groups.older, items: paginatedItems.filter(n => {
        const yesterdayStart = new Date();
        yesterdayStart.setDate(yesterdayStart.getDate() - 1);
        yesterdayStart.setHours(0, 0, 0, 0);
        return n.timestamp < yesterdayStart.getTime();
      })},
    ].filter(g => g.items.length > 0);
  }, [paginatedItems, copy]);


  const openInDispatch = (n: typeof notifications[number]) => {
    if (!n.read) markRead(n.id);
    navigate(notifDestination(n));
  };

  const doRefresh = async () => {
    setRefreshing(true);
    try { await refresh(); toast.success(copy.refreshed); }
    catch { toast.error(copy.actionFail); }
    finally { setRefreshing(false); }
  };

  const FILTERS: { value: Filter; label: string; icon: typeof IconCheck }[] = [
    { value: 'all', label: copy.filters.all, icon: IconCheck },
    { value: 'unread', label: copy.filters.unread, icon: IconBellOff },
    { value: 'critical', label: copy.filters.critical, icon: IconAlertTriangle },
    { value: 'warning', label: copy.filters.warning, icon: IconAlertCircle },
    { value: 'info', label: copy.filters.info, icon: IconInfoCircle },
  ];


  return (
    <div className="flex h-full flex-col overflow-hidden bg-[var(--app-bg)]">
      {/* ── Header ── */}
      <header className="shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        <div className="mx-auto max-w-5xl w-full px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h1 className="text-base font-semibold text-[var(--text-primary)]">{copy.title}</h1>
              {unreadCount > 0 && (
                <span className="inline-flex items-center justify-center h-5 min-w-[20px] rounded-full bg-[var(--text-primary)] px-1.5 text-2xs font-bold text-[var(--surface)] tabular-nums">
                  {unreadCount}
                </span>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={doRefresh} disabled={refreshing} className="h-7 px-2.5 text-xs gap-1">
                <IconRefresh size={13} className={cn(refreshing && 'animate-spin')} />
                {copy.refresh}
              </Button>
              {unreadCount > 0 && (
                <Button variant="outline" size="sm" onClick={markAllRead} className="h-7 px-2.5 text-xs gap-1">
                  <IconCheck size={13} />
                  {copy.markAllRead}
                </Button>
              )}
            </div>
          </div>

          {/* Filter bar — compact pills */}
          <div className="mt-3 flex items-center gap-1.5">
            {FILTERS.map(f => {
              const active = filter === f.value;
              const count = counts[f.value];
              const Icon = f.icon;
              return (
                <button
                  key={f.value}
                  type="button"
                  onClick={() => { setFilter(f.value); setPage(0); }}
                  /*
                    The same soft treatment every other filter row in the app uses (Deliveries,
                    Returns, Cash — see PageFilterBar): a tint and coloured text, not a solid fill.
                    Five pills in flat brand blue made "selected" shout louder than anything else on
                    a page whose whole job is to rank what deserves attention.
                  */
                  className="inline-flex items-center gap-1.5 h-7 rounded-full px-2.5 text-xs font-[500] transition-colors border"
                  style={{
                    borderColor: active ? 'var(--brand-blue)' : 'var(--border)',
                    background: active ? 'var(--brand-blue-soft)' : 'transparent',
                    color: active ? 'var(--brand-blue)' : 'var(--text-muted)',
                  }}
                >
                  <Icon size={13} />
                  {f.label}
                  {count > 0 && (
                    <span
                      className="text-2xs font-bold px-1 rounded-full tabular-nums"
                      style={{
                        background: active ? 'var(--brand-blue)' : 'var(--hover-bg)',
                        color: active ? '#fff' : 'var(--text-muted)',
                      }}
                    >
                      {count}
                    </span>
                  )}
                </button>
              );
            })}

            <div className="mx-1.5 h-4 w-px bg-[var(--border)]" />

            {/* Search */}
            <div className="relative w-full sm:w-56">
              <IconSearch size={13} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--text-soft)]" />
              <Input
                value={query}
                onChange={e => { setQuery(e.target.value); setPage(0); }}
                placeholder={copy.searchPlaceholder}
                className="h-7 pl-7 text-xs bg-transparent border-[var(--border)]"
              />
            </div>
          </div>
        </div>
      </header>

      {/* ── List ── */}
      <div className="flex-1 overflow-y-auto">
        {filtered.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 py-24 text-center">
            <IconBellOff size={24} className="text-[var(--text-soft)]" />
            <p className="text-sm font-medium text-[var(--text-primary)]">{copy.empty.title}</p>
            <p className="max-w-xs text-xs text-[var(--text-muted)]">
              {filter === 'all' && !query ? copy.empty.subtitleAll : copy.empty.subtitleFiltered}
            </p>
          </div>
        ) : (
          <div className="mx-auto max-w-5xl px-6 py-3">
            {groups.map(group => (
              <section key={group.key} className="mb-4">
                {/* Group divider — thin line + small label */}
                <div className="mb-1.5 flex items-center gap-2 px-1">
                  <span className="text-2xs font-semibold text-[var(--text-muted)]">{group.label}</span>
                  <span className="text-2xs text-[var(--text-soft)]">{group.items.length}</span>
                  <div className="flex-1 h-px bg-[var(--border)]" />
                </div>

                {/* Rows */}
                <div className="rounded-lg border border-[var(--border)] bg-[var(--surface)] divide-y divide-[var(--border)]">
                  {group.items.map(n => {
                    const loc = getLocalizedNotif(n, locale);
                    const chips = [n.routeName, n.driverName, n.clientName].filter(Boolean);
                    return (
                      <button
                        key={n.id}
                        type="button"
                        onClick={() => openInDispatch(n)}
                        className={cn(
                          'flex w-full items-center gap-3 px-3 py-2.5 text-start transition-colors hover:bg-[var(--hover-bg)]',
                          // Neutral ink, not brand blue. Colour on this row means severity — the
                          // icon is red, amber or blue for a reason — and a blue edge for "unread"
                          // competed with the one signal that ranks the list.
                          !n.read && 'border-l-2 border-l-[var(--text-primary)]',
                        )}
                      >
                        <SeverityIcon severity={n.severity} className="w-6 h-6 shrink-0" />

                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <span className={cn(
                              'truncate text-sm leading-tight',
                              n.read ? 'font-medium text-[var(--text-secondary)]' : 'font-semibold text-[var(--text-primary)]',
                            )}>
                              {loc.title}
                            </span>
                          </div>
                          <p className="mt-px line-clamp-1 text-xs text-[var(--text-muted)] leading-tight">{loc.message}</p>
                          {chips.length > 0 && (
                            <div className="mt-1 flex flex-wrap gap-1">
                              {chips.map((c, idx) => (
                                <span key={idx} className="inline-flex items-center rounded bg-[var(--hover-bg)] px-1.5 py-px text-2xs font-medium text-[var(--text-muted)] leading-5">{c}</span>
                              ))}
                            </div>
                          )}
                        </div>

                        <div className="flex shrink-0 items-center gap-1.5">
                          <span className="text-2xs text-[var(--text-soft)] tabular-nums whitespace-nowrap">{relativeTime(n.timestamp, copy, tag)}</span>
                          <IconChevronRight size={13} className="text-[var(--text-soft)]" />
                        </div>
                      </button>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
        )}
      </div>

      {/*
        The shared pager, not a fourth hand-rolled one.

        What stood here reimplemented a numbered window, prev/next and a result count that
        TablePagination already provides on Deliveries, Returns and Cash — including the ellipsis
        this copy never had. Four implementations of one control drift apart, and the operator pays
        for it by relearning the footer on every page.
      */}
      <TablePagination
        page={safePage}
        totalPages={totalPages}
        totalElements={filtered.length}
        size={pageSize}
        onPageChange={setPage}
        onSizeChange={s => { setPageSize(s); setPage(0); }}
        labels={{ results: copy.countSuffix }}
      />
    </div>
  );
}
