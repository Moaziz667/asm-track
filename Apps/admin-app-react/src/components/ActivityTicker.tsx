import { useNavigate } from 'react-router-dom';
import {
  IconCircleCheck, IconTruck, IconContainer,
  IconAlertTriangle, IconLayoutGrid, IconChevronRight,
} from '@tabler/icons-react';
import { useRealtimeStatus } from '@/components/RealtimeProvider';
import { useNotificationsState, getLocalizedNotif, type Notification } from '@/components/AlertsProvider';
import { useLocaleStore } from '@/lib/i18n';
import { notifDestination } from '@/lib/api/dispatch-link';
import { cn } from '@/lib/utils';

// Recent operational activity, sourced from the server-backed notifications feed
// (so it survives a refresh) and kept live by the same RealtimeProvider socket.
// Rendered as a timeline: absolute clock time · colored dot on a continuous rail ·
// tinted type-icon chip · title/subtitle.

type TypeStyle = { Icon: typeof IconTruck; fg: string; bg: string };

// Icon + color by event type. Exceptions (critical) win; then category. Purple (route)
// has no theme token, so it is an inline literal — acceptable here, not a themed surface.
function typeStyle(n: Notification): TypeStyle {
  if (n.severity === 'critical') return { Icon: IconAlertTriangle, fg: 'var(--danger)', bg: 'var(--danger-bg)' };
  if (n.category === 'route') return { Icon: IconLayoutGrid, fg: '#7c6cf0', bg: 'rgba(124,108,240,0.12)' };
  if (n.category === 'erp') return { Icon: IconContainer, fg: 'var(--brand)', bg: 'var(--brand-bg)' };
  return { Icon: IconTruck, fg: 'var(--success)', bg: 'var(--success-bg)' };
}

const clock = (ts: number, locale: string) =>
  new Date(ts).toLocaleTimeString(locale === 'ar' ? 'ar-TN' : locale === 'en' ? 'en-GB' : 'fr-FR',
    { hour: '2-digit', minute: '2-digit', hour12: false });

export default function ActivityTicker() {
  const navigate = useNavigate();
  const locale = useLocaleStore(s => s.locale) || 'fr';
  const connected = useRealtimeStatus();
  const { notifications } = useNotificationsState();
  // Keep the feed light: last 30 events, ~6 visible then scroll.
  const entries = notifications.slice(0, 30);

  const titleLabel = locale === 'ar' ? 'النشاط المباشر' : locale === 'en' ? 'Live Activity' : 'Activité en direct';
  const emptyLabel = locale === 'ar' ? 'في انتظار النشاط…' : locale === 'en' ? 'Waiting for activity…' : 'En attente d\'activité…';
  const viewAllLabel = locale === 'ar' ? 'عرض الكل' : locale === 'en' ? 'View all' : 'Voir tout';

  return (
    <div className="border border-[var(--border)] rounded-lg bg-[var(--surface)] shadow-[var(--shadow-card)] transition-shadow duration-200 hover:shadow-[var(--shadow-card-hover)] h-full overflow-hidden flex flex-col">
      <div className="px-4 py-3 border-b border-[var(--border)] flex items-center justify-between shrink-0">
        <span className="text-xs font-semibold text-[var(--text-primary)]">{titleLabel}</span>
        <span
          className={cn('text-2xs font-bold', connected ? 'text-[var(--success)]' : 'text-[var(--text-soft)]')}
          title={connected ? 'Connected' : 'Reconnecting…'}
        >
          {connected ? '● LIVE' : '—'}
        </span>
      </div>

      <div className="flex-1 overflow-y-auto px-2 py-1" style={{ scrollbarWidth: 'thin' }}>
        {entries.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-2 opacity-40 py-10">
            <IconCircleCheck size={22} strokeWidth={1.5} className="text-[var(--text-muted)]" />
            <span className="text-xs font-medium text-[var(--text-muted)]">{emptyLabel}</span>
          </div>
        ) : (
          <div className="flex flex-col">
            {entries.map((n, i) => {
              const { Icon, fg, bg } = typeStyle(n);
              const loc = getLocalizedNotif(n, locale);
              const ref = n.orderId || n.routeName;
              const isLast = i === entries.length - 1;
              return (
                <button
                  key={n.id}
                  type="button"
                  onClick={() => navigate(notifDestination(n))}
                  className="group flex items-stretch gap-3 rounded-md hover:bg-[var(--hover-bg)] transition-colors text-left"
                >
                  {/* Absolute clock time */}
                  <span className="w-10 shrink-0 text-end text-2xs font-mono tabular-nums text-[var(--text-muted)] pt-3.5">
                    {clock(n.timestamp, locale)}
                  </span>

                  {/* Timeline rail. The node used to be a 6px dot with a 2px ring — barely two
                      pixels of actual colour — sitting next to a bare icon in the same colour:
                      two marks encoding one fact, neither of them legible. The icon is now the
                      node, on the tint typeStyle had been returning and nobody was using. */}
                  <div className="flex flex-col items-center shrink-0 w-7">
                    <span className="w-px h-3 bg-[var(--border)]" style={{ visibility: i === 0 ? 'hidden' : 'visible' }} aria-hidden="true" />
                    <span
                      className="w-7 h-7 rounded-full flex items-center justify-center shrink-0 z-10 ring-2 ring-[var(--surface)]"
                      style={{ background: bg }}
                    >
                      <Icon size={15} strokeWidth={1.8} style={{ color: fg }} />
                    </span>
                    <span className="w-px flex-1 bg-[var(--border)]" style={{ visibility: isLast ? 'hidden' : 'visible' }} aria-hidden="true" />
                  </div>

                  {/* Title, then who and which shipment. The reference is set in the mono face so a
                      column of BL numbers scans vertically instead of blurring into the prose. */}
                  <div className="flex flex-col min-w-0 flex-1 py-2.5 pe-1">
                    <span className="text-xs font-medium text-[var(--text-primary)] leading-snug line-clamp-2">
                      {loc.title}
                    </span>
                    <span className="text-2xs text-[var(--text-muted)] truncate mt-1 flex items-center gap-1.5">
                      {n.clientName && <span className="truncate">{n.clientName}</span>}
                      {ref && (
                        <span className="font-mono text-[var(--text-soft)] shrink-0">{ref}</span>
                      )}
                      {!n.clientName && !ref && <span className="truncate">{loc.message || '—'}</span>}
                    </span>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* Footer — view full activity */}
      <button
        type="button"
        onClick={() => navigate('/notifications')}
        className="shrink-0 border-t border-[var(--border)] px-4 py-2.5 flex items-center justify-center gap-1.5 text-xs font-semibold text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer"
      >
        {viewAllLabel}
        <IconChevronRight size={14} />
      </button>
    </div>
  );
}
