package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.FindingCategory;
import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private ClaimAnalyzer claimAnalyzer;
    private AnalysisResultJsonCodec resultJsonCodec;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        resultResolver = mock(AnalysisResultResolver.class);
        lifecycleService = mock(AnalysisLifecycleService.class);
        claimAnalyzer = mock(ClaimAnalyzer.class);
        AnalysisResultSnapshotMapper snapshotMapper = new AnalysisResultSnapshotMapper();
        resultJsonCodec = new AnalysisResultJsonCodec(new ObjectMapper());
        analysisService = new AnalysisService(
                resultResolver,
                lifecycleService,
                claimAnalyzer,
                snapshotMapper,
                resultJsonCodec,
                new AnalysisActiveReuseConstraintDetector()
        );
    }

    @Test
    void createsAnalysisAndPersistsRestorableResultInLifecycleOrder() {
        CreateAnalysisRequest request = request("광고 문구");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenReturn(7L);
        when(claimAnalyzer.analyze(request.texts())).thenReturn(claimResult());

        AnalysisSubmissionResult result = analysisService.analyze(request);

        assertThat(result.outcome()).isEqualTo(AnalysisSubmissionResult.Outcome.CREATED);
        assertThat(result.response().analysisId()).isEqualTo(7L);
        assertThat(result.response().status()).isEqualTo(AnalysisStatus.COMPLETED);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(lifecycleService, claimAnalyzer);
        order.verify(lifecycleService).createPending(request, REUSE_KEY);
        order.verify(lifecycleService).markProcessing(7L);
        order.verify(claimAnalyzer).analyze(request.texts());
        order.verify(lifecycleService).completeWithResult(
                org.mockito.ArgumentMatchers.eq(7L),
                resultJson.capture()
        );

        AnalysisResultSnapshot restored = resultJsonCodec.deserialize(resultJson.getValue());
        assertThat(restored.summary().findingCount()).isEqualTo(1);
        assertThat(restored.summary().officialFunctionMatchedCount()).isEqualTo(1);
        assertThat(restored.findings()).hasSize(1);
    }

    @Test
    void returnsRestoredResultWithoutCreatingOrRunningAnalyzer() {
        CreateAnalysisRequest request = request("광고 문구");
        AnalysisResultSnapshot snapshot = snapshot();
        when(resultResolver.resolve(request)).thenReturn(
                new AnalysisResultResolution.Reused(21L, snapshot, REUSE_KEY)
        );

        AnalysisSubmissionResult result = analysisService.analyze(request);

        assertThat(result.outcome()).isEqualTo(AnalysisSubmissionResult.Outcome.REUSED);
        assertThat(result.response().analysisId()).isEqualTo(21L);
        assertThat(result.response().status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(result.response().summary().findingCount()).isEqualTo(1);
        assertThat(result.response().findings()).hasSize(1);
        verify(lifecycleService, never()).createPending(any(), any());
        verify(claimAnalyzer, never()).analyze(any());
    }

    @Test
    void returnsInProgressWithoutCreatingOrRunningAnalyzer() {
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
        verify(claimAnalyzer, never()).analyze(any());
    }

    @Test
    void marksCommittedAnalysisFailedAndRethrowsAnalyzerFailure() {
        CreateAnalysisRequest request = request("광고 문구");
        IllegalStateException failure = new IllegalStateException("Mock 분석 실패");
        when(resultResolver.resolve(request))
                .thenReturn(new AnalysisResultResolution.NewAnalysis(REUSE_KEY));
        when(lifecycleService.createPending(request, REUSE_KEY)).thenReturn(23L);
        when(claimAnalyzer.analyze(request.texts())).thenThrow(failure);

        assertThatThrownBy(() -> analysisService.analyze(request)).isSameAs(failure);

        verify(lifecycleService).markProcessing(23L);
        verify(lifecycleService).fail(23L, "Mock 분석 실패");
        verify(lifecycleService, never()).completeWithResult(any(), anyString());
    }

    @Test
    void recoversNamedActiveReuseConflictAsInProgress() {
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
        verify(claimAnalyzer, never()).analyze(any());
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
        verify(claimAnalyzer, never()).analyze(any());
    }

    private ClaimAnalysisResult claimResult() {
        return new ClaimAnalysisResult(
                List.of(new Finding(
                        "시력을 회복합니다.",
                        "#claim",
                        RiskLevel.CAUTION,
                        FindingCategory.FUNCTION_CLAIM,
                        "확인이 필요합니다.",
                        "눈 건강에 도움을 줄 수 있음"
                )),
                1
        );
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
