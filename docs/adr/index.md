# Décisions d'architecture (ADR)

Ce dossier consigne les décisions structurantes d'ASM Track : celles qui seraient coûteuses à
défaire, et dont la raison ne se lit pas dans le code.

!!! quote "Ce qu'un ADR est, et n'est pas"
    Un ADR ne décrit pas **ce que** fait le système — le code le fait mieux. Il répond à la seule
    question que le code ne peut pas documenter : **pourquoi cette solution, et qu'a-t-on refusé ?**

    Six mois plus tard, c'est cette réponse qui manque, jamais l'autre.

---

## Les décisions

| # | Décision | Ce qu'elle a coûté |
|---|---|---|
| [001](001-multi-tenant-par-schema.md) | Isolation des clients par schéma PostgreSQL | migrations à rejouer sur chaque schéma |
| [002](002-politique-rbac-unique.md) | Politique d'autorisation déclarative, unique et *fail-closed* | l'URL porte une part du sens métier |
| [003](003-ports-adapters-erp.md) | Intégration ERP par Ports & Adapters | plus petit dénominateur commun entre ERP |
| [004](004-outbox-transactionnel.md) | Synchronisation ERP par outbox transactionnel | cohérence à terme, pas immédiate |

---

## Format

Chaque fiche suit la même structure :

```mermaid
flowchart LR
    A["Contexte<br/>la contrainte réelle"] --> B["Décision"]
    B --> C["Alternatives écartées<br/>avec leur motif"]
    C --> D["Conséquences<br/>y compris ce qui coûte"]

    style D fill:#fff3e0,stroke:#ef6c00
```

!!! warning "La dernière section est la plus importante"
    Un ADR qui n'énonce que des avantages n'a pas été écrit honnêtement. Chacun de ces quatre
    documents nomme ce que la décision coûte, et ce qui reste ouvert.

---

## Comment ces décisions se répondent

```mermaid
flowchart TB
    A["ADR-001<br/>Schéma par client"] --> B["Le locataire doit voyager<br/>partout : HTTP, AMQP, tâches"]
    B --> C["ADR-002<br/>Autorisation déclarative"]
    C --> D["Une politique unique évaluée<br/>par la gateway ET le service"]
    A --> E["ADR-004<br/>Outbox transactionnel"]
    E --> F["Les messages portent<br/>l'en-tête du locataire"]
    F --> G["ADR-003<br/>Ports & Adapters"]
    G --> H["L'adaptateur résout la config ERP<br/>du bon client"]

    style A fill:#e8eaf6,stroke:#3f51b5
```

**Aucune n'est isolée.** Le multi-tenant impose que le locataire traverse chaque frontière — ce qui
explique les post-processeurs AMQP, l'en-tête sur les messages, et pourquoi l'adaptateur ERP importe
la configuration tenant sans importer la partie Hibernate.
