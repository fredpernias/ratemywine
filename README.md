# RateMyWine – collecte programmatique de notes critiques (Java + Spring Boot)

Ce dépôt contient désormais des programmes **Java** pour automatiser la récupération de notes (Parker, Hachette, etc.) à partir d'une liste de vins ou via crawl d'un site, avec une base **Maven/Spring Boot** prête pour persister en PostgreSQL.

> ⚠️ Important : vérifiez les CGU/robots.txt des sites interrogés avant un usage intensif.

## Prérequis

- Java 17+
- Maven 3.9+
- PostgreSQL local avec une base `ratemywine`

## Lancement du projet Maven

```bash
export DB_USER=postgres
export DB_PASSWORD=postgres
mvn spring-boot:run
```

La connexion PostgreSQL est configurée via :

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ratemywine
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
```

Les entités JPA sont mappées sur :

- `wines` (historique),
- `millenia` (nouvelles notes extraites depuis le scraper Millenium avec source, score, distinction et type de guide/concours).

## Build

```bash
mvn clean package
```

## 1) Collecte depuis un CSV d'entrée

Format attendu :

```csv
domaine,appellation,millesime
Chateau Margaux,Margaux,2015
Domaine de la Romanee-Conti,Romanee-Conti,2018
```

Exécution (classe utilitaire existante) :

```bash
mvn -q -DskipTests package
java -cp target/classes com.ratemywine.CollectRatings --input wines.csv --output ratings.csv
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
mvn -q -DskipTests package
java -cp target/classes com.ratemywine.MilleniumWineRatingsScraper "https://www.millesima.fr/tous-nos-vins.html"
# ou simplement (utilise cette même URL par défaut)
java -cp target/classes com.ratemywine.MilleniumWineRatingsScraper
```

Options :

- `--max-pages 200`
- `--min-delay 1.0`
- `--max-delay 2.0`
- `--timeout 20`
- `--csv wine_ratings.csv`
- `--json wine_ratings.json`
- `--user-agent "Mozilla/5.0 (compatible; WineRatingsBot/1.0)"`


Le scraper `MilleniumWineRatingsScraper` exporte désormais des colonnes détaillées par source:

- `source_key`, `source_name`, `source_type`
- `rating_value`, `rating_scale`
- `distinction` (médailles/mentions: Tre Bicchieri, Gold, Coup de coeur, etc.)
- `extraction_source`

Sources couvertes: Wine Advocate/Parker, Wine Spectator, Decanter, Wine Enthusiast, Vinous, James Suckling, Jancis Robinson, Falstaff, Hachette, RVF, Bettane+Desseauve, Gilbert & Gaillard, Gambero Rosso, Slow Wine, Guia Peñín, DWWA, IWC, IWSC, CMB, Mundus Vini, Berliner Wine Trophy, Concours Général Agricole, Vinalies Internationales, Challenge International du Vin, MICHELIN Grapes (si présent dans les pages crawlées).
