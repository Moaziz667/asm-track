import { createTheme, MantineColorsTuple, rem } from '@mantine/core';

/**
 * ============================================================================
 * PRO DESKTOP INDUSTRIAL HUD THEME
 * ----------------------------------------------------------------------------
 * Optimized for: high-density technical operations, data-dense command centers,
 * and surgical precision. 
 *
 * Design principles:
 *  - High Contrast: Stark white backgrounds with pure neutral zinc borders.
 *  - Signal Color: Intense Safety Orange for primary actions and alerts.
 *  - Surgical Geometry: Constant 2px radius for an industrial, structural feel.
 *  - Data Hero: Typography optimized for scannability and technical density.
 * ============================================================================
 */

// --- Brand: Warm Ember Orange (#F08734) -------------------------------------
const safetyOrange: MantineColorsTuple = [
  '#fef5ed',
  '#fde8d5',
  '#fbd1ab',
  '#f9b981',
  '#f5a05a',
  '#F08734', // Primary 500
  '#d9782e',
  '#c16a28',
  '#a95b22',
  '#8a4a1c',
];

// --- Neutral: High-Contrast Zinc Scale ---------------------------------------
const zinc: MantineColorsTuple = [
  '#fafafa',
  '#f4f4f5',
  '#e4e4e7',
  '#d4d4d8',
  '#a1a1aa',
  '#71717a',
  '#52525b',
  '#3f3f46',
  '#27272a',
  '#18181b', // 900
];

// --- Semantic Industrial Palettes ------------------------------------------
const success: MantineColorsTuple = [
  '#f0fdf4', '#dcfce7', '#bbf7d0', '#86efac', '#4ade80',
  '#22c55e', '#16a34a', '#15803d', '#166534', '#14532d',
];

const warning: MantineColorsTuple = [
  '#fffbeb', '#fef3c7', '#fde68a', '#fcd34d', '#fbbf24',
  '#f59e0b', '#d97706', '#b45309', '#92400e', '#78350f',
];

const danger: MantineColorsTuple = [
  '#fef2f2', '#fee2e2', '#fecaca', '#fca5a5', '#f87171',
  '#ef4444', '#dc2626', '#b91c1c', '#991b1b', '#7f1d1d',
];

export const theme = createTheme({
  primaryColor: 'brand',
  primaryShade: { light: 5, dark: 7 },
  colors: {
    brand: safetyOrange,
    zinc,
    success,
    warning,
    danger,
  },

  // --------------------------------------------------------------------------
  // TYPOGRAPHY — Technical & Clean
  // --------------------------------------------------------------------------
  fontFamily: '"IBM Plex Sans", system-ui, -apple-system, sans-serif',
  fontFamilyMonospace: '"JetBrains Mono", "IBM Plex Mono", ui-monospace, monospace',
  fontSmoothing: true,

  fontSizes: {
    xs: rem(11),
    sm: rem(12),
    md: rem(13),
    lg: rem(14),
    xl: rem(16),
  },

  lineHeights: {
    xs: '1.25',
    sm: '1.3',
    md: '1.4',
    lg: '1.45',
    xl: '1.5',
  },

  headings: {
    fontWeight: '600',
    textWrap: 'balance',
    sizes: {
      h1: { fontSize: rem(20), lineHeight: '1.1', fontWeight: '700' },
      h2: { fontSize: rem(16), lineHeight: '1.2', fontWeight: '700' },
      h3: { fontSize: rem(14), lineHeight: '1.2', fontWeight: '600' },
      h4: { fontSize: rem(13), lineHeight: '1.3', fontWeight: '600' },
      h5: { fontSize: rem(12), lineHeight: '1.3', fontWeight: '600' },
      h6: { fontSize: rem(11), lineHeight: '1.3', fontWeight: '700' },
    },
  },

  // --------------------------------------------------------------------------
  // GEOMETRY — Surgical Precision
  // --------------------------------------------------------------------------
  defaultRadius: rem(2),
  radius: {
    xs: rem(0),
    sm: rem(2),
    md: rem(2),
    lg: rem(4),
    xl: rem(4),
  },

  spacing: {
    xxs: rem(2),
    xs: rem(4),
    sm: rem(8),
    md: rem(12),
    lg: rem(16),
    xl: rem(20),
    xxl: rem(24),
  },

  // No soft shadows. Clean 1px border is the container.
  shadows: {
    xs: '0 1px 0 rgba(0, 0, 0, 0.05)',
    sm: '0 1px 2px rgba(0, 0, 0, 0.1)',
    md: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
    lg: 'none',
    xl: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
  },

  cursorType: 'default',
  focusRing: 'auto',

  // --------------------------------------------------------------------------
  // COMPONENTS — Industry-standard ergonomics
  // --------------------------------------------------------------------------
  components: {
    Button: {
      defaultProps: { size: 'xs', radius: 'sm' },
      styles: {
        root: {
          fontWeight: 600,
          letterSpacing: '0.02em',
          textTransform: 'uppercase',
          height: rem(28),
        },
      },
    },

    ActionIcon: {
      defaultProps: { size: 'md', radius: 'sm', variant: 'outline', color: 'zinc.3' },
    },

    TextInput: {
      defaultProps: { size: 'xs', radius: 'sm' },
      styles: {
        input: { minHeight: rem(30), fontWeight: 500 },
        label: { marginBottom: rem(4), fontSize: rem(10), fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em' }
      },
    },

    Select: {
      defaultProps: { size: 'xs', radius: 'sm', checkIconPosition: 'right' },
      styles: {
        input: { minHeight: rem(30), fontWeight: 500 },
        label: { marginBottom: rem(4), fontSize: rem(10), fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em' }
      },
    },

    Card: {
      defaultProps: { radius: 'sm', withBorder: true, padding: 'md' },
    },

    Paper: {
      defaultProps: { radius: 'sm', withBorder: true },
    },

    Badge: {
      defaultProps: { radius: 'sm', variant: 'filled', size: 'sm' },
      styles: {
        root: { textTransform: 'uppercase', fontWeight: 700, fontSize: rem(9), letterSpacing: '0.05em', height: rem(18) },
      },
    },

    Table: {
      defaultProps: { verticalSpacing: 6, horizontalSpacing: 'md', withRowBorders: true, highlightOnHover: true },
      styles: {
        th: { fontSize: rem(10), fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em' },
        td: { fontSize: rem(12), fontWeight: 500 },
      },
    },

    Modal: {
      defaultProps: {
        radius: 'sm',
        transitionProps: { transition: 'fade', duration: 200 },
        overlayProps: { backgroundOpacity: 0.35, blur: 3 },
      },
      styles: {
        header: { padding: '12px 20px' },
        title: { fontWeight: 700, fontSize: rem(13), textTransform: 'uppercase', letterSpacing: '0.05em' },
        body: { padding: rem(20) },
      },
    },

    AppShell: {
    },

    Pagination: {
      defaultProps: { size: 'xs', radius: 'sm', color: 'zinc.9' },
    },
  },

  other: {
    brandOrange: '#F08734',
    brandGray: '#18181B',
    surface: {
      canvas: '#f4f4f5',
      base: '#ffffff',
      subtle: '#fafafa',
      sunken: '#e4e4e7',
    },
    border: {
      default: '#e4e4e7',
      strong: '#d4d4d8',
      active: '#F08734',
    },
  },
});
