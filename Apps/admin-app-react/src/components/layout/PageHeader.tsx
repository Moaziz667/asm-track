
import React from 'react';
import { Link } from 'react-router-dom';
import { tw } from '@/lib/typography';

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  /** Si fourni, affiche un lien ← Retour au-dessus du titre */
  backHref?: string;
  /** Libellé du lien retour (défaut: "Retour") */
  backLabel?: string;
  /** Indice clavier affiché en muted à côté du titre */
  keyboardHint?: string;
}

/**
 * En-tête de page standardisé.
 * Toutes les pages doivent utiliser ce composant pour garantir
 * un alignement et une typographie cohérents.
 *
 * Structure :
 *   ← Retour (optionnel)
 *   Titre de la page          [actions]
 *   Sous-titre descriptif
 */
export function PageHeader({
  title,
  subtitle,
  actions,
  backHref,
  backLabel = 'Retour',
  keyboardHint,
}: PageHeaderProps) {
  return (
    <div className="flex flex-col gap-0.5 pb-5 mb-6 border-b border-[var(--border-color)] shrink-0">
      {/* Lien retour */}
      {backHref && (
        <Link
          to={backHref}
          className="inline-flex items-center gap-1.5 text-xs font-semibold text-[var(--text-soft)] hover:text-[var(--text-muted)] transition-colors no-underline mb-2 w-fit"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M19 12H5M12 5l-7 7 7 7"/>
          </svg>
          {backLabel}
        </Link>
      )}

      {/* Ligne titre + actions */}
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-baseline gap-3 flex-wrap">
          <h1 className={tw.pageTitle}>{title}</h1>
          {keyboardHint && (
            <span className={tw.keyHint}>{keyboardHint}</span>
          )}
        </div>
        {actions && (
          <div className="flex items-center gap-2 shrink-0">
            {actions}
          </div>
        )}
      </div>

      {/* Sous-titre */}
      {subtitle && (
        <p className={`${tw.subtitle} mt-0.5`}>{subtitle}</p>
      )}
    </div>
  );
}

