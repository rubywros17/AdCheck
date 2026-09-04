package com.adcheck.analysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "analyses")
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String pageUrl;

    @Column(length = 300)
    private String pageTitle;

    @Column(length = 200)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    protected Analysis() {
    }

    private Analysis(String pageUrl, String pageTitle, String productName) {
        this.pageUrl = pageUrl;
        this.pageTitle = pageTitle;
        this.productName = productName;
        this.status = AnalysisStatus.PENDING;
    }

    public static Analysis create(String pageUrl, String pageTitle, String productName) {
        return new Analysis(pageUrl, pageTitle, productName);
    }

    public void startProcessing() {
        status = AnalysisStatus.PROCESSING;
    }

    public void complete() {
        status = AnalysisStatus.COMPLETED;
        completedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public String getProductName() {
        return productName;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
