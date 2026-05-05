---
name: High-Velocity Utility
colors:
  surface: '#faf8ff'
  surface-dim: '#dad9e0'
  surface-bright: '#faf8ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f4f3f9'
  surface-container: '#efedf3'
  surface-container-high: '#e9e7ee'
  surface-container-highest: '#e3e2e8'
  on-surface: '#1a1b20'
  on-surface-variant: '#444650'
  inverse-surface: '#2f3035'
  inverse-on-surface: '#f1f0f6'
  outline: '#757682'
  outline-variant: '#c5c6d2'
  surface-tint: '#435b9f'
  primary: '#00113a'
  on-primary: '#ffffff'
  primary-container: '#002366'
  on-primary-container: '#758dd5'
  inverse-primary: '#b3c5ff'
  secondary: '#5e5e5e'
  on-secondary: '#ffffff'
  secondary-container: '#e2e2e2'
  on-secondary-container: '#646464'
  tertiary: '#121515'
  on-tertiary: '#ffffff'
  tertiary-container: '#272929'
  on-tertiary-container: '#8f9090'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b3c5ff'
  on-primary-fixed: '#00174a'
  on-primary-fixed-variant: '#2a4386'
  secondary-fixed: '#e2e2e2'
  secondary-fixed-dim: '#c6c6c6'
  on-secondary-fixed: '#1b1b1b'
  on-secondary-fixed-variant: '#474747'
  tertiary-fixed: '#e2e2e2'
  tertiary-fixed-dim: '#c6c6c7'
  on-tertiary-fixed: '#1a1c1c'
  on-tertiary-fixed-variant: '#454747'
  background: '#faf8ff'
  on-background: '#1a1b20'
  surface-variant: '#e3e2e8'
typography:
  h1:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  h2:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  h3:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 26px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-caps:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.05em
  status-indicator:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  unit: 8px
  margin-mobile: 16px
  gutter: 12px
  touch-target-min: 48px
  stack-sm: 8px
  stack-md: 16px
  stack-lg: 24px
---

## Brand & Style

This design system is built for the high-stakes, fast-paced environment of logistics. The visual direction is **Corporate / Modern** with a **High-Contrast** edge, prioritizing immediate recognition and error reduction. The aesthetic is utilitarian and authoritative, stripping away unnecessary ornamentation to focus entirely on task completion.

The personality of the design system is dependable and precise. It utilizes sharp edges and clear boundaries to define interaction zones, ensuring that drivers can operate the interface with minimal cognitive load while in transit or at a delivery site.

## Colors

The palette is anchored by Deep Cobalt, representing professional reliability. High-contrast ratios are strictly maintained to ensure legibility under varying lighting conditions, from direct sunlight to nighttime cabin environments.

- **Light Mode:** Uses a stark White (#FFFFFF) base with Deep Cobalt for primary actions and Black for high-priority text.
- **Dark Mode:** Reverses the hierarchy with a Black (#000000) base, using Deep Cobalt for key structural elements and White for readability.
- **Status Tones:** Functional colors for Transit, Picked Up, Complete, and Failed must meet WCAG AA contrast requirements against both black and white backgrounds.

## Typography

The design system utilizes **Inter** for its exceptional legibility and neutral, systematic character. The type hierarchy is oversized compared to standard consumer apps to account for vibration and arm's-length viewing in vehicle mounts.

Key information like addresses and customer names use the `h2` or `body-lg` styles to ensure they are glanceable. Labels use uppercase tracking to differentiate meta-data from actionable content.

## Layout & Spacing

This design system employs a **Fluid Grid** model based on an 8px square baseline. On mobile devices, a 4-column system is used with 16px side margins. 

The layout philosophy prioritizes "Thumb Zones"—placing critical action buttons in the bottom third of the screen. All interactive elements must adhere to a minimum 48px touch target to accommodate drivers wearing gloves or operating the device in a mount.

## Elevation & Depth

To maintain a professional and functional feel, this design system avoids heavy shadows and decorative blurs. Depth is conveyed through **Tonal Layers** and **Bold Borders**.

- **Level 0 (Base):** The primary background color (White or Black).
- **Level 1 (Cards):** Surfaces use a subtle 1px border (#E0E0E0 in light mode, #262626 in dark mode) to define boundaries.
- **Level 2 (Popovers/Modals):** A crisp, high-opacity 4px shadow is used only for temporary overlays to signify they sit above the main workflow.
- **Active State:** Elements being pressed should "depress" visually, removing the border or changing the background to a slightly darker/lighter tint of the primary color.

## Shapes

The shape language is **Soft** (Level 1), utilizing a 4px (0.25rem) base radius. This provides a clean, modern look without feeling overly "friendly" or "playful," which could undermine the professional nature of the tool.

- **Standard Elements:** 4px radius (Buttons, Input Fields).
- **Large Containers:** 8px radius (Route Cards, Map Overlays).
- **Status Tags:** Fully rounded "pill" shapes are reserved exclusively for status indicators to make them distinct from buttons.

## Components

### Buttons
- **Primary:** Solid Deep Cobalt with White text. Full-width on mobile for maximum strike zone.
- **Secondary:** Transparent with a 2px Deep Cobalt border.
- **Destructive/Failed:** Solid Black with White text or a heavy red border.

### Status Indicators
- **Transit:** Cobalt background, white text, "Pulse" icon.
- **Picked Up:** Black background, white text.
- **Complete:** Green background, white check icon.
- **Failed:** Dark orange background, white exclamation icon.
- *Styling:* All indicators use the `status-indicator` type spec and a pill-shaped container.

### Map Elements
- **Navigation Overlay:** A Level 1 card anchored to the top or bottom of the screen with high-contrast directional arrows.
- **Waypoints:** Simple circular markers. The current destination is Deep Cobalt; upcoming stops are Black with White numerals.

### Input Fields
- High-contrast borders (2px) that thicken when focused. 
- Large-scale numeric keyboards are triggered by default for zip codes or package IDs to minimize input errors.

### Lists & Cards
- Logistics cards should group data logically: Time at top-right, ID at top-left, and Address as the primary visual anchor in the center.