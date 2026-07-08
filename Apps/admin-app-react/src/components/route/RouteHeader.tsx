

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { IconArrowLeft, IconRefresh } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import styles from '@/pages/route-details/route-details.module.scss';

interface RouteHeaderProps {
  routeName: string;
  routeStatus: string;
  isOptimized?: boolean;
  date?: string;
  city?: string;
  onBack: () => void;
  onRefresh: () => void;
  isRefreshing?: boolean;
}

export function RouteHeader({
  routeName,
  routeStatus,
  isOptimized,
  date,
  city,
  onBack,
  onRefresh,
  isRefreshing,
}: RouteHeaderProps) {
  const t = useT();
  return (
    <div className={styles.pageHeader}>
      <div className={styles.headerLeft}>
        <button className={styles.backButton} onClick={onBack} title={t.routeDetailPage?.back || 'Retour'}>
          <IconArrowLeft size={15} />
        </button>
        <div className={styles.routeInfo}>
          <div className={styles.breadcrumb}>{t.routeDetailPage?.breadcrumbDetail || 'Détail'} · {t.pages.routes?.title || 'Tournée'}</div>
          <div className={styles.routeDetails}>
            <div className={styles.routeName}>{routeName}</div>
            <StatusBadge status={routeStatus as string} size="md" />
            {isOptimized && (
              <Badge variant="outline" className={styles.optimizedBadge}>
                {t.routeDetailPage?.optimized || 'Optimisée'}
              </Badge>
            )}
          </div>
        </div>
      </div>

      <div className={styles.headerRight}>
        {date && (
          <div className={styles.dateInfo}>
            {date}
            {city && ` · ${city}`}
          </div>
        )}
        <Button
          onClick={onRefresh}
          disabled={isRefreshing}
          className={styles.refreshButton}
          variant="outline"
          size="sm"
        >
          <IconRefresh
            size={14}
            className={cn(isRefreshing && 'animate-spin')}
          />
          <span className="hidden sm:inline">{t.routeDetailPage?.refresh || 'Actualiser'}</span>
        </Button>
      </div>
    </div>
  );
}

