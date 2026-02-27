package com.ratemywine;

import com.ratemywine.model.Millenia;
import com.ratemywine.repository.MilleniaRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MilleniaScrapingServiceTest {
    private static final Pattern LISTING_ENCODED_WINE_URL_RE = Pattern.compile(
            "&#34;url&#34;:\\s*&#34;(https://www\\.millesima\\.fr/[^&\\s]+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)&#34;"
    );
    private static final Pattern CANONICAL_WINE_PATH_RE = Pattern.compile(
            "^/(.+-(?:19|20)\\d{2})(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VINTAGE_FROM_PATH_RE = Pattern.compile(
            "((?:19|20)\\d{2})(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);

    private static final String PAGE_2_WINE_PATH = "/chateau-lynch-bages-2016.html";
    private static final String PEYRABON_START_SUFFIX_PATH = "/chateau-peyrabon-2024-e-cb-1.html";
    private static final String PEYRABON_START_CANONICAL_PATH = "/chateau-peyrabon-2024.html";
    private static final String PEYRABON_FAMILY_SLUG = "chateau-peyrabon";
    private static final Set<Integer> EXPECTED_PEYRABON_VINTAGES = Set.of(
            2023, 2022, 2021, 2020, 2019, 2017, 2016, 2015, 2014, 2013, 2012,
            2011, 2010, 2009, 2008, 2006, 2004, 2002, 2001, 2000, 1999, 1998
    );

    @Autowired
    private MilleniaScrapingService scrapingService;

    @Autowired
    private MilleniaRepository milleniaRepository;

    private HttpServer server;
    private int firstPageWineEntriesCount;
    private Set<String> firstPageWinePaths;
    private Set<String> additionalVintageWinePaths;
    private String allWinesHtmlTemplate;
    private String sampleWinePageHtml;
    private String peyrabonPageHtml;
    private Set<String> peyrabonRawWinePaths;
    private Set<String> peyrabonCanonicalWinePaths;
    private String baseUrl;
    private String startUrl;

    @BeforeEach
    void setUp() throws IOException {
        milleniaRepository.deleteAll();
        allWinesHtmlTemplate = readResource("allWines.html");
        sampleWinePageHtml = readResource("sampleWinePage.html");
        peyrabonPageHtml = readResource("peyrabon.html");
        ListingPageWines firstPageWines = extractFirstPageWines(allWinesHtmlTemplate);
        firstPageWineEntriesCount = firstPageWines.entriesCount();
        firstPageWinePaths = canonicalizeWinePaths(firstPageWines.uniqueWinePaths());
        additionalVintageWinePaths = extractWineFamilyVintagePaths(sampleWinePageHtml, "chateau-lynch-bages");
        peyrabonRawWinePaths = extractWineFamilyVintagePathsRaw(peyrabonPageHtml, PEYRABON_FAMILY_SLUG);
        peyrabonCanonicalWinePaths = canonicalizeWinePaths(peyrabonRawWinePaths);
        additionalVintageWinePaths.remove(PAGE_2_WINE_PATH);
        additionalVintageWinePaths.removeAll(firstPageWinePaths);
        peyrabonCanonicalWinePaths.remove(PEYRABON_START_CANONICAL_PATH);
        Set<Integer> peyrabonFixtureVintages = peyrabonCanonicalWinePaths.stream()
                .map(MilleniaScrapingServiceTest::extractVintageFromPath)
                .collect(Collectors.toSet());

        Assertions.assertEquals(45, firstPageWineEntriesCount, "Fixture allWines must contain 45 wine entries");
        Assertions.assertTrue(firstPageWinePaths.size() >= 44, "Fixture should contain at least 44 unique wine URLs");
        Assertions.assertFalse(firstPageWinePaths.contains(PAGE_2_WINE_PATH));
        Assertions.assertFalse(additionalVintageWinePaths.isEmpty(),
                "Fixture sampleWinePage should expose additional vintage links");
        Assertions.assertEquals(EXPECTED_PEYRABON_VINTAGES, peyrabonFixtureVintages,
                "Fixture peyrabon should expose all expected millesimes");

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            if ("/tous-nos-vins.html".equals(path) && "page=2".equals(query)) {
                respond(exchange, buildSecondPageListingHtml());
                return;
            }
            if ("/tous-nos-vins.html".equals(path)) {
                respond(exchange, allWinesHtmlTemplate.replace("https://www.millesima.fr", baseUrl));
                return;
            }
            if (firstPageWinePaths.contains(path)
                    || PAGE_2_WINE_PATH.equals(path)
                    || additionalVintageWinePaths.contains(path)) {
                respond(exchange, sampleWinePageHtml);
                return;
            }
            if (PEYRABON_START_SUFFIX_PATH.equals(path)
                    || PEYRABON_START_CANONICAL_PATH.equals(path)
                    || peyrabonRawWinePaths.contains(path)
                    || peyrabonCanonicalWinePaths.contains(path)) {
                respond(exchange, peyrabonPageHtml.replace("https://www.millesima.fr", baseUrl));
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        startUrl = baseUrl + "/tous-nos-vins.html";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void scrapeAllWinesReads45Page1NavigatesPage2AndParsesNineLynchBagesRatings() throws InterruptedException {
        int changedFirstRun = scrapingService.scrapeAndSync(startUrl, 120);

        long expectedWinePages = firstPageWinePaths.size() + 1L + additionalVintageWinePaths.size();
        Assertions.assertEquals(expectedWinePages, changedFirstRun);
        Assertions.assertEquals(expectedWinePages, milleniaRepository.count());

        Set<String> scrapedUrls = milleniaRepository.findAll().stream()
                .map(Millenia::getPageUrl)
                .collect(Collectors.toSet());
        long firstPageScraped = firstPageWinePaths.stream()
                .map(path -> baseUrl + path)
                .filter(scrapedUrls::contains)
                .count();
        Assertions.assertEquals((long) firstPageWinePaths.size(), firstPageScraped);
        Assertions.assertTrue(scrapedUrls.contains(baseUrl + PAGE_2_WINE_PATH));
        long additionalVintagesScraped = additionalVintageWinePaths.stream()
                .map(path -> baseUrl + path)
                .filter(scrapedUrls::contains)
                .count();
        Assertions.assertEquals((long) additionalVintageWinePaths.size(), additionalVintagesScraped,
                "Le scraper doit suivre les liens de tous les millesimes depuis la page vin");

        Millenia lynchBages = milleniaRepository.findFirstByPageUrl(baseUrl + PAGE_2_WINE_PATH)
                .orElseThrow();
        Assertions.assertTrue(lynchBages.getWineName().contains("Lynch-Bages 2016"));
        Assertions.assertTrue(lynchBages.getNom() != null && lynchBages.getNom().contains("Lynch-Bages"));
        Assertions.assertEquals(Integer.valueOf(2016), lynchBages.getMillesime());
        Assertions.assertEquals("97+/100", lynchBages.getRatingParker());
        Assertions.assertEquals("17+/20", lynchBages.getRatingJancisRobinson());
        Assertions.assertEquals(Integer.valueOf(9), lynchBages.getRatingsCount());
        Assertions.assertEquals(9, countFilledRatings(lynchBages));

        OffsetDateTime lynchBagesScrapedAt = lynchBages.getScrapedAt();
        int changedSecondRun = scrapingService.scrapeAndSync(startUrl, 120);
        Assertions.assertEquals(0, changedSecondRun);
        Millenia lynchBagesReloaded = milleniaRepository.findFirstByPageUrl(baseUrl + PAGE_2_WINE_PATH)
                .orElseThrow();
        Assertions.assertEquals(lynchBagesScrapedAt, lynchBagesReloaded.getScrapedAt());
    }

    @Test
    void scrapePeyrabonFromSuffixUrlFindsAllExpectedVintages() throws InterruptedException {
        int changedRows = scrapingService.scrapeAndSync(baseUrl + PEYRABON_START_SUFFIX_PATH, 120);

        Set<String> scrapedUrls = milleniaRepository.findAll().stream()
                .map(Millenia::getPageUrl)
                .collect(Collectors.toSet());
        Set<String> unexpectedUrls = scrapedUrls.stream()
                .filter(url -> !url.contains("/chateau-peyrabon-"))
                .collect(Collectors.toSet());
        Set<Integer> scrapedPeyrabonVintages = scrapedUrls.stream()
                .filter(url -> url.contains("/chateau-peyrabon-"))
                .map(MilleniaScrapingServiceTest::extractVintageFromUrl)
                .filter(EXPECTED_PEYRABON_VINTAGES::contains)
                .collect(Collectors.toSet());

        Assertions.assertTrue(unexpectedUrls.isEmpty(),
                "Le scraper ne doit pas suivre les liens d'autres sections (ex: du meme producteur)");
        Assertions.assertTrue(scrapedUrls.contains(baseUrl + PEYRABON_START_CANONICAL_PATH),
                "Le scraper doit canoniser les URLs suffixees vers la page millesime");
        Assertions.assertEquals(EXPECTED_PEYRABON_VINTAGES.size() + 1, scrapedUrls.size(),
                "Le scraper doit persister uniquement le millesime de depart et ses autres millesimes");
        Assertions.assertEquals(EXPECTED_PEYRABON_VINTAGES, scrapedPeyrabonVintages,
                "Le scraper doit suivre tous les millesimes de la page Peyrabon");
        Assertions.assertEquals(EXPECTED_PEYRABON_VINTAGES.size() + 1, changedRows,
                "Le run doit persister uniquement les pages de millesimes attendues");
    }

    private static int countFilledRatings(Millenia row) {
        int count = 0;
        if (isFilled(row.getRatingParker())) {
            count++;
        }
        if (isFilled(row.getRatingJancisRobinson())) {
            count++;
        }
        if (isFilled(row.getRatingDecanter())) {
            count++;
        }
        if (isFilled(row.getRatingWineSpectator())) {
            count++;
        }
        if (isFilled(row.getRatingJamesSuckling())) {
            count++;
        }
        if (isFilled(row.getRatingVinousAntonioGalloni())) {
            count++;
        }
        if (isFilled(row.getRatingVinousNealMartin())) {
            count++;
        }
        if (isFilled(row.getRatingFigaro())) {
            count++;
        }
        if (isFilled(row.getRatingTheWineIndependent())) {
            count++;
        }
        return count;
    }

    private static boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }

    private String buildSecondPageListingHtml() {
        return "<!doctype html><html><head>"
                + "<title>Tous nos vins - page 2</title>"
                + "<meta name=\"type\" content=\"Listing\" />"
                + "<link rel=\"canonical\" href=\"" + baseUrl + "/tous-nos-vins.html?page=2\" />"
                + "</head><body>"
                + "<script type=\"application/ld+json\">"
                + "{\"@context\":\"https://schema.org\",\"@type\":\"ItemList\",\"itemListElement\":["
                + "{\"@type\":\"ListItem\",\"position\":1,\"url\":\"" + baseUrl + PAGE_2_WINE_PATH + "\"}"
                + "]}"
                + "</script>"
                + "</body></html>";
    }

    private static ListingPageWines extractFirstPageWines(String html) {
        Matcher matcher = LISTING_ENCODED_WINE_URL_RE.matcher(html);
        Set<String> paths = new LinkedHashSet<>();
        int entries = 0;
        while (matcher.find()) {
            entries++;
            paths.add(URI.create(matcher.group(1)).getPath());
        }
        return new ListingPageWines(entries, paths);
    }

    private static Set<String> extractWineFamilyVintagePaths(String html, String wineFamilySlug) {
        return canonicalizeWinePaths(extractWineFamilyVintagePathsRaw(html, wineFamilySlug));
    }

    private static Set<String> extractWineFamilyVintagePathsRaw(String html, String wineFamilySlug) {
        Pattern familyVintageRe = Pattern.compile(
                "(?i)(?:https?://www\\.millesima\\.fr/)?("
                        + Pattern.quote(wineFamilySlug)
                        + "-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)");
        Matcher matcher = familyVintageRe.matcher(html.replace("\\/", "/"));
        Set<String> paths = new LinkedHashSet<>();
        while (matcher.find()) {
            paths.add("/" + matcher.group(1).toLowerCase());
        }
        return paths;
    }

    private static Set<String> canonicalizeWinePaths(Set<String> rawPaths) {
        return rawPaths.stream()
                .map(MilleniaScrapingServiceTest::canonicalizeWinePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String canonicalizeWinePath(String path) {
        Matcher matcher = CANONICAL_WINE_PATH_RE.matcher(path.toLowerCase());
        if (!matcher.matches()) {
            return path;
        }
        return "/" + matcher.group(1) + ".html";
    }

    private static Integer extractVintageFromPath(String path) {
        Matcher matcher = VINTAGE_FROM_PATH_RE.matcher(path.toLowerCase());
        if (!matcher.find()) {
            throw new IllegalArgumentException("No millesime in path: " + path);
        }
        return Integer.valueOf(matcher.group(1));
    }

    private static Integer extractVintageFromUrl(String url) {
        return extractVintageFromPath(URI.create(url).getPath());
    }

    private record ListingPageWines(int entriesCount, Set<String> uniqueWinePaths) {}

    private static String readResource(String name) throws IOException {
        try (InputStream input = MilleniaScrapingServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Resource not found: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(payload);
        }
    }
}
