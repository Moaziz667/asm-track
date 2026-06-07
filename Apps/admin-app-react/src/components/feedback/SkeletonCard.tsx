
import React from 'react';
import { useT } from '@/lib/LocaleContext';

interface SkeletonCardProps {
  /** Nombre de lignes de contenu simulées (défaut : 3) */
  rows?: number;
  /** Hauteur totale de la carte en px (défaut : 88px) */
  height?: number;
  /** Classe CSS supplémentaire sur le conteneur */
  className?: string;
}

/**
 * Squelette animé pour les cartes KPI, cartes véhicules, cartes chauffeurs.
 * Remplace le contenu réel pendant le chargement des données.
 */
export function SkeletonCard({ rows = 3, height = 88, className = '' }: SkeletonCardProps) {
  const t = useT();
  return (
    <div
      className={`bg-white rounded-xl border border-slate-200 p-4 animate-pulse ${className}`}
      style={{ minHeight: height }}
      aria-busy="true"
      aria-label={t.loading.data}
    >
      {/* Ligne titre */}
      <div className="h-3 bg-slate-200 rounded-full w-2/5 mb-3" />
      {/* Lignes de contenu */}
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          className="h-2.5 bg-slate-100 rounded-full mb-2"
          style={{ width: `${65 + (i % 3) * 12}%` }}
        />
      ))}
    </div>
  );
}

