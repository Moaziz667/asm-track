import { useMemo, useState, useEffect } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import {
  IconCheck,
  IconTrash,
  IconSearch,
  IconArrowLeft,
  IconRadar2
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
import StatusBadge from '@/components/StatusBadge';
import { Button, buttonVariants } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardHeader, CardTitle, CardContent, CardDescription, CardFooter } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Input } from '@/components/ui/input';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { ReassignCommandOverlay } from '@/components/overlays/ReassignCommandOverlay';
import { type DriverOption, type RouteOption } from '@/components/overlays/ReassignModal';
import { toast } from 'sonner';

type Filter = 'all' | 'critical' | 'warning' | 'info' | 'unread';

const SEV_COLOR: Record<string, string> = {
  critical: 'bg-red-500/40 dark:bg-red-400/40',
  warning: 'bg-amber-500/40 dark:bg-amber-400/40',
  info: 'bg-blue-500/40 dark:bg-blue-400/40',
};

function getExtraCopy(locale: string) {
  const isAr = locale === 'ar';
  const isFr = locale === 'fr';
  
  return {
    searchPlaceholder: isAr ? 'البحث عن الشحنات، السائقين أو الرحلات...' : isFr ? 'Rechercher commandes, chauffeurs...' : 'Search shipments, couriers, routes...',
    diagnosticTitle: isAr ? 'سجل تشخيص التنبيهات التشغيلية' : isFr ? 'JOURNAL DE DIAGNOSTIC DES EXCEPTIONS' : 'OPERATIONAL DIAGNOSTIC LOGS',
    selectPrompt: isAr ? 'اختر تنبيهاً من القائمة لتشغيل الفحص التشخيصي المتقدم' : isFr ? 'Sélectionnez un événement pour lancer les diagnostics' : 'Select an active alert from the queue to run system diagnostics.',
    backToFeed: isAr ? 'العودة للقائمة' : isFr ? 'Retour au flux' : 'Back to Feed',
    callClient: isAr ? 'اتصال بالعميل' : isFr ? 'Appeler le client' : 'Call Client',
    callCourier: isAr ? 'اتصال بالسائق' : isFr ? 'Appeler le livreur' : 'Call Courier',
    viewTracking: isAr ? 'تتبع مباشر' : isFr ? 'Suivi en direct' : 'View Live Tracking',
    replanDelivery: isAr ? 'إعادة جدولة' : isFr ? 'Replanifier' : 'Replan Delivery',
    forceReassign: isAr ? 'إعادة تعيين' : isFr ? 'Forcer Réassignation' : 'Force Reassign',
    viewRoute: isAr ? 'تفاصيل الرحلة' : isFr ? 'Détails Tournée' : 'View Route Details',
    retryIngestion: isAr ? 'إعادة المزامنة' : isFr ? 'Réessayer l\'ingestion' : 'Retry Ingestion',
    openWorkspace: isAr ? 'فتح مساحة العمل' : isFr ? 'Ouvrir l\'espace de travail' : 'Open Import Workspace',
    acknowledgeClear: isAr ? 'تأكيد وإخفاء' : isFr ? 'Acquitter & Effacer' : 'Acknowledge & Clear',
  };
}

function fmtClock(ts: number, locale: string) {
  const loc = locale === 'fr' ? 'fr-FR' : locale === 'ar' ? 'ar' : 'en-US';
  return new Date(ts).toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit', hour12: false });
}

function buildRelTime(copy: any, dateLocale: string) {
  return (ts: number) => {
    const diff = Math.floor((Date.now() - ts) / 1000);
    if (diff < 60) return copy.justNow;
    if (diff < 3600) return copy.minutesAgo.replace('{minutes}', String(Math.floor(diff / 60)));
    if (diff < 86400) return copy.hoursAgo.replace('{hours}', String(Math.floor(diff / 3600)));
    if (diff < 7 * 86400) return copy.daysAgo.replace('{days}', String(Math.floor(diff / 86400)));
    return new Date(ts).toLocaleDateString(dateLocale, { day: '2-digit', month: 'short' });
  };
}

export default function NotificationsPage() {
  const t = useT();
  const copy = t.notificationsPage;
  const { locale } = useLocaleStore();
  const extraCopy = useMemo(() => getExtraCopy(locale), [locale]);
  
  const {
    notifications,
    unreadCount,
    markRead,
    markAllRead,
    clearAll,
  } = useNotifications();

  const [filter, setFilter] = useState<Filter>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedNotifId, setSelectedNotifId] = useState<string | null>(null);
  const [isReassignOverlayOpen, setIsReassignOverlayOpen] = useState(false);
  
  const [isRetryingSync, setIsRetryingSync] = useState(false);
  const [isTriggeringOps, setIsTriggeringOps] = useState<string | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const mockDrivers: DriverOption[] = [
    { id: '1', name: 'Youssef K.', phone: '+216 99 123 456', vehicleCapacityKg: 1200, currentLoadKg: 400, todayRouteId: 'R1', todayRouteName: 'ROUTE-NORTH', todayStopCount: 12 },
    { id: '2', name: 'Ahmed S.', phone: '+216 55 987 654', vehicleCapacityKg: 800, currentLoadKg: 100 },
  ];
  const mockRoutes: RouteOption[] = [
    { id: 'R2', name: 'ROUTE-SOUTH', driverName: 'Karim B.', stopCount: 8, status: 'IN_PROGRESS', capacityKg: 1200, currentLoadKg: 600 },
  ];

  usePageBreadcrumb([{ label: copy.title }]);

  const relTime = useMemo(
    () => buildRelTime(copy.time, copy.time.dateLocale),
    [copy.time],
  );

  const counts: Record<Filter, number> = useMemo(
    () => ({
      all: notifications.length,
      unread: notifications.filter(n => !n.read).length,
      critical: notifications.filter(n => n.severity === 'critical').length,
      warning: notifications.filter(n => n.severity === 'warning').length,
      info: notifications.filter(n => n.severity === 'info').length,
    }),
    [notifications],
  );

  const filtered = useMemo(() => {
    let list = notifications;
    
    if (filter === 'unread') {
      list = list.filter(n => !n.read);
    } else if (filter !== 'all') {
      list = list.filter(n => n.severity === filter);
    }
    
    if (searchQuery.trim() !== '') {
      const q = searchQuery.toLowerCase();
      list = list.filter(
        n =>
          (n.orderId && n.orderId.toLowerCase().includes(q)) ||
          (n.driverName && n.driverName.toLowerCase().includes(q)) ||
          (n.routeName && n.routeName.toLowerCase().includes(q)) ||
          (n.clientName && n.clientName.toLowerCase().includes(q)) ||
          n.event.toLowerCase().includes(q) ||
          getLocalizedNotif(n, locale).title.toLowerCase().includes(q) ||
          getLocalizedNotif(n, locale).message.toLowerCase().includes(q)
      );
    }
    
    return list;
  }, [notifications, filter, searchQuery, locale]);

  const groups = useMemo(() => {
    const todayStart = new Date().setHours(0, 0, 0, 0);
    const weekStart = Date.now() - 7 * 86400_000;

    return [
      {
        key: 'today',
        label: copy.groups.today,
        items: filtered.filter(n => n.timestamp >= todayStart),
      },
      {
        key: 'week',
        label: copy.groups.week,
        items: filtered.filter(n => n.timestamp < todayStart && n.timestamp >= weekStart),
      },
      {
        key: 'older',
        label: copy.groups.older,
        items: filtered.filter(n => n.timestamp < weekStart),
      },
    ].filter(g => g.items.length > 0);
  }, [filtered, copy.groups]);

  useEffect(() => {
    if (selectedNotifId && !filtered.some(n => n.id === selectedNotifId)) {
      setSelectedNotifId(null);
    }
  }, [filtered, selectedNotifId]);

  const activeNotif = useMemo(
    () => notifications.find(n => n.id === selectedNotifId) || null,
    [notifications, selectedNotifId]
  );

  const handleSelect = (n: Notification) => {
    setSelectedNotifId(n.id);
    if (!n.read) {
      markRead(n.id);
    }
  };

  const handleRetrySync = () => {
    setIsRetryingSync(true);
    setTimeout(() => {
      setIsRetryingSync(false);
      toast.success(locale === 'ar' 
        ? 'تمت إعادة محاولة مزامنة ERP بنجاح.' 
        : locale === 'fr' 
          ? 'Synchronisation ERP réessayée avec succès.' 
          : 'ERP integration ingestion retried successfully.'
      );
    }, 1200);
  };

  const FILTERS: { value: Filter; label: string }[] = [
    { value: 'all', label: copy.filters.all },
    { value: 'unread', label: copy.filters.unread },
    { value: 'critical', label: copy.filters.critical },
    { value: 'warning', label: copy.filters.warning },
    { value: 'info', label: copy.filters.info },
  ];

  return (
    <div className="flex flex-col h-full overflow-hidden bg-background">
      
      {/* ── Header ── */}
      <div className="sticky top-0 z-20 min-h-16 h-auto lg:h-16 py-4 lg:py-0 flex items-center shrink-0 bg-background border-b border-border">
        <div className="px-6 w-full flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4 sm:gap-8 min-w-0">
            <div>
              <span className="text-xs font-medium text-muted-foreground mb-0.5 block">
                {locale === 'ar' ? 'سجل تشخيص التنبيهات التشغيلية والمخالفات في الوقت الفعلي' : locale === 'fr' ? 'Console de diagnostic des alertes et exceptions en temps réel' : 'Console and real-time exceptions diagnostic feed'}
              </span>
              <h1 className="text-lg font-semibold text-foreground leading-tight tracking-tight">
                {copy.title}
              </h1>
            </div>
            
            <Separator orientation="vertical" className="hidden sm:block h-6" />
            
            <div className="flex items-center gap-3 text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-red-500/50 dark:bg-red-400/50" />
                <span className="font-semibold text-foreground">{counts.critical}</span> {copy.statCritical}
              </span>
              <span>•</span>
              <span className="flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-blue-500/50 dark:bg-blue-400/50" />
                <span className="font-semibold text-foreground">{counts.unread}</span> {copy.statUnread}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {unreadCount > 0 && (
              <Button
                variant="outline"
                size="sm"
                onClick={markAllRead}
              >
                <IconCheck size={14} className="mr-2" />
                {copy.markAllRead}
              </Button>
            )}
            {notifications.length > 0 && (
              <Button
                variant="outline"
                size="sm"
                onClick={clearAll}
                className="text-destructive border-destructive/30 hover:bg-destructive/10"
              >
                <IconTrash size={14} className="mr-2" />
                {copy.clearAll}
              </Button>
            )}
            <RefreshButton
              refreshing={isRefreshing}
              showText
              onClick={() => {
                setIsRefreshing(true);
                setTimeout(() => setIsRefreshing(false), 800);
              }}
            />
          </div>
        </div>
      </div>

      {/* ── Main Split-Pane Screen ── */}
      <div className="flex-1 flex items-stretch min-h-0 relative">
        
        {/* ── Left Queue Stream Panel ── */}
        <div className={cn(
          "w-full md:w-[40%] flex flex-col border-r border-border bg-muted/10 min-w-[340px] max-w-[480px] shrink-0 min-h-0",
          selectedNotifId && "hidden md:flex"
        )}>
          {/* Operations Header Toolbar */}
          <div className="p-4 border-b border-border bg-background flex flex-col gap-3 shrink-0">
            <div className="relative">
              <IconSearch size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
              <Input
                type="text"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                placeholder={extraCopy.searchPlaceholder}
                className="pl-9 h-9"
              />
            </div>

            {/* Pill Filters */}
            <ScrollArea className="w-full">
              <div className="flex items-center gap-2 pb-1">
                {FILTERS.map(f => {
                  const active = filter === f.value;
                  const count = counts[f.value];
                  return (
                    <Badge
                      key={f.value}
                      variant={active ? "default" : "secondary"}
                      className="cursor-pointer whitespace-nowrap text-xs h-7"
                      onClick={() => setFilter(f.value)}
                    >
                      {f.label}
                      {count > 0 && (
                        <span className={cn(
                          "ml-2 text-[10px] px-1.5 py-0.5 rounded-sm",
                          active ? "bg-primary-foreground/20 text-primary-foreground" : "bg-muted text-muted-foreground"
                        )}>
                          {count}
                        </span>
                      )}
                    </Badge>
                  );
                })}
              </div>
            </ScrollArea>
          </div>

          {/* Chronological live queue list */}
          <ScrollArea className="flex-1">
            {groups.length === 0 ? (
              <div className="text-center py-20 px-4 select-none">
                <IconCheck size={24} className="mx-auto mb-3 text-muted-foreground/50" />
                <p className="text-sm font-semibold text-foreground">{copy.empty.title}</p>
                <p className="text-sm text-muted-foreground mt-1 max-w-[280px] mx-auto">
                  {filter === 'all' && !searchQuery ? copy.empty.subtitleAll : copy.empty.subtitleFiltered}
                </p>
              </div>
            ) : (
              <div className="flex flex-col">
                {groups.map(group => (
                  <section key={group.key} className="flex flex-col">
                    <header className="px-4 py-2 flex items-center justify-between bg-muted/40 border-y border-border sticky top-0 z-10 first:border-t-0">
                      <span className="text-[10px] uppercase font-bold text-muted-foreground">
                        {group.label}
                      </span>
                      <span className="text-[10px] font-semibold text-muted-foreground">
                        {group.items.length} {group.items.length === 1 ? copy.eventSingular : copy.eventPlural}
                      </span>
                    </header>

                    <div className="flex flex-col">
                      {group.items.map(n => {
                        const isSelected = selectedNotifId === n.id;
                        const localized = getLocalizedNotif(n, locale);
                        const metaParts = [n.routeName, n.driverName, n.clientName].filter(Boolean);

                        return (
                          <div
                            key={n.id}
                            role="button"
                            tabIndex={0}
                            onClick={() => handleSelect(n)}
                            onKeyDown={e => e.key === 'Enter' && handleSelect(n)}
                            className={cn(
                              "group text-start flex items-stretch border-b border-border last:border-b-0 cursor-pointer transition-colors relative",
                              isSelected 
                                ? "bg-primary/5" 
                                : !n.read 
                                  ? "bg-muted/30 hover:bg-muted" 
                                  : "bg-background hover:bg-muted/50"
                            )}
                          >
                            <span
                              aria-hidden
                              className={cn("w-1 shrink-0", SEV_COLOR[n.severity] || 'bg-border')}
                            />
                            <div className="flex-1 min-w-0 p-4 flex flex-col gap-1.5">
                              <div className="flex items-center justify-between gap-3">
                                <p className={cn(
                                  "text-sm truncate transition-colors",
                                  isSelected ? "text-primary font-semibold" : "text-foreground font-medium"
                                )}>
                                  {localized.title}
                                </p>
                                <Badge variant="outline" className="text-[9px] h-5 px-1.5 uppercase shrink-0 font-mono">
                                  {n.event}
                                </Badge>
                              </div>

                              <p className="text-xs text-muted-foreground line-clamp-1">
                                {localized.message}
                              </p>

                              <div className="flex items-center justify-between gap-2 mt-2 text-[10px] text-muted-foreground">
                                {metaParts.length > 0 ? (
                                  <span className="truncate flex-1 font-medium">
                                    {metaParts.join(' • ')}
                                  </span>
                                ) : (
                                  <div className="flex-1" />
                                )}
                                <div className="flex items-center gap-1.5 shrink-0 font-mono">
                                  <span>{fmtClock(n.timestamp, locale)}</span>
                                  <span>•</span>
                                  <span className="font-semibold">{relTime(n.timestamp)}</span>
                                </div>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </section>
                ))}
              </div>
            )}
          </ScrollArea>
        </div>

        {/* ── Right Side: Active Workspace Console ── */}
        <div className={cn(
          "w-full md:w-[60%] flex flex-col bg-muted/10 min-w-[360px] shrink-0 min-h-0",
          !selectedNotifId && "hidden md:flex"
        )}>
          {activeNotif ? (
            <div className="flex-1 flex flex-col min-h-0 relative">
              <div className="md:hidden shrink-0 border-b border-border bg-background p-2">
                <Button variant="ghost" size="sm" onClick={() => setSelectedNotifId(null)} className="text-muted-foreground">
                  <IconArrowLeft size={16} className="mr-2" />
                  {extraCopy.backToFeed}
                </Button>
              </div>

              {/* Header */}
              <div className="shrink-0 bg-background border-b border-border px-6 py-4 flex items-center justify-between gap-4 sticky top-0 z-10">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline" className={cn(
                    "text-[10px] uppercase font-bold",
                    activeNotif.severity === 'critical' ? "border-red-200 dark:border-red-950/50 text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-950/20" :
                    activeNotif.severity === 'warning' ? "border-amber-200 dark:border-amber-950/50 text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-950/20" :
                    "border-blue-200 dark:border-blue-950/50 text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-950/20"
                  )}>
                    {activeNotif.severity}
                  </Badge>
                  
                  {activeNotif.orderId && <Badge variant="secondary" className="text-xs font-mono">ORDER: {activeNotif.orderId}</Badge>}
                  {activeNotif.routeName && <Badge variant="secondary" className="text-xs font-mono">ROUTE: {activeNotif.routeName}</Badge>}
                  {activeNotif.driverName && <Badge variant="secondary" className="text-xs font-mono">DRIVER: {activeNotif.driverName}</Badge>}
                  <span className="text-xs text-muted-foreground ml-2 font-mono">
                    {fmtClock(activeNotif.timestamp, locale)}
                  </span>
                </div>
              </div>

              {/* Scrollable Content */}
              <ScrollArea className="flex-1 bg-muted/10">
                <div className="p-6 flex flex-col gap-6">
                  
                  {/* Context */}
                  <div>
                    <h2 className="text-xl font-semibold text-foreground mb-2">
                      {getLocalizedNotif(activeNotif, locale).title}
                    </h2>
                    <p className="text-sm text-muted-foreground leading-relaxed max-w-2xl">
                      {getLocalizedNotif(activeNotif, locale).message}
                    </p>
                  </div>

                  {/* Dynamic Payload Diagnostics */}
                  {activeNotif.event === 'sla.breach' && (
                    <Card>
                      <CardHeader className="pb-3 border-b">
                        <CardTitle className="text-sm font-semibold uppercase tracking-tight text-muted-foreground">System Failure Analysis</CardTitle>
                      </CardHeader>
                      <CardContent className="pt-4 p-0">
                        <table className="w-full text-sm text-left">
                          <tbody>
                            <tr className="border-b border-border">
                              <td className="py-3 px-6 text-muted-foreground font-medium w-1/3">Reason</td>
                              <td className="py-3 px-6 text-foreground">Courier inactive duration exceeded dispatch threshold.</td>
                            </tr>
                            <tr className="border-b border-border">
                              <td className="py-3 px-6 text-muted-foreground font-medium">Threshold Limit</td>
                              <td className="py-3 px-6 text-foreground font-mono">{activeNotif.eventParams?.limit} MIN</td>
                            </tr>
                            <tr className="border-b border-border">
                              <td className="py-3 px-6 text-muted-foreground font-medium">Elapsed Time</td>
                              <td className="py-3 px-6 text-destructive font-mono font-bold">{activeNotif.eventParams?.elapsed} MIN (OVERRUN)</td>
                            </tr>
                            <tr>
                              <td className="py-3 px-6 text-muted-foreground font-medium">Breach Motif</td>
                              <td className="py-3 px-6 text-foreground font-mono">{activeNotif.eventParams?.motif || 'UNKNOWN'}</td>
                            </tr>
                          </tbody>
                        </table>
                      </CardContent>
                    </Card>
                  )}

                  {activeNotif.event === 'erp.sync_failed' && (
                    <Card>
                      <CardHeader className="pb-3 border-b">
                        <CardTitle className="text-sm font-semibold uppercase tracking-tight text-muted-foreground flex items-center gap-2">
                          diagnostics_inbound_stream.json
                        </CardTitle>
                      </CardHeader>
                      <CardContent className="pt-0 p-0">
                        <pre className="p-4 overflow-x-auto text-xs font-mono text-emerald-600 dark:text-emerald-400 bg-muted/50 rounded-b-xl m-0 border-t border-border">
{`{
  "status": "SYNC_FAILED",
  "erpOrderId": "${activeNotif.orderId || 'ERP-98231'}",
  "error": "CONNECTION_TIMEOUT_INBOUND_GATEWAY",
  "timestamp": "${new Date(activeNotif.timestamp).toISOString()}",
  "attempts": 3,
  "payload": {
    "orderId": "${activeNotif.deliveryId || 'UUID-7762-1A'}",
    "client": "${activeNotif.clientName || 'Société Générale'}"
  }
}`}
                        </pre>
                      </CardContent>
                    </Card>
                  )}

                  {/* Event Chronology */}
                  <Card>
                    <CardHeader className="pb-3 border-b border-border">
                      <CardTitle className="text-[11px] font-medium text-[var(--text-muted)] tracking-wide">Event Chronology Stream</CardTitle>
                    </CardHeader>
                    <CardContent className="pt-6">
                      <div className="flex flex-col gap-0">
                        {(() => {
                          const timelineEvents = [
                            {
                              timestamp: activeNotif.timestamp,
                              status: activeNotif.severity === 'critical' ? 'FAILED' : activeNotif.severity === 'warning' ? 'SLA_BREACH' : 'VALIDATED',
                              note: `SYSTEM EXCEPTION: ${activeNotif.event.replace('.', ' ')}`,
                            },
                            {
                              timestamp: activeNotif.timestamp - 120000,
                              status: 'VALIDATED',
                              note: 'System telemetry check-in (OK)',
                            },
                            ...((activeNotif.routeName || activeNotif.driverName) ? [{
                              timestamp: activeNotif.timestamp - 3600000,
                              status: 'ASSIGNED',
                              note: `Assigned to ${activeNotif.routeName || activeNotif.driverName} (OK)`,
                            }] : []),
                            {
                              timestamp: activeNotif.timestamp - 7200000,
                              status: 'SCHEDULED',
                              note: 'Order ingested from ERP (OK)',
                            }
                          ];

                          return timelineEvents.map((evt, idx) => {
                            const isFirst = idx === 0;
                            const isLast  = idx === timelineEvents.length - 1;
                            const ts = evt.timestamp;
                            const dotColor = isFirst 
                              ? (evt.status === 'FAILED' ? '#EF4444' : evt.status === 'SLA_BREACH' ? '#F59E0B' : 'var(--brand)') 
                              : 'var(--border)';

                            return (
                              <div key={idx} style={{ display: 'flex', gap: 14 }}>
                                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flexShrink: 0 }}>
                                  <div style={{
                                    width: 20, height: 20, borderRadius: '50%', flexShrink: 0,
                                    background: isFirst ? dotColor : 'var(--app-bg)',
                                    border: `2px solid ${dotColor}`,
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                  }}>
                                    {isFirst && <div style={{ width: 6, height: 6, borderRadius: '50%', background: '#fff' }} />}
                                  </div>
                                  {!isLast && (
                                    <div style={{ width: 1, flex: 1, minHeight: 16, background: 'var(--border)', margin: '2px 0' }} />
                                  )}
                                </div>
                                <div style={{ paddingBottom: isLast ? 0 : 14 }}>
                                  <div className="flex items-center gap-2 mb-0.5">
                                    <StatusBadge status={evt.status} size="sm" />
                                    <span className="text-[10px] font-mono text-[var(--text-muted)]">
                                      {ts ? new Date(ts).toLocaleString('fr-FR') : '—'}
                                    </span>
                                  </div>
                                  {evt.note && (
                                    <p className="text-[11px] text-[var(--text-primary)] mt-0.5">{evt.note}</p>
                                  )}
                                </div>
                              </div>
                            );
                          });
                        })()}
                      </div>
                    </CardContent>
                  </Card>

                </div>
              </ScrollArea>

              {/* Floating Action Strip */}
              <div className="shrink-0 bg-background border-t border-border p-4 flex items-center justify-end gap-3 sticky bottom-0 z-10 shadow-sm">
                <div className="flex items-center gap-3 w-full sm:w-auto overflow-x-auto scrollbar-none">
                  
                  {/* Category A */}
                  {activeNotif.event.startsWith('delivery.') && !['delivery.handoff_confirmed'].includes(activeNotif.event) && (
                    <a
                      href={activeNotif.routeId ? `/routes/${activeNotif.routeId}` : `/deliveries/${activeNotif.deliveryId || activeNotif.orderId}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className={cn(buttonVariants({ variant: "default" }))}
                    >
                      {extraCopy.viewTracking}
                    </a>
                  )}

                  {/* Category B */}
                  {activeNotif.event === 'sla.breach' && (
                    <>
                      <a
                        href="tel:+21699000000"
                        className={cn(buttonVariants({ variant: "outline" }))}
                      >
                        {extraCopy.callClient}
                      </a>
                      {activeNotif.driverName && (
                        <a
                          href="tel:+21699123456"
                          className={cn(
                            buttonVariants({ variant: "outline" }),
                            "border-emerald-500 text-emerald-600 hover:bg-emerald-50"
                          )}
                        >
                          {extraCopy.callCourier}
                        </a>
                      )}

                      {activeNotif.eventParams?.motif === 'SLA_TRANSIT' ? (
                        <a
                          href={`/deliveries/${activeNotif.deliveryId || activeNotif.orderId}`}
                          className={cn(buttonVariants({ variant: "default" }))}
                        >
                          {extraCopy.viewTracking}
                        </a>
                      ) : (
                        <>
                          <Button variant="secondary">{extraCopy.replanDelivery}</Button>
                          <Button variant="destructive" onClick={() => setIsReassignOverlayOpen(true)}>
                            {extraCopy.forceReassign}
                          </Button>
                        </>
                      )}
                    </>
                  )}

                  {/* Category C */}
                  {(activeNotif.event.startsWith('route.') || activeNotif.event.startsWith('STOPS_') || activeNotif.event === 'delivery.handoff_confirmed') && (
                    <a
                      href={`/routes/${activeNotif.routeId || 'unknown'}`}
                      className={cn(buttonVariants({ variant: "default" }))}
                    >
                      {extraCopy.viewRoute}
                    </a>
                  )}

                  {/* Category D */}
                  {activeNotif.event.startsWith('erp.') && (
                    activeNotif.event === 'erp.sync_failed' ? (
                      <Button disabled={isRetryingSync} onClick={handleRetrySync}>
                        {isRetryingSync ? <span className="animate-spin mr-2">⟳</span> : null}
                        {extraCopy.retryIngestion}
                      </Button>
                    ) : (
                      <a
                        href="/import"
                        className={cn(buttonVariants({ variant: "default" }))}
                      >
                        {extraCopy.openWorkspace}
                      </a>
                    )
                  )}

                  <div className="flex-1" />

                  <Button
                    variant="ghost"
                    onClick={() => {
                      markRead(activeNotif.id);
                      setSelectedNotifId(null);
                    }}
                    className="text-muted-foreground ml-auto"
                  >
                    {extraCopy.acknowledgeClear}
                  </Button>
                </div>
              </div>
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center select-none bg-muted/10">
              <div className="w-16 h-16 rounded-2xl bg-muted/50 flex items-center justify-center shrink-0 mb-6">
                <IconRadar2 size={32} className="text-muted-foreground animate-pulse" />
              </div>

              <div className="max-w-[300px]">
                <h3 className="text-sm font-semibold text-foreground">
                  {extraCopy.diagnosticTitle}
                </h3>
                <p className="text-sm text-muted-foreground mt-2">
                  {extraCopy.selectPrompt}
                </p>
              </div>

              <Card className="mt-12 bg-muted/30 border-muted-foreground/20 text-start w-full max-w-[400px]">
                <CardHeader className="py-3 px-4 border-b border-border">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-xs font-mono font-bold text-muted-foreground tracking-wider uppercase">SYSTEM_INGESTION_LOG</CardTitle>
                    <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                  </div>
                </CardHeader>
                <CardContent className="py-3 px-4 flex flex-col gap-1 font-mono text-[10px] text-muted-foreground">
                  <div>[00:51:02] WS_LISTENER_ESTABLISHED - connected</div>
                  <div>[00:51:10] QUEUE_BUFFER_RESOLVED - 0Exceptions</div>
                  <div>[00:52:00] SYS_DIAGNOSTICS_ACTIVE - ready</div>
                </CardContent>
              </Card>
            </div>
          )}
        </div>
      </div>

      <ReassignCommandOverlay 
        open={isReassignOverlayOpen}
        onCancel={() => setIsReassignOverlayOpen(false)}
        onConfirm={(payload) => {
          console.log('Executing reassignment override:', payload);
          setIsReassignOverlayOpen(false);
          markRead(selectedNotifId || '');
          setSelectedNotifId(null);
        }}
        entityName={activeNotif?.orderId || activeNotif?.deliveryId || 'Unknown'}
        drivers={mockDrivers}
        routes={mockRoutes}
        currentDriverId="driver_99"
      />
    </div>
  );
}
