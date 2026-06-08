

import { useRef, useEffect, useState } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { IconBell, IconCheck } from '@tabler/icons-react';
import { useNotifications, type Notification, getLocalizedNotif } from './AlertsProvider';
import { dispatchDeskQueueLink } from '@/lib/dispatch-link';
import { useLocaleStore } from '@/lib/i18n';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { CopyDict } from '@/lib/LocaleContext';

import trackyInfo from '@/assets/tracky_info.png';
import trackyWarning from '@/assets/tracky_warning.png';
import trackyCritical from '@/assets/tracky_critical.png';

/* ── Mascot severity mapping ──────────────────────────────────────────────── */

const SEV_MASCOT: Record<string, string> = {
  critical: trackyCritical,
  warning: trackyWarning,
  info: trackyInfo,
};

/* ── Compact relative time ("3m", "2h", "1d", "Yesterday") ────────────────── */

function relativeTime(ts: number, copy: CopyDict['notificationsDropdown']): string {
  const diff = Math.floor((Date.now() - ts) / 1000);
  if (diff < 60) return copy.justNow;
  if (diff < 3600) return `${Math.floor(diff / 60)}m`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h`;
  const days = Math.floor(diff / 86400);
  if (days === 1) return '1d';
  return `${days}d`;
}

/* ── Navigate destination: command alerts open the Dispatch Desk, filtered ──── */

function navigateDest(n: Notification): string {
  if (n.event === 'erp.orders_ready') return '/import?tab=ready';
  if (n.deliveryId || n.orderId) {
    return dispatchDeskQueueLink({ orderRef: n.orderId, orderId: n.orderId, deliveryId: n.deliveryId });
  }
  if (n.category === 'route' && n.routeId) return `/routes/${n.routeId}`;
  return '/notifications';
}

/* ── Single notification row ───────────────────────────────────────────────── */

function NotifRow({
  n, onAction, copy, locale,
}: {
  n: Notification;
  onAction: (n: Notification) => void;
  copy: CopyDict['notificationsDropdown'];
  locale: string;
}) {
  const localized = getLocalizedNotif(n, locale);

  return (
    <button
      type="button"
      onClick={() => onAction(n)}
      className={cn(
        'group relative w-full flex items-start text-start px-4 py-3 gap-3',
        'transition-colors duration-100',
        'border-b border-[var(--border)] last:border-b-0',
        'hover:bg-[var(--hover-bg)]',
        'outline-none focus-visible:bg-[var(--hover-bg)]',
      )}
    >
      {/* Mascot severity square image */}
      <img
        src={SEV_MASCOT[n.severity] || trackyInfo}
        alt={n.severity}
        className="w-8 h-8 rounded-md border border-[var(--border)] shrink-0 mt-0.5 object-cover"
      />

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between gap-2">
          <p className={cn(
            'text-[13px] leading-tight truncate text-[var(--text-primary)]',
            n.read ? 'font-medium' : 'font-semibold',
          )}>
            {localized.title}
          </p>
          <span className="text-[11px] text-[var(--text-soft)] font-mono shrink-0 tabular-nums">
            {relativeTime(n.timestamp, copy)}
          </span>
        </div>

        <p className="text-[12px] text-[var(--text-muted)] truncate mt-0.5 leading-snug">
          {localized.message}
        </p>
      </div>

      {/* Unread dot */}
      {!n.read && (
        <span className="w-1.5 h-1.5 rounded-full bg-[var(--brand)] shrink-0 mt-2" />
      )}
    </button>
  );
}

/* ── AlertBell dropdown ────────────────────────────────────────────────────── */

export default function AlertBell() {
  const t = useT();
  const { locale } = useLocaleStore();
  const { notifications, unreadCount, markRead, markAllRead } = useNotifications();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const router = useRouter();

  const copy = t.notificationsDropdown;
  const hasCritical = notifications.some(n => !n.read && n.severity === 'critical');

  const preview = notifications.slice(0, 10);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleAction = (n: Notification) => {
    markRead(n.id);
    setOpen(false);
    router(navigateDest(n));
  };

  return (
    <div ref={ref} className="relative">
      {/* Bell button */}
      <div className="relative inline-flex">
        <button
          type="button"
          onClick={() => setOpen(v => !v)}
          aria-label={copy.title}
          className={cn(
            'w-8 h-8 flex items-center justify-center rounded-md',
            'border border-[var(--border)] text-[var(--text-muted)]',
            'bg-[var(--surface)] hover:bg-[var(--hover-bg)] hover:text-[var(--text-primary)]',
            'transition-colors duration-100 outline-none',
            'focus-visible:ring-2 focus-visible:ring-[var(--brand)]',
            open && 'bg-[var(--hover-bg)] text-[var(--text-primary)]',
          )}
        >
          <IconBell size={16} stroke={2.5} />
        </button>

        {/* Badge — static, no pulse */}
        {unreadCount > 0 && (
          <span
            className={cn(
              'absolute -top-1 -right-1 rtl:right-auto rtl:-left-1',
              'min-w-[16px] h-4 rounded-full text-white text-[9px] font-bold',
              'flex items-center justify-center px-1 leading-none',
              'border-[1.5px] border-[var(--surface)] pointer-events-none',
              'font-mono tabular-nums select-none',
              hasCritical ? 'bg-[var(--danger)]' : 'bg-[var(--brand)]',
            )}
          >
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </div>

      {/* Popover dropdown — solid surface, no glassmorphism */}
      {open && (
        <div className="absolute top-10 right-0 rtl:right-auto rtl:left-0 w-[340px] max-w-[calc(100vw-24px)] bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-lg z-[300] overflow-hidden animate-in fade-in slide-in-from-top-1 duration-100">

          {/* Header — title + mark all read */}
          <div className="px-4 py-3 flex items-center justify-between border-b border-[var(--border)]">
            <span className="text-[13px] font-semibold text-[var(--text-primary)] select-none">
              {copy.title}
            </span>
            {unreadCount > 0 && (
              <button
                type="button"
                onClick={markAllRead}
                className="inline-flex items-center gap-1 text-[12px] font-medium text-[var(--brand)] hover:underline transition-colors outline-none"
              >
                <IconCheck size={12} stroke={2} />
                {copy.markAllRead}
              </button>
            )}
          </div>

          {/* Flat notification list */}
          <div className="max-h-[400px] overflow-y-auto">
            {preview.length === 0 ? (
              <div className="py-12 px-4 text-center select-none">
                <p className="text-[13px] text-[var(--text-muted)]">{copy.noNotifications}</p>
              </div>
            ) : (
              <div className="flex flex-col">
                {preview.map(n => (
                  <NotifRow key={n.id} n={n} onAction={handleAction} copy={copy} locale={locale} />
                ))}
              </div>
            )}
          </div>

          {/* Footer — full-width link */}
          <button
            type="button"
            onClick={() => { setOpen(false); router('/notifications'); }}
            className="w-full py-2.5 text-center text-[12px] font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)] border-t border-[var(--border)] transition-colors outline-none"
          >
            {copy.viewAll}
          </button>
        </div>
      )}
    </div>
  );
}
