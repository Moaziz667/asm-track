# ASM Track Admin — Plan d'élévation Enterprise UX (5.4/10 → 9-10/10)

> Basé sur l'audit UX réel (file:line) du 2026-06-11. Objectif : passer d'un "panneau CRUD
> compétent" à une "plateforme logistique desktop-grade" jugée Best-in-Class (Linear / Stripe /
> Shopify Admin / Salesforce). On ne vise pas le perfectionnisme : on cible le ROI par phase.

## Scorecard de départ (audit)

| Catégorie | Départ | Cible |
|---|---|---|
| Design System | 3/10 | 9/10 |
| Enterprise Readiness | 5/10 | 9/10 |
| Information Architecture | 8/10 | 9/10 |
| Workflow Efficiency | 6/10 | 9/10 |
| Table Maturity | 5.5/10 | 9/10 |
| Forms | 6/10 | 9/10 |
| Accessibility | 5/10 | 9/10 |
| Visual Hierarchy | 6/10 | 9/10 |
| Operational Excellence | 5.5/10 | 9/10 |
| Frontend Architecture | 4/10 | 8/10 |
| **GLOBAL** | **5.4** | **9+** |

Déjà fait (avant ce plan) : font-weight hiérarchie, WCAG `--text-soft`, status-bg teintés,
Button typo unifiée, AddButton → wrapper Button, Rma LAZY, lucide consolidé.

---

## PHASE 1 — Discipline & cohérence du design system  (≈ 2-3 sem)
**But : qu'on ne puisse plus distinguer deux écrans faits par deux devs. Score visuel 3→7.**

1.1. **Finir l'élévation (shadows)** — donner de vraies valeurs aux tokens `--shadow-*`
   (actuellement `none`) : échelle douce light / profonde dark. Le système `.card`/`.depth-*`/
   dropdowns les consomme déjà. *(Refait après perte d'un edit précédent.)*
1.2. **Purger le dernier `font-weight: 500 !important`** (globals.css:694) — résidu qui aplatit
   encore certains boutons brand/dark.
1.3. **Primitives canoniques, une seule de chaque, utilisées partout** :
   - `Input`/`FieldInput` → remplacer les ~dizaines de `<input>` bruts (CompaniesPage, AuditLogs,
     FailureReasonsSettings modal, DeliveriesPage pin modal, ZonesPage…).
   - `Select`/`FieldSelect` → idem `<select>` bruts.
   - `Table` shadcn → remplacer les `<table>` bruts + la `DataTable` en grille de `<div>`.
   - `Card`/`SectionCard`, `KPICard` → supprimer les cartes bespoke (KPIDisplay inline du Dashboard,
     SurgicalSettingCard résidus).
   - Fusionner les 2 `DisplaySettingsDropdown` (ui/ vs layout/) en un.
1.4. **Tokeniser les couleurs de statut** : sortir `STATUS_COLOR_MAP`/`ROUTE_PALETTE`/les ~140 hex
   inline (`#4CAF82`, `#C7372F`…) vers CSS vars + faire passer les pages par `<StatusBadge>` au lieu
   de réimplémenter. Supprimer `lib/design-tokens.ts` legacy (conflit avec globals.css).
1.5. **Lint d'enforcement** (le garde-fou qui empêche la régression) : règle ESLint/Tailwind qui
   **interdit** `text-[Npx]`, `rounded-[Npx]`, `#hex`, `style={{…color…}}` hors globals.css.
1.6. **Échelle typo unique** : remplacer les `text-[9px..28px]` arbitraires par l'échelle
   (`text-2xs..2xl` déjà définie) + utiliser `tw.*`/classes sémantiques.

**Livrable Phase 1** : design system respecté à >90%, zéro valeur arbitraire nouvelle (lint),
élévation cohérente. → Design System 7-8/10, Visual Hierarchy 8/10.

---

## PHASE 2 — Orientation-action & excellence opérationnelle  (≈ 3-4 sem)
**But : le dispatcher DÉCIDE et AGIT vite. Score opérationnel 5.5→9. C'est le cœur enterprise.**

2.1. **Hiérarchie d'urgence SLA dominante** — l'urgent CRIE :
   - Lignes/cartes overdue = fond/bord rouge, at-risk = orange, on-track = neutre (les status-bg
     teintés sont la base — les généraliser à Deliveries / Dispatch Desk / Dashboard).
   - Tri par défaut : les à-risque en haut.
2.2. **Supprimer le cap de 8 exceptions** (DashboardPage.tsx:294 `slice(0,8)`) — afficher le vrai
   compte + liste scrollable. Une troncature qui cache 32/40 risques est un mensonge opérationnel.
2.3. **Export** (CSV/Excel "vue visible") sur toutes les tables — un ops manager sort toujours des
   rapports. Bouton dans `PageFilterBar`, côté client.
2.4. **Bulk actions** là où ça a du sens (NB: pin = fallback auto-géocodé, PAS de bulk pin) :
   - Étendre le multi-select Dispatch Desk (existant) ; Returns transitions en masse.
   - Barre d'action contextuelle "N sélectionnés → [action]".
2.5. **Raccourcis clavier** (palette de commandes / actions fréquentes) — signature Linear.
2.6. **Decision-support** : suggestion de réassignation (chauffeur le plus proche via OSRM),
   clusters d'échecs par zone, alertes SLA prédictives. *(Plus avancé — fin de phase.)*
2.7. **Forms pro** : validation inline (erreur sous le champ), états d'erreur visuels (bord rouge),
   remplacer `window.prompt()` RMA par un ConfirmModal+textarea.

**Livrable Phase 2** : app action-oriented, exceptions toujours visibles, bulk + export + raccourcis.
→ Workflow 9/10, Table 8-9/10, Operational 9/10, Forms 8/10.

---

## PHASE 3 — Temps réel & Accessibilité (procurement-ready)  (≈ 2-3 sem)
**But : état toujours exact + WCAG AA. Sans ça, un client enterprise REFUSE d'acheter.**

3.1. **Websocket temps réel** : remplacer le polling 30s (Deliveries/Routes/Dashboard) par push via
   le `RealtimeProvider` existant. Invalidation React Query ciblée. L'état live ne ment jamais.
3.2. **Accessibilité WCAG AA** (non négociable en procurement) :
   - `htmlFor`/`id` sur tous les labels (field.tsx + formulaires).
   - `aria-label`/`title` sur les boutons icône (Zones/Routes/Drivers/…).
   - `<div onClick>` → `role`/`tabIndex`/handlers clavier (ou vrais `<button>`).
   - `DataTable` div-grid → `<table>` sémantique.
   - Audit contraste complet (au-delà de text-soft déjà fait).
   - Navigation 100% clavier + focus visible partout (la base `:focus-visible` existe).

**Livrable Phase 3** : temps réel partout, WCAG AA validé (axe-core), clavier complet.
→ Accessibility 9/10, Operational +1.

---

## PHASE 4 — Maturité d'ingénierie (durabilité)  (continu)
**But : maintenable, testé, robuste. Signal de sérieux pour due diligence technique.**

4.1. **Découper les god-pages** (>1000 lignes) : RouteDetailsPage (1287), DriversPage (1101),
   DashboardPage (1089), DeliveriesPage (1033) → composants (table / modals / hooks / colonnes).
4.2. **Tests** (vitest est configuré, 0 test) : workflows critiques (state machine livraison,
   transitions RMA, dispatch/réassignation, calcul SLA).
4.3. **Error boundaries** par zone de page (le composant existe, l'appliquer) — un crash de page
   ne casse pas l'app.
4.4. **Organisation composants** : ranger les ~35 composants racine dans des dossiers clairs ;
   supprimer doublons/morts.
4.5. **Uniformiser le data-fetching** : tout React Query (retirer les ~21 useEffect+fetch).

**Livrable Phase 4** : code testé, découpé, robuste. → Frontend Architecture 8/10.

---

## Ordre & ROI

```
Phase 1 (cohérence)  ──► première impression "pro" — le plus visible, à faire en premier
Phase 2 (action)     ──► transforme en vrai outil de dispatch — le plus de valeur métier
Phase 3 (RT + a11y)  ──► débloque la vente enterprise (procurement)
Phase 4 (maturité)   ──► durabilité, à mener en continu / parallèle
```

**Recommandation** : Phase 1 puis Phase 2 d'abord (cohérence + action = 80% de la perception
"enterprise"). Phase 3 dès qu'une vente enterprise est en vue. Phase 4 en fond continu.

## Vérification (par phase)
- Build : `npx tsc --noEmit` = 0, `npx vite build` vert, à chaque lot.
- Phase 1 : lint anti-arbitraire = 0 violation ; revue visuelle 2 écrans random indistinguables.
- Phase 2 : un dispatcher repère les SLA à risque en <2s ; bulk/export testés E2E.
- Phase 3 : axe-core 0 violation critique ; nav clavier complète ; état live <1s.
- Phase 4 : couverture tests sur les 4 workflows critiques ; aucune page >500 lignes.
