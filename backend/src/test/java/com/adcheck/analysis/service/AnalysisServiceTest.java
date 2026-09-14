package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import com.adcheck.finding.domain.FindingCategory;
import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisServiceTest {

    private static final AnalysisReuseKey REUSE_KEY = new AnalysisReuseKey(
            "https://shop.example.com/product/1",
            "a".repeat(64),
            "v1"
    );

    private AnalysisResultResolver resultResolver;
    private AnalysisLifecycleService lifecycleService;
    private AnalysisBackgroundJob backgroundJob;
    private AnalysisResultJsonCodec resultJsonCodec;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        resultResolver = mock(AnalysisResultResolver.class);
        lifecycleService = mock(AnalysisLifecycleService.class);
        backgroundJob = mock(AnalysisBackgroundJob.class);
        resultJsonCodec = new AnalysisResultJsonCodec(new ObjectMapper());
        analysisService = new AnalysisService(
                resultResolver,
                lifecycleService,
                backgroundJob,
                new AnalysisResultSnapshotMapper(),
                new AnalysisActiveReuseConstraintDetector(),
                resultJsonCodec
        );
    }

    @Test
    void commitsPendingBeforeSubmittingBackgroundJobAndReturnsPending() {
        CreateAnalysisRequest request = request("광고 문구");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenReturn(7L);

        AnalysisSubmissionResult result = analysisService.analyze(request);

        assertThat(result.outcome()).isEqualTo(AnalysisSubmissionResult.Outcome.SUBMITTED);
        assertThat(result.response().analysisId()).isEqualTo(7L);
        assertThat(result.response().status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(result.response().summary()).isNull();
        assertThat(result.response().findings()).isEmpty();

        ArgumentCaptor<AnalysisJobInput> input = ArgumentCaptor.forClass(AnalysisJobInput.class);
        InOrder order = inOrder(lifecycleService, backgroundJob);
        order.verify(lifecycleService).createPending(request, REUSE_KEY);
        order.verify(backgroundJob).process(org.mockito.ArgumentMatchers.eq(7L), input.capture());
        assertThat(input.getValue().texts()).isEqualTo(request.texts());
        assertThat(input.getValue().images()).isEqualTo(request.images());
    }

    @Test
    void returnsRestoredResultWithoutCreatingOrSubmittingJob() {
        CreateAnalysisRequest request = request("광고 문구");
        when(resultResolver.resolve(request)).thenReturn(
                new AnalysisResultResolution.Reused(21L, snapshot(), REUSE_KEY)
        );

        AnalysisSubmissionResult result = analysisService.analyze(request);

        assertThat(result.outcome()).isEqualTo(AnalysisSubmissionResult.Outcome.REUSED);
        assertThat(result.response().analysisId()).isEqualTo(21L);
        assertThat(result.response().status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(result.response().summary().findingCount()).isEqualTo(1);
        assertThat(result.response().findings()).hasSize(1);
        verify(lifecycleService, never()).createPending(any(), any());
        verify(backgroundJob, never()).process(any(), any());
    }

    @Test
    void returnsInProgressWithoutCreatingOrSubmittingJob() {
        CreateAnalysisRequest request = request("광고 문구");
        when(resultResolver.resolve(request)).thenReturn(
                new AnalysisResultResolution.InProgress(22L, AnalysisStatus.PROCESSING, REUSE_KEY)
        );

        AnalysisSubmissionResult result = analysisService.analyze(request);

        assertThat(result.outcome()).isEqualTo(AnalysisSubmissionResult.Outcome.IN_PROGRESS);
        assertThat(result.response().analysisId()).isEqualTo(22L);
        assertThat(result.response().status()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(result.response().summary()).isNull();
        assertThat(result.response().findings()).isEmpty();
        verify(lifecycleService, never()).createPending(any(), any());
        verify(backgroundJob, never()).process(any(), any());
    }

    @Test
    void recoversNamedActiveReuseConflictWithoutSubmittingDuplicateJob() {
        CreateAnalysisRequest request = request("광고 문구");
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "duplicate key violates unique constraint uk_analyses_active_result_reuse"
        );
        Analysis active = mock(Analysis.class);
        when(active.getId()).thenReturn(24L);
        when(active.getStatus()).thenReturn(AnalysisStatus.PENDING);
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenThrow(conflict);
        when(lifecycleService.findActive(REUSE_KEY)).thenReturn(Optional.of(active));

        AnalysisSubmissionResult result = analysisService.analyze(request);

        assertThat(result.outcome()).isEqualTo(AnalysisSubmissionResult.Outcome.IN_PROGRESS);
        assertThat(result.response().analysisId()).isEqualTo(24L);
        verify(lifecycleService).findActive(REUSE_KEY);
        verify(backgroundJob, never()).process(any(), any());
    }

    @Test
    void marksPendingFailedAndReturnsServiceUnavailableWhenQueueRejects() {
        CreateAnalysisRequest request = request("광고 문구");
        TaskRejectedException rejection = new TaskRejectedException("queue full");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenReturn(25L);
        org.mockito.Mockito.doThrow(rejection)
                .when(backgroundJob).process(org.mockito.ArgumentMatchers.eq(25L), any());

        assertThatThrownBy(() -> analysisService.analyze(request))
                .isInstanceOfSatisfying(AnalysisQueueFullException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCode()).isEqualTo("ANALYSIS_QUEUE_FULL");
                    assertThat(exception.getCause()).isSameAs(rejection);
                });

        verify(lifecycleService).fail(25L, "분석 작업 대기열이 가득 찼습니다.");
    }

    @Test
    void preservesFailurePersistenceErrorAsSuppressedWhenQueueRejects() {
        CreateAnalysisRequest request = request("광고 문구");
        TaskRejectedException rejection = new TaskRejectedException("queue full");
        IllegalStateException failurePersistence = new IllegalStateException("failed to persist FAILED");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenReturn(26L);
        org.mockito.Mockito.doThrow(rejection)
                .when(backgroundJob).process(org.mockito.ArgumentMatchers.eq(26L), any());
        org.mockito.Mockito.doThrow(failurePersistence)
                .when(lifecycleService).fail(26L, "분석 작업 대기열이 가득 찼습니다.");

        assertThatThrownBy(() -> analysisService.analyze(request))
                .isInstanceOfSatisfying(AnalysisQueueFullException.class, exception ->
                        assertThat(exception.getSuppressed()).containsExactly(failurePersistence));
    }

    @Test
    void doesNotHideUnrelatedIntegrityViolation() {
        CreateAnalysisRequest request = request("광고 문구");
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("another constraint failed");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenThrow(failure);

        assertThatThrownBy(() -> analysisService.analyze(request)).isSameAs(failure);

        verify(lifecycleService, never()).findActive(any());
        verify(backgroundJob, never()).process(any(), any());
    }

    @Test
    void failsClearlyWhenActiveAnalysisCannotBeRecovered() {
        CreateAnalysisRequest request = request("광고 문구");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key violates unique constraint uk_analyses_active_result_reuse"
                )
        );
        when(lifecycleService.findActive(REUSE_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisService.analyze(request))
                .isInstanceOf(AnalysisConflictRecoveryException.class)
                .hasMessage("동일한 분석 요청의 진행 상태를 확인하지 못했습니다.");

        verify(lifecycleService).findActive(REUSE_KEY);
        verify(backgroundJob, never()).process(any(), any());
    }

    @Test
    void returnsMinimalResponseForPendingAnalysis() {
        Analysis pending = mock(Analysis.class);
        when(pending.getStatus()).thenReturn(AnalysisStatus.PENDING);
        when(lifecycleService.findById(30L)).thenReturn(Optional.of(pending));

        var response = analysisService.getAnalysis(30L);

        assertThat(response.analysisId()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(response.summary()).isNull();
        assertThat(response.findings()).isEmpty();
    }

    @Test
    void returnsMinimalResponseForProcessingAnalysis() {
        Analysis processing = mock(Analysis.class);
        when(processing.getStatus()).thenReturn(AnalysisStatus.PROCESSING);
        when(lifecycleService.findById(31L)).thenReturn(Optional.of(processing));

        var response = analysisService.getAnalysis(31L);

        assertThat(response.status()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(response.summary()).isNull();
        assertThat(response.findings()).isEmpty();
    }

    @Test
    void returnsMinimalResponseForFailedAnalysis() {
        Analysis failed = mock(Analysis.class);
        when(failed.getStatus()).thenReturn(AnalysisStatus.FAILED);
        when(lifecycleService.findById(32L)).thenReturn(Optional.of(failed));

        var response = analysisService.getAnalysis(32L);

        assertThat(response.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(response.summary()).isNull();
        assertThat(response.findings()).isEmpty();
    }

    @Test
    void returnsRestoredResultForCompletedAnalysis() {
        Analysis completed = mock(Analysis.class);
        when(completed.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(completed.getResultJson()).thenReturn(resultJsonCodec.serialize(snapshot()));
        when(lifecycleService.findById(33L)).thenReturn(Optional.of(completed));

        var response = analysisService.getAnalysis(33L);

        assertThat(response.analysisId()).isEqualTo(33L);
        assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(response.summary().findingCount()).isEqualTo(1);
        assertThat(response.findings()).hasSize(1);
        assertThat(response.findings().getFirst().sourceText()).isEqualTo("시력을 회복합니다.");
    }

    @Test
    void throwsNotFoundWhenAnalysisDoesNotExist() {
        when(lifecycleService.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisService.getAnalysis(99L))
                .isInstanceOfSatisfying(AnalysisNotFoundException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("ANALYSIS_NOT_FOUND");
                });
    }

    private AnalysisResultSnapshot snapshot() {
        return new AnalysisResultSnapshot(
                new AnalysisResultSnapshot.Summary(1, 1),
                List.of(new AnalysisResultSnapshot.Finding(
                        "시력을 회복합니다.",
                        "#claim",
                        RiskLevel.CAUTION,
                        FindingCategory.FUNCTION_CLAIM,
                        "확인이 필요합니다.",
                        "눈 건강에 도움을 줄 수 있음"
                ))
        );
    }

    private CreateAnalysisRequest request(String content) {
        return new CreateAnalysisRequest(
                "https://shop.example.com/product/1",
                "상품 페이지",
                "루테인",
                List.of(new PageTextEvidence(content, "#claim")),
                List.of()
        );
    }
}
