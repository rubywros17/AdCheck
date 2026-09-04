package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;

import java.util.List;

public interface ClaimAnalyzer {

    ClaimAnalysisResult analyze(List<PageTextEvidence> texts);
}
