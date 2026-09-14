package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.dto.PageImageEvidence;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisBackgroundJobTest {

    private AnalysisLifecycleService lifecycleService;
    private ClaimAnalyzer claimAnalyzer;
    private AnalysisResultJsonCodec resultJsonCodec;
    private AnalysisBackgroundJob backgroundJob;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(AnalysisLifecycleService.class);
        claimAnalyzer = mock(ClaimAnalyzer.class);
        resultJsonCodec = new AnalysisResultJsonCodec(new ObjectMapper());
        backgroundJob = new AnalysisBackgroundJob(
                lifecycleService,
                claimAnalyzer,
                new AnalysisResultSnapshotMapper(),
                resultJsonCodec
        );
    }

    @Test
    void processesAnalysisSequentiallyAndPersistsRestorableResult() {
        AnalysisJobInput input = input();
        when(claimAnalyzer.analyze(input.texts())).thenReturn(claimResult());

        backgroundJob.process(7L, input);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(lifecycleService, claimAnalyzer);
        order.verify(lifecycleService).markProcessing(7L);
        order.verify(claimAnalyzer).analyze(input.texts());
        order.verify(lifecycleService).completeWithResult(
                org.mockito.ArgumentMatchers.eq(7L),
                resultJson.capture(),
                org.mockito.ArgumentMatchers.eq(true)
        );
        AnalysisResultSnapshot restored = resultJsonCodec.deserialize(resultJson.getValue());
        assertThat(restored.summary().findingCount()).isEqualTo(1);
        assertThat(restored.summary().officialFunctionMatchedCount()).isEqualTo(1);
        assertThat(restored.findings()).hasSize(1);
    }

    @Test
    void computesHasFindingFalseWhenNoFindingsPresent() {
        AnalysisJobInput input = input();
        when(claimAnalyzer.analyze(input.texts()))
                .thenReturn(new ClaimAnalysisResult(List.of(), 0));

        backgroundJob.process(12L, input);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).completeWithResult(
                org.mockito.ArgumentMatchers.eq(12L),
                resultJson.capture(),
                org.mockito.ArgumentMatchers.eq(false)
        );
        AnalysisResultSnapshot restored = resultJsonCodec.deserialize(resultJson.getValue());
        assertThat(restored.findings()).isEmpty();
    }

    @Test
    void marksAnalysisFailedWithoutPropagatingBackgroundFailure() {
        AnalysisJobInput input = input();
        IllegalStateException failure = new IllegalStateException("Mock 분석 실패");
        when(claimAnalyzer.analyze(input.texts())).thenThrow(failure);

        assertThatCode(() -> backgroundJob.process(8L, input)).doesNotThrowAnyException();

        verify(lifecycleService).markProcessing(8L);
        verify(lifecycleService).fail(8L, "Mock 분석 실패");
        verify(lifecycleService, never()).completeWithResult(
                org.mockito.ArgumentMatchers.eq(8L),
                anyString(),
                anyBoolean()
        );
    }

    @Test
    void preservesFailurePersistenceExceptionAsSuppressed() {
        AnalysisJobInput input = input();
        IllegalStateException original = new IllegalStateException("Mock 분석 실패");
        IllegalStateException persistence = new IllegalStateException("FAILED 저장 실패");
        when(claimAnalyzer.analyze(input.texts())).thenThrow(original);
        org.mockito.Mockito.doThrow(persistence)
                .when(lifecycleService).fail(9L, "Mock 분석 실패");

        backgroundJob.process(9L, input);

        assertThat(original.getSuppressed()).containsExactly(persistence);
    }

    @Test
    void asyncEntryPointUsesNamedExecutorAndHasNoTransaction() throws Exception {
        var process = AnalysisBackgroundJob.class.getMethod(
                "process", Long.class, AnalysisJobInput.class
        );

        assertThat(process.getAnnotation(Async.class).value())
                .isEqualTo(AnalysisAsyncConfiguration.EXECUTOR_NAME);
        assertThat(process.getAnnotation(Transactional.class)).isNull();
        assertThat(AnalysisBackgroundJob.class).isNotEqualTo(AnalysisService.class);
    }

    @Test
    void jobInputDefensivelyCopiesTextAndImageCollections() {
        List<PageTextEvidence> texts = new ArrayList<>(List.of(
                new PageTextEvidence("광고 문구", "#claim")
        ));
        List<PageImageEvidence> images = new ArrayList<>(List.of(
                new PageImageEvidence("https://example.com/image.jpg", "상품 이미지")
        ));

        AnalysisJobInput input = new AnalysisJobInput(texts, images);
        texts.clear();
        images.clear();

        assertThat(input.texts()).hasSize(1);
        assertThat(input.images()).hasSize(1);
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

    private AnalysisJobInput input() {
        return new AnalysisJobInput(
                List.of(new PageTextEvidence("시력을 회복합니다.", "#claim")),
                List.of()
        );
    }
}
