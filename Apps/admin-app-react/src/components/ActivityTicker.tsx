import { useNavigate } from 'react-router-dom';
import { IconActivity, IconTruck, IconRoute, IconPackage } from '@tabler/icons-react';
import { useRealtimeStatus } from '@/components/RealtimeProvider';
import { useNotificationsState, getLocalizedNotif, type Notification } from '@/components/AlertsProvider';
import { useLocaleStore } from '@/lib/i18n';
import { formatElapsed } from '@/lib/sla';
import { notifDestination } from '@/lib/api/dispatch-link';
import { cn } from '@/lib/utils';

// Recent operational activity, sourced from the server-backed notifications feed
// (so it survives a refresh) and kept live by the same RealtimeProvider socket.

function categoryIcon(category: Notification['category']) {
  if (category === 'route') return IconRoute;
  if (category === 'erp') return IconPackage;
  return IconTruck;
}

export default function ActivityTicker() {
  const navigate = useNavigate();
  const locale = useLocaleStore(s => s.locale) || 'fr';
  const connected = useRealtimeStatus();
  const { notifications } = useNotificationsState();
  const entries = notifications;

  const titleLabel = locale === 'ar' ? 'النشاط المباشر' : locale === 'en' ? 'Live Activity' : 'Activité en direct';
  const emptyLabel = locale === 'ar' ? 'في انتظار النشاط…' : locale === 'en' ? 'Waiting for activity…' : 'En attente d\'activité…';

  const severityTone = (severity: string) => {
    if (severity === 'critical') return 'text-[var(--danger)]';
    if (severity === 'warning') return 'text-[var(--warning)]';
    return 'text-[var(--brand)]';
  };

  return (
    <div className="card h-full overflow-hidden flex flex-col">
      <div className="ps-10 pe-4 py-3 border-b border-[var(--border)] flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2">
          <IconActivity size={15} className="text-[var(--brand)]" />
          <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{titleLabel}</span>
        </div>
        <span
          className={cn('text-2xs font-bold', connected ? 'text-[var(--success)]' : 'text-[var(--text-soft)]')}
          title={connected ? 'Connected' : 'Reconnecting…'}
        >
          {connected ? '● LIVE' : '—'}
        </span>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-2" style={{ scrollbarWidth: 'thin' }}>
        {entries.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-2 opacity-40 py-10">
            <IconActivity size={22} stroke={1.5} className="text-[var(--text-muted)]" />
            <span className="text-xs font-medium text-[var(--text-muted)]">{emptyLabel}</span>
          </div>
        ) : (
          <div className="flex flex-col">
            {entries.map(n => {
              const Icon = categoryIcon(n.category);
              const loc = getLocalizedNotif(n, locale);
              const subtitle = [n.clientName, n.orderId || n.routeName].filter(Boolean).join(' · ');
              return (
                <button
                  key={n.id}
                  type="button"
                  onClick={() => navigate(notifDestination(n))}
                  className="flex items-start gap-2 px-2 py-2 rounded-md hover:bg-[var(--hover-bg)] transition-colors text-left"
                >
                  <Icon size={14} className={cn('mt-0.5 shrink-0', severityTone(n.severity))} />
                  <div className="flex flex-col min-w-0 flex-1">
                    <span className="text-xs font-semibold text-[var(--text-primary)] leading-tight truncate">
                      {loc.title}
                    </span>
                    <span className="text-2xs text-[var(--text-muted)] truncate">
                      {subtitle || loc.message || '—'}
                    </span>
                  </div>
                  <span className="text-2xs font-mono text-[var(--text-soft)] shrink-0 mt-0.5">
                    {formatElapsed(new Date(n.timestamp).toISOString(), locale)}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
