# Décisions d'architecture (ADR)

Ce dossier consigne les décisions structurantes d'ASM Track : celles qui seraient coûteuses à
défaire, et dont la raison ne se lit pas dans le code.

Un ADR ne décrit pas *ce que* fait le système — le code le fait mieux. Il répond à la seule question
que le code ne peut pas documenter : **pourquoi cette solution, et qu'a-t-on refusé ?** Six mois plus
tard, c'est cette réponse qui manque, jamais l'autre.

| # | Décision | Statut |
|---|---|---|
| [001](001-multi-tenant-par-schema.md) | Isolation des clients par schéma PostgreSQL | Acceptée |
| [002](002-politique-rbac-unique.md) | Une politique d'autorisation déclarative, unique et *fail-closed* | Acceptée |
| [003](003-ports-adapters-erp.md) | Intégration ERP par Ports & Adapters | Acceptée |
| [004](004-outbox-transactionnel.md) | Synchronisation ERP par outbox transactionnel | Acceptée |

## Format

Chaque fiche suit la même structure : le **contexte** (la contrainte réelle), la **décision**, les
**alternatives écartées** avec leur motif, et les **conséquences** — y compris celles qui coûtent.
Un ADR qui n'énonce que des avantages n'a pas été écrit honnêtement.
