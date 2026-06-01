
import React from 'react';
import styles from '@/styles/map-pins.module.scss';

export function DriverPin() {
  return (
    <div className={styles.driverPin}>
      <svg width="34" height="34" viewBox="0 0 34 34" xmlns="http://www.w3.org/2000/svg">
        <circle cx="17" cy="17" r="17" fill="#0EA5E9" />
        <circle cx="17" cy="17" r="15" fill="none" stroke="white" strokeWidth="2" strokeOpacity="0.5" />
        <circle cx="17" cy="17" r="6" fill="white" fillOpacity="0.95" />
      </svg>
    </div>
  );
}

