package com.ratemywine;

import com.ratemywine.model.Millenia;
import com.ratemywine.repository.MilleniaRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MilleniaScrapingService {

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

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
                .scrape(startUrl, maxPages, 0, 0, 20, DEFAULT_USER_AGENT);
        Map<String, MilleniumWineRatingsScraper.WineRating> uniqueRatings = new LinkedHashMap<>();
        for (MilleniumWineRatingsScraper.WineRating rating : rawRatings) {
            uniqueRatings.putIfAbsent(rating.url() + "|" + rating.sourceKey(), rating);
        }

        int changedRows = 0;
        for (MilleniumWineRatingsScraper.WineRating rating : uniqueRatings.values()) {
            Millenia entity = milleniaRepository.findByPageUrlAndSourceKey(rating.url(), rating.sourceKey())
                    .orElseGet(Millenia::new);

            if (!hasDiff(entity, rating)) {
                continue;
            }

            entity.setPageUrl(rating.url());
            entity.setWineName(rating.wineName());
            entity.setSourceKey(rating.sourceKey());
            entity.setSourceName(rating.sourceName());
            entity.setSourceType(rating.sourceType());
            entity.setRatingValue(rating.ratingValue());
            entity.setRatingScale(rating.ratingScale());
            entity.setDistinction(rating.distinction());
            entity.setExtractionSource(rating.extractionSource());
            entity.setScrapedAt(OffsetDateTime.now());

            milleniaRepository.save(entity);
            changedRows++;
        }
        return changedRows;
    }

    private boolean hasDiff(Millenia entity, MilleniumWineRatingsScraper.WineRating rating) {
        if (entity.getId() == null) {
            return true;
        }
        return !Objects.equals(entity.getWineName(), rating.wineName())
                || !Objects.equals(entity.getSourceName(), rating.sourceName())
                || !Objects.equals(entity.getSourceType(), rating.sourceType())
                || !Objects.equals(entity.getRatingValue(), rating.ratingValue())
                || !Objects.equals(entity.getRatingScale(), rating.ratingScale())
                || !Objects.equals(entity.getDistinction(), rating.distinction())
                || !Objects.equals(entity.getExtractionSource(), rating.extractionSource());
    }
}
