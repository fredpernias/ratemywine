# Millenium wine ratings scraper

Script Python pour parcourir la section **"Tous nos vins"** d'un site, suivre les liens internes page par page, et récupérer les notations détectées sur les pages produits.

## Installation

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Utilisation

```bash
python3 millenium_wine_ratings_scraper.py "https://example.com/tous-nos-vins" \
  --max-pages 500 \
  --min-delay 1 \
  --max-delay 2 \
  --csv notes_vins.csv \
  --json notes_vins.json
```

## Ce que fait le script

- Démarre depuis l'URL fournie (section "tous nos vins").
- Explore les liens du même domaine uniquement.
- Respecte une pause aléatoire de 1 à 2 secondes (configurable) entre chaque page.
- Extrait les notes via:
  - `application/ld+json` (`aggregateRating`) quand disponible.
  - un fallback regex texte (formats de type `17/20`, `92/100`, etc.).
- Exporte les résultats en CSV et JSON.

## Remarques

- Adapte `--max-pages` selon la taille du site.
- Le script ne contourne pas les protections anti-bot.
- Vérifie les CGU/robots.txt du site ciblé avant scraping.
