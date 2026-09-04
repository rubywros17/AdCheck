package com.adcheck.finding.domain;

public record Finding(
        String sourceText,
        String selector,
        RiskLevel riskLevel,
        FindingCategory category,
        String message,
        String officialFunction
) {
}
