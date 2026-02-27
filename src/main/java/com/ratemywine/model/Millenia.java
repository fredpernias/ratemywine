package com.ratemywine.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "millenia")
public class Millenia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_url", nullable = false)
    private String pageUrl;

    @Column(name = "wine_name", nullable = false)
    private String wineName;

    @Column(name = "source_key", nullable = false)
    private String sourceKey;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "rating_value")
    private String ratingValue;

    @Column(name = "rating_scale")
    private String ratingScale;

    @Column(name = "distinction")
    private String distinction;

    @Column(name = "extraction_source")
    private String extractionSource;

    @Column(name = "scraped_at", nullable = false)
    private OffsetDateTime scrapedAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getWineName() {
        return wineName;
    }

    public void setWineName(String wineName) {
        this.wineName = wineName;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRatingValue() {
        return ratingValue;
    }

    public void setRatingValue(String ratingValue) {
        this.ratingValue = ratingValue;
    }

    public String getRatingScale() {
        return ratingScale;
    }

    public void setRatingScale(String ratingScale) {
        this.ratingScale = ratingScale;
    }

    public String getDistinction() {
        return distinction;
    }

    public void setDistinction(String distinction) {
        this.distinction = distinction;
    }

    public String getExtractionSource() {
        return extractionSource;
    }

    public void setExtractionSource(String extractionSource) {
        this.extractionSource = extractionSource;
    }

    public OffsetDateTime getScrapedAt() {
        return scrapedAt;
    }

    public void setScrapedAt(OffsetDateTime scrapedAt) {
        this.scrapedAt = scrapedAt;
    }
}
