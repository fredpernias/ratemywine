package com.ratemywine.corpustograph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CorpusParser {
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    public List<DocumentData> parseDirectory(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Le chemin sélectionné n'est pas un dossier valide.");
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }

        List<DocumentData> documents = new ArrayList<>();
        for (Path path : files) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String cleanText = stripHtml(content);
            Map<String, Integer> tf = buildTermFrequency(cleanText);
            int tokenCount = tf.values().stream().mapToInt(Integer::intValue).sum();
            if (tokenCount == 0) {
                continue;
            }
            String title = path.getFileName().toString();
            String summary = createSummary(cleanText);
            documents.add(new DocumentData(path, title, cleanText, summary, tokenCount, tf));
        }

        return documents;
    }

    private boolean isSupported(Path path) {
        String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return file.endsWith(".txt") || file.endsWith(".md") || file.endsWith(".html") || file.endsWith(".htm");
    }

    private String stripHtml(String text) {
        return text.replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ")
                .replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Map<String, Integer> buildTermFrequency(String content) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : TOKEN_SPLIT.split(content.toLowerCase(Locale.ROOT))) {
            if (token.length() < 2) {
                continue;
            }
            tf.merge(token, 1, Integer::sum);
        }
        return tf;
    }

    private String createSummary(String text) {
        String[] words = TOKEN_SPLIT.split(text.replaceAll("\\s+", " ").trim());
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }
            if (count > 0) {
                summary.append(' ');
            }
            summary.append(word);
            count++;
            if (count >= 30) {
                break;
            }
        }
        if (words.length > 30) {
            summary.append("…");
        }
        return summary.isEmpty() ? "(Résumé indisponible)" : summary.toString();
    }
}
