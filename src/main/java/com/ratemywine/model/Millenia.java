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

    @Column(name = "nom")
    private String nom;

    @Column(name = "millesime")
    private Integer millesime;

    @Column(name = "rating_parker")
    private String ratingParker;

    @Column(name = "rating_jancis_robinson")
    private String ratingJancisRobinson;

    @Column(name = "rating_decanter")
    private String ratingDecanter;

    @Column(name = "rating_wine_spectator")
    private String ratingWineSpectator;

    @Column(name = "rating_james_suckling")
    private String ratingJamesSuckling;

    @Column(name = "rating_vinous_antonio_galloni")
    private String ratingVinousAntonioGalloni;

    @Column(name = "rating_vinous_neal_martin")
    private String ratingVinousNealMartin;

    @Column(name = "rating_figaro")
    private String ratingFigaro;

    @Column(name = "rating_the_wine_independent")
    private String ratingTheWineIndependent;

    @Column(name = "ratings_count")
    private Integer ratingsCount;

    @Column(name = "other_ratings", columnDefinition = "text")
    private String otherRatings;

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

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public Integer getMillesime() {
        return millesime;
    }

    public void setMillesime(Integer millesime) {
        this.millesime = millesime;
    }

    public String getRatingParker() {
        return ratingParker;
    }

    public void setRatingParker(String ratingParker) {
        this.ratingParker = ratingParker;
    }

    public String getRatingJancisRobinson() {
        return ratingJancisRobinson;
    }

    public void setRatingJancisRobinson(String ratingJancisRobinson) {
        this.ratingJancisRobinson = ratingJancisRobinson;
    }

    public String getRatingDecanter() {
        return ratingDecanter;
    }

    public void setRatingDecanter(String ratingDecanter) {
        this.ratingDecanter = ratingDecanter;
    }

    public String getRatingWineSpectator() {
        return ratingWineSpectator;
    }

    public void setRatingWineSpectator(String ratingWineSpectator) {
        this.ratingWineSpectator = ratingWineSpectator;
    }

    public String getRatingJamesSuckling() {
        return ratingJamesSuckling;
    }

    public void setRatingJamesSuckling(String ratingJamesSuckling) {
        this.ratingJamesSuckling = ratingJamesSuckling;
    }

    public String getRatingVinousAntonioGalloni() {
        return ratingVinousAntonioGalloni;
    }

    public void setRatingVinousAntonioGalloni(String ratingVinousAntonioGalloni) {
        this.ratingVinousAntonioGalloni = ratingVinousAntonioGalloni;
    }

    public String getRatingVinousNealMartin() {
        return ratingVinousNealMartin;
    }

    public void setRatingVinousNealMartin(String ratingVinousNealMartin) {
        this.ratingVinousNealMartin = ratingVinousNealMartin;
    }

    public String getRatingFigaro() {
        return ratingFigaro;
    }

    public void setRatingFigaro(String ratingFigaro) {
        this.ratingFigaro = ratingFigaro;
    }

    public String getRatingTheWineIndependent() {
        return ratingTheWineIndependent;
    }

    public void setRatingTheWineIndependent(String ratingTheWineIndependent) {
        this.ratingTheWineIndependent = ratingTheWineIndependent;
    }

    public Integer getRatingsCount() {
        return ratingsCount;
    }

    public void setRatingsCount(Integer ratingsCount) {
        this.ratingsCount = ratingsCount;
    }

    public String getOtherRatings() {
        return otherRatings;
    }

    public void setOtherRatings(String otherRatings) {
        this.otherRatings = otherRatings;
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
