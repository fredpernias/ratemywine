package com.ratemywine.corpustograph;

import java.nio.file.Path;
import java.util.Map;

public record DocumentData(
        Path path,
        String title,
        String rawText,
        String summary,
        int tokenCount,
        Map<String, Integer> termFrequency
) {
}
