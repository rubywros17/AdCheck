package com.adcheck.analysis.service;

import com.adcheck.finding.domain.Finding;

import java.util.List;

public record ClaimAnalysisResult(List<Finding> findings, int officialFunctionMatchedCount) {

    public ClaimAnalysisResult {
        findings = List.copyOf(findings);
    }
}
