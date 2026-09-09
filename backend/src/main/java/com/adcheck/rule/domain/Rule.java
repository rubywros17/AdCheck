package com.adcheck.rule.domain;

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
@Table(name = "rules")
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, unique = true)
    private String ruleCode;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "expression_type", nullable = false)
    private String expressionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_examples")
    private String candidateExamples;

    @Column(name = "judgment_category", nullable = false)
    private String judgmentCategory;

    @Column(name = "application_conditions", nullable = false, columnDefinition = "text")
    private String applicationConditions;

    @Column(name = "exceptions", columnDefinition = "text")
    private String exceptions;

    @Column(name = "required_evidence", columnDefinition = "text")
    private String requiredEvidence;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus;

    protected Rule() {
    }
}
