
import React from 'react';
import styles from '@/styles/map-pins.module.scss';

export function DepotPin() {
  return (
    <div className={styles.depotPin}>
      <svg width="42" height="42" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg">
        <circle cx="21" cy="21" r="21" fill="#111827" />
        <circle cx="21" cy="21" r="19" fill="none" stroke="white" strokeWidth="1.5" strokeOpacity="0.4" />
        <polygon points="21,10 10,19 32,19" fill="white" fillOpacity="0.95" />
        <rect x="12" y="19" width="18" height="11" fill="white" fillOpacity="0.9" rx="1" />
        <rect x="18" y="23" width="6" height="7" fill="#111827" rx="1" />
      </svg>
    </div>
  );
}

