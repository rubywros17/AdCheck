package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.FindingResponse;
import com.adcheck.analysis.repository.AnalysisRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AnalysisService {

    private final AnalysisRepository analysisRepository;
    private final ClaimAnalyzer claimAnalyzer;

    public AnalysisService(AnalysisRepository analysisRepository, ClaimAnalyzer claimAnalyzer) {
        this.analysisRepository = analysisRepository;
        this.claimAnalyzer = claimAnalyzer;
    }

    @Transactional
    public AnalysisResponse analyze(CreateAnalysisRequest request) {
        Analysis analysis = Analysis.create(
                request.pageUrl(), request.pageTitle(), request.productName()
        );
        analysisRepository.save(analysis);
        analysis.startProcessing();

        ClaimAnalysisResult result = claimAnalyzer.analyze(request.texts());
        analysis.complete();

        List<FindingResponse> findings = result.findings().stream()
                .map(FindingResponse::from)
                .toList();
        AnalysisSummary summary = new AnalysisSummary(
                findings.size(), result.officialFunctionMatchedCount()
        );

        return new AnalysisResponse(analysis.getId(), analysis.getStatus(), summary, findings);
    }
}
