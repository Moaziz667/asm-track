/**
 * ASM Track — Système typographique
 * Classes Tailwind prédéfinies à utiliser sur TOUTES les pages.
 * Aucune classe text-* / font-* libre n'est autorisée en dehors de ce fichier.
 */

export const tw = {
  // Titres
  pageTitle:    'text-2xl font-bold text-[var(--text-strong)] leading-tight',
  sectionTitle: 'text-base font-semibold text-[var(--text-strong)]',
  cardTitle:    'text-sm font-semibold text-[var(--text-strong)]',

  // Descriptions
  subtitle:  'text-sm text-[var(--text-muted)]',
  body:      'text-sm text-[var(--text)]',
  bodyMuted: 'text-sm text-[var(--text-muted)]',

  // Labels (section headers, table headers)
  label:     'text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]',
  labelSm:   'text-[10px] font-bold uppercase tracking-wider text-[var(--text-soft)]',

  // Données
  dataValue: 'text-sm font-semibold text-[var(--text-strong)] tabular-nums',
  dataSub:   'text-xs text-[var(--text-muted)]',

  // Badges
  badge:     'text-[10px] font-bold uppercase tracking-wide',

  // Monospace (IDs, codes, montants)
  mono:      'font-mono text-[12px] text-[var(--text)]',
  monoSm:    'font-mono text-[11px] text-[var(--text-muted)]',

  // Légende
  caption:   'text-xs text-[var(--text-soft)]',

  // Hint clavier
  keyHint:   'text-[10px] font-mono text-[var(--text-muted)] bg-[var(--surface-3)] border border-[var(--border-color)] rounded px-1.5 py-0.5',
} as const;
