

import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';
import { format, parseISO } from 'date-fns';
import { fr, enUS, arEG } from 'date-fns/locale';

interface TimelineEvent {
  id?: string;
  status: string;
  actor?: string;
  changedBy?: string;
  changedByRole?: string;
  eventKey?: string;
  eventParams?: Record<string, any> | string;
  changedAt?: string | Date;
  timestamp?: string | Date;
}

interface OperationalTimelineProps {
  events: TimelineEvent[];
  className?: string;
}

const TIMELINE_DICT: Record<string, Record<string, string>> = {
  DELIVERY_CREATED: {
    fr: '📦 Livraison initialisée sur le système.',
    en: '📦 Delivery initialized on the system.',
    ar: '📦 تم إنشاء الشحنة في النظام.',
  },
  DELIVERY_SCHEDULED: {
    fr: '📅 Livraison planifiée pour le client.',
    en: '📅 Delivery scheduled.',
    ar: '📅 تم جدولة الشحنة.',
  },
  DELIVERY_SCHEDULED_BY_DRIVER: {
    fr: '📅 Livraison planifiée par le livreur.',
    en: '📅 Delivery scheduled by driver.',
    ar: '📅 تم جدولة الشحنة من قبل السائق.',
  },
  DELIVERY_PICKED_UP: {
    fr: '🚚 Colis pris en charge par le livreur au dépôt.',
    en: '🚚 Package loaded and checked-out by courier.',
    ar: '🚚 تم استلام الطرد من المستودع بواسطة السائق.',
  },
  DELIVERY_TRANSIT_STARTED: {
    fr: '🗺️ Transit commencé — Colis en cours d\'acheminement.',
    en: '🗺️ Transit initiated — Package is en route.',
    ar: '🗺️ بدأ الشحن — الطرد في الطريق إلى الوجهة.',
  },
  DELIVERY_COMPLETED: {
    fr: '✅ Livraison complétée avec succès.',
    en: '✅ Delivery successfully completed.',
    ar: '✅ تم تسليم الشحنة بنجاح.',
  },
  DELIVERY_PARTIALLY_DELIVERED: {
    fr: '⚠️ Livraison partielle effectuée.',
    en: '⚠️ Partial delivery completed.',
    ar: '⚠️ تم التسليم الجزئي للشحنة.',
  },
  DELIVERY_FAILED: {
    fr: '❌ Livraison en échec. Motif : {reason}',
    en: '❌ Delivery failed. Reason: {reason}',
    ar: '❌ فشل التسليم. السبب: {reason}',
  },
  DELIVERY_CANCELLED: {
    fr: '🚫 Livraison annulée. Motif : {reason}',
    en: '🚫 Delivery cancelled. Reason: {reason}',
    ar: '🚫 تم إلغاء الشحنة. السبب: {reason}',
  },
  DELIVERY_REPLANNED: {
    fr: '🔄 Livraison replanifiée pour une nouvelle tentative. Chauffeur précédent: {previousDriver}',
    en: '🔄 Delivery replanned for another attempt. Previous driver: {previousDriver}',
    ar: '🔄 تمت إعادة جدولة الشحنة لمحاولة أخرى. السائق السابق: {previousDriver}',
  },
  RETURN_TO_ORIGIN_CONFIRMED: {
    fr: '↩️ Retour au dépôt confirmé. Motif : {reason}',
    en: '↩️ Return to origin confirmed. Reason: {reason}',
    ar: '↩️ تم تأكيد العودة إلى المستودع. السبب: {reason}',
  },
  ROUTE_STOP_CANCELLED: {
    fr: '🚫 Arrêt annulé. Motif : {reason}',
    en: '🚫 Stop cancelled. Reason: {reason}',
    ar: '🚫 تم إلغاء المحطة. السبب: {reason}',
  },
  ROUTE_STOP_REMOVED: {
    fr: '🔄 Arrêt retiré de la tournée (replanification programmée).',
    en: '🔄 Stop removed from sequence (replanning scheduled).',
    ar: '🔄 تمت إزالة المحطة من الرحلة (مجدولة لإعادة التخطيط).',
  },
  SLA_BREACH_ALERT: {
    fr: '⏱️ Alerte SLA : Créneau de {slaType} dépassé de {delay} minutes.',
    en: '⏱️ SLA Alert: {slaType} window exceeded by {delay} minutes.',
    ar: '⏱️ تنبيه SLA: تم تجاوز نافذة {slaType} بمقدار {delay} دقيقة.',
  },
};

const getEventColor = (status: string) => {
  switch (status) {
    case 'DELIVERED':
    case 'COMPLETED':
    case 'CLOSED':
      return 'bg-emerald-500/10 border-emerald-500/30 text-emerald-500';
    case 'FAILED':
      return 'bg-rose-500/10 border-rose-500/30 text-rose-500';
    case 'CANCELLED':
      return 'bg-slate-500/10 border-slate-500/30 text-slate-500';
    case 'PARTIALLY_DELIVERED':
    case 'PARTIAL':
      return 'bg-amber-500/10 border-amber-500/30 text-amber-500';
    case 'IN_TRANSIT':
    case 'PICKED_UP':
      return 'bg-sky-500/10 border-sky-500/30 text-sky-500';
    case 'SCHEDULED':
      return 'bg-indigo-500/10 border-indigo-500/30 text-indigo-500';
    default:
      return 'bg-slate-500/10 border-slate-500/30 text-slate-500';
  }
};

export default function OperationalTimeline({ events, className }: OperationalTimelineProps) {
  const t = useT();
  const { locale } = useLocaleStore();

  const getDateLocale = () => {
    if (locale === 'ar') return arEG;
    if (locale === 'en') return enUS;
    return fr;
  };

  const formatEventTime = (timeStr?: string | Date) => {
    if (!timeStr) return '';
    try {
      const date = typeof timeStr === 'string' ? parseISO(timeStr) : timeStr;
      return format(date, 'Pp', { locale: getDateLocale() });
    } catch {
      return String(timeStr);
    }
  };

  const renderEventDescription = (evt: TimelineEvent) => {
    const activeLocale = locale || 'fr';
    const key = evt.eventKey || evt.status || 'UNKNOWN';
    const templateObj = TIMELINE_DICT[key];
    
    // Parse parameters
    let params: Record<string, any> = {};
    if (evt.eventParams) {
      if (typeof evt.eventParams === 'string') {
        try {
          params = JSON.parse(evt.eventParams);
        } catch {
          params = {};
        }
      } else {
        params = evt.eventParams;
      }
    }

    if (!templateObj) {
      // Fallback for unmapped custom keys — construct readable message
      const fallbackMsg = key.replace(/_/g, ' ').toLowerCase();
      const capitalized = fallbackMsg.charAt(0).toUpperCase() + fallbackMsg.slice(1);
      
      const reasonVal = params.reason || params.motif || params.comment || '';
      return reasonVal ? `${capitalized} (${reasonVal})` : capitalized;
    }

    const template = templateObj[activeLocale] || templateObj['en'] || templateObj['fr'];

    // Interpolate bracket parameters safely
    return template.replace(/\{(\w+)\}/g, (match, k) => {
      const val = params[k];
      if (val !== undefined && val !== null) {
        // Translate key outcomes or statuses if they are strings
        if (k === 'slaType') {
          return activeLocale === 'ar' ? 'الانتظار' : (activeLocale === 'en' ? 'waiting' : 'attente');
        }
        return String(val);
      }
      return match;
    });
  };

  if (!events || events.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center p-8 text-center border border-[var(--border)] rounded-md bg-[var(--surface-sub)]">
        <span className="text-xs text-[var(--text-muted)]">{t.empty.history}</span>
      </div>
    );
  }

  // Sort events chronologically (latest first)
  const sortedEvents = [...events].sort((a, b) => {
    const timeA = new Date(a.changedAt || a.timestamp || 0).getTime();
    const timeB = new Date(b.changedAt || b.timestamp || 0).getTime();
    return timeB - timeA;
  });

  return (
    <div className={cn('relative pl-4 rtl:pl-0 rtl:pr-4 border-l rtl:border-l-0 rtl:border-r border-[var(--border)] space-y-6', className)}>
      {sortedEvents.map((evt, idx) => {
        const timeVal = evt.changedAt || evt.timestamp;
        const colorClasses = getEventColor(evt.status);
        const actorName = evt.changedBy || evt.actor || 'SYSTEM';
        const actorRole = evt.changedByRole || 'SYSTEM';
        
        return (
          <div key={evt.id || idx} className="relative group animate-fade-in">
            {/* Timeline Node Dot */}
            <div 
              className={cn(
                'absolute -left-[21px] rtl:-left-0 rtl:-right-[21px] top-1.5 w-3.5 h-3.5 rounded-full border-2 border-[var(--app-bg)] bg-[var(--surface)] transition-transform duration-200 group-hover:scale-125 z-10 flex items-center justify-center',
                evt.status === 'COMPLETED' || evt.status === 'DELIVERED' ? 'bg-emerald-500' : 
                evt.status === 'FAILED' ? 'bg-rose-500' : 
                evt.status === 'CANCELLED' ? 'bg-slate-500' : 'bg-[var(--text-soft)]'
              )}
            />

            {/* Event Panel */}
            <div className="p-3.5 rounded-md border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] hover:shadow-sm transition-all duration-200">
              <div className="flex flex-col md:flex-row md:items-center justify-between gap-1.5 mb-1.5">
                <span className="text-[12px] font-semibold tracking-tight text-[var(--text-primary)]">
                  {renderEventDescription(evt)}
                </span>
                <span className="text-[10px] font-medium text-[var(--text-soft)] shrink-0">
                  {formatEventTime(timeVal)}
                </span>
              </div>

              {/* Actor Tagging Details */}
              <div className="flex items-center gap-1.5">
                <span className="text-[9px] font-medium text-[var(--text-muted)]">
                  {locale === 'ar' ? 'بواسطة:' : (locale === 'en' ? 'By:' : 'Par :')}
                </span>
                <span className="text-[9px] font-bold text-[var(--text-secondary)]">
                  {actorName}
                </span>
                <span className={cn(
                  'text-[8px] font-extrabold px-1.5 py-0.5 rounded-[3px] border uppercase scale-90 origin-left rtl:origin-right shrink-0',
                  colorClasses
                )}>
                  {t.actors[actorRole] || actorRole}
                </span>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

