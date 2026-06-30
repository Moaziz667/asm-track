# Product

## Register

product

## Users
Dispatchers, operations admins, and managers running last-mile delivery for **ASM Track** (single-tenant).
Context: a data-dense web console used during the working day under time pressure — triaging routes, dispatching
drivers, watching a live map, resolving failed deliveries/returns, and reconciling ERP (Odoo) sync. They skim,
act fast, and keep many rows + a live map on screen at once. Primary task on any screen is *decide and act*, not browse.

## Product Purpose
The admin web (`Apps/admin-app-react`) is the command surface of ASM Track's delivery platform: import orders from
Odoo, plan routes, dispatch to drivers, track live, capture proof of delivery, and sync outcomes back to Odoo.
Success = a dispatcher can understand the state of the operation at a glance and take the right corrective action
in the fewest moves, with zero ambiguity about delivery/route/RMA/ERP status.

## Brand Personality
**Modern · friendly · approachable** — but expressed for an operations audience, not a consumer app.
"Friendly" means *low cognitive load and humane copy*, not decorative. Warmth shows up in clear language, helpful
empty/error states, and purposeful micro-motion — never in louder color, extra accents, or playful fonts. The
underlying feel stays calm, precise, and trustworthy so dispatchers trust the numbers.

## Anti-references
Avoid the generic AI-SaaS "tells": Inter / system-default fonts, purple→blue gradients, cards nested inside cards,
gray text on colored fills, an icon tile above every heading, multiple competing accent colors, bounce/elastic
easing. Do **not** drift toward a flashy marketing-landing aesthetic or a heavy consumer dashboard (Stripe-clone
gradients, Linear-clone glow). The reference point is a restrained operations console (AWS Cloudscape-class
density and neutrality), not a showcase.

## Design Principles
1. **The data is the design.** Hierarchy, density, and legibility of tables + the live map come first; chrome recedes.
2. **One accent, one type system.** Single action-blue accent and Open Sans / JetBrains Mono are locked — see
   `.ai/ui-system.md` and `src/globals.css`. New work reuses tokens; it never invents colors, badges, or modals.
3. **Warmth through words and motion, not decoration.** Make it human via copy, empty/error states, and purposeful
   transitions — keep surfaces quiet. "Friendly" is never a license to break token restraint.
4. **Never color-only status.** Every status pairs an icon/label with its tone (`StatusBadge` is the one color map).
5. **Decide-and-act in the fewest moves.** Reduce steps to the corrective action; surface the next action, not a menu.

## Accessibility & Inclusion
No formal WCAG conformance target is committed at the product level. In practice the existing token system is
contrast-tuned toward WCAG-AA and status is never conveyed by color alone; keep that bar when adding UI. Respect
`prefers-reduced-motion` for any new animation so motion is an enhancement, not a barrier.
