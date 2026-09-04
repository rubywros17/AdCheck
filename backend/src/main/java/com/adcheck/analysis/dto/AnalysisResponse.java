package com.adcheck.analysis.dto;

import com.adcheck.analysis.domain.AnalysisStatus;

import java.util.List;

public record AnalysisResponse(
        Long analysisId,
        AnalysisStatus status,
        AnalysisSummary summary,
        List<FindingResponse> findings
) {
}
