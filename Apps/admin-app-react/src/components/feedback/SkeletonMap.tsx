

import React from 'react';
import { useT } from '@/lib/i18n/LocaleContext';

interface SkeletonMapProps {
  /** Hauteur de la zone carte (défaut : 320px) */
  height?: number;
  className?: string;
}

/**
 * Placeholder animé affiché pendant l'hydratation de Leaflet.
 * Utiliser avec next/dynamic (SSR désactivé) pour les composants carte.
 */
export function SkeletonMap({ height = 320, className = '' }: SkeletonMapProps) {
  const t = useT();
  return (
    <div
      className={`relative flex items-center justify-center bg-slate-100 rounded-xl overflow-hidden animate-pulse ${className}`}
      style={{ height }}
      aria-busy="true"
      aria-label={t.loading.map}
    >
      {/* Grille simulant les tuiles de carte */}
      <div className="absolute inset-0 opacity-30"
        style={{
          backgroundImage: 'linear-gradient(rgba(148,163,184,0.3) 1px, transparent 1px), linear-gradient(90deg, rgba(148,163,184,0.3) 1px, transparent 1px)',
          backgroundSize: '40px 40px',
        }}
      />
      {/* Indicateur central */}
      <div className="relative flex flex-col items-center gap-2 text-slate-400">
        <div className="w-8 h-8 rounded-full bg-slate-300 flex items-center justify-center">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="10" r="3"/>
            <path d="M12 2a8 8 0 0 0-8 8c0 5.25 8 14 8 14s8-8.75 8-14a8 8 0 0 0-8-8Z"/>
          </svg>
        </div>
        <span className="text-xs font-medium text-slate-400">{t.loading.map}</span>
      </div>
    </div>
  );
}

