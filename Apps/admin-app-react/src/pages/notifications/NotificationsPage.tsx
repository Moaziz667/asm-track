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
        <div className="mx-auto max-w-5xl w-full px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h1 className="text-base font-semibold text-[var(--text-primary)]">{copy.title}</h1>
              {unreadCount > 0 && (
                <span className="inline-flex items-center justify-center h-5 min-w-[20px] rounded-full bg-[var(--brand)] px-1.5 text-2xs font-bold text-white tabular-nums">
                  {unreadCount}
                </span>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={doRefresh} disabled={refreshing} className="h-7 px-2.5 text-xs gap-1">
                <IconRefresh size={13} className={cn(refreshing && 'animate-spin')} />
                {x.refresh}
              </Button>
              {unreadCount > 0 && (
                <Button variant="outline" size="sm" onClick={markAllRead} className="h-7 px-2.5 text-xs gap-1">
                  <IconCheck size={13} />
                  {x.markAllRead}
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
                  className={cn(
                    'inline-flex items-center gap-1.5 h-7 rounded-md px-2.5 text-xs font-medium transition-colors',
                    active
                      ? 'bg-[var(--brand)] text-white'
                      : 'text-[var(--text-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-secondary)]',
                  )}
                >
                  <Icon size={13} />
                  {f.label}
                  {count > 0 && (
                    <span className={cn(
                      'ml-0.5 tabular-nums',
                      active ? 'text-white/70' : 'text-[var(--text-soft)]',
                    )}>
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
                placeholder={x.search}
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
                          !n.read && 'border-l-2 border-l-[var(--brand)]',
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
                          <span className="text-2xs text-[var(--text-soft)] tabular-nums whitespace-nowrap">{relTime(n.timestamp)}</span>
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

      {/* ── Pagination ── */}
      {totalPages > 1 && (
        <div className="shrink-0 border-t border-[var(--border)] bg-[var(--surface)] px-6 py-2">
          <div className="mx-auto max-w-5xl flex items-center justify-between">
            <span className="text-2xs text-[var(--text-muted)] tabular-nums">
              {x.showing.replace('{from}', String(from)).replace('{to}', String(to)).replace('{total}', String(filtered.length))}
            </span>
            <div className="flex items-center gap-1">
              <Button
                variant="outline"
                size="sm"
                disabled={safePage === 0}
                onClick={() => setPage(p => Math.max(0, p - 1))}
                className="h-6 px-1.5 text-2xs"
              >
                <IconChevronLeft size={12} />
              </Button>
              {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                let pageNum: number;
                if (totalPages <= 5) pageNum = i;
                else if (safePage < 3) pageNum = i;
                else if (safePage > totalPages - 4) pageNum = totalPages - 5 + i;
                else pageNum = safePage - 2 + i;
                return (
                  <button
                    key={pageNum}
                    type="button"
                    onClick={() => setPage(pageNum)}
                    className={cn(
                      'h-6 min-w-[24px] rounded px-1.5 text-2xs font-medium tabular-nums transition-colors',
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
                className="h-6 px-1.5 text-2xs"
              >
                <IconChevronRight size={12} />
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
