package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.FindingResponse;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisService {

    private static final String DEFAULT_FAILURE_MESSAGE = "분석 처리 중 오류가 발생했습니다.";

    private final AnalysisResultResolver resultResolver;
    private final AnalysisLifecycleService lifecycleService;
    private final ClaimAnalyzer claimAnalyzer;
    private final AnalysisResultSnapshotMapper snapshotMapper;
    private final AnalysisResultJsonCodec resultJsonCodec;
    private final AnalysisActiveReuseConstraintDetector activeReuseConstraintDetector;

    public AnalysisService(
            AnalysisResultResolver resultResolver,
            AnalysisLifecycleService lifecycleService,
            ClaimAnalyzer claimAnalyzer,
            AnalysisResultSnapshotMapper snapshotMapper,
            AnalysisResultJsonCodec resultJsonCodec,
            AnalysisActiveReuseConstraintDetector activeReuseConstraintDetector
    ) {
        this.resultResolver = resultResolver;
        this.lifecycleService = lifecycleService;
        this.claimAnalyzer = claimAnalyzer;
        this.snapshotMapper = snapshotMapper;
        this.resultJsonCodec = resultJsonCodec;
        this.activeReuseConstraintDetector = activeReuseConstraintDetector;
    }

    public AnalysisSubmissionResult analyze(CreateAnalysisRequest request) {
        AnalysisResultResolution resolution = resultResolver.resolve(request);

        return switch (resolution) {
            case AnalysisResultResolution.Reused reused -> reuse(reused);
            case AnalysisResultResolution.InProgress inProgress -> inProgress(inProgress);
            case AnalysisResultResolution.NewAnalysis newAnalysis -> create(request, newAnalysis.reuseKey());
        };
    }

    private AnalysisSubmissionResult reuse(AnalysisResultResolution.Reused reused) {
        AnalysisResponse response = snapshotMapper.toResponse(
                reused.analysisId(),
                AnalysisStatus.COMPLETED,
                reused.snapshot()
        );
        return AnalysisSubmissionResult.reused(response);
    }

    private AnalysisSubmissionResult inProgress(AnalysisResultResolution.InProgress inProgress) {
        return AnalysisSubmissionResult.inProgress(new AnalysisResponse(
                inProgress.analysisId(),
                inProgress.status(),
                null,
                List.of()
        ));
    }

    private AnalysisSubmissionResult create(CreateAnalysisRequest request, AnalysisReuseKey reuseKey) {
        Long analysisId;
        try {
            analysisId = lifecycleService.createPending(request, reuseKey);
        } catch (DataIntegrityViolationException exception) {
            if (!activeReuseConstraintDetector.isActiveReuseConflict(exception)) {
                throw exception;
            }
            return recoverConcurrentRequest(reuseKey);
        }

        try {
            lifecycleService.markProcessing(analysisId);
            AnalysisResponse response = responseFrom(
                    analysisId,
                    claimAnalyzer.analyze(request.texts())
            );
            AnalysisResultSnapshot snapshot = snapshotMapper.toSnapshot(response);
            String resultJson = resultJsonCodec.serialize(snapshot);
            lifecycleService.completeWithResult(analysisId, resultJson);
            return AnalysisSubmissionResult.created(response);
        } catch (RuntimeException exception) {
            markFailed(analysisId, exception);
            throw exception;
        }
    }

    private AnalysisSubmissionResult recoverConcurrentRequest(AnalysisReuseKey reuseKey) {
        Analysis analysis = lifecycleService.findActive(reuseKey)
                .orElseThrow(AnalysisConflictRecoveryException::new);
        return inProgress(new AnalysisResultResolution.InProgress(
                analysis.getId(),
                analysis.getStatus(),
                reuseKey
        ));
    }

    private AnalysisResponse responseFrom(Long analysisId, ClaimAnalysisResult result) {
        List<FindingResponse> findings = result.findings().stream()
                .map(FindingResponse::from)
                .toList();
        AnalysisSummary summary = new AnalysisSummary(
                findings.size(), result.officialFunctionMatchedCount()
        );

        return new AnalysisResponse(
                analysisId,
                AnalysisStatus.COMPLETED,
                summary,
                findings
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
