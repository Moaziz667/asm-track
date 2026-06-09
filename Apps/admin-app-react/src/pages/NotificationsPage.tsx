import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  IconCheck,
  IconSearch,
  IconRefresh,
  IconBellOff,
  IconChevronRight,
} from '@tabler/icons-react';

import {
  getLocalizedNotif,
  useNotifications,
  type Notification,
} from '@/components/AlertsProvider';
import { SeverityIcon } from '@/components/AlertBell';

import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';
import { toast } from '@/lib/toast';
import { dispatchDeskQueueLink } from '@/lib/dispatch-link';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Separator } from '@/components/ui/separator';

type Filter = 'all' | 'unread' | 'critical' | 'warning' | 'info';

function localeTag(locale: string) {
  return locale === 'fr' ? 'fr-FR' : locale === 'ar' ? 'ar' : 'en-US';
}

function extra(locale: string) {
  const fr = locale === 'fr', ar = locale === 'ar';
  return {
    subtitle: ar ? 'مركز التنبيهات التشغيلية في الوقت الفعلي' : fr ? "Centre d'alertes opérationnelles en temps réel" : 'Real-time operational alerts center',
    search: ar ? 'بحث في التنبيهات…' : fr ? 'Rechercher des alertes…' : 'Search alerts…',
    refresh: ar ? 'تحديث' : fr ? 'Actualiser' : 'Refresh',
    refreshed: ar ? 'تم التحديث' : fr ? 'Actualisé' : 'Refreshed',
    actionFail: ar ? 'فشل تنفيذ الإجراء' : fr ? "Échec de l'action" : 'Action failed',
  };
}

export default function NotificationsPage() {
  const t = useT();
  const copy = t.notificationsPage;
  const { locale } = useLocaleStore();
  const x = useMemo(() => extra(locale), [locale]);
  const tag = localeTag(locale);
  const navigate = useNavigate();

  const { notifications, unreadCount, markRead, markAllRead, refresh } = useNotifications();

  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [refreshing, setRefreshing] = useState(false);

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

  const groups = useMemo(() => {
    const todayStart = new Date().setHours(0, 0, 0, 0);
    const weekStart = Date.now() - 7 * 86400_000;
    return [
      { key: 'today', label: copy.groups.today, items: filtered.filter(n => n.timestamp >= todayStart) },
      { key: 'week', label: copy.groups.week, items: filtered.filter(n => n.timestamp < todayStart && n.timestamp >= weekStart) },
      { key: 'older', label: copy.groups.older, items: filtered.filter(n => n.timestamp < weekStart) },
    ].filter(g => g.items.length > 0);
  }, [filtered, copy.groups]);

  const relTime = (ts: number) => {
    const diff = Math.floor((Date.now() - ts) / 1000);
    const c = copy.time;
    if (diff < 60) return c.justNow;
    if (diff < 3600) return c.minutesAgo.replace('{minutes}', String(Math.floor(diff / 60)));
    if (diff < 86400) return c.hoursAgo.replace('{hours}', String(Math.floor(diff / 3600)));
    if (diff < 7 * 86400) return c.daysAgo.replace('{days}', String(Math.floor(diff / 86400)));
    return new Date(ts).toLocaleDateString(tag, { day: '2-digit', month: 'short' });
  };

  // Clicking an alert opens the Dispatch Desk, pre-filtered to that command —
  // where the actual actions (assign / reassign / replan) live.
  const openInDispatch = (n: typeof notifications[number]) => {
    if (!n.read) markRead(n.id);
    if (n.event === 'erp.orders_ready') {
      navigate('/import?tab=ready');
    } else if (n.deliveryId || n.orderId) {
      navigate(dispatchDeskQueueLink({ orderRef: n.orderId, orderId: n.orderId, deliveryId: n.deliveryId }));
    } else if (n.routeId) {
      navigate(`/routes/${n.routeId}`);
    }
  };

  const doRefresh = async () => {
    setRefreshing(true);
    try { await refresh(); toast.success(x.refreshed); }
    catch { toast.error(x.actionFail); }
    finally { setRefreshing(false); }
  };

  const FILTERS: { value: Filter; label: string }[] = [
    { value: 'all', label: copy.filters.all },
    { value: 'unread', label: copy.filters.unread },
    { value: 'critical', label: copy.filters.critical },
    { value: 'warning', label: copy.filters.warning },
    { value: 'info', label: copy.filters.info },
  ];

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[var(--app-bg)]">
      {/* ── Page header (AWS-console style) ── */}
      <header className="shrink-0 border-b border-[var(--border)] bg-[var(--surface)] px-6 py-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h1 className="text-xl font-semibold tracking-tight text-[var(--text-primary)]">{copy.title}</h1>
            <p className="mt-0.5 text-[13px] text-[var(--text-muted)]">{x.subtitle}</p>
          </div>
          <div className="flex items-center gap-2">
            <span className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
              <span className="h-2 w-2 rounded-full bg-red-500" />
              <b className="text-[var(--text-primary)]">{counts.critical}</b> {copy.statCritical}
              <span className="mx-1 opacity-40">·</span>
              <span className="h-2 w-2 rounded-full bg-sky-500" />
              <b className="text-[var(--text-primary)]">{counts.unread}</b> {copy.statUnread}
            </span>
            <Separator orientation="vertical" className="hidden h-6 sm:block" />
            <Button variant="outline" size="sm" onClick={doRefresh} disabled={refreshing}>
              <IconRefresh size={14} className={cn('mr-2', refreshing && 'animate-spin')} />
              {x.refresh}
            </Button>
            {unreadCount > 0 && (
              <Button variant="outline" size="sm" onClick={markAllRead}>
                <IconCheck size={14} className="mr-2" />
                {copy.markAllRead}
              </Button>
            )}
          </div>
        </div>

        {/* Toolbar: segmented severity control + search */}
        <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="inline-flex items-center rounded-lg border border-[var(--border)] bg-[var(--app-bg)] p-0.5">
            {FILTERS.map(f => {
              const activeF = filter === f.value;
              const c = counts[f.value];
              return (
                <button
                  key={f.value}
                  type="button"
                  onClick={() => setFilter(f.value)}
                  className={cn(
                    'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                    activeF ? 'bg-[var(--surface)] text-[var(--text-primary)] shadow-sm' : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]',
                  )}
                >
                  {f.label}
                  <span className={cn('rounded px-1 text-[10px] font-bold tabular-nums', activeF ? 'text-[var(--brand)]' : 'text-[var(--text-muted)]')}>{c}</span>
                </button>
              );
            })}
          </div>
          <div className="relative w-full sm:w-72">
            <IconSearch size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
            <Input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder={x.search}
              className="h-9 pl-9"
            />
          </div>
        </div>
      </header>

      {/* ── List ── */}
      <div className="flex-1 overflow-y-auto">
        {groups.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-3 py-24 text-center">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
              <IconBellOff size={26} className="text-[var(--text-muted)]" />
            </div>
            <p className="text-sm font-semibold text-[var(--text-primary)]">{copy.empty.title}</p>
            <p className="max-w-xs text-sm text-[var(--text-muted)]">
              {filter === 'all' && !query ? copy.empty.subtitleAll : copy.empty.subtitleFiltered}
            </p>
          </div>
        ) : (
          <div className="mx-auto max-w-4xl px-4 py-4 sm:px-6">
            {groups.map(group => (
              <section key={group.key} className="mb-6">
                <div className="mb-2 flex items-center justify-between px-1">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">{group.label}</span>
                  <span className="text-[11px] font-medium text-[var(--text-muted)]">
                    {group.items.length} {group.items.length === 1 ? copy.eventSingular : copy.eventPlural}
                  </span>
                </div>
                <div className="overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)]">
                  {group.items.map((n, i) => {
                    const loc = getLocalizedNotif(n, locale);
                    const chips = [n.routeName, n.driverName, n.clientName].filter(Boolean);
                    return (
                      <button
                        key={n.id}
                        type="button"
                        onClick={() => openInDispatch(n)}
                        className={cn(
                          'flex w-full items-start gap-3 px-4 py-3 text-start transition-colors hover:bg-[var(--hover-bg)]',
                          i > 0 && 'border-t border-[var(--border)]',
                          !n.read && 'bg-[var(--brand-soft)]/30',
                        )}
                      >
                        {/* Severity Icon */}
                        <SeverityIcon severity={n.severity} className="w-8 h-8 mt-0.5" />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className={cn('truncate text-sm', n.read ? 'font-medium text-[var(--text-primary)]' : 'font-semibold text-[var(--text-primary)]')}>
                              {loc.title}
                            </span>
                            {!n.read && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--brand)]" />}
                          </div>
                          <p className="mt-0.5 line-clamp-1 text-xs text-[var(--text-muted)]">{loc.message}</p>
                          {chips.length > 0 && (
                            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                              {chips.map((c, idx) => (
                                <span key={idx} className="rounded border border-[var(--border)] bg-[var(--app-bg)] px-1.5 py-0.5 text-[10px] font-medium text-[var(--text-muted)]">{c}</span>
                              ))}
                            </div>
                          )}
                        </div>
                        <div className="flex shrink-0 items-center gap-2 pt-0.5">
                          <span className="text-[11px] font-medium text-[var(--text-muted)]">{relTime(n.timestamp)}</span>
                          <IconChevronRight size={15} className="text-[var(--text-muted)]" />
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
    </div>
  );
}
