// Custom SVG Status Badge Icons
// ─────────────────────────────────


import React from 'react';

export const StatusIcons = {
  Delivered: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="20 6 9 17 4 12"></polyline>
    </svg>
  ),
  Partial: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <line x1="5" y1="12" x2="19" y2="12"></line>
    </svg>
  ),
  Refused: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"></circle>
      <line x1="15" y1="9" x2="9" y2="15"></line>
      <line x1="9" y1="9" x2="15" y2="15"></line>
    </svg>
  ),
  Damaged: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 2L2 7v10c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V7l-10-5z"></path>
      <line x1="12" y1="12" x2="12" y2="16"></line>
      <line x1="12" y1="8" x2="12.01" y2="8"></line>
    </svg>
  ),
  Missing: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"></circle>
      <line x1="8" y1="12" x2="16" y2="12"></line>
    </svg>
  ),
  Returned: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="20 12 8 12 8 2"></polyline>
      <path d="M20 12L17 9"></path>
    </svg>
  ),
  WrongItem: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"></circle>
      <line x1="12" y1="8" x2="12" y2="12"></line>
      <line x1="12" y1="16" x2="12.01" y2="16"></line>
    </svg>
  ),
  NotHome: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
      <polyline points="9 22 9 12 15 12 15 22"></polyline>
      <circle cx="12" cy="8" r="2"></circle>
    </svg>
  ),
  Expired: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"></circle>
      <polyline points="12 6 12 12 16 14"></polyline>
      <line x1="8" y1="12" x2="16" y2="12"></line>
    </svg>
  ),
  Postponed: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="17 1 21 5 21 9"></polyline>
      <path d="M3 11V9a4 4 0 0 1 4-4h12a4 4 0 0 1 4 4v12a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4v-2"></path>
    </svg>
  ),
};

export const BadgeStatusMap: Record<string, { className: string; label: string; icon: () => React.JSX.Element }> = {
  DELIVERED: { className: 'delivered', label: 'Livré', icon: StatusIcons.Delivered },
  PARTIAL: { className: 'partial', label: 'Partielle', icon: StatusIcons.Partial },
  PARTIALLY_DELIVERED: { className: 'partial', label: 'Partielle', icon: StatusIcons.Partial },
  REFUSED: { className: 'refused', label: 'Refusé', icon: StatusIcons.Refused },
  DAMAGED: { className: 'damaged', label: 'Endommagé', icon: StatusIcons.Damaged },
  MISSING: { className: 'missing', label: 'Manquant', icon: StatusIcons.Missing },
  RETURNED: { className: 'returned', label: 'Retourné', icon: StatusIcons.Returned },
  WRONG_ITEM: { className: 'wrongItem', label: 'Mauvais article', icon: StatusIcons.WrongItem },
  NOT_HOME: { className: 'notHome', label: 'Client absent', icon: StatusIcons.NotHome },
  EXPIRED: { className: 'expired', label: 'Périmé', icon: StatusIcons.Expired },
  POSTPONED: { className: 'postponed', label: 'Reporté', icon: StatusIcons.Postponed },
};

