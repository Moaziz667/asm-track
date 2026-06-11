/**
 * ASM Track — Design Tokens
 * Single source of truth for colors, typography, spacing.
 * Import this everywhere instead of hardcoding hex values.
 */

// NOTE: these point at the live CSS theme tokens (globals.css) instead of a divergent
// hardcoded palette, so JS-styled components stay in sync with the rest of the app
// (single source of truth = the CSS variables). Status palettes below stay literal —
// they are self-contained badge colors, not theme colors.
export const colors = {
  // ── Backgrounds ─────────────────────────────────────────────────────
  bg: 'var(--app-bg)',
  surface: 'var(--surface)',
  surfaceHover: 'var(--hover-bg)',
  surfaceSelected: 'var(--brand-soft)',

  // ── Borders ─────────────────────────────────────────────────────────
  border: 'var(--border)',
  borderEmphasis: 'var(--border-strong)',

  // ── Text ────────────────────────────────────────────────────────────
  textPrimary: 'var(--text-primary)',
  textSecondary: 'var(--text-secondary)',
  textMuted: 'var(--text-muted)',

  // ── Brand ───────────────────────────────────────────────────────────
  brand: 'var(--brand)',

  // ── Actions ─────────────────────────────────────────────────────────
  primary: 'var(--brand)',
  primaryHover: 'var(--brand-hover)',
  primaryBg: 'var(--brand-soft)',

  danger: 'var(--danger)',
  dangerBg: 'var(--danger-bg)',
  dangerBorder: 'var(--danger)',
  dangerHover: 'var(--danger-bg)',

  warning: 'var(--warning)',
  warningBg: 'var(--warning-bg)',

  success: 'var(--success)',
  successBg: 'var(--success-bg)',

  // ── Navigation ──────────────────────────────────────────────────────
  nav: '#1E293B',
  navBorder: 'rgba(255,255,255,0.08)',

  // ── Shadows ─────────────────────────────────────────────────────────
  shadow: '0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04)',
  shadowMd: '0 4px 6px rgba(0,0,0,0.07), 0 2px 4px rgba(0,0,0,0.05)',
  shadowLg: '0 10px 15px rgba(0,0,0,0.07), 0 4px 6px rgba(0,0,0,0.05)',

  // ── Status (badges ONLY — use nowhere else) ──────────────────────────
  status: {
    DRAFT: { text: '#64748B', bg: '#F1F5F9', border: '#E2E8F0', dot: '#94A3B8' },
    VALIDATED: { text: '#2563EB', bg: '#EFF6FF', border: '#BFDBFE', dot: '#3B82F6' },
    IN_PROGRESS: { text: '#D97706', bg: '#FFFBEB', border: '#FDE68A', dot: '#F59E0B' },
    CLOSED: { text: '#059669', bg: '#F0FDF4', border: '#A7F3D0', dot: '#10B981' },
    CANCELLED: { text: '#6B7280', bg: '#F9FAFB', border: '#E5E7EB', dot: '#9CA3AF' },
    FAILED: { text: '#DC2626', bg: '#FEF2F2', border: '#FECACA', dot: '#EF4444' },
    SLA_BREACH: { text: '#DC2626', bg: '#FEF2F2', border: '#FECACA', dot: '#EF4444' },
    // Delivery statuses
    UNSCHEDULED: { text: '#D97706', bg: '#FFFBEB', border: '#FDE68A', dot: '#F59E0B' },
    SCHEDULED: { text: '#2563EB', bg: '#EFF6FF', border: '#BFDBFE', dot: '#3B82F6' },
    PICKED_UP: { text: '#0891B2', bg: '#ECFEFF', border: '#A5F3FC', dot: '#06B6D4' },
    IN_TRANSIT: { text: '#EA580C', bg: '#FFF7ED', border: '#FED7AA', dot: '#F97316' },
    DELIVERED: { text: '#059669', bg: '#F0FDF4', border: '#A7F3D0', dot: '#10B981' },
    COMPLETED: { text: '#059669', bg: '#F0FDF4', border: '#A7F3D0', dot: '#10B981' },
    PARTIAL: { text: '#7C3AED', bg: '#F5F3FF', border: '#DDD6FE', dot: '#8B5CF6' },
    PARTIALLY_DELIVERED: { text: '#7C3AED', bg: '#F5F3FF', border: '#DDD6FE', dot: '#8B5CF6' },
    // Stop removal statuses
    REMOVED_REPLANNED: { text: '#92400E', bg: '#FFFBEB', border: '#FDE68A', dot: '#D97706' },
    REMOVED_CANCELLED: { text: '#991B1B', bg: '#FEF2F2', border: '#FECACA', dot: '#DC2626' },
    FAILED_ATTEMPT: { text: '#9A3412', bg: '#FFF7ED', border: '#FED7AA', dot: '#EA580C' },
  } as Record<string, { text: string; bg: string; border: string; dot: string }>,
} as const;

export const typography = {
  // ── Sizes ────────────────────────────────────────────────────────────
  pageTitle: { fontSize: 18, fontWeight: 800 },
  sectionHeader: { fontSize: 11, fontWeight: 800, textTransform: 'uppercase' as const, letterSpacing: '0.08em' },
  tableHeader: { fontSize: 10, fontWeight: 700, textTransform: 'uppercase' as const },
  tableData: { fontSize: 12, fontWeight: 600 },
  tableDataSub: { fontSize: 11, fontWeight: 500 },
  badge: { fontSize: 9, fontWeight: 700, textTransform: 'uppercase' as const, letterSpacing: '0.06em' },
  button: { fontSize: 12, fontWeight: 700 },
  mono: { fontFamily: "'JetBrains Mono', 'Fira Code', 'Menlo', monospace" },
} as const;

export const spacing = {
  pagePadding: 32,
  sectionGap: 24,
  cardPadding: 20,
  rowHeight: 48,
  rowHeightSm: 36,
  rowHeightLg: 56,
  cellPadding: '0 12px',
  buttonHeight: 34,
  buttonHeightSm: 28,
  radius: {
    sm: 4,
    md: 6,
    lg: 10,
    xl: 14,
  },
} as const;

// ── Shared style builders ─────────────────────────────────────────────

export const DRIVER_STATUS_COLOR = {
  ONLINE:   { dot: '#10B981', label: 'En service',   bg: '#F0FDF4', text: '#059669' },
  ON_BREAK: { dot: '#F59E0B', label: 'En pause',     bg: '#FFFBEB', text: '#D97706' },
  OFFLINE:  { dot: '#9CA3AF', label: 'Hors service', bg: '#F4F4F5', text: '#6B7280' },
} as const satisfies Record<string, { dot: string; label: string; bg: string; text: string }>;

export type DriverOnlineStatus = keyof typeof DRIVER_STATUS_COLOR;

// Pastel account-status palette (PENDING_SETUP / ACTIVE / SUSPENDED).
// Each entry is a self-contained pill: soft background, dark text, dot, border.
export const DRIVER_ACCOUNT_STATUS_COLOR = {
  PENDING_SETUP: { dot: '#D97706', bg: '#FEF3C7', text: '#92400E', border: '#FDE68A', label: 'En attente' },
  ACTIVE:        { dot: '#059669', bg: '#D1FAE5', text: '#065F46', border: '#A7F3D0', label: 'Actif' },
  SUSPENDED:     { dot: '#6B7280', bg: '#F3F4F6', text: '#374151', border: '#E5E7EB', label: 'Suspendu' },
} as const satisfies Record<string, { dot: string; bg: string; text: string; border: string; label: string }>;

export type DriverAccountStatusKey = keyof typeof DRIVER_ACCOUNT_STATUS_COLOR;

export const btn = {
  primary: {
    height: spacing.buttonHeight,
    padding: '0 14px',
    borderRadius: spacing.radius.md,
    border: 'none',
    background: colors.primary,
    color: '#fff',
    fontSize: typography.button.fontSize,
    fontWeight: typography.button.fontWeight,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    transition: 'background 0.15s',
    whiteSpace: 'nowrap' as const,
  },
  secondary: {
    height: spacing.buttonHeight,
    padding: '0 14px',
    borderRadius: spacing.radius.md,
    border: `1px solid ${colors.border}`,
    background: colors.surface,
    color: colors.textPrimary,
    fontSize: typography.button.fontSize,
    fontWeight: typography.button.fontWeight,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    transition: 'background 0.15s',
    whiteSpace: 'nowrap' as const,
  },
  danger: {
    height: spacing.buttonHeight,
    padding: '0 14px',
    borderRadius: spacing.radius.md,
    border: `1px solid ${colors.dangerBorder}`,
    background: colors.dangerBg,
    color: colors.danger,
    fontSize: typography.button.fontSize,
    fontWeight: typography.button.fontWeight,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    transition: 'background 0.15s',
    whiteSpace: 'nowrap' as const,
  },
  ghost: {
    height: spacing.buttonHeightSm,
    padding: '0 8px',
    borderRadius: spacing.radius.sm,
    border: 'none',
    background: 'transparent',
    color: colors.textSecondary,
    fontSize: 12,
    fontWeight: 600,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    gap: 4,
    transition: 'all 0.15s',
    whiteSpace: 'nowrap' as const,
  },
} as const;
