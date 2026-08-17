# Diagrammes

Tous les diagrammes de la documentation, exportés en **SVG** (vectoriel, net à l'impression — le
format à insérer dans le rapport).

## Régénérer

Depuis la racine du dépôt :

```bash
bash scripts/render-diagrams.sh
```

Prérequis : **Docker** uniquement (rien à installer en local). Voir la section « Exporter les
diagrammes » de [`docs/exploitation/developpement.md`](../docs/exploitation/developpement.md).

## Contenu

| Dossier / fichier | Rôle |
|---|---|
| `svg/` | un SVG par diagramme — **le bundle à utiliser** |
| `mmd/` | blocs Mermaid extraits des `.md` (regénérés à chaque exécution, ne pas éditer) |
| `puml/` | sources PlantUML des vues UML que Mermaid ne fait pas (cas d'utilisation, déploiement) — **à éditer à la main** |
| `INDEX.md` | manifeste : chaque SVG → sa doc source → son type |
| `puppeteer-config.json` | config chromium pour mermaid-cli (généré par le script) |

Les blocs Mermaid se modifient **dans les `.md`** de `docs/`, pas dans `mmd/`. Seuls les `.puml` se
modifient ici.
