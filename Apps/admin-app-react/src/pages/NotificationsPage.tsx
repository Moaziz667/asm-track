import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  IconCheck,
  IconSearch,
  IconRefresh,
  IconBellOff,
  IconExternalLink,
  IconPhone,
  IconArrowsExchange,
  IconReload,
  IconChevronRight,
  IconInbox,
} from '@tabler/icons-react';

import {
  getLocalizedNotif,
  type Notification,
  useNotifications,
} from '@/components/AlertsProvider';

import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';
import { toast } from '@/lib/toast';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Separator } from '@/components/ui/separator';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet';
import { ReassignCommandOverlay } from '@/components/overlays/ReassignCommandOverlay';
import { type DriverOption, type RouteOption } from '@/components/overlays/ReassignModal';
import { useDrivers } from '@/hooks/useDrivers';
import { useRoutes } from '@/hooks/useRoutes';
import { useReassignException, useReplayErpSync } from '@/hooks/useNotificationOps';
import { getCurrentRole, canDispatch } from '@/lib/auth';

type Filter = 'all' | 'unread' | 'critical' | 'warning' | 'info';

const SEV_DOT: Record<string, string> = {
  critical: 'bg-red-500',
  warning: 'bg-amber-500',
  info: 'bg-sky-500',
};

const SEV_BADGE: Record<string, string> = {
  critical: 'border-red-200 dark:border-red-900/50 text-red-700 dark:text-red-400 bg-red-50 dark:bg-red-950/30',
  warning: 'border-amber-200 dark:border-amber-900/50 text-amber-700 dark:text-amber-400 bg-amber-50 dark:bg-amber-950/30',
  info: 'border-sky-200 dark:border-sky-900/50 text-sky-700 dark:text-sky-400 bg-sky-50 dark:bg-sky-950/30',
};

function localeTag(locale: string) {
  return locale === 'fr' ? 'fr-FR' : locale === 'ar' ? 'ar' : 'en-US';
}

// Curated, ordered fields surfaced in the detail drawer (real payload only).
const DETAIL_FIELDS: { key: string; label: Record<string, string> }[] = [
  { key: 'clientName', label: { fr: 'Client', en: 'Client', ar: 'العميل' } },
  { key: 'clientPhone', label: { fr: 'Téléphone client', en: 'Client phone', ar: 'هاتف العميل' } },
  { key: 'driverName', label: { fr: 'Chauffeur', en: 'Driver', ar: 'السائق' } },
  { key: 'routeName', label: { fr: 'Tournée', en: 'Route', ar: 'الرحلة' } },
  { key: 'orderId', label: { fr: 'Commande', en: 'Order', ar: 'الطلب' } },
  { key: 'dropoffAddress', label: { fr: 'Adresse', en: 'Address', ar: 'العنوان' } },
  { key: 'reason', label: { fr: 'Motif', en: 'Reason', ar: 'السبب' } },
  { key: 'elapsed', label: { fr: 'Temps écoulé (min)', en: 'Elapsed (min)', ar: 'الوقت المنقضي' } },
  { key: 'limit', label: { fr: 'Seuil (min)', en: 'Threshold (min)', ar: 'الحد' } },
  { key: 'stopCount', label: { fr: 'Arrêts', en: 'Stops', ar: 'المحطات' } },
  { key: 'parcelCount', label: { fr: 'Colis', en: 'Parcels', ar: 'الطرود' } },
  { key: 'totalAmount', label: { fr: 'Montant', en: 'Amount', ar: 'المبلغ' } },
];

function extra(locale: string) {
  const fr = locale === 'fr', ar = locale === 'ar';
  return {
    subtitle: ar ? 'مركز التنبيهات التشغيلية في الوقت الفعلي' : fr ? "Centre d'alertes opérationnelles en temps réel" : 'Real-time operational alerts center',
    search: ar ? 'بحث في التنبيهات…' : fr ? 'Rechercher des alertes…' : 'Search alerts…',
    refresh: ar ? 'تحديث' : fr ? 'Actualiser' : 'Refresh',
    refreshed: ar ? 'تم التحديث' : fr ? 'Actualisé' : 'Refreshed',
    details: ar ? 'تفاصيل الحدث' : fr ? "Détails de l'événement" : 'Event details',
    noDetails: ar ? 'لا توجد تفاصيل إضافية' : fr ? 'Aucun détail supplémentaire' : 'No additional details',
    selectPrompt: ar ? 'اختر تنبيهاً لعرض التفاصيل والإجراءات' : fr ? 'Sélectionnez une alerte pour voir les détails et les actions' : 'Select an alert to view details and actions',
    view: ar ? 'عرض' : fr ? 'Ouvrir' : 'Open',
    callClient: ar ? 'اتصال بالعميل' : fr ? 'Appeler le client' : 'Call client',
    reassign: ar ? 'إعادة تعيين' : fr ? 'Réassigner' : 'Reassign',
    retry: ar ? 'إعادة مزامنة ERP' : fr ? 'Relancer la synchro ERP' : 'Retry ERP sync',
    ack: ar ? 'تأكيد وإخفاء' : fr ? 'Acquitter' : 'Acknowledge',
    retryOk: (n: number) => ar ? `تمت إعادة جدولة ${n} عملية مزامنة` : fr ? `${n} synchronisation(s) relancée(s)` : `${n} sync(s) re-queued`,
    retryNone: ar ? 'لا توجد عمليات مزامنة فاشلة لإعادتها' : fr ? 'Aucune synchro en échec à relancer' : 'No failed syncs to replay',
    actionFail: ar ? 'فشل تنفيذ الإجراء' : fr ? "Échec de l'action" : 'Action failed',
    reassignOk: ar ? 'تمت إعادة التعيين' : fr ? 'Réassignation effectuée' : 'Reassigned successfully',
  };
}

export default function NotificationsPage() {
  const t = useT();
  const copy = t.notificationsPage;
  const { locale } = useLocaleStore();
  const x = useMemo(() => extra(locale), [locale]);
  const tag = localeTag(locale);
  const navigate = useNavigate();

  const { notifications, unreadCount, markRead, markAllRead, acknowledge, refresh } = useNotifications();

  const role = getCurrentRole();
  const isAdmin = role === 'ADMIN';
  const canAct = canDispatch(role);

  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [reassignOpen, setReassignOpen] = useState(false);

  const { data: drivers = [] } = useDrivers();
  const { data: routes = [] } = useRoutes(undefined, canAct);
  const reassignMutation = useReassignException();
  const replayMutation = useReplayErpSync();

  usePageBreadcrumb([{ label: copy.title }]);

  const driverOptions: DriverOption[] = useMemo(
    () => drivers.map(d => ({ id: d.id, name: d.name, phone: (d as { phone?: string }).phone })),
    [drivers],
  );
  const routeOptions: RouteOption[] = useMemo(
    () => routes.map(r => ({
      id: r.id, name: r.name, driverName: r.driverName,
      stopCount: r.stops?.length ?? 0, status: r.status, city: r.city,
    })),
    [routes],
  );

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

  const active = useMemo(
    () => notifications.find(n => n.id === selectedId) ?? null,
    [notifications, selectedId],
  );

  const relTime = (ts: number) => {
    const diff = Math.floor((Date.now() - ts) / 1000);
    const c = copy.time;
    if (diff < 60) return c.justNow;
    if (diff < 3600) return c.minutesAgo.replace('{minutes}', String(Math.floor(diff / 60)));
    if (diff < 86400) return c.hoursAgo.replace('{hours}', String(Math.floor(diff / 3600)));
    if (diff < 7 * 86400) return c.daysAgo.replace('{days}', String(Math.floor(diff / 86400)));
    return new Date(ts).toLocaleDateString(tag, { day: '2-digit', month: 'short' });
  };
  const absTime = (ts: number) =>
    new Date(ts).toLocaleString(tag, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

  const openDetail = (n: Notification) => {
    setSelectedId(n.id);
    if (!n.read) markRead(n.id);
  };
  const closeDetail = () => setSelectedId(null);

  const doRefresh = async () => {
    setRefreshing(true);
    try { await refresh(); toast.success(x.refreshed); }
    catch { toast.error(x.actionFail); }
    finally { setRefreshing(false); }
  };

  const doRetry = async () => {
    try {
      const res = await replayMutation.mutateAsync();
      toast.success(res.replayed > 0 ? x.retryOk(res.replayed) : x.retryNone);
      await refresh();
    } catch { toast.error(x.actionFail); }
  };

  const FILTERS: { value: Filter; label: string }[] = [
    { value: 'all', label: copy.filters.all },
    { value: 'unread', label: copy.filters.unread },
    { value: 'critical', label: copy.filters.critical },
    { value: 'warning', label: copy.filters.warning },
    { value: 'info', label: copy.filters.info },
  ];

  const detailRows = active
    ? DETAIL_FIELDS
        .map(f => ({ label: f.label[locale] ?? f.label.en, value: active.eventParams?.[f.key] }))
        .filter(r => r.value && r.value.trim() !== '')
    : [];

  const isErpFail = active?.event?.startsWith('erp.') && active.event.includes('fail');
  const canReassign = canAct && !!active?.deliveryId &&
    (active.severity === 'critical' || active.severity === 'warning');

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
                        onClick={() => openDetail(n)}
                        className={cn(
                          'flex w-full items-start gap-3 px-4 py-3 text-start transition-colors hover:bg-[var(--hover-bg)]',
                          i > 0 && 'border-t border-[var(--border)]',
                          !n.read && 'bg-[var(--brand-soft)]/30',
                        )}
                      >
                        <span className={cn('mt-1.5 h-2 w-2 shrink-0 rounded-full', SEV_DOT[n.severity] ?? 'bg-[var(--border)]')} />
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

      {/* ── Detail drawer ── */}
      <Sheet open={!!active} onOpenChange={(o) => { if (!o) closeDetail(); }}>
        <SheetContent side="right" className="w-full gap-0 p-0 sm:max-w-md">
          {active && (
            <div className="flex h-full flex-col">
              <SheetHeader className="border-b border-[var(--border)] p-5">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={cn('rounded border px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide', SEV_BADGE[active.severity])}>
                    {active.severity}
                  </span>
                  <span className="font-mono text-[11px] text-[var(--text-muted)]">{active.event}</span>
                </div>
                <SheetTitle className="mt-2 text-base font-semibold text-[var(--text-primary)]">
                  {getLocalizedNotif(active, locale).title}
                </SheetTitle>
                <SheetDescription className="text-[var(--text-muted)]">
                  {absTime(active.timestamp)}
                </SheetDescription>
              </SheetHeader>

              <div className="flex-1 overflow-y-auto p-5">
                <p className="text-sm leading-relaxed text-[var(--text-primary)]">
                  {getLocalizedNotif(active, locale).message}
                </p>

                <div className="mt-5">
                  <h3 className="mb-2 text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">{x.details}</h3>
                  {detailRows.length > 0 ? (
                    <dl className="overflow-hidden rounded-lg border border-[var(--border)]">
                      {detailRows.map((r, i) => (
                        <div key={r.label} className={cn('flex items-start justify-between gap-4 px-3 py-2', i > 0 && 'border-t border-[var(--border)]')}>
                          <dt className="text-xs font-medium text-[var(--text-muted)]">{r.label}</dt>
                          <dd className="text-right text-xs font-semibold text-[var(--text-primary)]">{r.value}</dd>
                        </div>
                      ))}
                    </dl>
                  ) : (
                    <p className="text-xs text-[var(--text-muted)]">{x.noDetails}</p>
                  )}
                </div>
              </div>

              {/* Real actions (role-gated) */}
              <div className="flex flex-wrap items-center gap-2 border-t border-[var(--border)] p-4">
                {(active.deliveryId || active.routeId) && (
                  <Button
                    variant="default"
                    size="sm"
                    onClick={() => {
                      navigate(active.routeId ? `/routes/${active.routeId}` : `/deliveries/${active.deliveryId}`);
                      closeDetail();
                    }}
                  >
                    <IconExternalLink size={14} className="mr-1.5" />
                    {x.view}
                  </Button>
                )}
                {active.eventParams?.clientPhone && (
                  <a
                    href={`tel:${active.eventParams.clientPhone}`}
                    className="inline-flex h-8 items-center gap-1.5 rounded-md border border-[var(--border)] px-3 text-xs font-semibold text-[var(--text-primary)] hover:bg-[var(--hover-bg)]"
                  >
                    <IconPhone size={14} />
                    {x.callClient}
                  </a>
                )}
                {canReassign && (
                  <Button variant="outline" size="sm" onClick={() => setReassignOpen(true)}>
                    <IconArrowsExchange size={14} className="mr-1.5" />
                    {x.reassign}
                  </Button>
                )}
                {isAdmin && isErpFail && (
                  <Button variant="outline" size="sm" onClick={doRetry} disabled={replayMutation.isPending}>
                    <IconReload size={14} className={cn('mr-1.5', replayMutation.isPending && 'animate-spin')} />
                    {x.retry}
                  </Button>
                )}
                <div className="flex-1" />
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-[var(--text-muted)]"
                  onClick={() => { acknowledge(active.id); closeDetail(); }}
                >
                  <IconInbox size={14} className="mr-1.5" />
                  {x.ack}
                </Button>
              </div>
            </div>
          )}
        </SheetContent>
      </Sheet>

      {/* Real reassign overlay — live drivers/routes + ops reassign endpoint */}
      <ReassignCommandOverlay
        open={reassignOpen}
        entityName={active?.orderId || active?.clientName || active?.deliveryId || '—'}
        drivers={driverOptions}
        routes={routeOptions}
        loading={reassignMutation.isPending}
        onCancel={() => setReassignOpen(false)}
        onConfirm={async (payload) => {
          if (!active?.deliveryId) { setReassignOpen(false); return; }
          try {
            await reassignMutation.mutateAsync({
              deliveryId: active.deliveryId,
              driverId: payload.targetType === 'driver' ? payload.targetId : undefined,
              targetRouteId: payload.targetType === 'route' ? payload.targetId : undefined,
              insertAtOrder: payload.stopOrder,
              startTimeWindow: payload.startTime,
              endTimeWindow: payload.endTime,
              note: payload.note,
            });
            toast.success(x.reassignOk);
            setReassignOpen(false);
            acknowledge(active.id);
            closeDetail();
            await refresh();
          } catch {
            toast.error(x.actionFail);
          }
        }}
      />
    </div>
  );
}
