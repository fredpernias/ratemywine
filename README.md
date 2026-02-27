# RateMyWine – collecte programmatique de notes critiques (Java)

Ce dépôt contient désormais des programmes **Java** pour automatiser la récupération de notes (Parker, Hachette, etc.) à partir d'une liste de vins ou via crawl d'un site.

> ⚠️ Important : vérifiez les CGU/robots.txt des sites interrogés avant un usage intensif.

## Prérequis

- Java 17+ installé (`java`, `javac`)

## Compilation

```bash
mkdir -p out
javac -d out src/main/java/com/ratemywine/*.java
```

## 1) Collecte depuis un CSV d'entrée

Format attendu :

```csv
domaine,appellation,millesime
Chateau Margaux,Margaux,2015
Domaine de la Romanee-Conti,Romanee-Conti,2018
```

Exécution :

```bash
java -cp out com.ratemywine.CollectRatings --input wines.csv --output ratings.csv
```

Options utiles :

- `--max-results 5` : nombre de liens candidats testés par vin.
- `--sleep 1.2` : pause (secondes) entre requêtes HTTP.
- `--timeout 15` : timeout HTTP.

Sortie :

- colonnes d'origine,
- `best_url` : URL où une note a été trouvée,
- `ratings_json` : notes trouvées (JSON compact),
- `status` : `ok` / `no-rating-found` / `no-candidate-page`.

## 2) Crawl d'un site et extraction des notes

Exécution :

```bash
java -cp out com.ratemywine.MilleniumWineRatingsScraper "https://www.exemple.com/tous-nos-vins"
```

Options :

- `--max-pages 200`
- `--min-delay 1.0`
- `--max-delay 2.0`
- `--timeout 20`
- `--csv wine_ratings.csv`
- `--json wine_ratings.json`
- `--user-agent "Mozilla/5.0 (compatible; WineRatingsBot/1.0)"`
