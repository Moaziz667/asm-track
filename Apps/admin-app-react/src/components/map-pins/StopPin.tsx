
import React from 'react';
import styles from '@/styles/map-pins.module.scss';

interface StopPinProps {
  label: string;
  status: string;
  selected?: boolean;
}

export function StopPin({ label, status, selected = false }: StopPinProps) {
  const statusMap: Record<string, string> = {
    COMPLETED: 'completed',
    DELIVERED: 'delivered',
    FAILED: 'failed',
    CANCELLED: 'failed',
    FAILED_ATTEMPT: 'failed',
    PARTIAL: 'partial',
    PARTIALLY_DELIVERED: 'partial',
    IN_TRANSIT: 'inTransit',
    PICKED_UP: 'inTransit',
    ARRIVED: 'arrived',
    SCHEDULED: 'scheduled',
    PENDING: 'pending',
  };

  const statusClass = statusMap[status] || 'pending';
  const fontSize = String(label).length > 2 ? 8 : String(label).length > 1 ? 10 : 12;

  return (
    <div className={`${styles.stopPin} ${styles[statusClass]} ${selected ? styles.selected : ''}`}>
      <svg width="28" height="36" viewBox="0 0 28 36" xmlns="http://www.w3.org/2000/svg">
        <path d="M14 0C6.268 0 0 6.268 0 14c0 5.746 3.44 10.71 8.44 13.07L14 36l5.56-8.93C24.56 24.71 28 19.746 28 14 28 6.268 21.732 0 14 0z" />
        <text
          x="14"
          y="15"
          textAnchor="middle"
          dominantBaseline="middle"
          fill="white"
          fontSize={fontSize}
          fontWeight="800"
          fontFamily="system-ui,sans-serif"
          letterSpacing="-0.3"
        >
          {label}
        </text>
      </svg>
    </div>
  );
}

