package com.ratemywine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CollectRatings {
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final Map<String, Pattern> RATING_PATTERNS = Map.of(
            "robert_parker", Pattern.compile("(?:Robert\\s+Parker|Parker)[^\\d]{0,25}(\\d{2,3}(?:[\\.,]\\d+)?(?:\\s*/\\s*\\d{2,3})?)", Pattern.CASE_INSENSITIVE),
            "hachette", Pattern.compile("(?:Guide\\s+Hachette|Hachette)[^\\d]{0,25}(\\d{1,2}(?:[\\.,]\\d+)?(?:\\s*/\\s*\\d{1,2})?)", Pattern.CASE_INSENSITIVE),
            "wine_spectator", Pattern.compile("(?:Wine\\s+Spectator)[^\\d]{0,25}(\\d{2,3}(?:[\\.,]\\d+)?(?:\\s*/\\s*\\d{2,3})?)", Pattern.CASE_INSENSITIVE),
            "jancis_robinson", Pattern.compile("(?:Jancis\\s+Robinson)[^\\d]{0,25}(\\d{1,2}(?:[\\.,]\\d+)?(?:\\s*/\\s*\\d{2})?)", Pattern.CASE_INSENSITIVE)
    );

    private CollectRatings() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = ArgParser.toMap(args, 0);
        String input = required(cli, "--input");
        String output = required(cli, "--output");
        int maxResults = Integer.parseInt(cli.getOrDefault("--max-results", "5"));
        int timeout = Integer.parseInt(cli.getOrDefault("--timeout", "15"));
        double sleep = Double.parseDouble(cli.getOrDefault("--sleep", "1.2"));

        List<WineQuery> wines = readWines(Path.of(input));
        List<Map<String, String>> rows = new ArrayList<>();

        for (WineQuery wine : wines) {
            Map<String, String> result = scrapeWineRatings(wine, timeout, maxResults, sleep);
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            row.put("domaine", wine.domaine);
            row.put("appellation", wine.appellation);
            row.put("millesime", wine.millesime);
            row.putAll(result);
            rows.add(row);
        }

        writeOutput(Path.of(output), rows);
    }

    private static String required(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Paramètre requis: " + key);
        }
        return value;
    }

    private static String fetchHtml(String url, int timeout) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeout)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(timeout))
                .GET().build();
        HttpResponse<byte[]> response = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static List<String> duckduckgoLinks(String query, int timeout, int maxResults) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://duckduckgo.com/html/?q=" + encoded;
        try {
            String html = fetchHtml(url, timeout);
            return extractLinksFromDdg(html, maxResults);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> extractLinksFromDdg(String html, int maxResults) {
        Pattern pattern = Pattern.compile("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        List<String> links = new ArrayList<>();
        Matcher m = pattern.matcher(html);
        while (m.find() && links.size() < maxResults) {
            String href = m.group(1).replace("&amp;", "&");
            if (href.contains("millesima.fr")) {
                links.add(href);
            }
        }
        return links;
    }

    private static String htmlToText(String html) {
        String cleaned = html.replaceAll("(?is)<script[\\s\\S]*?</script>", " ");
        cleaned = cleaned.replaceAll("(?is)<style[\\s\\S]*?</style>", " ");
        cleaned = cleaned.replaceAll("<[^>]+>", " ");
        cleaned = cleaned.replace("&nbsp;", " ").replace("&amp;", "&");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> extractRatingsFromText(String text) {
        Map<String, String> ratings = new LinkedHashMap<>();
        for (Map.Entry<String, Pattern> e : RATING_PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            if (m.find()) {
                ratings.put(e.getKey(), m.group(1).replace(" ", ""));
            }
        }
        return ratings;
    }

    private static Map<String, String> scrapeWineRatings(WineQuery wine, int timeout, int maxResults, double sleepSeconds) throws InterruptedException {
        String query = "site:millesima.fr " + wine.domaine + " " + wine.appellation + " " + wine.millesime;
        List<String> candidates = duckduckgoLinks(query.trim(), timeout, maxResults);
        if (candidates.isEmpty()) {
            return Map.of("best_url", "", "ratings_json", "{}", "status", "no-candidate-page");
        }

        for (String url : candidates) {
            try {
                String html = fetchHtml(url, timeout);
                String text = htmlToText(html);
                Map<String, String> ratings = extractRatingsFromText(text);
                if (!ratings.isEmpty()) {
                    return Map.of(
                            "best_url", url,
                            "ratings_json", toCompactJson(ratings),
                            "status", "ok"
                    );
                }
                Thread.sleep((long) (sleepSeconds * 1000));
            } catch (Exception ignored) {
                // ignore candidate failure and continue
            }
        }

        return Map.of("best_url", candidates.get(0), "ratings_json", "{}", "status", "no-rating-found");
    }

    private static String toCompactJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(e.getKey())).append('"').append(':')
                    .append('"').append(jsonEscape(e.getValue())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<WineQuery> readWines(Path path) throws IOException {
        List<WineQuery> wines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return wines;
            }
            String[] columns = header.split(",", -1);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < columns.length; i++) {
                idx.put(columns[i].trim(), i);
            }
            if (!idx.containsKey("domaine") || !idx.containsKey("appellation") || !idx.containsKey("millesime")) {
                throw new IllegalArgumentException("Colonnes manquantes dans le CSV: domaine, appellation, millesime");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                wines.add(new WineQuery(
                        safeGet(values, idx.get("domaine")),
                        safeGet(values, idx.get("appellation")),
                        safeGet(values, idx.get("millesime"))
                ));
            }
        }
        return wines;
    }

    private static String safeGet(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return "";
        }
        return values[index].trim();
    }

    private static void writeOutput(Path output, List<Map<String, String>> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("domaine,appellation,millesime,best_url,ratings_json,status\n");
            for (Map<String, String> row : rows) {
                writer.write(csv(row.getOrDefault("domaine", "")) + ","
                        + csv(row.getOrDefault("appellation", "")) + ","
                        + csv(row.getOrDefault("millesime", "")) + ","
                        + csv(row.getOrDefault("best_url", "")) + ","
                        + csv(row.getOrDefault("ratings_json", "{}")) + ","
                        + csv(row.getOrDefault("status", "")) + "\n");
            }
        }
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private record WineQuery(String domaine, String appellation, String millesime) {}

    private static final class ArgParser {
        private ArgParser() {}

        private static Map<String, String> toMap(String[] args, int from) {
            Map<String, String> map = new HashMap<>();
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
