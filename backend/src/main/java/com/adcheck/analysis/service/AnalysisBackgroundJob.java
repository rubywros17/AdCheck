package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.FindingResponse;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
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
            ClaimAnalysisResult claimResult = claimAnalyzer.analyze(input.texts());
            AnalysisResponse response = responseFrom(analysisId, claimResult);
            AnalysisResultSnapshot snapshot = snapshotMapper.toSnapshot(response);
            String resultJson = resultJsonCodec.serialize(snapshot);
            boolean hasFinding = !claimResult.findings().isEmpty();
            lifecycleService.completeWithResult(analysisId, resultJson, hasFinding);
        } catch (RuntimeException exception) {
            markFailed(analysisId, exception);
            log.error("Background analysis failed. analysisId={}", analysisId, exception);
        }
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
