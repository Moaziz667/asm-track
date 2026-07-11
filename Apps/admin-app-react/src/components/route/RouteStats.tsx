

import { ReactNode } from 'react';
import { IconTruck } from '@tabler/icons-react';
import { Progress } from '@/components/ui/progress';
import { cn, formatMinutes } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import styles from '@/pages/route-details/route-details.module.scss';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';

interface KpiCardProps {
  label: string;
  value: string | number;
  sub?: string | ReactNode;
  isAlert?: boolean;
  icon?: ReactNode;
  driverStatus?: { dot: string; label: string };
}

function KpiCard({ label, value, sub, isAlert, icon, driverStatus }: KpiCardProps) {
  return (
    <div className={styles.kpiCard}>
      <div className={styles.label}>{label}</div>
      {driverStatus ? (
        <div className={styles.driverStatus}>
          <div
            className={styles.dot}
            style={{ backgroundColor: driverStatus.dot }}
          />
          <div className={cn(styles.value, isAlert && 'text-red-600')}>{value}</div>
        </div>
      ) : icon ? (
        // Icon variant: a bordered tile holds the icon next to the value (e.g. the
        // vehicle plate sits beside a framed car icon).
        <div className="flex items-center gap-2">
          <span
            className="flex items-center justify-center w-7 h-7 rounded-[var(--radius-md)] border shrink-0"
            style={{ borderColor: 'var(--border)', background: 'var(--app-bg)', color: 'var(--text-muted)' }}
          >
            {icon}
          </span>
          <div className={cn(styles.value, isAlert && 'text-red-600')}>{value}</div>
        </div>
      ) : (
        <div className={cn(styles.value, isAlert && 'text-red-600')}>{value}</div>
      )}
      {sub && (
        <div className={styles.sub}>
          {typeof sub === 'string' ? sub : sub}
        </div>
      )}
      {driverStatus && (
        <div className={cn(styles.sub, 'mt-1')}>{driverStatus.label}</div>
      )}
    </div>
  );
}

interface RouteStatsProps {
  completed: number;
  failed: number;
  partial?: number;
  total: number;
  progressPercent: number;
  driverId?: string;
  driverName?: string;
  driverStatus?: string;
  driverStatusColor?: { text: string; dot: string; label: string };
  driverPhone?: string;
  cumulativeDelayMinutes?: number;
  routeStartDelayMinutes?: number;
  onTimeCompletionRate?: number;
  distance?: string;
  duration?: string;
  vehiclePlate?: string;
  vehicleType?: string;
  totalWeightKg?: number;
  vehicleCapacityKg?: number;
}

export function RouteStats({
  completed,
  failed,
  partial = 0,
  total,
  progressPercent,
  driverId,
  driverName,
  driverStatus,
  driverStatusColor,
  driverPhone,
  cumulativeDelayMinutes,
  routeStartDelayMinutes,
  onTimeCompletionRate,
  distance,
  duration,
  vehiclePlate,
  vehicleType,
  totalWeightKg,
  vehicleCapacityKg,
}: RouteStatsProps) {
  const t = useT();
  const formatMins = formatMinutes;

  return (
    <div className={styles.kpiStrip}>
      <div className={styles.kpiGrid}>
        {/* Driver KPI — avatar + name + status */}
        <div className={styles.kpiCard}>
          <div className={styles.label}>{t.routeDetailPage?.labelDriver || 'Chauffeur'}</div>
          <div className="flex items-center gap-2 mt-0.5">
            <DriverAvatarById driverId={driverId} name={driverName ?? undefined} size={24} />
            <div className={styles.value}>{driverName || (t.common?.nonAssigne ?? 'Unassigned')}</div>
          </div>
          {driverStatusColor && (
            <div className={cn(styles.sub, 'mt-1')} style={{ color: driverStatusColor.text }}>
              {driverStatusColor.label}
            </div>
          )}
        </div>

        {/* Progression */}
        <KpiCard
          label={t.routeDetailPage?.labelProgress || 'Progression'}
          value={`${completed + failed + partial}/${total}`}
          sub={failed > 0 ? `${failed} ${t.common?.echoue ?? 'failed'}` : `${total} ${t.common?.arrets ?? 'stops'}`}
          isAlert={failed > 0}
        />

        {/* Retard Cumulé */}
        <KpiCard
          label={t.routeDetailPage?.labelCumulativeDelay || 'Cumulative Delay'}
          value={formatMins(cumulativeDelayMinutes)}
          sub={`${t.common?.depart ?? 'Departure'} +${formatMins(routeStartDelayMinutes)}`}
          isAlert={(cumulativeDelayMinutes ?? 0) > 0}
        />

        {/* Ponctualité */}
        <KpiCard
          label={t.common?.ponctualite ?? 'Punctuality'}
          value={
            onTimeCompletionRate != null
              ? `${onTimeCompletionRate.toFixed(0)}%`
              : '—'
          }
          sub={t.routeDetailPage?.withinWindow || 'on time'}
        />

        {/* Distance */}
        <KpiCard
          label={t.common?.distance ?? 'Distance'}
          value={distance || '—'}
          sub={duration}
        />

        {/* Véhicule */}
        <KpiCard
          label={t.common?.vehicule ?? 'Vehicle'}
          value={vehiclePlate || '—'}
          sub={vehicleType}
          icon={<IconTruck size={15} stroke={1.8} />}
        />

        {/* Charge totale */}
        <KpiCard
          label={t.routeDetailPage?.labelTotalLoad || 'Charge totale'}
          value={
            totalWeightKg && totalWeightKg > 0
              ? `${totalWeightKg.toFixed(1)} ${t.routeDetailPage?.unitKg || 'kg'}`
              : '—'
          }
          sub={vehicleCapacityKg ? `/ ${vehicleCapacityKg} ${t.routeDetailPage?.unitKg || 'kg'} ${t.routeDetailPage?.capacity || 'cap.'}` : undefined}
        />
      </div>

      {/* Linear progress bar */}
      <div className={styles.progressContainer}>
        <div
          className={cn(
            styles.progressBar,
            progressPercent >= 100 && styles.complete
          )}
          style={{ width: `${Math.min(progressPercent, 100)}%` }}
        />
      </div>
    </div>
  );
}

