package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.repository.AnalysisRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AnalysisLifecycleService {

    private final AnalysisRepository analysisRepository;

    public AnalysisLifecycleService(AnalysisRepository analysisRepository) {
        this.analysisRepository = analysisRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createPending(CreateAnalysisRequest request, AnalysisReuseKey reuseKey) {
        Analysis analysis = Analysis.create(
                request.pageUrl(),
                request.pageTitle(),
                request.productName(),
                reuseKey.normalizedUrl(),
                reuseKey.contentHash(),
                reuseKey.pipelineVersion()
        );
        return analysisRepository.saveAndFlush(analysis).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(Long analysisId) {
        findRequired(analysisId).startProcessing();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeWithResult(Long analysisId, String resultJson) {
        findRequired(analysisId).completeWithResult(resultJson);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long analysisId, String errorMessage) {
        findRequired(analysisId).fail(errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Analysis> findActive(AnalysisReuseKey reuseKey) {
        return analysisRepository.findActiveByReuseKey(
                reuseKey.normalizedUrl(),
                reuseKey.contentHash(),
                reuseKey.pipelineVersion()
        );
    }

    private Analysis findRequired(Long analysisId) {
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException(
                        "분석을 찾을 수 없습니다. analysisId=" + analysisId
                ));
    }
}
