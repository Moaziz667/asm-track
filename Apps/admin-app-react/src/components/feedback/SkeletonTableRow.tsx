
import React from 'react';

interface SkeletonTableRowProps {
  /** Nombre de colonnes simulées (défaut : 6) */
  columns?: number;
  /** Nombre de lignes à afficher (défaut : 8) */
  count?: number;
  /** Hauteur de chaque ligne en px (défaut : 48) */
  rowHeight?: number;
}

/**
 * Squelette animé pour les tableaux de données.
 * Afficher pendant le chargement initial de la liste.
 */
export function SkeletonTableRow({ columns = 6, count = 8, rowHeight = 48 }: SkeletonTableRowProps) {
  return (
    <div aria-busy="true" aria-label="Chargement des données">
      {Array.from({ length: count }).map((_, rowIdx) => (
        <div
          key={rowIdx}
          className="flex items-center gap-4 px-4 border-b border-slate-100 animate-pulse"
          style={{ height: rowHeight }}
        >
          {/* Checkbox simulée */}
          <div className="w-4 h-4 bg-slate-200 rounded shrink-0" />
          {/* Colonnes */}
          {Array.from({ length: columns }).map((_, colIdx) => {
            const widths = ['w-32', 'w-24', 'w-16', 'w-20', 'w-12', 'w-16'];
            return (
              <div
                key={colIdx}
                className={`h-2.5 bg-slate-${colIdx === 0 ? '200' : '100'} rounded-full ${widths[colIdx % widths.length]}`}
              />
            );
          })}
        </div>
      ))}
    </div>
  );
}

