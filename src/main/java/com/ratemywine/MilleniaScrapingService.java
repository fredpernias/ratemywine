package com.ratemywine;

import com.ratemywine.model.Millenia;
import com.ratemywine.repository.MilleniaRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MilleniaScrapingService {

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final double DEFAULT_MIN_DELAY_SECONDS = 0.5;
    private static final double DEFAULT_MAX_DELAY_SECONDS = 2.0;
    private static final Pattern YEAR_RE = Pattern.compile("(?:19|20)\\d{2}");
    private static final String AGGREGATE_SOURCE_KEY = "aggregated_ratings";
    private static final String AGGREGATE_SOURCE_NAME = "Aggregated ratings";
    private static final String AGGREGATE_SOURCE_TYPE = "aggregate";
    private static final String AGGREGATE_EXTRACTION_SOURCE = "aggregated";
    private static final Set<String> KNOWN_RATING_KEYS = Set.of(
            "wine_advocate",
            "jancis_robinson",
            "decanter",
            "wine_spectator",
            "james_suckling",
            "vinous",
            "vinous_antonio_galloni",
            "vinous_neal_martin",
            "figaro",
            "the_wine_independent"
    );

    private final MilleniaRepository milleniaRepository;

    public MilleniaScrapingService(MilleniaRepository milleniaRepository) {
        this.milleniaRepository = milleniaRepository;
    }

    @Transactional
    public int scrapeAndSyncMillesimaAllWines(int maxPages) throws InterruptedException {
        return scrapeAndSync(MilleniumWineRatingsScraper.DEFAULT_MILLESIMA_START_URL, maxPages);
    }

    @Transactional
    public int scrapeAndSync(String startUrl, int maxPages) throws InterruptedException {
        List<MilleniumWineRatingsScraper.WineRating> rawRatings = MilleniumWineRatingsScraper
                .scrape(startUrl, maxPages, DEFAULT_MIN_DELAY_SECONDS, DEFAULT_MAX_DELAY_SECONDS, 20, DEFAULT_USER_AGENT);
        Map<String, List<MilleniumWineRatingsScraper.WineRating>> ratingsByUrl = new LinkedHashMap<>();
        for (MilleniumWineRatingsScraper.WineRating rating : rawRatings) {
            if (rating.url() == null || rating.url().isBlank()) {
                continue;
            }
            ratingsByUrl.computeIfAbsent(rating.url(), ignored -> new ArrayList<>()).add(rating);
        }

        int changedRows = 0;
        for (Map.Entry<String, List<MilleniumWineRatingsScraper.WineRating>> entry : ratingsByUrl.entrySet()) {
            String pageUrl = entry.getKey();
            List<MilleniumWineRatingsScraper.WineRating> ratings = entry.getValue();
            String wineName = resolveWineName(ratings, pageUrl);
            WineIdentity identity = extractWineIdentity(wineName, pageUrl);
            AggregatedRatings aggregatedRatings = aggregateRatings(ratings);

            List<Millenia> existingRows = milleniaRepository.findAllByPageUrl(pageUrl);
            Millenia entity = existingRows.isEmpty() ? new Millenia() : existingRows.get(0);
            boolean duplicatesRemoved = false;
            if (existingRows.size() > 1) {
                milleniaRepository.deleteAll(existingRows.subList(1, existingRows.size()));
                duplicatesRemoved = true;
            }

            if (!duplicatesRemoved && !hasDiff(entity, pageUrl, wineName, identity, aggregatedRatings)) {
                continue;
            }

            applyAggregatedState(entity, pageUrl, wineName, identity, aggregatedRatings);
            milleniaRepository.save(entity);
            changedRows++;
        }
        return changedRows;
    }

    private void applyAggregatedState(
            Millenia entity,
            String pageUrl,
            String wineName,
            WineIdentity identity,
            AggregatedRatings ratings
    ) {
        entity.setPageUrl(pageUrl);
        entity.setWineName(wineName);
        entity.setNom(identity.nom());
        entity.setMillesime(identity.millesime());

        entity.setRatingParker(ratings.ratingParker());
        entity.setRatingJancisRobinson(ratings.ratingJancisRobinson());
        entity.setRatingDecanter(ratings.ratingDecanter());
        entity.setRatingWineSpectator(ratings.ratingWineSpectator());
        entity.setRatingJamesSuckling(ratings.ratingJamesSuckling());
        entity.setRatingVinousAntonioGalloni(ratings.ratingVinousAntonioGalloni());
        entity.setRatingVinousNealMartin(ratings.ratingVinousNealMartin());
        entity.setRatingFigaro(ratings.ratingFigaro());
        entity.setRatingTheWineIndependent(ratings.ratingTheWineIndependent());
        entity.setRatingsCount(ratings.ratingsCount());
        entity.setOtherRatings(ratings.otherRatings());

        entity.setSourceKey(AGGREGATE_SOURCE_KEY);
        entity.setSourceName(AGGREGATE_SOURCE_NAME);
        entity.setSourceType(AGGREGATE_SOURCE_TYPE);
        entity.setRatingValue("");
        entity.setRatingScale("");
        entity.setDistinction("");
        entity.setExtractionSource(AGGREGATE_EXTRACTION_SOURCE);
        entity.setScrapedAt(OffsetDateTime.now());
    }

    private boolean hasDiff(
            Millenia entity,
            String pageUrl,
            String wineName,
            WineIdentity identity,
            AggregatedRatings ratings
    ) {
        if (entity.getId() == null) {
            return true;
        }
        return !Objects.equals(entity.getPageUrl(), pageUrl)
                || !Objects.equals(entity.getWineName(), wineName)
                || !Objects.equals(entity.getNom(), identity.nom())
                || !Objects.equals(entity.getMillesime(), identity.millesime())
                || !Objects.equals(entity.getRatingParker(), ratings.ratingParker())
                || !Objects.equals(entity.getRatingJancisRobinson(), ratings.ratingJancisRobinson())
                || !Objects.equals(entity.getRatingDecanter(), ratings.ratingDecanter())
                || !Objects.equals(entity.getRatingWineSpectator(), ratings.ratingWineSpectator())
                || !Objects.equals(entity.getRatingJamesSuckling(), ratings.ratingJamesSuckling())
                || !Objects.equals(entity.getRatingVinousAntonioGalloni(), ratings.ratingVinousAntonioGalloni())
                || !Objects.equals(entity.getRatingVinousNealMartin(), ratings.ratingVinousNealMartin())
                || !Objects.equals(entity.getRatingFigaro(), ratings.ratingFigaro())
                || !Objects.equals(entity.getRatingTheWineIndependent(), ratings.ratingTheWineIndependent())
                || !Objects.equals(entity.getRatingsCount(), ratings.ratingsCount())
                || !Objects.equals(entity.getOtherRatings(), ratings.otherRatings())
                || !Objects.equals(entity.getSourceKey(), AGGREGATE_SOURCE_KEY)
                || !Objects.equals(entity.getSourceName(), AGGREGATE_SOURCE_NAME)
                || !Objects.equals(entity.getSourceType(), AGGREGATE_SOURCE_TYPE)
                || !Objects.equals(entity.getRatingValue(), "")
                || !Objects.equals(entity.getRatingScale(), "")
                || !Objects.equals(entity.getDistinction(), "")
                || !Objects.equals(entity.getExtractionSource(), AGGREGATE_EXTRACTION_SOURCE);
    }

    private AggregatedRatings aggregateRatings(List<MilleniumWineRatingsScraper.WineRating> ratings) {
        Map<String, String> ratingsBySource = new LinkedHashMap<>();
        for (MilleniumWineRatingsScraper.WineRating rating : ratings) {
            String normalizedValue = toRatingToken(rating);
            if (normalizedValue == null) {
                continue;
            }
            String sourceKey = normalizeSourceKey(rating.sourceKey());
            ratingsBySource.merge(sourceKey, normalizedValue, MilleniaScrapingService::preferMoreInformative);
        }

        String vinousAntonioGalloni = preferMoreInformative(
                ratingsBySource.get("vinous_antonio_galloni"),
                ratingsBySource.get("vinous")
        );
        String otherRatings = buildOtherRatings(ratingsBySource);

        return new AggregatedRatings(
                ratingsBySource.get("wine_advocate"),
                ratingsBySource.get("jancis_robinson"),
                ratingsBySource.get("decanter"),
                ratingsBySource.get("wine_spectator"),
                ratingsBySource.get("james_suckling"),
                vinousAntonioGalloni,
                ratingsBySource.get("vinous_neal_martin"),
                ratingsBySource.get("figaro"),
                ratingsBySource.get("the_wine_independent"),
                ratingsBySource.size(),
                otherRatings
        );
    }

    private String buildOtherRatings(Map<String, String> ratingsBySource) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : ratingsBySource.entrySet()) {
            if (KNOWN_RATING_KEYS.contains(entry.getKey())) {
                continue;
            }
            pairs.add(entry.getKey() + "=" + entry.getValue());
        }
        if (pairs.isEmpty()) {
            return null;
        }
        return String.join("; ", pairs);
    }

    private String toRatingToken(MilleniumWineRatingsScraper.WineRating rating) {
        String value = normalizeToken(rating.ratingValue());
        String scale = normalizeToken(rating.ratingScale());
        String distinction = normalizeToken(rating.distinction());

        if (value != null && scale != null) {
            return value + "/" + scale;
        }
        if (value != null) {
            return value;
        }
        return distinction;
    }

    private String normalizeSourceKey(String sourceKey) {
        String normalized = sourceKey == null ? "" : sourceKey.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String preferMoreInformative(String current, String candidate) {
        if (current == null || current.isBlank()) {
            return candidate;
        }
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        int currentScore = scoreRatingToken(current);
        int candidateScore = scoreRatingToken(candidate);
        if (candidateScore > currentScore) {
            return candidate;
        }
        return current;
    }

    private static int scoreRatingToken(String token) {
        int score = token.length();
        if (token.contains("/")) {
            score += 5;
        }
        if (token.contains("+")) {
            score += 2;
        }
        return score;
    }

    private String resolveWineName(List<MilleniumWineRatingsScraper.WineRating> ratings, String pageUrl) {
        for (MilleniumWineRatingsScraper.WineRating rating : ratings) {
            String wineName = normalizeToken(rating.wineName());
            if (wineName != null && !"Titre inconnu".equalsIgnoreCase(wineName)) {
                return wineName;
            }
        }
        return deriveNameFromUrl(pageUrl, extractYear(pageUrl));
    }

    private WineIdentity extractWineIdentity(String wineName, String pageUrl) {
        Integer millesime = extractYear(wineName);
        if (millesime == null) {
            millesime = extractYear(pageUrl);
        }

        String nom = normalizeNom(wineName, millesime);
        if (nom.isBlank()) {
            nom = deriveNameFromUrl(pageUrl, millesime);
        }
        return new WineIdentity(nom, millesime);
    }

    private Integer extractYear(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = YEAR_RE.matcher(text);
        Integer year = null;
        while (matcher.find()) {
            year = Integer.valueOf(matcher.group());
        }
        return year;
    }

    private String normalizeNom(String wineName, Integer millesime) {
        if (wineName == null) {
            return "";
        }
        String nom = wineName.trim();
        if (nom.isBlank()) {
            return "";
        }
        if (millesime != null) {
            nom = nom.replaceFirst("\\s*(?:19|20)\\d{2}\\s*$", "").trim();
            if (nom.isBlank()) {
                return "";
            }
            nom = nom.replaceAll("\\s{2,}", " ").trim();
        }
        return nom;
    }

    private String deriveNameFromUrl(String pageUrl, Integer millesime) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return "Unknown wine";
        }
        String cleaned = pageUrl;
        int queryIdx = cleaned.indexOf('?');
        if (queryIdx >= 0) {
            cleaned = cleaned.substring(0, queryIdx);
        }
        int fragmentIdx = cleaned.indexOf('#');
        if (fragmentIdx >= 0) {
            cleaned = cleaned.substring(0, fragmentIdx);
        }
        int slashIdx = cleaned.lastIndexOf('/');
        String slug = slashIdx >= 0 ? cleaned.substring(slashIdx + 1) : cleaned;
        slug = slug.replaceFirst("\\.html$", "");
        slug = slug.replace('-', ' ').replaceAll("\\s{2,}", " ").trim();
        if (millesime != null) {
            slug = slug.replaceFirst("\\s*(?:19|20)\\d{2}(?:\\s+\\d+)?\\s*$", "").trim();
        }
        return slug.isBlank() ? "Unknown wine" : slug;
    }

    private record AggregatedRatings(
            String ratingParker,
            String ratingJancisRobinson,
            String ratingDecanter,
            String ratingWineSpectator,
            String ratingJamesSuckling,
            String ratingVinousAntonioGalloni,
            String ratingVinousNealMartin,
            String ratingFigaro,
            String ratingTheWineIndependent,
            Integer ratingsCount,
            String otherRatings
    ) {}

    private record WineIdentity(String nom, Integer millesime) {}
}
