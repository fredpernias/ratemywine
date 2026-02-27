package com.ratemywine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MilleniumWineRatingsScraper {
    private static final Pattern RATING_WITH_SCALE_RE = Pattern.compile(
            "(?:(?:note|notation|rating|score)\\s*[:\\-]?\\s*)?(\\d{1,3}(?:[\\.,]\\d)?)\\s*(?:/\\s*(20|100)|sur\\s*(20|100))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_RE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern H1_RE = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_JSONLD_RE = Pattern.compile(
            "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern HREF_RE = Pattern.compile("<a[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
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
        CliArgs cli = new CliArgs(startUrl, maxPages, minDelay, maxDelay, timeoutSeconds,
                Path.of("wine_ratings.csv"), Path.of("wine_ratings.json"), userAgent);
        return crawlAndExtract(cli);
    }

    public static void main(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (cli.minDelay > cli.maxDelay) {
            System.err.println("Erreur: --min-delay doit être <= --max-delay");
            System.exit(2);
        }

        List<WineRating> ratings = crawlAndExtract(cli);
        writeOutputs(ratings, Path.of(cli.csvPath), Path.of(cli.jsonPath));

        System.out.println("Terminé. " + ratings.size() + " note(s) extraite(s).");
        System.out.println("CSV : " + cli.csvPath);
        System.out.println("JSON: " + cli.jsonPath);
    }

    private static List<WineRating> crawlAndExtract(CliArgs cli) throws InterruptedException {
        URI start = URI.create(cli.startUrl);
        String domain = start.getHost();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(trimTrailingSlash(cli.startUrl));
        Set<String> seenUrls = new HashSet<>();
        List<WineRating> ratings = new ArrayList<>();
        Random random = new Random();

        while (!queue.isEmpty() && seenUrls.size() < cli.maxPages) {
            String url = queue.poll();
            if (!seenUrls.add(url)) {
                continue;
            }

            System.err.printf("[%d/%d] Visite: %s%n", seenUrls.size(), cli.maxPages, url);
            String html;
            try {
                html = fetchHtml(url, cli.userAgent, cli.timeoutSeconds);
            } catch (Exception e) {
                System.err.println("  -> Erreur HTTP: " + e.getMessage());
                continue;
            }

            String title = findTitle(html);
            String text = stripTags(html);
            List<WineRating> pageRatings = new ArrayList<>();
            pageRatings.addAll(extractRatingsFromJsonLd(html, url, title));
            pageRatings.addAll(extractRatingsBySource(text, url, title));
            if (pageRatings.isEmpty()) {
                pageRatings = extractRatingsFromText(text, url, title);
            }

            if (!pageRatings.isEmpty()) {
                ratings.addAll(pageRatings);
                System.err.println("  -> " + pageRatings.size() + " note(s) détectée(s)");
            }

            for (String link : extractInternalLinks(html, url, domain)) {
                if (!seenUrls.contains(link) && !queue.contains(link)) {
                    queue.add(link);
                }
            }

            double delay = cli.minDelay + random.nextDouble() * (cli.maxDelay - cli.minDelay);
            Thread.sleep((long) (delay * 1000));
        }
        return ratings;
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
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String findTitle(String html) {
        Matcher titleMatch = TITLE_RE.matcher(html);
        if (titleMatch.find()) {
            String t = stripTags(titleMatch.group(1));
            if (!t.isBlank()) {
                return t;
            }
        }
        Matcher h1Match = H1_RE.matcher(html);
        if (h1Match.find()) {
            String h = stripTags(h1Match.group(1));
            if (!h.isBlank()) {
                return h;
            }
        }
        return "Titre inconnu";
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

    private static Set<String> extractInternalLinks(String html, String baseUrl, String domain) {
        Set<String> links = new LinkedHashSet<>();
        Matcher m = HREF_RE.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String normalized = normalizeUrl(baseUrl, href);
            if (normalized == null) {
                continue;
            }
            URI uri = URI.create(normalized);
            if (uri.getHost() != null && uri.getHost().equalsIgnoreCase(domain)) {
                links.add(normalized);
            }
        }
        return links;
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
            URI cleaned = new URI(absolute.getScheme(), absolute.getAuthority(), absolute.getPath(), absolute.getQuery(), null);
            return trimTrailingSlash(cleaned.toString());
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
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
                sb.append('\\n');
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
    ) {}

    private record RatingScore(String value, String scale) {}

    private record SourcePattern(String key, String label, String type, String defaultScale,
                                 Pattern matcher, Pattern distinctionPattern) {}

    private static SourcePattern source(String key, String label, String type, String defaultScale,
                                        String sourceRegex, String distinctionRegex) {
        Pattern matcher = Pattern.compile(sourceRegex, Pattern.CASE_INSENSITIVE);
        Pattern distinction = distinctionRegex == null ? null : Pattern.compile(distinctionRegex, Pattern.CASE_INSENSITIVE);
        return new SourcePattern(key, label, type, defaultScale, matcher, distinction);
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

        private CliArgs(String startUrl, int maxPages, double minDelay, double maxDelay, int timeoutSeconds,
                        String csvPath, String jsonPath, String userAgent) {
            this.startUrl = startUrl;
            this.maxPages = maxPages;
            this.minDelay = minDelay;
            this.maxDelay = maxDelay;
            this.timeoutSeconds = timeoutSeconds;
            this.csvPath = csvPath;
            this.jsonPath = jsonPath;
            this.userAgent = userAgent;
        }

        private static CliArgs parse(String[] args) {
            if (args.length < 1) {
                throw new IllegalArgumentException("Usage: MilleniumWineRatingsScraper <start_url> [options]");
            }
            String startUrl = args[0];
            Map<String, String> options = ArgParser.toMap(args, 1);

            return new CliArgs(
                    startUrl,
                    Integer.parseInt(options.getOrDefault("--max-pages", "200")),
                    Double.parseDouble(options.getOrDefault("--min-delay", "1.0")),
                    Double.parseDouble(options.getOrDefault("--max-delay", "2.0")),
                    Integer.parseInt(options.getOrDefault("--timeout", "20")),
                    options.getOrDefault("--csv", "wine_ratings.csv"),
                    options.getOrDefault("--json", "wine_ratings.json"),
                    options.getOrDefault("--user-agent", "Mozilla/5.0 (compatible; WineRatingsBot/1.0)")
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
