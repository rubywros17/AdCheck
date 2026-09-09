package com.adcheck.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "reference_sources")
public class ReferenceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false, unique = true)
    private String sourceId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "issuer", columnDefinition = "text")
    private String issuer;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "local_source_path", columnDefinition = "text")
    private String localSourcePath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_source_id")
    private ReferenceSource parentSource;

    @Column(name = "document_version")
    private String documentVersion;

    @Column(name = "issued_at_raw")
    private String issuedAtRaw;

    @Column(name = "effective_from_raw")
    private String effectiveFromRaw;

    @Column(name = "effective_to_raw")
    private String effectiveToRaw;

    @Column(name = "retrieved_at_raw")
    private String retrievedAtRaw;

    @Column(name = "verification_status", nullable = false)
    private String verificationStatus;

    @Column(name = "section", columnDefinition = "text")
    private String section;

    @Column(name = "printed_page")
    private String printedPage;

    @Column(name = "pdf_page")
    private String pdfPage;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    protected ReferenceSource() {
    }
}
