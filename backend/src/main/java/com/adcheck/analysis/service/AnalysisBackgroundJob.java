package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.FindingResponse;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.FindingCategory;
import com.adcheck.finding.domain.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisBackgroundJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisBackgroundJob.class);
    private static final String DEFAULT_FAILURE_MESSAGE = "분석 처리 중 오류가 발생했습니다.";

    private final AnalysisLifecycleService lifecycleService;
    private final ClaimAnalyzer claimAnalyzer;
    private final AnalysisResultSnapshotMapper snapshotMapper;
    private final AnalysisResultJsonCodec resultJsonCodec;

    public AnalysisBackgroundJob(
            AnalysisLifecycleService lifecycleService,
            ClaimAnalyzer claimAnalyzer,
            AnalysisResultSnapshotMapper snapshotMapper,
            AnalysisResultJsonCodec resultJsonCodec
    ) {
        this.lifecycleService = lifecycleService;
        this.claimAnalyzer = claimAnalyzer;
        this.snapshotMapper = snapshotMapper;
        this.resultJsonCodec = resultJsonCodec;
    }

    @Async(AnalysisAsyncConfiguration.EXECUTOR_NAME)
    public void process(Long analysisId, AnalysisJobInput input) {
        try {
            lifecycleService.markProcessing(analysisId);
            ClaimAnalysisResult claimResult = claimAnalyzer.analyze(input.texts(), input.images());
            AnalysisResponse response = responseFrom(analysisId, claimResult);
            AnalysisResultSnapshot snapshot = snapshotMapper.toSnapshot(response);
            String resultJson = resultJsonCodec.serialize(snapshot);
            boolean hasFinding = !response.findings().isEmpty();
            lifecycleService.completeWithResult(analysisId, resultJson, hasFinding);
        } catch (RuntimeException exception) {
            markFailed(analysisId, exception);
            log.error("Background analysis failed. analysisId={}", analysisId, exception);
        }
    }

    /**
     * {@link ClaimAnalysisResult}는 AI 1차 추출 후보(claims/productCandidates/
     * ingredientCandidates/riskSignalCandidates)만 담을 뿐 Finding을 직접 만들지 않는다
     * (Backend Contract, 2026-09-10) — Product/Ingredient 확정, Rule 판정, AI #2
     * Comparison까지 거쳐야 진짜 Finding이 나온다. 그 전체 파이프라인은 아직 이
     * BackgroundJob에 연결되지 않았으므로(Rule Engine/Product 확정 미연동, 별도 작업),
     * 지금은 riskSignalCandidate 1건당 Finding 1건을 만드는 최소 매핑만 수행한다.
     *
     * <p>이 임시 매핑에서 {@code officialFunctionMatchedCount}는 항상 0이다 — 공식
     * 기능성과의 실제 비교는 확정된 원료·Rule 판정이 있어야 가능한데 아직 연결 전이라서다.
     * 전체 파이프라인이 연결되면 이 메서드는 riskSignalCandidate가 아니라 AI #2
     * (GeminiClaimComparisonService)의 {@code ClaimComparisonResult}를 Finding으로
     * 변환하는 형태로 교체되어야 한다.
     */
    private AnalysisResponse responseFrom(Long analysisId, ClaimAnalysisResult result) {
        List<FindingResponse> findings = result.riskSignalCandidates().stream()
                .map(this::toFinding)
                .map(FindingResponse::from)
                .toList();
        AnalysisSummary summary = new AnalysisSummary(findings.size(), 0);

        return new AnalysisResponse(
                analysisId,
                AnalysisStatus.COMPLETED,
                summary,
                findings
        );
    }

    private Finding toFinding(RiskSignalCandidate candidate) {
        String selector = candidate.source() != null ? candidate.source().selector() : null;
        return new Finding(
                candidate.text(),
                selector,
                RiskLevel.CAUTION,
                FindingCategory.FUNCTION_CLAIM,
                "AI가 감지한 주의 필요 표현입니다(signalType=%s). Rule 판정 연동 전이라 최종 위반 여부는 아직 확인되지 않았습니다."
                        .formatted(candidate.signalType()),
                null
        );
    }

    private void markFailed(Long analysisId, RuntimeException originalException) {
        try {
            lifecycleService.fail(analysisId, failureMessage(originalException));
        } catch (RuntimeException failurePersistenceException) {
            originalException.addSuppressed(failurePersistenceException);
        }
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }

        String simpleName = exception.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank()
                ? DEFAULT_FAILURE_MESSAGE
                : simpleName;
    }
}
