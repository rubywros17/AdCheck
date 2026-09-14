package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisService {

    private static final String QUEUE_REJECTION_FAILURE_MESSAGE = "분석 작업 대기열이 가득 찼습니다.";

    private final AnalysisResultResolver resultResolver;
    private final AnalysisLifecycleService lifecycleService;
    private final AnalysisBackgroundJob backgroundJob;
    private final AnalysisResultSnapshotMapper snapshotMapper;
    private final AnalysisActiveReuseConstraintDetector activeReuseConstraintDetector;

    public AnalysisService(
            AnalysisResultResolver resultResolver,
            AnalysisLifecycleService lifecycleService,
            AnalysisBackgroundJob backgroundJob,
            AnalysisResultSnapshotMapper snapshotMapper,
            AnalysisActiveReuseConstraintDetector activeReuseConstraintDetector
    ) {
        this.resultResolver = resultResolver;
        this.lifecycleService = lifecycleService;
        this.backgroundJob = backgroundJob;
        this.snapshotMapper = snapshotMapper;
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
        AnalysisJobInput jobInput = AnalysisJobInput.from(request);
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
            backgroundJob.process(analysisId, jobInput);
        } catch (TaskRejectedException exception) {
            throw rejectSubmission(analysisId, exception);
        }

        return AnalysisSubmissionResult.submitted(new AnalysisResponse(
                analysisId,
                AnalysisStatus.PENDING,
                null,
                List.of()
        ));
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

    private AnalysisQueueFullException rejectSubmission(
            Long analysisId,
            TaskRejectedException rejection
    ) {
        AnalysisQueueFullException exception = new AnalysisQueueFullException(rejection);
        try {
            lifecycleService.fail(analysisId, QUEUE_REJECTION_FAILURE_MESSAGE);
        } catch (RuntimeException failurePersistenceException) {
            exception.addSuppressed(failurePersistenceException);
        }
        return exception;
    }
}
