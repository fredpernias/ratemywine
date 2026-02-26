# RateMyWine – collecte programmatique de notes critiques

Ce dépôt contient un script Python pour **automatiser la récupération de notes** (Parker, Hachette, etc.) à partir d'une liste de vins.

> ⚠️ Important : vérifiez les CGU/robots.txt des sites interrogés avant un usage intensif.

## Fonctionnement

Le script :
1. lit un CSV d'entrée (`domaine`, `appellation`, `millesime`),
2. construit une requête de recherche web ciblant Millésima,
3. récupère les pages candidats,
4. extrait les notes des institutions via des patterns textuels,
5. exporte un CSV enrichi.

## Installation

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Format du CSV d'entrée

Exemple :

```csv
domaine,appellation,millesime
Chateau Margaux,Margaux,2015
Domaine de la Romanee-Conti,Romanee-Conti,2018
```

## Exécution

```bash
python3 scripts/collect_ratings.py --input wines.csv --output ratings.csv
```

Options utiles :

- `--max-results 5` : nombre de liens candidats testés par vin.
- `--sleep 1.2` : pause (secondes) entre requêtes HTTP.
- `--timeout 15` : timeout HTTP.

## Sortie

Le CSV de sortie contient :

- colonnes d'origine,
- `best_url` : URL où une note a été trouvée,
- `ratings_json` : notes trouvées (JSON compact),
- `status` : `ok` / `no-rating-found` / `no-candidate-page`.

## Prochaines améliorations

- Ajouter des connecteurs API officiels (quand disponibles).
- Introduire un matching fuzzy (nom de cuvée précis).
- Ajouter un mode Playwright pour les pages dynamiques JavaScript.
