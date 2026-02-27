package com.ratemywine.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wines")
public class Wine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domaine;

    @Column(nullable = false)
    private String appellation;

    @Column(nullable = false)
    private Integer millesime;

    @Column(name = "best_url")
    private String bestUrl;

    @Column(name = "ratings_json", columnDefinition = "TEXT")
    private String ratingsJson;

    @Column
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDomaine() {
        return domaine;
    }

    public void setDomaine(String domaine) {
        this.domaine = domaine;
    }

    public String getAppellation() {
        return appellation;
    }

    public void setAppellation(String appellation) {
        this.appellation = appellation;
    }

    public Integer getMillesime() {
        return millesime;
    }

    public void setMillesime(Integer millesime) {
        this.millesime = millesime;
    }

    public String getBestUrl() {
        return bestUrl;
    }

    public void setBestUrl(String bestUrl) {
        this.bestUrl = bestUrl;
    }

    public String getRatingsJson() {
        return ratingsJson;
    }

    public void setRatingsJson(String ratingsJson) {
        this.ratingsJson = ratingsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
