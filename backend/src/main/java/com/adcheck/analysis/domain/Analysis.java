package com.adcheck.analysis.domain;

import com.adcheck.product.domain.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "analyses")
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, length = 2048)
    private String pageUrl;

    @Column(length = 300)
    private String pageTitle;

    @Column(length = 200)
    private String productName;

    @Column(name = "normalized_url", length = 2048)
    private String normalizedUrl;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "pipeline_version", length = 50)
    private String pipelineVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "has_finding", nullable = false)
    private boolean hasFinding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    protected Analysis() {
    }

    private Analysis(String pageUrl, String pageTitle, String productName) {
        this(pageUrl, pageTitle, productName, null, null, null);
    }

    private Analysis(
            String pageUrl,
            String pageTitle,
            String productName,
            String normalizedUrl,
            String contentHash,
            String pipelineVersion
    ) {
        this.pageUrl = pageUrl;
        this.pageTitle = pageTitle;
        this.productName = productName;
        this.normalizedUrl = normalizedUrl;
        this.contentHash = contentHash;
        this.pipelineVersion = pipelineVersion;
        this.status = AnalysisStatus.PENDING;
    }

    public static Analysis create(String pageUrl, String pageTitle, String productName) {
        return new Analysis(pageUrl, pageTitle, productName);
    }

    public static Analysis create(
            String pageUrl,
            String pageTitle,
            String productName,
            String normalizedUrl,
            String contentHash,
            String pipelineVersion
    ) {
        return new Analysis(
                pageUrl,
                pageTitle,
                productName,
                normalizedUrl,
                contentHash,
                pipelineVersion
        );
    }

    public void startProcessing() {
        status = AnalysisStatus.PROCESSING;
    }

    /** Product 식별에 성공했을 때만 호출된다 — 식별 실패(NOT_FOUND/AMBIGUOUS) 시에는 null로 남는다. */
    public void assignProduct(Product product) {
        this.product = product;
    }

    public void complete() {
        status = AnalysisStatus.COMPLETED;
        errorMessage = null;
        completedAt = Instant.now();
    }

    public void completeWithResult(String resultJson, boolean hasFinding) {
        if (resultJson == null || resultJson.isBlank()) {
            throw new IllegalArgumentException("완료할 분석 결과 JSON이 필요합니다.");
        }
        this.resultJson = resultJson;
        this.hasFinding = hasFinding;
        complete();
    }

    public void fail(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("분석 실패 사유가 필요합니다.");
        }
        status = AnalysisStatus.FAILED;
        this.errorMessage = errorMessage;
        resultJson = null;
        completedAt = null;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
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

    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isHasFinding() {
        return hasFinding;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
