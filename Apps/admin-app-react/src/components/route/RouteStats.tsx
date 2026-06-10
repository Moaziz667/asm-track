

import { ReactNode } from 'react';
import { Progress } from '@/components/ui/progress';
import { cn, formatMinutes } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import styles from '@/styles/route-details.module.scss';

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
  total: number;
  progressPercent: number;
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
  total,
  progressPercent,
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
        {/* Driver KPI — with status dot */}
        <KpiCard
          label={t.routeDetailPage?.labelDriver || 'Chauffeur'}
          value={driverName || (t.routeDetailPage?.notAssigned || 'Non assigné')}
          sub={driverStatus && driverStatusColor && driverStatusColor.label}
          driverStatus={
            driverStatusColor
              ? { dot: driverStatusColor.dot, label: driverStatusColor.label }
              : undefined
          }
        />

        {/* Progression */}
        <KpiCard
          label={t.routeDetailPage?.labelProgress || 'Progression'}
          value={`${completed}/${total}`}
          sub={failed > 0 ? `${failed} ${t.routeDetailPage?.failed || 'échoué(s)'}` : `${total} ${t.routeDetailPage?.stops || 'arrêts'}`}
          isAlert={failed > 0}
        />

        {/* Retard Cumulé */}
        <KpiCard
          label={t.routeDetailPage?.labelCumulativeDelay || 'Retard Cumulé'}
          value={formatMins(cumulativeDelayMinutes)}
          sub={`${t.routeDetailPage?.departure || 'Départ'} +${formatMins(routeStartDelayMinutes)}`}
          isAlert={(cumulativeDelayMinutes ?? 0) > 0}
        />

        {/* Ponctualité */}
        <KpiCard
          label={t.routeDetailPage?.labelPunctuality || 'Ponctualité'}
          value={
            onTimeCompletionRate != null
              ? `${onTimeCompletionRate.toFixed(0)}%`
              : '—'
          }
          sub={t.routeDetailPage?.withinWindow || 'dans fenêtre'}
        />

        {/* Distance */}
        <KpiCard
          label={t.routeDetailPage?.labelDistance || 'Distance'}
          value={distance || '—'}
          sub={duration}
        />

        {/* Véhicule */}
        <KpiCard
          label={t.routeDetailPage?.labelVehicle || 'Véhicule'}
          value={vehiclePlate || '—'}
          sub={vehicleType}
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

