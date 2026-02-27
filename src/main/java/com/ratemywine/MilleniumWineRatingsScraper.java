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
            "(?:(?:note|notation|rating|score)\\s*[:\\-]?\\s*)?(\\d{1,2}(?:[\\.,]\\d)?)\\s*(?:/\\s*(20|100)|sur\\s*(20|100))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_RE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern H1_RE = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_JSONLD_RE = Pattern.compile(
            "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern HREF_RE = Pattern.compile("<a[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_RATING_VALUE_RE = Pattern.compile("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:[.,][0-9]+)?)\"?");
    private static final Pattern JSON_BEST_RATING_RE = Pattern.compile("\"bestRating\"\\s*:\\s*\"?([0-9]+)\"?");
    private static final Pattern JSON_NAME_RE = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private MilleniumWineRatingsScraper() {}

    public static void main(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (cli.minDelay > cli.maxDelay) {
            System.err.println("Erreur: --min-delay doit être <= --max-delay");
            System.exit(2);
        }

        List<WineRating> ratings = crawlAndExtract(cli);
        writeOutputs(ratings, Path.of(cli.csvPath), Path.of(cli.jsonPath));

        System.out.println("Terminé. " + ratings.size() + " notes extraites.");
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
            List<WineRating> pageRatings = extractRatingsFromJsonLd(html, url, title);
            if (pageRatings.isEmpty()) {
                pageRatings = extractRatingsFromText(html, url, title);
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
            String bestRating = best.find() ? best.group(1) : "?";

            Matcher name = JSON_NAME_RE.matcher(scriptBody);
            String wineName = name.find() ? name.group(1) : title;

            results.add(new WineRating(url, wineName, ratingValue, bestRating, "json-ld"));
        }
        return results;
    }

    private static List<WineRating> extractRatingsFromText(String html, String url, String title) {
        String text = stripTags(html);
        List<WineRating> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = RATING_WITH_SCALE_RE.matcher(text);
        while (m.find()) {
            String value = m.group(1).replace(',', '.');
            String scale = Objects.toString(m.group(2), Objects.toString(m.group(3), "?"));
            String key = value + ":" + scale;
            if (seen.add(key)) {
                found.add(new WineRating(url, title, value, scale, "regex-text"));
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
            writer.write("url,wine_name,rating_value,rating_scale,source\n");
            for (WineRating row : ratings) {
                writer.write(csvEscape(row.url) + ","
                        + csvEscape(row.wineName) + ","
                        + csvEscape(row.ratingValue) + ","
                        + csvEscape(row.ratingScale) + ","
                        + csvEscape(row.source) + "\n");
            }
        }

        try (OutputStream out = Files.newOutputStream(jsonPath)) {
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < ratings.size(); i++) {
                WineRating r = ratings.get(i);
                sb.append("  {\"url\":\"").append(jsonEscape(r.url))
                        .append("\",\"wine_name\":\"").append(jsonEscape(r.wineName))
                        .append("\",\"rating_value\":\"").append(jsonEscape(r.ratingValue))
                        .append("\",\"rating_scale\":\"").append(jsonEscape(r.ratingScale))
                        .append("\",\"source\":\"").append(jsonEscape(r.source)).append("\"}");
                if (i < ratings.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("]\n");
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

    private record WineRating(String url, String wineName, String ratingValue, String ratingScale, String source) {}

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
