package com.adcheck.rule.service;

import java.util.List;

public record RuleAnalysisResult(String claimId, List<RuleMatch> matches, List<Diagnostic> diagnostics) {
    public RuleAnalysisResult {
        matches = List.copyOf(matches);
        diagnostics = List.copyOf(diagnostics);
    }

    public record RuleMatch(
            String claimId, Long ruleId, String ruleCode, String ruleVersion,
            String scopeType, String judgmentCategory, String severity, String reviewStatus,
            boolean candidatePresent, RuleEvaluation evaluation,
            List<SourceMetadata> sources, List<Diagnostic> diagnostics
    ) {
        public RuleMatch {
            sources = List.copyOf(sources);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public enum Diagnostic { INGREDIENT_SPECIFIC_NOT_EVALUATED, SOURCE_MISSING, DRAFT_RULE }

    public record SourceMetadata(
            Long referenceSourceId, String sourceId, String title, String sourceType,
            String issuer, String sourceUrl, String documentVersion, String verificationStatus,
            String section, String printedPage, String pdfPage
    ) { }
}
