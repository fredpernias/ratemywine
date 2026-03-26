package com.ratemywine.corpustograph;

public enum SimilarityModel {
    BM25("BM25"),
    TF_IDF("TF-IDF (cosine)");

    private final String label;

    SimilarityModel(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
