package com.adcheck.analysis.dto;

import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.RiskLevel;

public record FindingResponse(
        String sourceText,
        String selector,
        RiskLevel riskLevel,
        String category,
        String message,
        String officialFunction
) {
    public static FindingResponse from(Finding finding) {
        return new FindingResponse(
                finding.sourceText(),
                finding.selector(),
                finding.riskLevel(),
                finding.category(),
                finding.message(),
                finding.officialFunction()
        );
    }
}
