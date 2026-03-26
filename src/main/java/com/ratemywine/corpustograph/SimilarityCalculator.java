package com.ratemywine.corpustograph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SimilarityCalculator {

    public double[][] computeSimilarityMatrix(List<DocumentData> docs, SimilarityModel model) {
        return switch (model) {
            case BM25 -> bm25SymmetricMatrix(docs);
            case TF_IDF -> tfIdfCosineMatrix(docs);
        };
    }

    private double[][] bm25SymmetricMatrix(List<DocumentData> docs) {
        int n = docs.size();
        double[][] matrix = new double[n][n];
        double avgDocLen = docs.stream().mapToInt(DocumentData::tokenCount).average().orElse(1.0);

        Map<String, Integer> df = documentFrequency(docs);
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            double value = Math.log(1.0 + ((n - e.getValue() + 0.5) / (e.getValue() + 0.5)));
            idf.put(e.getKey(), Math.max(0.0, value));
        }

        for (int i = 0; i < n; i++) {
            matrix[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double scoreIJ = bm25Score(docs.get(i), docs.get(j), idf, avgDocLen);
                double scoreJI = bm25Score(docs.get(j), docs.get(i), idf, avgDocLen);
                double sym = (scoreIJ + scoreJI) / 2.0;
                matrix[i][j] = sym;
                matrix[j][i] = sym;
            }
        }
        normalize(matrix);
        return matrix;
    }

    private double bm25Score(DocumentData query, DocumentData doc, Map<String, Double> idf, double avgDocLen) {
        final double k1 = 1.5;
        final double b = 0.75;
        double len = Math.max(1.0, doc.tokenCount());
        double score = 0.0;
        for (String term : query.termFrequency().keySet()) {
            Integer tf = doc.termFrequency().get(term);
            if (tf == null || tf == 0) {
                continue;
            }
            double denom = tf + k1 * (1.0 - b + b * (len / avgDocLen));
            score += idf.getOrDefault(term, 0.0) * (tf * (k1 + 1.0) / denom);
        }
        return score;
    }

    private double[][] tfIdfCosineMatrix(List<DocumentData> docs) {
        int n = docs.size();
        double[][] matrix = new double[n][n];
        Map<String, Integer> df = documentFrequency(docs);
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }

        List<Map<String, Double>> vectors = docs.stream().map(d -> tfIdfVector(d, idf)).toList();
        double[] norms = vectors.stream().mapToDouble(this::norm).toArray();

        for (int i = 0; i < n; i++) {
            matrix[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double sim = cosine(vectors.get(i), vectors.get(j), norms[i], norms[j]);
                matrix[i][j] = sim;
                matrix[j][i] = sim;
            }
        }
        normalize(matrix);
        return matrix;
    }

    private Map<String, Integer> documentFrequency(List<DocumentData> docs) {
        Map<String, Integer> df = new HashMap<>();
        for (DocumentData doc : docs) {
            for (String term : doc.termFrequency().keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        return df;
    }

    private Map<String, Double> tfIdfVector(DocumentData doc, Map<String, Double> idf) {
        Map<String, Double> vector = new HashMap<>();
        for (Map.Entry<String, Integer> e : doc.termFrequency().entrySet()) {
            double tf = 1.0 + Math.log(e.getValue());
            double weight = tf * idf.getOrDefault(e.getKey(), 0.0);
            vector.put(e.getKey(), weight);
        }
        return vector;
    }

    private double norm(Map<String, Double> vector) {
        return Math.sqrt(vector.values().stream().mapToDouble(v -> v * v).sum());
    }

    private double cosine(Map<String, Double> a, Map<String, Double> b, double normA, double normB) {
        if (normA == 0 || normB == 0) {
            return 0;
        }
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> large = a.size() <= b.size() ? b : a;
        double dot = 0.0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double other = large.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        return dot / (normA * normB);
    }

    private void normalize(double[][] matrix) {
        double max = 0.0;
        for (double[] row : matrix) {
            for (double v : row) {
                max = Math.max(max, v);
            }
        }
        if (max <= 0.0) {
            return;
        }
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                matrix[i][j] = Math.min(1.0, matrix[i][j] / max);
            }
        }
    }
}
