---
name: High-Velocity Utility
colors:
  surface: '#101415'
  surface-dim: '#101415'
  surface-bright: '#363a3b'
  surface-container-lowest: '#0b0f10'
  surface-container-low: '#191c1e'
  surface-container: '#1d2022'
  surface-container-high: '#272a2c'
  surface-container-highest: '#323537'
  on-surface: '#e0e3e5'
  on-surface-variant: '#c5c6d2'
  inverse-surface: '#e0e3e5'
  inverse-on-surface: '#2d3133'
  outline: '#8e909c'
  outline-variant: '#444650'
  surface-tint: '#b3c5ff'
  primary: '#b3c5ff'
  on-primary: '#0d2c6e'
  primary-container: '#002366'
  on-primary-container: '#758dd5'
  inverse-primary: '#435b9f'
  secondary: '#b8c4ff'
  on-secondary: '#002584'
  secondary-container: '#173bab'
  on-secondary-container: '#a0b1ff'
  tertiary: '#4cd6ff'
  on-tertiary: '#003543'
  tertiary-container: '#002d39'
  on-tertiary-container: '#009cc0'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b3c5ff'
  on-primary-fixed: '#00174a'
  on-primary-fixed-variant: '#2a4386'
  secondary-fixed: '#dde1ff'
  secondary-fixed-dim: '#b8c4ff'
  on-secondary-fixed: '#001453'
  on-secondary-fixed-variant: '#173bab'
  tertiary-fixed: '#b7eaff'
  tertiary-fixed-dim: '#4cd6ff'
  on-tertiary-fixed: '#001f28'
  on-tertiary-fixed-variant: '#004e60'
  background: '#101415'
  on-background: '#e0e3e5'
  surface-variant: '#323537'
typography:
  display-lg:
    fontFamily: Space Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Space Grotesk
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: '0'
  body-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: '0'
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 48px
  gutter: 16px
  margin: 24px
---

## Brand & Style

This design system is engineered for high-performance file management and cloud operations. The brand personality is rooted in **technical precision, security, and velocity**. It caters to a professional audience that values speed and clarity over decorative flair.

The visual style is **Corporate/Modern** with a technical edge. It leverages deep architectural shadows and high-contrast typography to ensure information hierarchy is immediate. The aesthetic avoids unnecessary ornamentation, focusing instead on utilitarian efficiency and the "low-level" power implied by the application's core functionality.

## Colors

The palette is anchored by **Deep Cobalt (#002366)**, a color that communicates stability and depth. To maintain the high-velocity aesthetic, the color system utilizes a true-black base (`#000000`) for the lowest container level, ensuring maximum OLED efficiency and dramatic contrast.

- **Primary:** Used for high-action touchpoints and critical branding.
- **Surface Tiers:** Layering moves from `#000000` (canvas) to `#121212` (main UI cards) and `#1E1E1E` (hover states/secondary elements).
- **Accents:** A brighter tertiary cyan is reserved for progress indicators and "active" sync states to draw the eye to moving data.

## Typography

This design system employs a dual-font strategy. **Space Grotesk** is used for headlines to provide a geometric, futuristic feel that aligns with the "Asm" (Assembly) technical naming. Its distinct letterforms emphasize the high-tech nature of the product.

**Inter** is the workhorse for all functional text, body copy, and data tables. It was chosen for its exceptional readability in dark mode and its neutral, systematic appearance. 

- **High Contrast:** All body text must use White (#FFFFFF) or Light Gray (#E0E0E0).
- **Labels:** Small labels use uppercase with increased letter spacing to maintain legibility at high information densities.

## Layout & Spacing

The layout philosophy follows a **systematic fluid grid** based on a 4px baseline. This ensures that every element—from the smallest icon to the largest data container—is aligned to a predictable rhythm.

- **Grid Model:** A 12-column grid is standard for desktop views, transitioning to a single column for mobile.
- **Density:** To achieve a "utility" feel, the system leans toward tighter padding (`sm` and `md`) in data lists, allowing more information to be visible on screen simultaneously.
- **Safe Areas:** Large `xl` spacers are reserved for separating distinct logical sections of the application, such as the sidebar navigation from the main file explorer.

## Elevation & Depth

In the dark mode of this design system, depth is conveyed through **Tonal Layering** rather than heavy shadows. Since the background is pure black, elevation is represented by progressively lighter surface colors.

1.  **Level 0 (Lowest):** #000000 — App background/canvas.
2.  **Level 1 (Surface):** #121212 — Sidebar and main content area backgrounds.
3.  **Level 2 (Raised):** #1E1E1E — Cards, floating action buttons, and modal overlays.
4.  **Stroke Hierarchy:** Subtle 1px outlines using `#3F3F46` (Zinc) are used to define boundaries where tonal shifts are too subtle. 

Shadows, when used, are strictly reserved for top-level modals and are highly diffused (24px blur, 0.4 opacity) with a slight blue tint to maintain brand cohesion.

## Shapes

The shape language of this design system is **Soft (Level 1)**. Elements utilize a 4px (`0.25rem`) corner radius to maintain a professional, slightly sharp appearance. This precision reflects the "AsmDrive" focus on accuracy and system-level performance.

- **Buttons/Inputs:** 4px radius for a crisp, organized look.
- **Large Containers:** 8px (`rounded-lg`) is the maximum radius allowed, used only for primary dashboard widgets or modal windows.
- **Checkboxes:** 2px radius to ensure they appear distinct from circular radio buttons while remaining consistent with the overall sharp aesthetic.

## Components

### Buttons
- **Primary:** Solid Deep Cobalt (#002366) with White text. Bold weight.
- **Secondary:** Outlined with a 1px Zinc border. Text in White.
- **Utility:** Ghost buttons for navigation, using primary color only on hover states.

### Data Lists
The core of the utility experience. Lists should use alternating row tints (Zebra striping) using Surface (#121212) and Surface-Container (#1E1E1E). High-contrast icons help distinguish file types quickly.

### Input Fields
Dark backgrounds (#000000) with a 1px Zinc outline. On focus, the outline shifts to the primary Deep Cobalt with a subtle outer glow of the same color.

### Chips/Status Badges
Status indicators (e.g., "Synced", "Error") should use semi-transparent background tints of the semantic color (e.g., 10% Opacity Green) with high-intensity text colors to ensure visibility against the dark background.

### Cards
Cards are used to group related metrics. They should use the Surface-Container-High (#2A2A2A) color when placed on the main Surface, or include a 1px border for definition.