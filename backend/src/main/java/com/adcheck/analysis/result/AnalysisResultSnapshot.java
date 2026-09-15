package com.adcheck.analysis.result;

import com.adcheck.finding.domain.RiskLevel;

import java.util.List;
import java.util.Objects;

public record AnalysisResultSnapshot(
        Summary summary,
        List<Finding> findings
) {

    public AnalysisResultSnapshot {
        summary = Objects.requireNonNull(summary, "summary는 null일 수 없습니다.");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings는 null일 수 없습니다."));
    }

    public record Summary(
            int findingCount,
            int officialFunctionMatchedCount
    ) {
    }

    public record Finding(
            String sourceText,
            String selector,
            RiskLevel riskLevel,
            String category,
            String message,
            String officialFunction
    ) {
    }
}
