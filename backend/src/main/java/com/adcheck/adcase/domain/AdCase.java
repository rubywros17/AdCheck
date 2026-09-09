package com.adcheck.adcase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "ad_cases")
public class AdCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, unique = true)
    private String caseId;

    @Column(name = "case_type", nullable = false)
    private String caseType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ingredient_codes")
    private String ingredientCodes;

    @Column(name = "other_main_ingredients", columnDefinition = "text")
    private String otherMainIngredients;

    @Column(name = "text_kind")
    private String textKind;

    @Column(name = "quoted_text", columnDefinition = "text")
    private String quotedText;

    @Column(name = "source_decision")
    private String sourceDecision;

    @Column(name = "source_reason_summary", columnDefinition = "text")
    private String sourceReasonSummary;

    @Column(name = "printed_page")
    private String printedPage;

    @Column(name = "pdf_page")
    private String pdfPage;

    @Column(name = "verification_status", nullable = false)
    private String verificationStatus;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    protected AdCase() {
    }
}
