import { useNavigate } from 'react-router-dom';
import { IconActivity, IconTruck, IconRoute, IconPackage, IconPointFilled } from '@tabler/icons-react';
import { useRealtimeStatus } from '@/components/RealtimeProvider';
import { useNotificationsState, getLocalizedNotif, type Notification } from '@/components/AlertsProvider';
import { useLocaleStore } from '@/lib/i18n';
import { formatElapsed } from '@/lib/sla';
import { dispatchDeskQueueLink } from '@/lib/dispatch-link';
import { cn } from '@/lib/utils';

// Recent operational activity, sourced from the server-backed notifications feed
// (so it survives a refresh) and kept live by the same RealtimeProvider socket.
const MAX_ENTRIES = 14;

function categoryIcon(category: Notification['category']) {
  if (category === 'route') return IconRoute;
  if (category === 'erp') return IconPackage;
  return IconTruck;
}

const SEV_DOT: Record<string, string> = {
  critical: '#C7372F',
  warning: '#D4772C',
  info: 'var(--brand)',
};

export default function ActivityTicker() {
  const navigate = useNavigate();
  const locale = useLocaleStore(s => s.locale) || 'fr';
  const connected = useRealtimeStatus();
  const { notifications } = useNotificationsState();
  const entries = notifications.slice(0, MAX_ENTRIES);

  const titleLabel = locale === 'ar' ? 'النشاط المباشر' : locale === 'en' ? 'Live Activity' : 'Activité en direct';
  const emptyLabel = locale === 'ar' ? 'في انتظار النشاط…' : locale === 'en' ? 'Waiting for activity…' : 'En attente d\'activité…';

  return (
    <div className="card h-full overflow-hidden flex flex-col">
      <div className="ps-8 pe-4 py-3 border-b border-[var(--border)] flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2">
          <IconActivity size={16} style={{ color: 'var(--brand)' }} />
          <span className="text-[14px] font-bold text-[var(--text-primary)]">{titleLabel}</span>
        </div>
        <span
          className={cn('inline-flex items-center gap-1 text-[10px] font-bold', connected ? 'text-[#4CAF82]' : 'text-[var(--text-soft)]')}
          title={connected ? 'Connected' : 'Reconnecting…'}
        >
          <IconPointFilled size={12} className={connected ? 'animate-pulse' : ''} />
          {connected ? 'LIVE' : '—'}
        </span>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-2" style={{ scrollbarWidth: 'thin' }}>
        {entries.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-2 opacity-40 py-10">
            <IconActivity size={22} stroke={1.5} className="text-[var(--text-muted)]" />
            <span className="text-[11px] font-medium text-[var(--text-muted)]">{emptyLabel}</span>
          </div>
        ) : (
          <div className="flex flex-col">
            {entries.map(n => {
              const Icon = categoryIcon(n.category);
              const dot = SEV_DOT[n.severity] || 'var(--brand)';
              const loc = getLocalizedNotif(n, locale);
              const subtitle = [n.clientName, n.orderId || n.routeName].filter(Boolean).join(' · ');
              return (
                <button
                  key={n.id}
                  type="button"
                  onClick={() => navigate(dispatchDeskQueueLink({
                    orderRef: n.orderId, orderId: n.orderId, deliveryId: n.deliveryId,
                  }))}
                  className="flex items-start gap-2.5 px-2 py-2 rounded-[8px] hover:bg-[var(--hover-bg)] transition-colors text-left"
                >
                  <span className="mt-0.5 shrink-0 w-1.5 h-1.5 rounded-full" style={{ background: dot }} />
                  <Icon size={14} className="mt-0.5 shrink-0 text-[var(--text-muted)]" />
                  <div className="flex flex-col min-w-0 flex-1">
                    <span className="text-[12px] font-semibold text-[var(--text-primary)] leading-tight truncate">
                      {loc.title}
                    </span>
                    <span className="text-[11px] text-[var(--text-muted)] truncate">
                      {subtitle || loc.message || '—'}
                    </span>
                  </div>
                  <span className="text-[10px] font-mono text-[var(--text-soft)] shrink-0 mt-0.5">
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
