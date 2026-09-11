package com.adcheck.rule.service;

import java.util.Objects;

public record RuleEvaluation(Status status, ReasonCode reasonCode, String reason) {
    public RuleEvaluation {
        Objects.requireNonNull(status);
        Objects.requireNonNull(reasonCode);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
    }

    public enum Status { MATCHED, NOT_MATCHED, REVIEW_REQUIRED }
    public enum ReasonCode {
        SUPPORTED_CONDITION_CONFIRMED, CONDITION_NOT_MET, EXCEPTION_CONFIRMED,
        MISSING_CLAIM_TEXT, CONTEXT_UNVERIFIED, OUTSIDE_SUPPORTED_LANGUAGE,
        OFFICIAL_FUNCTION_DATA_INCOMPLETE, OFFICIAL_FUNCTION_EXACT_MATCH,
        SEMANTIC_COMPARISON_REQUIRED, UNSUPPORTED_RULE, RULE_DEFINITION_CHANGED
    }
}
