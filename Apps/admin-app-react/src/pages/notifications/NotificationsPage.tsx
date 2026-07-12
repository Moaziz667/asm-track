import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  IconCheck,
  IconSearch,
  IconRefresh,
  IconBellOff,
  IconChevronRight,
  IconChevronLeft,
  IconAlertTriangle,
  IconAlertCircle,
  IconInfoCircle,
} from '@tabler/icons-react';

import {
  getLocalizedNotif,
  useNotifications,
  type Notification,
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

type Filter = 'all' | 'unread' | 'critical' | 'warning' | 'info';

const PAGE_SIZE = 25;

function localeTag(locale: string) {
  return locale === 'fr' ? 'fr-FR' : locale === 'ar' ? 'ar' : 'en-US';
}

function extra(locale: string) {
  const fr = locale === 'fr', ar = locale === 'ar';
  return {
    subtitle: ar ? 'مركز التنبيهات التشغيلية في الوقت الفعلي' : fr ? "Centre d'alertes opérationnelles en temps réel" : 'Real-time operational alerts center',
    search: ar ? 'بحث في التنبيهات…' : fr ? 'Rechercher…' : 'Search alerts…',
    refresh: ar ? 'تحديث' : fr ? 'Actualiser' : 'Refresh',
    refreshed: ar ? 'تم التحديث' : fr ? 'Actualisé' : 'Refreshed',
    actionFail: ar ? 'فشل تنفيذ الإجراء' : fr ? "Échec de l'action" : 'Action failed',
    markAllRead: ar ? 'تحديد الكل كمقروء' : fr ? 'Tout marquer lu' : 'Mark all read',
    today: ar ? 'اليوم' : fr ? "Aujourd'hui" : 'Today',
    yesterday: ar ? 'أمس' : fr ? 'Hier' : 'Yesterday',
    older: ar ? 'أقدم' : fr ? 'Plus ancien' : 'Older',
    events: ar ? 'أحداث' : fr ? 'événements' : 'events',
    event: ar ? 'حدث' : fr ? 'événement' : 'event',
    pageOf: ar ? 'صفحة {page} من {total}' : fr ? 'Page {page} sur {total}' : 'Page {page} of {total}',
    showing: ar ? 'عرض {from}-{to} من {total}' : fr ? '{from}-{to} sur {total}' : '{from}-{to} of {total}',
    previous: ar ? 'السابق' : fr ? 'Précédent' : 'Previous',
    next: ar ? 'التالي' : fr ? 'Suivant' : 'Next',
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
  const [page, setPage] = useState(0);

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

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const paginatedItems = useMemo(
    () => filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE),
    [filtered, safePage],
  );

  const groups = useMemo(() => {
    const todayStart = new Date().setHours(0, 0, 0, 0);
    const weekStart = Date.now() - 7 * 86400_000;
    return [
      { key: 'today', label: x.today, items: paginatedItems.filter(n => n.timestamp >= todayStart) },
      { key: 'yesterday', label: x.yesterday, items: paginatedItems.filter(n => {
        const yesterdayStart = new Date();
        yesterdayStart.setDate(yesterdayStart.getDate() - 1);
        yesterdayStart.setHours(0, 0, 0, 0);
        return n.timestamp >= yesterdayStart.getTime() && n.timestamp < todayStart;
      })},
      { key: 'older', label: x.older, items: paginatedItems.filter(n => {
        const yesterdayStart = new Date();
        yesterdayStart.setDate(yesterdayStart.getDate() - 1);
        yesterdayStart.setHours(0, 0, 0, 0);
        return n.timestamp < yesterdayStart.getTime();
      })},
    ].filter(g => g.items.length > 0);
  }, [paginatedItems, x]);

  const relTime = (ts: number) => {
    const diff = Math.floor((Date.now() - ts) / 1000);
    if (diff < 60) return locale === 'fr' ? "à l'instant" : locale === 'ar' ? 'الآن' : 'just now';
    if (diff < 3600) {
      const m = Math.floor(diff / 60);
      return locale === 'fr' ? `il y a ${m} min` : locale === 'ar' ? `منذ ${m} دقيقة` : `${m}m ago`;
    }
    if (diff < 86400) {
      const h = Math.floor(diff / 3600);
      return locale === 'fr' ? `il y a ${h}h` : locale === 'ar' ? `منذ ${h} ساعة` : `${h}h ago`;
    }
    if (diff < 7 * 86400) {
      const d = Math.floor(diff / 86400);
      return locale === 'fr' ? `il y a ${d}j` : locale === 'ar' ? `منذ ${d} يوم` : `${d}d ago`;
    }
    return new Date(ts).toLocaleDateString(tag, { day: '2-digit', month: 'short' });
  };

  const openInDispatch = (n: typeof notifications[number]) => {
    if (!n.read) markRead(n.id);
    navigate(notifDestination(n));
  };

  const doRefresh = async () => {
    setRefreshing(true);
    try { await refresh(); toast.success(x.refreshed); }
    catch { toast.error(x.actionFail); }
    finally { setRefreshing(false); }
  };

  const FILTERS: { value: Filter; label: string; icon: typeof IconCheck }[] = [
    { value: 'all', label: copy.filters.all, icon: IconCheck },
    { value: 'unread', label: copy.filters.unread, icon: IconBellOff },
    { value: 'critical', label: copy.filters.critical, icon: IconAlertTriangle },
    { value: 'warning', label: copy.filters.warning, icon: IconAlertCircle },
    { value: 'info', label: copy.filters.info, icon: IconInfoCircle },
  ];

  const from = safePage * PAGE_SIZE + 1;
  const to = Math.min((safePage + 1) * PAGE_SIZE, filtered.length);

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[var(--app-bg)]">
      {/* ── Header ── */}
      <header className="shrink-0 border-b border-[var(--border)] bg-[var(--surface)]">
        <div className="mx-auto max-w-5xl w-full px-6 py-5">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-lg font-semibold text-[var(--text-primary)]">{copy.title}</h1>
              <p className="mt-0.5 text-xs text-[var(--text-muted)]">{x.subtitle}</p>
            </div>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={doRefresh} disabled={refreshing} className="h-8 px-3 text-xs">
                <IconRefresh size={14} className={cn('mr-1.5', refreshing && 'animate-spin')} />
                {x.refresh}
              </Button>
              {unreadCount > 0 && (
                <Button variant="outline" size="sm" onClick={markAllRead} className="h-8 px-3 text-xs">
                  <IconCheck size={14} className="mr-1.5" />
                  {x.markAllRead}
                </Button>
              )}
            </div>
          </div>

          {/* Stats cards */}
          <div className="mt-4 grid grid-cols-2 sm:grid-cols-5 gap-3">
            {FILTERS.map(f => {
              const active = filter === f.value;
              const count = counts[f.value];
              const Icon = f.icon;
              return (
                <button
                  key={f.value}
                  type="button"
                  onClick={() => { setFilter(f.value); setPage(0); }}
                  className={cn(
                    'flex items-center gap-2.5 rounded-lg border px-3 py-2.5 text-left transition-all',
                    active
                      ? 'border-[var(--brand)] bg-[var(--brand-soft)]/10'
                      : 'border-[var(--border)] bg-[var(--surface)] hover:border-[var(--border-strong)]',
                  )}
                >
                  <div className={cn(
                    'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
                    active ? 'bg-[var(--brand)] text-white' : 'bg-[var(--hover-bg)] text-[var(--text-muted)]',
                  )}>
                    <Icon size={14} />
                  </div>
                  <div className="min-w-0">
                    <div className={cn('text-xs font-medium', active ? 'text-[var(--brand)]' : 'text-[var(--text-muted)]')}>
                      {f.label}
                    </div>
                    <div className="text-lg font-bold text-[var(--text-primary)] tabular-nums">{count}</div>
                  </div>
                </button>
              );
            })}
          </div>

          {/* Search */}
          <div className="mt-4 relative w-full sm:w-80">
            <IconSearch size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
            <Input
              value={query}
              onChange={e => { setQuery(e.target.value); setPage(0); }}
              placeholder={x.search}
              className="h-9 pl-9 bg-[var(--app-bg)] border-[var(--border)]"
            />
          </div>
        </div>
      </header>

      {/* ── List ── */}
      <div className="flex-1 overflow-y-auto">
        {filtered.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-3 py-24 text-center">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
              <IconBellOff size={26} className="text-[var(--text-muted)]" />
            </div>
            <p className="text-sm font-semibold text-[var(--text-primary)]">{copy.empty.title}</p>
            <p className="max-w-xs text-xs text-[var(--text-muted)]">
              {filter === 'all' && !query ? copy.empty.subtitleAll : copy.empty.subtitleFiltered}
            </p>
          </div>
        ) : (
          <div className="mx-auto max-w-5xl px-6 py-4">
            {groups.map(group => (
              <section key={group.key} className="mb-5">
                <div className="mb-2 flex items-center justify-between px-1">
                  <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-muted)]">{group.label}</span>
                  <span className="text-xs text-[var(--text-muted)]">
                    {group.items.length} {group.items.length === 1 ? x.event : x.events}
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
                          !n.read && 'bg-[var(--brand-soft)]/20',
                        )}
                      >
                        <SeverityIcon severity={n.severity} className="w-8 h-8 mt-0.5 shrink-0" />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className={cn('truncate text-sm', n.read ? 'font-medium text-[var(--text-primary)]' : 'font-semibold text-[var(--text-primary)]')}>
                              {loc.title}
                            </span>
                            {!n.read && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--brand)]" />}
                          </div>
                          <p className="mt-0.5 line-clamp-1 text-xs text-[var(--text-muted)]">{loc.message}</p>
                          {chips.length > 0 && (
                            <div className="mt-1.5 flex flex-wrap gap-1.5">
                              {chips.map((c, idx) => (
                                <span key={idx} className="rounded bg-[var(--hover-bg)] px-1.5 py-0.5 text-2xs font-medium text-[var(--text-secondary)]">{c}</span>
                              ))}
                            </div>
                          )}
                        </div>
                        <div className="flex shrink-0 items-center gap-2 pt-0.5">
                          <span className="text-xs text-[var(--text-muted)]">{relTime(n.timestamp)}</span>
                          <IconChevronRight size={14} className="text-[var(--text-muted)]" />
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

      {/* ── Pagination ── */}
      {totalPages > 1 && (
        <div className="shrink-0 border-t border-[var(--border)] bg-[var(--surface)] px-6 py-3">
          <div className="mx-auto max-w-5xl flex items-center justify-between">
            <span className="text-xs text-[var(--text-muted)]">
              {x.showing.replace('{from}', String(from)).replace('{to}', String(to)).replace('{total}', String(filtered.length))}
            </span>
            <div className="flex items-center gap-1.5">
              <Button
                variant="outline"
                size="sm"
                disabled={safePage === 0}
                onClick={() => setPage(p => Math.max(0, p - 1))}
                className="h-7 px-2 text-xs"
              >
                <IconChevronLeft size={14} className="mr-1" />
                {x.previous}
              </Button>
              {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                let pageNum: number;
                if (totalPages <= 5) {
                  pageNum = i;
                } else if (safePage < 3) {
                  pageNum = i;
                } else if (safePage > totalPages - 4) {
                  pageNum = totalPages - 5 + i;
                } else {
                  pageNum = safePage - 2 + i;
                }
                return (
                  <button
                    key={pageNum}
                    type="button"
                    onClick={() => setPage(pageNum)}
                    className={cn(
                      'h-7 min-w-[28px] rounded px-2 text-xs font-medium transition-colors',
                      pageNum === safePage
                        ? 'bg-[var(--brand)] text-white'
                        : 'text-[var(--text-muted)] hover:bg-[var(--hover-bg)]',
                    )}
                  >
                    {pageNum + 1}
                  </button>
                );
              })}
              <Button
                variant="outline"
                size="sm"
                disabled={safePage >= totalPages - 1}
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                className="h-7 px-2 text-xs"
              >
                {x.next}
                <IconChevronRight size={14} className="ml-1" />
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
