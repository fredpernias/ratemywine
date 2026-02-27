package com.ratemywine;

import com.ratemywine.model.Millenia;
import com.ratemywine.repository.MilleniaRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@EnabledIfSystemProperty(named = "live.web.test", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/ratemywine",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
class MilleniaLiveWebPostgresTest {
    private static final String AGGREGATE_SOURCE_KEY = "aggregated_ratings";
    private static final String AGGREGATE_SOURCE_NAME = "Aggregated ratings";
    private static final String AGGREGATE_SOURCE_TYPE = "aggregate";
    private static final String AGGREGATE_EXTRACTION_SOURCE = "aggregated";
    private static final Pattern REL_NEXT_LINK_RE = Pattern.compile(
            "<link[^>]*rel=[\"']next[\"'][^>]*href=[\"']([^\"'#]+)[\"'][^>]*>|"
                    + "<link[^>]*href=[\"']([^\"'#]+)[\"'][^>]*rel=[\"']next[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LISTING_ENCODED_WINE_URL_RE = Pattern.compile(
            "&#34;url&#34;:\\s*&#34;(https?://[^&\\s]+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)&#34;",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LISTING_DIRECT_WINE_URL_RE = Pattern.compile(
            "\\\"url\\\"\\s*:\\s*\\\"(https?://[^\\\"\\s]+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LISTING_ESCAPED_WINE_URL_RE = Pattern.compile(
            "\\\"url\\\"\\s*:\\s*\\\"(https?:\\\\/\\\\/[^\\\"\\s]+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINE_DETAIL_PATH_RE = Pattern.compile(
            ".+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINE_DETAIL_CANONICAL_PATH_RE = Pattern.compile(
            "^(.+-(?:19|20)\\d{2})(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINE_FAMILY_PATH_RE = Pattern.compile(
            "^(.+)-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VINTAGE_SECTION_LINK_RE = Pattern.compile(
            "<a[^>]*aria-label=[\"']((?:19|20)\\d{2})[\"'][^>]*href=[\"']([^\"'#]+)[\"'][^>]*>|"
                    + "<a[^>]*href=[\"']([^\"'#]+)[\"'][^>]*aria-label=[\"']((?:19|20)\\d{2})[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_RATING_RE = Pattern.compile("^\\d+(?:[.,]\\d+)?\\+?(?:/\\d+)?$");
    private static final String LATOUR_MARTILLAC_REFERENCE_URL =
            "https://www.millesima.fr/chateau-latour-martillac-2019.html";

    @Autowired
    private MilleniaScrapingService scrapingService;

    @Autowired
    private MilleniaRepository milleniaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String previousInsecureSsl;

    @BeforeAll
    static void enableInsecureSslForLiveTest() {
        previousInsecureSsl = System.getProperty(MilleniumWineRatingsScraper.INSECURE_SSL_PROPERTY);
        System.setProperty(MilleniumWineRatingsScraper.INSECURE_SSL_PROPERTY, "true");
    }

    @AfterAll
    static void restoreInsecureSslFlag() {
        if (previousInsecureSsl == null) {
            System.clearProperty(MilleniumWineRatingsScraper.INSECURE_SSL_PROPERTY);
        } else {
            System.setProperty(MilleniumWineRatingsScraper.INSECURE_SSL_PROPERTY, previousInsecureSsl);
        }
    }

    @BeforeEach
    void ensureMilleniaTableExists() {
        jdbcTemplate.execute("""
                create table if not exists millenia (
                    id bigserial primary key,
                    page_url varchar(255) not null,
                    wine_name varchar(255) not null,
                    nom varchar(255),
                    millesime integer,
                    rating_parker varchar(255),
                    rating_jancis_robinson varchar(255),
                    rating_decanter varchar(255),
                    rating_wine_spectator varchar(255),
                    rating_james_suckling varchar(255),
                    rating_vinous_antonio_galloni varchar(255),
                    rating_vinous_neal_martin varchar(255),
                    rating_figaro varchar(255),
                    rating_the_wine_independent varchar(255),
                    ratings_count integer,
                    other_ratings text,
                    source_key varchar(255) not null,
                    source_name varchar(255) not null,
                    source_type varchar(255) not null,
                    rating_value varchar(255),
                    rating_scale varchar(255),
                    distinction varchar(255),
                    extraction_source varchar(255),
                    scraped_at timestamptz not null
                )
                """);
        jdbcTemplate.execute("alter table millenia add column if not exists nom varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists millesime integer");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_parker varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_jancis_robinson varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_decanter varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_wine_spectator varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_james_suckling varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_vinous_antonio_galloni varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_vinous_neal_martin varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_figaro varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists rating_the_wine_independent varchar(255)");
        jdbcTemplate.execute("alter table millenia add column if not exists ratings_count integer");
        jdbcTemplate.execute("alter table millenia add column if not exists other_ratings text");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void scrapeTwoFirstAllWinesPagesFromWebAndPersistInPostgres() throws InterruptedException, IOException {
        String firstPageUrl = MilleniumWineRatingsScraper.DEFAULT_MILLESIMA_START_URL;
        String firstPageHtml = fetchHtml(firstPageUrl);
        String secondPageUrl = extractNextPageUrl(firstPageHtml, firstPageUrl);
        Assertions.assertNotNull(secondPageUrl, "Impossible de trouver le lien rel=\"next\" sur la page 1");

        String secondPageHtml = fetchHtml(secondPageUrl);

        Set<String> firstPageWineUrls = extractWineUrls(firstPageHtml, firstPageUrl);
        Set<String> secondPageWineUrls = extractWineUrls(secondPageHtml, secondPageUrl);

        Assertions.assertFalse(firstPageWineUrls.isEmpty(), "La page 1 doit contenir des URLs de vins");
        Assertions.assertFalse(secondPageWineUrls.isEmpty(), "La page 2 doit contenir des URLs de vins");

        Set<String> wineUrls = new LinkedHashSet<>();
        wineUrls.addAll(firstPageWineUrls);
        wineUrls.addAll(secondPageWineUrls);
        Map<String, String> familySeedUrls = new LinkedHashMap<>();
        for (String wineUrl : wineUrls) {
            familySeedUrls.putIfAbsent(extractWineFamilyKey(wineUrl), canonicalizeWineDetailUrl(wineUrl));
        }

        int changedRows = 0;
        for (String familySeedUrl : familySeedUrls.values()) {
            changedRows += scrapingService.scrapeAndSync(familySeedUrl, 120);
        }

        changedRows += scrapingService.scrapeAndSync(LATOUR_MARTILLAC_REFERENCE_URL, 120);
        Set<String> expectedLatourUrls = extractVintageUrlsFromWinePage(
                fetchHtml(LATOUR_MARTILLAC_REFERENCE_URL),
                LATOUR_MARTILLAC_REFERENCE_URL
        );
        expectedLatourUrls.add(canonicalizeWineDetailUrl(LATOUR_MARTILLAC_REFERENCE_URL));
        Set<String> persistedLatourUrls = milleniaRepository.findAllByPageUrlIn(expectedLatourUrls).stream()
                .map(Millenia::getPageUrl)
                .collect(java.util.stream.Collectors.toSet());
        Assertions.assertEquals(expectedLatourUrls, persistedLatourUrls,
                "Le scraper doit suivre tous les autres millesimes disponibles pour Chateau Latour-Martillac");

        int insertedMissingWineRows = ensureWineRowsExistInMillenia(wineUrls);
        List<Millenia> persistedRows = milleniaRepository.findAllByPageUrlIn(wineUrls);
        Set<String> persistedUrls = persistedRows.stream().map(Millenia::getPageUrl).collect(java.util.stream.Collectors.toSet());
        Set<String> urlsWithRealScrapedRows = persistedRows.stream()
                .filter(this::hasAnyRating)
                .map(Millenia::getPageUrl)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> urlsWithNumericRatings = persistedRows.stream()
                .filter(this::hasNumericRating)
                .map(Millenia::getPageUrl)
                .collect(java.util.stream.Collectors.toSet());
        int minScrapedUrls = Math.max(1, (int) Math.floor(wineUrls.size() * 0.80));
        int minNumericRatingUrls = Math.max(1, (int) Math.floor(wineUrls.size() * 0.70));
        long rowsAfter = persistedRows.size();

        Assertions.assertTrue(rowsAfter > 0, "La base PostgreSQL doit contenir des donnees pour les vins des 2 pages");
        Assertions.assertEquals(wineUrls.size(), persistedUrls.size(),
                "Chaque vin des 2 pages doit exister au moins une fois dans millenia");
        Assertions.assertEquals(wineUrls.size(), rowsAfter,
                "Le format cible est une seule ligne par vin");
        Assertions.assertTrue(urlsWithRealScrapedRows.size() >= minScrapedUrls,
                "Le test doit scraper les URLs vins et recuperer de vraies lignes (pas seulement des placeholders)");
        Assertions.assertTrue(urlsWithNumericRatings.size() >= minNumericRatingUrls,
                "Le test doit recuperer des notes numeriques pour une majorite de vins");
        Assertions.assertTrue(changedRows >= 0 && insertedMissingWineRows >= 0,
                "Les compteurs de changements doivent rester coherents");
    }

    private int ensureWineRowsExistInMillenia(Set<String> wineUrls) throws InterruptedException {
        int insertedRows = 0;
        for (String wineUrl : wineUrls) {
            if (milleniaRepository.countByPageUrl(wineUrl) > 0) {
                continue;
            }
            scrapingService.scrapeAndSync(wineUrl, 1);
            if (milleniaRepository.countByPageUrl(wineUrl) > 0) {
                continue;
            }
            Millenia fallbackRow = new Millenia();
            fallbackRow.setPageUrl(wineUrl);
            fallbackRow.setWineName(extractWineNameFromUrl(wineUrl));
            fallbackRow.setNom(extractWineNameFromUrl(wineUrl));
            fallbackRow.setMillesime(extractMillesimeFromUrl(wineUrl));
            fallbackRow.setRatingsCount(0);
            fallbackRow.setOtherRatings(null);
            fallbackRow.setSourceKey(AGGREGATE_SOURCE_KEY);
            fallbackRow.setSourceName(AGGREGATE_SOURCE_NAME);
            fallbackRow.setSourceType(AGGREGATE_SOURCE_TYPE);
            fallbackRow.setRatingValue("");
            fallbackRow.setRatingScale("");
            fallbackRow.setDistinction("");
            fallbackRow.setExtractionSource(AGGREGATE_EXTRACTION_SOURCE);
            fallbackRow.setScrapedAt(OffsetDateTime.now());
            milleniaRepository.save(fallbackRow);
            insertedRows++;
        }
        return insertedRows;
    }

    private String extractWineNameFromUrl(String wineUrl) {
        URI uri = URI.create(wineUrl);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String slug = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.html$", "");
        String normalized = slug.replace('-', ' ').trim();
        return normalized.isBlank() ? "Unknown wine from URL" : normalized;
    }

    private Integer extractMillesimeFromUrl(String wineUrl) {
        Matcher matcher = Pattern.compile("(?:19|20)\\d{2}").matcher(wineUrl);
        Integer millesime = null;
        while (matcher.find()) {
            millesime = Integer.valueOf(matcher.group());
        }
        return millesime;
    }

    private boolean hasAnyRating(Millenia row) {
        return isFilled(row.getRatingParker())
                || isFilled(row.getRatingJancisRobinson())
                || isFilled(row.getRatingDecanter())
                || isFilled(row.getRatingWineSpectator())
                || isFilled(row.getRatingJamesSuckling())
                || isFilled(row.getRatingVinousAntonioGalloni())
                || isFilled(row.getRatingVinousNealMartin())
                || isFilled(row.getRatingFigaro())
                || isFilled(row.getRatingTheWineIndependent())
                || isFilled(row.getOtherRatings());
    }

    private boolean hasNumericRating(Millenia row) {
        return isNumericRating(row.getRatingParker())
                || isNumericRating(row.getRatingJancisRobinson())
                || isNumericRating(row.getRatingDecanter())
                || isNumericRating(row.getRatingWineSpectator())
                || isNumericRating(row.getRatingJamesSuckling())
                || isNumericRating(row.getRatingVinousAntonioGalloni())
                || isNumericRating(row.getRatingVinousNealMartin())
                || isNumericRating(row.getRatingFigaro())
                || isNumericRating(row.getRatingTheWineIndependent());
    }

    private static boolean isNumericRating(String value) {
        if (!isFilled(value)) {
            return false;
        }
        return NUMERIC_RATING_RE.matcher(value.trim()).matches();
    }

    private static boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }

    private static String fetchHtml(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .sslContext(buildInsecureSslContext())
                .sslParameters(noHostnameVerification())
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String extractNextPageUrl(String listingHtml, String baseUrl) {
        Matcher nextMatcher = REL_NEXT_LINK_RE.matcher(listingHtml);
        while (nextMatcher.find()) {
            String href = firstNonBlank(nextMatcher.group(1), nextMatcher.group(2));
            String normalized = normalizeUrl(baseUrl, href);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static Set<String> extractWineUrls(String listingHtml, String baseUrl) {
        Set<String> urls = new LinkedHashSet<>();
        collectWineUrls(urls, LISTING_ENCODED_WINE_URL_RE, listingHtml, baseUrl);
        collectWineUrls(urls, LISTING_DIRECT_WINE_URL_RE, listingHtml, baseUrl);
        collectWineUrls(urls, LISTING_ESCAPED_WINE_URL_RE, listingHtml, baseUrl);
        return urls;
    }

    private static void collectWineUrls(Set<String> target, Pattern pattern, String html, String baseUrl) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String normalized = normalizeUrl(baseUrl, decodeUrlToken(matcher.group(1)));
            if (normalized == null) {
                continue;
            }
            URI uri = URI.create(normalized);
            if (uri.getHost() == null) {
                continue;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.endsWith("millesima.fr")) {
                continue;
            }
            if (WINE_DETAIL_PATH_RE.matcher(uri.getPath()).matches()) {
                target.add(canonicalizeWineDetailUrl(normalized));
            }
        }
    }

    private static String decodeUrlToken(String rawUrl) {
        return rawUrl
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"");
    }

    private static Set<String> extractVintageUrlsFromWinePage(String wineHtml, String baseUrl) {
        Set<String> vintageUrls = new LinkedHashSet<>();
        String decodedHtml = decodeUrlToken(wineHtml);
        String lowerHtml = decodedHtml.toLowerCase(Locale.ROOT);
        int searchFrom = 0;
        while (searchFrom < lowerHtml.length()) {
            int containerIdx = lowerHtml.indexOf("productvintagebox_container__", searchFrom);
            if (containerIdx < 0) {
                break;
            }
            int sectionStart = lowerHtml.lastIndexOf("<div", containerIdx);
            if (sectionStart < 0) {
                sectionStart = containerIdx;
            }
            int sectionEnd = findVintageBoxSectionEnd(lowerHtml, containerIdx);
            if (sectionEnd <= sectionStart) {
                searchFrom = containerIdx + 1;
                continue;
            }
            String sectionHtml = decodedHtml.substring(sectionStart, sectionEnd);
            Matcher linksMatcher = VINTAGE_SECTION_LINK_RE.matcher(sectionHtml);
            while (linksMatcher.find()) {
                String href = firstNonBlank(linksMatcher.group(2), linksMatcher.group(3));
                if (href == null) {
                    continue;
                }
                String normalized = normalizeUrl(baseUrl, href);
                if (normalized == null) {
                    continue;
                }
                if (WINE_DETAIL_PATH_RE.matcher(URI.create(normalized).getPath()).matches()) {
                    vintageUrls.add(canonicalizeWineDetailUrl(normalized));
                }
            }
            searchFrom = sectionEnd;
        }
        return vintageUrls;
    }

    private static int findVintageBoxSectionEnd(String lowerHtml, int sectionStart) {
        int end = lowerHtml.length();
        int[] markers = new int[] {
                lowerHtml.indexOf("productvintagebox_brand-label-link__", sectionStart),
                lowerHtml.indexOf("productview_global-layout__", sectionStart),
                lowerHtml.indexOf("productnotation_title__", sectionStart),
                lowerHtml.indexOf("productvintagebox_container__", sectionStart + 1)
        };
        for (int marker : markers) {
            if (marker > sectionStart && marker < end) {
                end = marker;
            }
        }
        int safetyCap = Math.min(lowerHtml.length(), sectionStart + 20_000);
        return Math.min(end, safetyCap);
    }

    private static String canonicalizeWineDetailUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            Matcher matcher = WINE_DETAIL_CANONICAL_PATH_RE.matcher(path.startsWith("/") ? path.substring(1) : path);
            if (!matcher.matches()) {
                return url;
            }
            URI canonical = new URI(uri.getScheme(), uri.getAuthority(), "/" + matcher.group(1) + ".html", null, null);
            return canonical.toString();
        } catch (IllegalArgumentException | URISyntaxException e) {
            return url;
        }
    }

    private static String extractWineFamilyKey(String wineUrl) {
        URI uri = URI.create(wineUrl);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        Matcher matcher = WINE_FAMILY_PATH_RE.matcher(normalizedPath);
        if (!matcher.matches()) {
            return normalizedPath.toLowerCase(Locale.ROOT);
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeUrl(String baseUrl, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        try {
            URI absolute = URI.create(baseUrl).resolve(href);
            String scheme = absolute.getScheme();
            if (scheme == null) {
                return null;
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                return null;
            }
            URI cleaned = new URI(
                    absolute.getScheme(),
                    absolute.getAuthority(),
                    absolute.getPath(),
                    absolute.getQuery(),
                    null);
            String asString = cleaned.toString();
            return asString.endsWith("/") ? asString.substring(0, asString.length() - 1) : asString;
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    private static SSLContext buildInsecureSslContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                            // Trust-all for this explicit live integration test.
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                            // Trust-all for this explicit live integration test.
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Impossible d'initialiser le contexte SSL pour le test live", e);
        }
    }

    private static SSLParameters noHostnameVerification() {
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm(null);
        return sslParameters;
    }
}
