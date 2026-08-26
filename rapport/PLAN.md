# Rapport PFE — ASM Track — squelette de référence

> Document de travail. C'est **la** source du plan : toute décision de structure se prend ici avant
> d'être écrite en LaTeX. Établi le 2026-08-18.

## Décisions arrêtées

| Sujet | Décision |
|---|---|
| Approche | **Scrum**, ossature par sprints, avec deux renforts : un chapitre Architecture séparé et un chapitre Validation transversal |
| Gabarit | Celui du mémoire ISIMS Sfax 2026 (« InvisiThreat ») — 8 sections identiques par sprint |
| Nombre de sprints | 5, plus un « Sprint 0 » d'initialisation |
| Cible | **140 pages** (12 liminaires + 118 corps + 5 biblio/annexes + 5 de marge) |
| Langue | Français |
| Bibliographie | biblatex/biber — bibliographie **et** webographie, pas seulement des liens |
| Ancien rapport | `MDS/report/` est **abandonné** (tentative périmée : AuthServer inexistant, pas de multi-tenance). Ne pas s'en inspirer. |

## Guide de rédaction officiel de l'institut (contraignant)

Consignes reçues le 2026-08-18, appliquées dans `preamble.tex` et `chapters/resume.tex` :

| Règle | Exigence | Où c'est fait |
|---|---|---|
| 1. Typographie | Times New Roman, corps 12, interligne 1,5 ; emphase en gras/italique | `\setmainfont{Times New Roman}` (XeLaTeX) ; `\onehalfspacing` |
| 2. Modèles | Page de garde, page de résumés, titres conformes aux modèles du guide | `chapters/couverture.tex`, `chapters/resume.tex` |
| 3. Format | Texte justifié ; largeur de texte 160 mm, hauteur 250 mm | `\usepackage[a4paper,textwidth=160mm,textheight=250mm]{geometry}` — geometry centre le bloc automatiquement |
| 4. Bibliographie | Times corps 10 ; étiquette `[ROU 99]` = 3 lettres du nom + 2 chiffres de l'année, suivi du nom complet, titre, éditeur, année (ou revue + volume + pages) | `style=alphabetic` (biblatex) + `\bibfont=\small` — approche le format demandé mais **pas encore vérifié à l'identique** sur un vrai `.bib`, voir « Reste à décider » |
| 5. Impression | Mémoire photocopié en recto-verso | note pour l'impression finale, sans effet sur le `.tex` |
| 6. Couverture physique | Carton blanc ; page de garde à gauche, résumé (≤ 10 lignes par langue FR/AR/EN) + mots clés à droite | `chapters/resume.tex` — résumés raccourcis à ~10 lignes chacun en corps 12 normal (pas de police réduite) |

> Piège déjà rencontré : forcer `\footnotesize` pour faire tenir un résumé trop long viole la règle 1
> (« corps 12 » pour l'ensemble des textes). La bonne réponse est de **raccourcir le texte** à la
> limite de 10 lignes que le guide fixe explicitement, pas de réduire la police.

### Ce qu'on reprend du mémoire modèle
- sommaire (mini-table des matières) en tête de chaque chapitre ;
- phrase d'annonce avant **chaque** figure, jamais de capture nue ;
- tableaux de test normés `TC-Sx-NN` : Cas de test / Description / Précondition / Scénario / Postcondition ;
- chapitre « Sprint 0 » qui absorbe acteurs, besoins, UC global, backlog et étude technique ;
- section « Concepts et mécanismes utilisés » propre à chaque sprint (la théorie mobilisée par ce sprint-là).

### Ce qu'on fait différemment
1. **Architecture en chapitre autonome** (ch. 3). Le modèle la range en sous-section ; ASM a 7 services,
   une multi-tenance par schéma et 45 routes de passerelle — ça ne tient pas dans une sous-section.
2. **Tests doublés.** Les tableaux TC manuels *plus* une sous-section « tests automatisés » chiffrée par
   sprint, puis un chapitre 9 pour la pyramide complète et la CI. C'est le principal écart de niveau
   avec le mémoire modèle, qui n'a que des scénarios manuels.
3. **Figures refaites.** Les 94 SVG de `diagrams/svg/` ont été produits pour la documentation MkDocs :
   ils ne seront **pas** repris tels quels. Les figures du rapport sont à créer spécifiquement.
4. **Logos conservés.** Les logos d'outils et de technologies sont attendus dans ce type de mémoire
   (section « environnement de travail ») — on les met.

---

## Plan et budget de pages

| # | Chapitre | Pages |
|---|---|---|
| — | Pages liminaires | 12 |
| — | Introduction générale | 3 |
| 1 | Cadre général du projet | 10 |
| 2 | Sprint 0 — Initialisation, besoins et étude technique | 17 |
| 3 | Architecture et conception générale | 13 |
| 4 | Sprint 1 — Socle d'identité, d'autorisation et référentiels | 15 |
| 5 | Sprint 2 — Intégration ERP agnostique | 16 |
| 6 | Sprint 3 — Planification des tournées et suivi temps réel | 13 |
| 7 | Sprint 4 — Application mobile chauffeur et mode hors ligne | 13 |
| 8 | Sprint 5 — Exploitation, résilience et assistant interne | 11 |
| 9 | Validation globale, qualité et déploiement | 9 |
| — | Conclusion générale et perspectives | 3 |
| — | Bibliographie et annexes | 5 |

Le chapitre 5 est le plus long **volontairement** : l'intégration ERP agnostique est l'apport le plus
original du projet.

---

## Gabarit d'un chapitre de sprint (ch. 4 à 8)

```
x.1  Introduction
x.2  Objectifs du sprint et planification des tâches
     x.2.1  Objectifs attendus
     x.2.2  Sprint backlog
x.3  Concepts et mécanismes utilisés
x.4  Analyse et spécification des besoins
     x.4.1  Diagramme de cas d'utilisation du sprint
     x.4.2  Description détaillée des cas d'utilisation
x.5  Modélisation conceptuelle
     x.5.1  Diagramme de classes
     x.5.2  Diagrammes de séquence
x.6  Réalisation
x.7  Tests et validation
     x.7.1  Scénarios de test (tableaux TC-Sx-NN)
     x.7.2  Tests automatisés          ← ajout ASM
x.8  Conclusion
```

---

## Détail des chapitres

### Pages liminaires (12 p)
Couverture · dédicace · remerciements · résumé FR / abstract EN · table des matières · liste des
figures · liste des tableaux · liste des abréviations.

### Introduction générale (3 p)
Contexte de la livraison du dernier kilomètre · problématique en une phrase · contributions
annoncées · plan du mémoire.

### Chapitre 1 — Cadre général du projet (10 p)
1. Introduction
2. Présentation de l'organisme d'accueil
3. Présentation du projet — contexte, problématique, objectifs, périmètre
4. Étude et critique de l'existant — Onfleet, Circuit, Track-POD, Odoo Inventory seul ; synthèse
   comparative ; solution proposée
5. Méthodologie de développement — Scrum, rôles, artefacts, outil de suivi
6. Conclusion

> À ne pas oublier : justifier explicitement le choix **SaaS multi-clients**, sinon le chapitre 4
> arrive sans motif.

### Chapitre 2 — Sprint 0 : initialisation, besoins et étude technique (17 p)
1. Introduction
2. Identification des acteurs — administrateur, dispatcheur, manager, chauffeur, destinataire, ERP
   (acteur secondaire)
3. Analyse des besoins — fonctionnels par domaine ; non fonctionnels (multi-tenance, hors-ligne,
   temps réel, traçabilité, agnosticité ERP, sécurité)
4. Diagramme de cas d'utilisation global
5. Diagramme de classes global
6. Gestion du projet avec Scrum — product backlog, planification des 5 sprints
7. Étude technique — environnement matériel, environnement logiciel, technologies et outils (logos)
8. Conclusion

### Chapitre 3 — Architecture et conception générale (13 p)
1. Introduction — ½ p
2. Microservices ou monolithe — décision argumentée, et pourquoi ce découpage par métier — 1,5 p
3. Vue d'ensemble — 6 services, passerelle, Keycloak, RabbitMQ, MinIO, OSRM — 2,5 p
   → figure `Architecture/architecture-globale.pdf`
4. Du modèle de classes aux bases — 2 p → figure `Architecture/donnees-par-service.png`
   Répartition des entités du chapitre 2 sur quatre bases, ce que deviennent les relations qui
   traversent une frontière de service (référence par identifiant, aucune jointure), Flyway comme
   mécanisme de version (159 migrations, dont 139 pour DeliveryMicroservice).
   **Pas de nouveau diagramme de classes** : celui du chapitre 2 fait foi, le répéter serait
   compté comme redondance.
5. **Multi-tenance par schéma** — 3 p — `company_<32hex>`, routage Hibernate, Keycloak
   Organizations, les trois rejets explicites, et pourquoi ni `tenant_id` ni une base par client
   → figure `Architecture/routage-multitenant.jpg`
6. Communication entre services — 2 p — REST synchrone contre événements, files de rebut,
   relance automatique → figure `Architecture/topologie-messagerie.png`
7. Déploiement Docker — 1 p — 17 conteneurs, ordre de démarrage, volumes (sans figure)
8. Conclusion — ½ p

> Les choix technologiques restent au chapitre 2 (« Étude technique ») : les traiter deux fois
> ferait doublon. Le chapitre 3 justifie des choix d'*architecture*, pas d'outils.

### Chapitre 4 — Sprint 1 : socle d'identité, d'autorisation et référentiels (15 p)
Concepts : OAuth2 / OIDC, JWT, JWKS, RBAC, isolation par schéma.
Contenu : Keycloak (realm `asm`, Organizations) · politique RBAC unique — 18 permissions, 3 rôles
composites, 46 règles — répliquée dans 5 services · passerelle API, 45 routes, validation JWKS · thème
Keycloakify · isolation des données démontrée par test · référentiels d'exploitation (chauffeurs,
véhicules, zones), chacun derrière l'authentification.

### Chapitre 5 — Sprint 2 : intégration ERP agnostique (16 p)
Concepts : ports & adaptateurs, contrat canonique, outbox transactionnel, idempotence, JSON-RPC vs REST.
Contenu : les quatre ports (`ErpSyncPort`, `ErpLookupPort`, `ErpOrderPort`, `ErpChangePort`) ·
contrat canonique de 27 champs · mapping guidé et **système de types** (SAFE / LOSSY / UNSUPPORTED,
5 types canoniques, 10 types source) · Odoo JSON-RPC et ERPNext REST · outbox transactionnel ·
idempotence · synchronisation des dépôts depuis les entrepôts du progiciel · tests contre
**Odoo 16 et 19 simultanément** en CI.

### Chapitre 6 — Sprint 3 : planification des tournées et suivi temps réel (13 p)
Concepts : problème de tournées, OSRM, machines à états, WebSocket/STOMP, SLA.
Contenu : construction et optimisation de tournée · dispatch et chauffeur le plus proche · machines
à états (livraison, tournée, arrêt) · moteur SLA · temps réel via relais STOMP RabbitMQ · suivi
public destinataire · cycle du retour — demande du destinataire, approbation, remise en stock.

### Chapitre 7 — Sprint 4 : application mobile chauffeur et mode hors ligne (13 p)
Concepts : architecture offline-first, file de rejeu, idempotence client, horloge d'action.
Contenu : Flutter / Riverpod / Hive · preuve de livraison — photo du bon de livraison signé par le destinataire + photo du colis remis, dans MinIO (le document signé fait foi, pas un tracé sur écran) · file d'attente
hors ligne, projection optimiste, `X-Client-Timestamp` · transfert de garde · encaissement COD et
remise de caisse · collecte de retour (RMA) — le mouvement inverse emprunte la même tournée, le même
écran et la même preuve, sans bon de livraison à photographier · exécution de la tournée (démarrage,
enlèvement au dépôt, transit, arrivée) · calendrier des tournées planifiées · centre de
synchronisation (relancer ou abandonner une écriture morte).

### Chapitre 8 — Sprint 5 : exploitation, résilience et assistant interne (11 p)
Concepts : observabilité, file de rebut, rejeu, RAG.
Contenu : console Santé Système · **les trois modes de panne** (adaptateur, ERP, courtier) et le
comportement de chacun · rejeu et DLQ · journal d'audit · analytique et rapports PDF · assistant
interne RAG (pgvector).

### Chapitre 9 — Validation globale, qualité et déploiement (9 p)
1. Pyramide de tests — unitaires, intégration sur PostgreSQL réel, acceptance, ERP bi-version, front
2. Pipeline GitLab CI — 5 étages (build, unit-test, security, integration-test, package), 14 jobs
3. Analyse de sécurité — Trivy
4. Déploiement Docker Compose
5. **Limites connues assumées** — aucun test de charge conduit, pas de serveur Prometheus déployé
6. Conclusion

### Conclusion générale et perspectives (3 p)
### Bibliographie et annexes (5 p)

---

## Chiffres du projet (mesurés le 2026-08-18, à citer tels quels)

| Élément | Valeur |
|---|---|
| DeliveryMicroservice | 341 classes, 38 472 LOC |
| ErpAdapterService | 121 classes, 13 815 LOC |
| DriverService / AppBackend / AssistantService | 73 / 46 / 54 classes |
| ApiGateway + asm-tenant-core | 6 + 10 classes |
| Admin React | 301 fichiers, 65 018 LOC, 27 pages |
| App chauffeur Flutter | 68 fichiers, 26 665 LOC |
| API | 294 endpoints, 58 contrôleurs |
| Routes passerelle | 45 |
| Migrations Flyway (Delivery) | 49 |
| Tests backend | 549 `@Test` |
| CI | 5 étages, 14 jobs |
| RBAC | 18 permissions, 3 rôles composites |
| ADR rédigés | 4 |

> `context.md` à la racine affirme que le système n'est **pas** multi-tenant : c'est faux et périmé.
> La source fiable est `docs/` et le code.

---

## Figures à produire

Les 94 SVG de `diagrams/svg/` sont destinés à MkDocs et **ne seront pas réutilisés**. Les figures du
rapport sont à créer spécifiquement, en visant environ **55 figures** dans le corps (≈ une toutes les
deux pages ; au-delà le mémoire se lit comme un catalogue).

Par chapitre, le minimum attendu :

| Chapitre | Figures |
|---|---|
| 1 | logo de l'organisme, cycle Scrum, tableau comparatif de l'existant |
| 2 | cas d'utilisation global, classes global, backlog, planification des sprints, **logos des outils et technologies** |
| 3 | architecture globale, architecture en couches, multi-tenance, modèle de données, déploiement Docker |
| 4 à 8 | par sprint : cas d'utilisation, classes, 2 à 3 séquences, 3 à 5 captures d'écran |
| 9 | pyramide de tests, pipeline CI |

---

## Reste à décider
- [ ] Titres courts ou longs pour les chapitres 4 à 8 dans la table des matières
- [ ] Établissement, encadrants et intitulé exact pour la page de couverture
- [ ] Ordre de rédaction des chapitres
