package com.ratemywine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class MilleniumWineRatingsScraper {
    public static final String DEFAULT_MILLESIMA_START_URL = "https://www.millesima.fr/tous-nos-vins.html";
    public static final String INSECURE_SSL_PROPERTY = "ratemywine.scraper.insecure-ssl";

    private static final Pattern RATING_WITH_SCALE_RE = Pattern.compile(
            "(?:(?:note|notation|rating|score)\\s*[:\\-]?\\s*)?(\\d{1,3}(?:[\\.,]\\d)?\\+?)\\s*(?:/\\s*(20|100)|sur\\s*(20|100))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_RE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern H1_RE = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_JSONLD_RE = Pattern.compile(
            "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern HREF_RE = Pattern.compile("<a[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_NEXT_LINK_RE = Pattern.compile(
            "<link[^>]*rel=[\"']next[\"'][^>]*href=[\"']([^\"'#]+)[\"'][^>]*>|<link[^>]*href=[\"']([^\"'#]+)[\"'][^>]*rel=[\"']next[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINE_DETAIL_PATH_RE = Pattern.compile(
            ".+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINE_DETAIL_CANONICAL_PATH_RE = Pattern.compile(
            "^(.+-(?:19|20)\\d{2})(?:-[a-z0-9]+)*\\.html$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LISTING_ENCODED_WINE_URL_RE = Pattern.compile(
            "&#34;url&#34;:\\s*&#34;(https?://[^&\\s]+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)&#34;",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LISTING_DIRECT_WINE_URL_RE = Pattern.compile(
            "\"url\"\\s*:\\s*\"(https?://[^\"\\s]+-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VINTAGE_SECTION_LINK_RE = Pattern.compile(
            "<a[^>]*aria-label=[\"']((?:19|20)\\d{2})[\"'][^>]*href=[\"']([^\"'#]+)[\"'][^>]*>|"
                    + "<a[^>]*href=[\"']([^\"'#]+)[\"'][^>]*aria-label=[\"']((?:19|20)\\d{2})[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRITIC_SLIDE_RE = Pattern.compile(
            "<div[^>]*WineCriticSlide_container__[^>]*>.*?<span[^>]*WineCriticSlide_name__[^>]*>(.*?)</span>.*?<span[^>]*WineCriticSlide_rating__[^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CRITIC_SCORE_RE = Pattern.compile(
            "(\\d{1,3}(?:[\\.,]\\d+)?\\+?)\\s*/\\s*(20|100)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_RATING_VALUE_RE = Pattern.compile("\\\"ratingValue\\\"\\s*:\\s*\\\"?([0-9]+(?:[.,][0-9]+)?)\\\"?");
    private static final Pattern JSON_BEST_RATING_RE = Pattern.compile("\\\"bestRating\\\"\\s*:\\s*\\\"?([0-9]+)\\\"?");
    private static final Pattern JSON_NAME_RE = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private static final List<SourcePattern> SOURCE_PATTERNS = List.of(
            source("wine_advocate", "The Wine Advocate / Parker", "critic", "100", "(?:wine\\s+advocate|robert\\s+parker|parker)", null),
            source("wine_spectator", "Wine Spectator", "critic", "100", "wine\\s+spectator", null),
            source("decanter", "Decanter", "critic", "100", "\\bdecanter\\b", null),
            source("wine_enthusiast", "Wine Enthusiast", "critic", "100", "wine\\s+enthusiast", null),
            source("vinous", "Vinous", "critic", "100", "\\bvinous\\b|antonio\\s+galloni", null),
            source("james_suckling", "James Suckling", "critic", "100", "james\\s*suckling", null),
            source("jancis_robinson", "Jancis Robinson", "critic", "20", "jancis\\s+robinson", null),
            source("falstaff", "Falstaff", "critic", "100", "\\bfalstaff\\b", null),
            source("guide_hachette", "Guide Hachette", "critic", null, "guide\\s+hachette|hachette", "(?:\\*{1,3}|coup\\s+de\\s+coeur)"),
            source("rvf", "RVF / Guide des Meilleurs Vins", "critic", "100", "revue\\s+du\\s+vin\\s+de\\s+france|\\brvf\\b|guide\\s+des\\s+meilleurs\\s+vins", null),
            source("bettane_desseauve", "Bettane+Desseauve", "critic", "100", "bettane\\s*\\+?\\s*desseauve", null),
            source("gilbert_gaillard", "Gilbert & Gaillard", "critic", "100", "gilbert\\s*&\\s*gaillard", "(?:gold|or|silver|argent|medal|m[ée]daille)"),
            source("gambero_rosso", "Gambero Rosso", "guide", null, "gambero\\s+rosso", "tre\\s+bicchieri|due\\s+bicchieri|bicchieri"),
            source("slow_wine", "Slow Wine", "guide", null, "slow\\s+wine", "escargot|great\\s+wine"),
            source("guia_penin", "Guía Peñín", "guide", "100", "gu[ií]a\\s+pe[nñ][ií]n|pe[nñ][ií]n", null),
            source("dwwe", "Decanter World Wine Awards", "competition", null, "decanter\\s+world\\s+wine\\s+awards|\\bdwwa\\b", "platinum|gold|silver|bronze|best\\s+in\\s+show"),
            source("iwc", "International Wine Challenge", "competition", null, "international\\s+wine\\s+challenge|\\biwc\\b", "gold|silver|bronze|commended"),
            source("iwsc", "International Wine & Spirit Competition", "competition", null, "international\\s+wine\\s*&\\s*spirit\\s+competition|\\biwsc\\b", "gold|silver|bronze"),
            source("cmb", "Concours Mondial de Bruxelles", "competition", null, "concours\\s+mondial\\s+de\\s+bruxelles|\\bcmb\\b", "grand\\s+gold|gold|silver|argent"),
            source("mundus_vini", "Mundus Vini", "competition", null, "mundus\\s+vini", "grand\\s+gold|gold|silver"),
            source("berliner_wine_trophy", "Berliner Wine Trophy", "competition", null, "berliner\\s+wine\\s+trophy", "gold|silver"),
            source("concours_general_agricole", "Concours Général Agricole", "competition", null, "concours\\s+g[ée]n[ée]ral\\s+agricole", "or|argent|bronze|gold|silver"),
            source("vinalies_internationales", "Vinalies Internationales", "competition", null, "vinalies\\s+internationales", "grand\\s+gold|gold|silver|argent"),
            source("challenge_international_du_vin", "Challenge International du Vin", "competition", null, "challenge\\s+international\\s+du\\s+vin", "gold|silver|bronze|or|argent"),
            source("michelin_grapes", "MICHELIN Grapes", "upcoming", null, "michelin\\s+grapes", "1\\s*grape|2\\s*grapes|3\\s*grapes|selected")
    );

    private MilleniumWineRatingsScraper() {}

    public static List<WineRating> scrape(String startUrl, int maxPages, double minDelay, double maxDelay,
                                          int timeoutSeconds, String userAgent) throws InterruptedException {
        return scrape(startUrl, maxPages, minDelay, maxDelay, timeoutSeconds, userAgent, null);
    }

    public static List<WineRating> scrape(String startUrl, int maxPages, double minDelay, double maxDelay,
                                          int timeoutSeconds, String userAgent, WinePageListener listener)
            throws InterruptedException {
        CliArgs cli = new CliArgs(startUrl, maxPages, minDelay, maxDelay, timeoutSeconds,
                "wine_ratings.csv", "wine_ratings.json", userAgent, "target/millesima-crawl.state.bin");
        return crawlAndExtract(cli, listener);
    }

    public static void main(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (cli.minDelay > cli.maxDelay) {
            System.err.println("Erreur: --min-delay doit être <= --max-delay");
            System.exit(2);
        }

        List<WineRating> ratings = crawlAndExtract(cli, null);
        writeOutputs(ratings, Path.of(cli.csvPath), Path.of(cli.jsonPath));

        System.out.println("Terminé. " + ratings.size() + " note(s) extraite(s).");
        System.out.println("CSV : " + cli.csvPath);
        System.out.println("JSON: " + cli.jsonPath);
    }

    private static List<WineRating> crawlAndExtract(CliArgs cli, WinePageListener listener) throws InterruptedException {
        URI start = URI.create(cli.startUrl);
        String domain = start.getHost();
        String normalizedStartUrl = normalizeUrl(cli.startUrl, cli.startUrl);
        String startUrl = normalizedStartUrl == null ? trimTrailingSlash(cli.startUrl) : normalizedStartUrl;
        Path statePath = Path.of(cli.statePath);
        CrawlState state = loadOrCreateState(statePath, startUrl, domain, cli.maxPages, isLikelyListingPath(start.getPath()));
        AtomicBoolean completed = new AtomicBoolean(false);
        Thread shutdownHook = new Thread(() -> {
            if (!completed.get()) {
                saveState(statePath, state);
                System.err.println("Etat du crawl sauvegarde dans " + statePath);
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            if (listener != null && state.resumedFromState) {
                replayScrapedPagesToListener(state, listener);
            }
            if (state.mode == CrawlMode.LISTING_START) {
                crawlFromListingStart(cli, domain, state, statePath, listener);
            } else {
                crawlWithGlobalPageCap(cli, domain, state, statePath, listener);
            }
            completed.set(true);
            deleteStateFile(statePath);
            return List.copyOf(state.ratings);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down.
            }
        }
    }

    private static void crawlWithGlobalPageCap(CliArgs cli, String domain, CrawlState state, Path statePath,
                                               WinePageListener listener)
            throws InterruptedException {
        Random random = new Random();

        while (!state.globalQueue.isEmpty() && state.seenGlobalUrls.size() < state.maxPages) {
            String url = state.globalQueue.poll();
            state.queuedGlobalUrls.remove(url);
            if (!state.seenGlobalUrls.add(url)) {
                continue;
            }

            System.err.printf("[%d/%d] Visite: %s%n", state.seenGlobalUrls.size(), state.maxPages, url);
            String html = fetchHtmlWithRandomDelay(url, cli, random);
            if (html == null) {
                saveState(statePath, state);
                continue;
            }

            ScrapedWinePage scrapedPage = extractScrapedWinePage(html, url);
            if (scrapedPage != null) {
                state.recordScrapedPage(scrapedPage);
                if (listener != null) {
                    listener.onWinePageScraped(scrapedPage.url(), scrapedPage.wineName(), scrapedPage.ratings());
                }
                if (!scrapedPage.ratings().isEmpty()) {
                    state.ratings.addAll(scrapedPage.ratings());
                    System.err.println("  -> " + scrapedPage.ratings().size() + " note(s) detectee(s)");
                }
            }

            for (String link : extractInternalLinks(html, url, domain)) {
                if (!state.seenGlobalUrls.contains(link) && state.queuedGlobalUrls.add(link)) {
                    state.globalQueue.add(link);
                }
            }
            saveState(statePath, state);
        }
    }

    private static void crawlFromListingStart(CliArgs cli, String domain, CrawlState state, Path statePath,
                                              WinePageListener listener)
            throws InterruptedException {
        Random random = new Random();

        while (!state.listingQueue.isEmpty() && state.seenListingUrls.size() < state.maxPages) {
            String listingUrl = state.listingQueue.poll();
            state.queuedListingUrls.remove(listingUrl);
            if (!state.seenListingUrls.add(listingUrl)) {
                continue;
            }

            System.err.printf("[listing %d/%d] Visite: %s%n", state.seenListingUrls.size(), state.maxPages, listingUrl);
            String listingHtml = fetchHtmlWithRandomDelay(listingUrl, cli, random);
            if (listingHtml == null) {
                saveState(statePath, state);
                continue;
            }

            for (String link : extractInternalLinks(listingHtml, listingUrl, domain)) {
                URI linkUri = URI.create(link);
                if (isLikelyListingPath(linkUri.getPath())) {
                    if (!state.seenListingUrls.contains(link) && state.queuedListingUrls.add(link)) {
                        state.listingQueue.add(link);
                    }
                    continue;
                }
                if (isLikelyWineDetailPath(linkUri.getPath()) && state.queuedWineUrls.add(link)) {
                    state.wineQueue.add(link);
                }
            }
            saveState(statePath, state);
        }

        while (!state.wineQueue.isEmpty()) {
            String wineUrl = state.wineQueue.poll();
            state.queuedWineUrls.remove(wineUrl);
            if (!state.seenWineUrls.add(wineUrl)) {
                continue;
            }

            System.err.printf("[wine %d] Visite: %s%n", state.seenWineUrls.size(), wineUrl);
            String wineHtml = fetchHtmlWithRandomDelay(wineUrl, cli, random);
            if (wineHtml == null) {
                saveState(statePath, state);
                continue;
            }

            ScrapedWinePage scrapedPage = extractScrapedWinePage(wineHtml, wineUrl);
            if (scrapedPage != null) {
                state.recordScrapedPage(scrapedPage);
                if (listener != null) {
                    listener.onWinePageScraped(scrapedPage.url(), scrapedPage.wineName(), scrapedPage.ratings());
                }
                if (!scrapedPage.ratings().isEmpty()) {
                    state.ratings.addAll(scrapedPage.ratings());
                    System.err.println("  -> " + scrapedPage.ratings().size() + " note(s) detectee(s)");
                }
            }

            for (String vintageUrl : extractWineVintageLinks(wineHtml, wineUrl, domain)) {
                if (!state.seenWineUrls.contains(vintageUrl) && state.queuedWineUrls.add(vintageUrl)) {
                    state.wineQueue.add(vintageUrl);
                }
            }
            saveState(statePath, state);
        }
    }

    private static String fetchHtmlWithRandomDelay(String url, CliArgs cli, Random random) throws InterruptedException {
        if (cli.maxDelay > 0 || cli.minDelay > 0) {
            double delay = cli.minDelay + random.nextDouble() * (cli.maxDelay - cli.minDelay);
            Thread.sleep((long) (delay * 1000));
        }
        try {
            return fetchHtml(url, cli.userAgent, cli.timeoutSeconds);
        } catch (Exception e) {
            System.err.println("  -> Erreur HTTP: " + e.getMessage());
            return null;
        }
    }

    private static ScrapedWinePage extractScrapedWinePage(String html, String pageUrl) {
        if (isLikelyListingPage(pageUrl, html)) {
            return null;
        }
        String title = findTitle(html);
        String text = stripTags(html);
        List<WineRating> pageRatings = new ArrayList<>();
        List<WineRating> criticSlideRatings = extractRatingsFromCriticSlides(html, pageUrl, title);
        if (!criticSlideRatings.isEmpty()) {
            pageRatings.addAll(criticSlideRatings);
        } else {
            pageRatings.addAll(extractRatingsFromJsonLd(html, pageUrl, title));
            pageRatings.addAll(extractRatingsBySource(text, pageUrl, title));
            if (pageRatings.isEmpty()) {
                pageRatings = extractRatingsFromText(text, pageUrl, title);
            }
        }
        return new ScrapedWinePage(pageUrl, title, dedupeRatings(pageRatings));
    }

    private static List<WineRating> extractRatingsFromWinePage(String html, String pageUrl) {
        ScrapedWinePage scraped = extractScrapedWinePage(html, pageUrl);
        if (scraped == null) {
            return List.of();
        }
        return scraped.ratings();
    }

    private static CrawlState loadOrCreateState(Path statePath, String startUrl, String domain, int maxPages, boolean listingStart) {
        if (Files.exists(statePath)) {
            CrawlState loaded = loadState(statePath);
            if (loaded != null && loaded.isCompatible(startUrl, domain, maxPages)) {
                loaded.resumedFromState = true;
                loaded.ensureInitialized();
                System.err.println("Reprise du crawl depuis l'etat: " + statePath);
                return loaded;
            }
            System.err.println("Etat existant incompatible, nouveau crawl initialise.");
        }

        CrawlState state = new CrawlState();
        state.startUrl = startUrl;
        state.domain = domain;
        state.maxPages = maxPages;
        state.mode = listingStart ? CrawlMode.LISTING_START : CrawlMode.GLOBAL;
        state.ensureInitialized();
        if (listingStart) {
            state.listingQueue.add(startUrl);
            state.queuedListingUrls.add(startUrl);
        } else {
            state.globalQueue.add(startUrl);
            state.queuedGlobalUrls.add(startUrl);
        }
        saveState(statePath, state);
        return state;
    }

    private static CrawlState loadState(Path statePath) {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(statePath))) {
            Object loaded = in.readObject();
            if (loaded instanceof CrawlState state) {
                state.ensureInitialized();
                return state;
            }
            return null;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Impossible de relire l'etat de crawl: " + e.getMessage());
            return null;
        }
    }

    private static void saveState(Path statePath, CrawlState state) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(statePath))) {
                out.writeObject(state);
            }
        } catch (IOException e) {
            System.err.println("Impossible de sauvegarder l'etat de crawl: " + e.getMessage());
        }
    }

    private static void deleteStateFile(Path statePath) {
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException e) {
            System.err.println("Impossible de supprimer l'etat de crawl: " + e.getMessage());
        }
    }

    private static void replayScrapedPagesToListener(CrawlState state, WinePageListener listener) {
        int replayed = 0;
        for (Map.Entry<String, ScrapedWinePageState> entry : state.scrapedPagesByUrl.entrySet()) {
            ScrapedWinePageState savedPage = entry.getValue();
            String wineName = savedPage == null ? "Titre inconnu" : savedPage.wineName;
            List<WineRating> ratings = savedPage == null || savedPage.ratings == null
                    ? List.of()
                    : List.copyOf(savedPage.ratings);
            listener.onWinePageScraped(entry.getKey(), wineName, ratings);
            replayed++;
        }
        if (replayed > 0) {
            System.err.println("Reconciliation BDD: " + replayed + " page(s) vin rejouee(s) depuis l'etat.");
        }
    }

    private static List<WineRating> extractRatingsBySource(String text, String url, String title) {
        List<WineRating> found = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        for (SourcePattern source : SOURCE_PATTERNS) {
            Matcher sourceMatcher = source.matcher.matcher(text);
            while (sourceMatcher.find()) {
                String window = extractWindow(text, sourceMatcher.start(), sourceMatcher.end(), 180);
                RatingScore score = findScore(window, source.defaultScale);
                String distinction = findDistinction(window, source.distinctionPattern);

                if (score == null && (distinction == null || distinction.isBlank())) {
                    continue;
                }

                String ratingValue = score == null ? "" : score.value();
                String ratingScale = score == null ? "" : score.scale();
                String key = source.key + "|" + ratingValue + "|" + ratingScale + "|" + Objects.toString(distinction, "");
                if (dedupe.add(key)) {
                    found.add(new WineRating(url, title, source.key, source.label, source.type,
                            ratingValue, ratingScale, Objects.toString(distinction, ""), "source-pattern"));
                }
            }
        }
        return found;
    }

    private static RatingScore findScore(String text, String defaultScale) {
        Matcher withScale = RATING_WITH_SCALE_RE.matcher(text);
        if (withScale.find()) {
            String value = withScale.group(1).replace(',', '.');
            String scale = Objects.toString(withScale.group(2), Objects.toString(withScale.group(3), defaultScale));
            return new RatingScore(value, scale == null ? "" : scale);
        }

        Matcher outOf100 = Pattern.compile("\\b(\\d{2,3}(?:[\\.,]\\d+)?)\\b").matcher(text);
        while (outOf100.find()) {
            String value = outOf100.group(1).replace(',', '.');
            try {
                double parsed = Double.parseDouble(value);
                if (parsed >= 50 && parsed <= 100) {
                    return new RatingScore(value, defaultScale == null ? "100" : defaultScale);
                }
                if (parsed >= 10 && parsed <= 20 && "20".equals(defaultScale)) {
                    return new RatingScore(value, "20");
                }
            } catch (NumberFormatException ignored) {
                // ignore parse issue
            }
        }
        return null;
    }

    private static String findDistinction(String text, Pattern distinctionPattern) {
        if (distinctionPattern == null) {
            return "";
        }
        Matcher matcher = distinctionPattern.matcher(text);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return "";
    }

    private static String extractWindow(String text, int start, int end, int radius) {
        int from = Math.max(0, start - radius);
        int to = Math.min(text.length(), end + radius);
        return text.substring(from, to);
    }

    private static String fetchHtml(String url, String userAgent, int timeoutSeconds) throws IOException, InterruptedException {
        HttpClient client = newHttpClient(timeoutSeconds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static HttpClient newHttpClient(int timeoutSeconds) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds));
        if (isInsecureSslEnabled()) {
            builder.sslContext(buildInsecureSslContext());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(sslParameters);
        }
        return builder.build();
    }

    private static boolean isInsecureSslEnabled() {
        if (Boolean.parseBoolean(System.getProperty(INSECURE_SSL_PROPERTY, "false"))) {
            return true;
        }
        return Boolean.parseBoolean(System.getenv("RATEMYWINE_SCRAPER_INSECURE_SSL"));
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
                            // Intentionally trust-all for opt-in troubleshooting mode.
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                            // Intentionally trust-all for opt-in troubleshooting mode.
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Impossible d'initialiser le mode SSL insecure", e);
        }
    }

    private static String findTitle(String html) {
        Matcher titleMatch = TITLE_RE.matcher(html);
        if (titleMatch.find()) {
            String t = normalizeWineTitle(stripTags(titleMatch.group(1)));
            if (!t.isBlank()) {
                return t;
            }
        }
        Matcher h1Match = H1_RE.matcher(html);
        if (h1Match.find()) {
            String h = normalizeWineTitle(stripTags(h1Match.group(1)));
            if (!h.isBlank()) {
                return h;
            }
        }
        return "Titre inconnu";
    }

    private static String normalizeWineTitle(String rawTitle) {
        return rawTitle.replaceAll("\\s+-\\s+Millesima\\.[^\\s]+$", "").trim();
    }

    private static List<WineRating> extractRatingsFromCriticSlides(String html, String url, String title) {
        List<WineRating> found = new ArrayList<>();
        Matcher slideMatcher = CRITIC_SLIDE_RE.matcher(html);
        while (slideMatcher.find()) {
            String sourceName = stripTags(slideMatcher.group(1));
            String ratingRaw = stripTags(slideMatcher.group(2));
            if (sourceName.isBlank() || ratingRaw.isBlank()) {
                continue;
            }
            RatingScore score = parseCriticScore(ratingRaw);
            if (score == null) {
                continue;
            }
            found.add(new WineRating(
                    url,
                    title,
                    criticKeyFromName(sourceName),
                    sourceName,
                    "critic",
                    score.value(),
                    score.scale(),
                    "",
                    "critic-slide"
            ));
        }
        return found;
    }

    private static RatingScore parseCriticScore(String ratingRaw) {
        Matcher scoreMatcher = CRITIC_SCORE_RE.matcher(ratingRaw);
        if (!scoreMatcher.find()) {
            return null;
        }
        String value = scoreMatcher.group(1).replace(',', '.');
        String scale = scoreMatcher.group(2);
        return new RatingScore(value, scale);
    }

    private static String criticKeyFromName(String sourceName) {
        String normalized = sourceName.toLowerCase(Locale.ROOT).replace("&amp;", "&");
        if (normalized.contains("parker")) {
            return "wine_advocate";
        }
        if (normalized.contains("robinson")) {
            return "jancis_robinson";
        }
        if (normalized.contains("wine spectator")) {
            return "wine_spectator";
        }
        if (normalized.contains("suckling")) {
            return "james_suckling";
        }
        if (normalized.contains("decanter")) {
            return "decanter";
        }
        if (normalized.contains("vinous") && normalized.contains("neal")) {
            return "vinous_neal_martin";
        }
        if (normalized.contains("vinous") && normalized.contains("galloni")) {
            return "vinous_antonio_galloni";
        }
        if (normalized.contains("the wine independent")) {
            return "the_wine_independent";
        }
        if (normalized.contains("figaro")) {
            return "figaro";
        }
        return normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static List<WineRating> extractRatingsFromJsonLd(String html, String url, String title) {
        List<WineRating> results = new ArrayList<>();
        Matcher scriptMatcher = SCRIPT_JSONLD_RE.matcher(html);
        while (scriptMatcher.find()) {
            String scriptBody = scriptMatcher.group(1);
            Matcher val = JSON_RATING_VALUE_RE.matcher(scriptBody);
            if (!val.find()) {
                continue;
            }
            String ratingValue = val.group(1).replace(',', '.');

            Matcher best = JSON_BEST_RATING_RE.matcher(scriptBody);
            String bestRating = best.find() ? best.group(1) : "";
            if (!"20".equals(bestRating) && !"100".equals(bestRating)) {
                continue;
            }

            Matcher name = JSON_NAME_RE.matcher(scriptBody);
            String wineName = name.find() ? name.group(1) : title;

            results.add(new WineRating(url, wineName, "generic_jsonld", "JSON-LD aggregate", "generic",
                    ratingValue, bestRating, "", "json-ld"));
        }
        return results;
    }

    private static List<WineRating> extractRatingsFromText(String text, String url, String title) {
        List<WineRating> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = RATING_WITH_SCALE_RE.matcher(text);
        while (m.find()) {
            String value = m.group(1).replace(',', '.');
            String scale = Objects.toString(m.group(2), Objects.toString(m.group(3), ""));
            String key = value + ":" + scale;
            if (seen.add(key)) {
                found.add(new WineRating(url, title, "generic_regex", "Regex text score", "generic",
                        value, scale, "", "regex-text"));
            }
        }
        return found;
    }

    private static List<WineRating> dedupeRatings(List<WineRating> ratings) {
        List<WineRating> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WineRating rating : ratings) {
            String key = rating.sourceKey() + "|" + rating.ratingValue() + "|" + rating.ratingScale() + "|" + rating.distinction();
            if (seen.add(key)) {
                deduped.add(rating);
            }
        }
        return deduped;
    }

    private static Set<String> extractInternalLinks(String html, String baseUrl, String domain) {
        Set<String> links = new LinkedHashSet<>();
        if (!isLikelyListingPage(baseUrl, html)) {
            if (isLikelyWineDetailPath(URI.create(baseUrl).getPath())) {
                links.addAll(extractWineVintageLinks(html, baseUrl, domain));
            }
            return links;
        }

        for (String href : extractPaginationLinks(html)) {
            String normalized = normalizeUrl(baseUrl, href);
            if (normalized == null) {
                continue;
            }
            URI uri = URI.create(normalized);
            if (uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(domain)
                    && isLikelyListingPath(uri.getPath())) {
                links.add(normalized);
            }
        }
        for (String href : extractListingWineLinks(html)) {
            String normalized = normalizeUrl(baseUrl, href);
            if (normalized == null) {
                continue;
            }
            URI uri = URI.create(normalized);
            if (uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(domain)
                    && isLikelyWineDetailPath(uri.getPath())) {
                links.add(normalized);
            }
        }
        if (!links.isEmpty()) {
            return links;
        }

        Matcher m = HREF_RE.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String normalized = normalizeUrl(baseUrl, href);
            if (normalized == null) {
                continue;
            }
            URI uri = URI.create(normalized);
            if (uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(domain)
                    && (isLikelyWineDetailPath(uri.getPath()) || isLikelyListingPath(uri.getPath()))) {
                links.add(normalized);
            }
        }
        return links;
    }

    private static Set<String> extractWineVintageLinks(String html, String baseUrl, String domain) {
        Set<String> links = new LinkedHashSet<>();
        URI baseUri = URI.create(baseUrl);
        String basePath = baseUri.getPath();
        String wineFamily = extractWineFamily(basePath);
        if (wineFamily == null) {
            return links;
        }

        links.addAll(extractVintageLinksFromVintageBox(html, baseUrl, domain, wineFamily));
        if (!links.isEmpty()) {
            return links;
        }

        String decodedHtml = decodeUrlToken(html);
        Pattern familyVintagePathRe = Pattern.compile(
                "(?i)(?:https?://[^\"'\\s<>]+/|/)?"
                        + Pattern.quote(wineFamily)
                        + "-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html");
        Matcher matcher = familyVintagePathRe.matcher(decodedHtml);
        while (matcher.find()) {
            String token = matcher.group();
            if (!token.startsWith("http://") && !token.startsWith("https://") && !token.startsWith("/")) {
                token = "/" + token;
            }
            String normalized = normalizeUrl(baseUrl, token);
            if (normalized == null) {
                continue;
            }
            URI uri = URI.create(normalized);
            if (uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(domain)
                    && isLikelyWineDetailPath(uri.getPath())
                    && isSameWineFamily(uri.getPath(), wineFamily)) {
                links.add(normalized);
            }
        }
        return links;
    }

    private static Set<String> extractVintageLinksFromVintageBox(String html, String baseUrl, String domain, String wineFamily) {
        Set<String> links = new LinkedHashSet<>();
        String decodedHtml = decodeUrlToken(html);
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
            Matcher sectionLinks = VINTAGE_SECTION_LINK_RE.matcher(sectionHtml);
            while (sectionLinks.find()) {
                String year = firstNonBlank(sectionLinks.group(1), sectionLinks.group(4));
                String href = firstNonBlank(sectionLinks.group(2), sectionLinks.group(3));
                if (year == null || href == null) {
                    continue;
                }
                String normalized = normalizeUrl(baseUrl, href);
                if (normalized == null) {
                    continue;
                }
                URI uri = URI.create(normalized);
                if (uri.getHost() != null
                        && uri.getHost().equalsIgnoreCase(domain)
                        && isLikelyWineDetailPath(uri.getPath())
                        && isSameWineFamily(uri.getPath(), wineFamily)
                        && uri.getPath().contains("-" + year)) {
                    links.add(normalized);
                }
            }
            searchFrom = sectionEnd;
        }
        return links;
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

    private static String extractWineFamily(String path) {
        if (!isLikelyWineDetailPath(path)) {
            return null;
        }
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        Matcher matcher = Pattern.compile("^(.+)-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html$", Pattern.CASE_INSENSITIVE)
                .matcher(normalizedPath);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    private static boolean isSameWineFamily(String path, String family) {
        if (path == null || family == null) {
            return false;
        }
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return normalizedPath.toLowerCase(Locale.ROOT)
                .matches(Pattern.quote(family.toLowerCase(Locale.ROOT)) + "-(?:19|20)\\d{2}(?:-[a-z0-9]+)*\\.html");
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

    private static boolean isLikelyListingPage(String url, String html) {
        URI uri = URI.create(url);
        if (isLikelyListingPath(uri.getPath())) {
            return true;
        }
        String lower = html.toLowerCase(Locale.ROOT);
        return lower.contains("name=\"type\" content=\"listing\"")
                || lower.contains("name=\"parentgroupidentifier\" content=\"tous-nos-vins\"");
    }

    private static boolean isLikelyListingPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.toLowerCase(Locale.ROOT).endsWith("/tous-nos-vins.html");
    }

    private static Set<String> extractPaginationLinks(String html) {
        Set<String> links = new LinkedHashSet<>();
        Matcher matcher = REL_NEXT_LINK_RE.matcher(html);
        while (matcher.find()) {
            String href = firstNonBlank(matcher.group(1), matcher.group(2));
            if (href != null) {
                links.add(href);
            }
        }
        return links;
    }

    private static Set<String> extractListingWineLinks(String html) {
        Set<String> links = new LinkedHashSet<>();
        Matcher encoded = LISTING_ENCODED_WINE_URL_RE.matcher(html);
        while (encoded.find()) {
            links.add(encoded.group(1));
        }
        Matcher direct = LISTING_DIRECT_WINE_URL_RE.matcher(html);
        while (direct.find()) {
            links.add(direct.group(1));
        }
        return links;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isLikelyWineDetailPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return WINE_DETAIL_PATH_RE.matcher(path).matches();
    }

    private static String normalizeUrl(String base, String href) {
        try {
            URI absolute = URI.create(base).resolve(href);
            if (absolute.getScheme() == null) {
                return null;
            }
            String scheme = absolute.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            boolean wineDetailPath = isLikelyWineDetailPath(absolute.getPath());
            String normalizedPath = wineDetailPath ? canonicalizeWineDetailPath(absolute.getPath()) : absolute.getPath();
            String query = wineDetailPath ? null : absolute.getQuery();
            URI cleaned = new URI(absolute.getScheme(), absolute.getAuthority(), normalizedPath, query, null);
            return trimTrailingSlash(cleaned.toString());
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    private static String canonicalizeWineDetailPath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        Matcher matcher = WINE_DETAIL_CANONICAL_PATH_RE.matcher(normalizedPath);
        if (!matcher.matches()) {
            return path;
        }
        return "/" + matcher.group(1) + ".html";
    }

    private static String stripTags(String text) {
        String withoutTags = TAG_RE.matcher(text).replaceAll(" ");
        return withoutTags.replaceAll("\\s+", " ").trim();
    }

    private static void writeOutputs(List<WineRating> ratings, Path csvPath, Path jsonPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            writer.write("url,wine_name,source_key,source_name,source_type,rating_value,rating_scale,distinction,extraction_source\\n");
            for (WineRating row : ratings) {
                writer.write(csvEscape(row.url) + ","
                        + csvEscape(row.wineName) + ","
                        + csvEscape(row.sourceKey) + ","
                        + csvEscape(row.sourceName) + ","
                        + csvEscape(row.sourceType) + ","
                        + csvEscape(row.ratingValue) + ","
                        + csvEscape(row.ratingScale) + ","
                        + csvEscape(row.distinction) + ","
                        + csvEscape(row.extractionSource) + "\\n");
            }
        }

        try (OutputStream out = Files.newOutputStream(jsonPath)) {
            StringBuilder sb = new StringBuilder("[\\n");
            for (int i = 0; i < ratings.size(); i++) {
                WineRating r = ratings.get(i);
                sb.append("  {\\\"url\\\":\\\"").append(jsonEscape(r.url))
                        .append("\\\",\\\"wine_name\\\":\\\"").append(jsonEscape(r.wineName))
                        .append("\\\",\\\"source_key\\\":\\\"").append(jsonEscape(r.sourceKey))
                        .append("\\\",\\\"source_name\\\":\\\"").append(jsonEscape(r.sourceName))
                        .append("\\\",\\\"source_type\\\":\\\"").append(jsonEscape(r.sourceType))
                        .append("\\\",\\\"rating_value\\\":\\\"").append(jsonEscape(r.ratingValue))
                        .append("\\\",\\\"rating_scale\\\":\\\"").append(jsonEscape(r.ratingScale))
                        .append("\\\",\\\"distinction\\\":\\\"").append(jsonEscape(r.distinction))
                        .append("\\\",\\\"extraction_source\\\":\\\"").append(jsonEscape(r.extractionSource)).append("\\\"}");
                if (i < ratings.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("]\\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String csvEscape(String raw) {
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }

    private static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record WineRating(
            String url,
            String wineName,
            String sourceKey,
            String sourceName,
            String sourceType,
            String ratingValue,
            String ratingScale,
            String distinction,
            String extractionSource
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public interface WinePageListener {
        void onWinePageScraped(String pageUrl, String wineName, List<WineRating> pageRatings);
    }

    private record ScrapedWinePage(String url, String wineName, List<WineRating> ratings) {}

    private static final class ScrapedWinePageState implements Serializable {
        private static final long serialVersionUID = 1L;

        private String wineName;
        private List<WineRating> ratings;

        private ScrapedWinePageState() {
            // For Java serialization
        }

        private ScrapedWinePageState(String wineName, List<WineRating> ratings) {
            this.wineName = wineName;
            this.ratings = ratings == null ? List.of() : new ArrayList<>(ratings);
        }
    }

    private record RatingScore(String value, String scale) {}

    private record SourcePattern(String key, String label, String type, String defaultScale,
                                 Pattern matcher, Pattern distinctionPattern) {}

    private static SourcePattern source(String key, String label, String type, String defaultScale,
                                        String sourceRegex, String distinctionRegex) {
        Pattern matcher = Pattern.compile(sourceRegex, Pattern.CASE_INSENSITIVE);
        Pattern distinction = distinctionRegex == null ? null : Pattern.compile(distinctionRegex, Pattern.CASE_INSENSITIVE);
        return new SourcePattern(key, label, type, defaultScale, matcher, distinction);
    }

    private enum CrawlMode {
        LISTING_START,
        GLOBAL
    }

    private static final class CrawlState implements Serializable {
        private static final long serialVersionUID = 1L;

        private CrawlMode mode;
        private String startUrl;
        private String domain;
        private int maxPages;
        private boolean resumedFromState;

        private final Queue<String> globalQueue = new ArrayDeque<>();
        private final Set<String> seenGlobalUrls = new HashSet<>();
        private final Set<String> queuedGlobalUrls = new HashSet<>();

        private final Queue<String> listingQueue = new ArrayDeque<>();
        private final Set<String> seenListingUrls = new HashSet<>();
        private final Set<String> queuedListingUrls = new HashSet<>();

        private final Queue<String> wineQueue = new ArrayDeque<>();
        private final Set<String> seenWineUrls = new HashSet<>();
        private final Set<String> queuedWineUrls = new HashSet<>();

        private final List<WineRating> ratings = new ArrayList<>();
        private Map<String, ScrapedWinePageState> scrapedPagesByUrl = new java.util.LinkedHashMap<>();

        private void recordScrapedPage(ScrapedWinePage page) {
            ensureInitialized();
            scrapedPagesByUrl.put(page.url(), new ScrapedWinePageState(page.wineName(), page.ratings()));
        }

        private boolean isCompatible(String expectedStartUrl, String expectedDomain, int expectedMaxPages) {
            return Objects.equals(startUrl, expectedStartUrl)
                    && Objects.equals(domain, expectedDomain)
                    && maxPages == expectedMaxPages;
        }

        private void ensureInitialized() {
            if (scrapedPagesByUrl == null) {
                scrapedPagesByUrl = new java.util.LinkedHashMap<>();
            }
        }
    }

    private static final class CliArgs {
        private final String startUrl;
        private final int maxPages;
        private final double minDelay;
        private final double maxDelay;
        private final int timeoutSeconds;
        private final String csvPath;
        private final String jsonPath;
        private final String userAgent;
        private final String statePath;

        private CliArgs(String startUrl, int maxPages, double minDelay, double maxDelay, int timeoutSeconds,
                        String csvPath, String jsonPath, String userAgent, String statePath) {
            this.startUrl = startUrl;
            this.maxPages = maxPages;
            this.minDelay = minDelay;
            this.maxDelay = maxDelay;
            this.timeoutSeconds = timeoutSeconds;
            this.csvPath = csvPath;
            this.jsonPath = jsonPath;
            this.userAgent = userAgent;
            this.statePath = statePath;
        }

        private static CliArgs parse(String[] args) {
            boolean hasStartUrl = args.length > 0 && !args[0].startsWith("--");
            String startUrl = hasStartUrl ? args[0] : DEFAULT_MILLESIMA_START_URL;
            Map<String, String> options = ArgParser.toMap(args, hasStartUrl ? 1 : 0);

            return new CliArgs(
                    startUrl,
                    Integer.parseInt(options.getOrDefault("--max-pages", "200")),
                    Double.parseDouble(options.getOrDefault("--min-delay", "0.5")),
                    Double.parseDouble(options.getOrDefault("--max-delay", "2.0")),
                    Integer.parseInt(options.getOrDefault("--timeout", "20")),
                    options.getOrDefault("--csv", "wine_ratings.csv"),
                    options.getOrDefault("--json", "wine_ratings.json"),
                    options.getOrDefault("--user-agent", "Mozilla/5.0 (compatible; WineRatingsBot/1.0)"),
                    options.getOrDefault("--state-file", "target/millesima-crawl.state.bin")
            );
        }
    }

    private static final class ArgParser {
        private ArgParser() {}

        private static Map<String, String> toMap(String[] args, int from) {
            java.util.HashMap<String, String> map = new java.util.HashMap<>();
            for (int i = from; i < args.length; i++) {
                String key = args[i];
                if (!key.startsWith("--") || i + 1 >= args.length) {
                    continue;
                }
                map.put(key, args[i + 1]);
                i++;
            }
            return map;
        }
    }
}


