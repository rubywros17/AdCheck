package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.RiskLevel;
import com.adcheck.product.domain.Product;
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

/**
 * {@link FindingAssembler}의 실제 조립 로직(Product/Ingredient/Rule/AI#2)은 별도로
 * {@code FindingAssemblerTest}에서 검증한다 — 이 테스트는 {@code AnalysisBackgroundJob}이
 * {@link ClaimAnalyzer}/{@link FindingAssembler}/{@link AnalysisLifecycleService}를 올바른
 * 순서로 호출하고, {@link FindingAssembler.Result}를 저장 가능한 결과로 정확히 변환하는지만
 * 검증하기 위해 {@code findingAssembler}를 목으로 대체한다.
 */
class AnalysisBackgroundJobTest {

    private AnalysisLifecycleService lifecycleService;
    private ClaimAnalyzer claimAnalyzer;
    private FindingAssembler findingAssembler;
    private AnalysisResultJsonCodec resultJsonCodec;
    private AnalysisBackgroundJob backgroundJob;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(AnalysisLifecycleService.class);
        claimAnalyzer = mock(ClaimAnalyzer.class);
        findingAssembler = mock(FindingAssembler.class);
        resultJsonCodec = new AnalysisResultJsonCodec(new ObjectMapper());
        backgroundJob = new AnalysisBackgroundJob(
                lifecycleService,
                claimAnalyzer,
                findingAssembler,
                new AnalysisResultSnapshotMapper(),
                resultJsonCodec
        );
    }

    @Test
    void processesAnalysisSequentiallyAndPersistsRestorableResult() {
        AnalysisJobInput input = input();
        ClaimAnalysisResult claimResult = claimResult();
        Product product = mock(Product.class);
        Finding finding = new Finding(
                "시력을 회복합니다.", "#claim", RiskLevel.CAUTION, "FUNCTION_EXCEED",
                "확인이 필요합니다.", "눈 건강에 도움을 줄 수 있음"
        );
        when(claimAnalyzer.analyze(input.texts(), input.images())).thenReturn(claimResult);
        when(findingAssembler.assemble(claimResult))
                .thenReturn(new FindingAssembler.Result(product, List.of(finding), 1, 1));

        backgroundJob.process(7L, input);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(lifecycleService, claimAnalyzer, findingAssembler);
        order.verify(lifecycleService).markProcessing(7L);
        order.verify(claimAnalyzer).analyze(input.texts(), input.images());
        order.verify(findingAssembler).assemble(claimResult);
        order.verify(lifecycleService).assignProduct(7L, product);
        order.verify(lifecycleService).completeWithResult(
                org.mockito.ArgumentMatchers.eq(7L),
                resultJson.capture(),
                org.mockito.ArgumentMatchers.eq(true)
        );
        AnalysisResultSnapshot restored = resultJsonCodec.deserialize(resultJson.getValue());
        assertThat(restored.summary().findingCount()).isEqualTo(1);
        assertThat(restored.summary().officialFunctionMatchedCount()).isEqualTo(1);
        assertThat(restored.findings()).hasSize(1);
        assertThat(restored.findings().getFirst().sourceText()).isEqualTo("시력을 회복합니다.");
        assertThat(restored.findings().getFirst().category()).isEqualTo("FUNCTION_EXCEED");
    }

    @Test
    void computesHasFindingFalseWhenNoFindingsAssembled() {
        AnalysisJobInput input = input();
        ClaimAnalysisResult claimResult = claimResult();
        when(claimAnalyzer.analyze(input.texts(), input.images())).thenReturn(claimResult);
        when(findingAssembler.assemble(claimResult))
                .thenReturn(new FindingAssembler.Result(null, List.of(), 0, 0));

        backgroundJob.process(12L, input);

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).assignProduct(12L, null);
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
        when(claimAnalyzer.analyze(input.texts(), input.images())).thenThrow(failure);

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
    void marksAnalysisFailedWhenFindingAssemblerThrows() {
        AnalysisJobInput input = input();
        ClaimAnalysisResult claimResult = claimResult();
        IllegalStateException failure = new IllegalStateException("Product 조회 실패");
        when(claimAnalyzer.analyze(input.texts(), input.images())).thenReturn(claimResult);
        when(findingAssembler.assemble(claimResult)).thenThrow(failure);

        assertThatCode(() -> backgroundJob.process(13L, input)).doesNotThrowAnyException();

        verify(lifecycleService).fail(13L, "Product 조회 실패");
        verify(lifecycleService, never()).completeWithResult(
                org.mockito.ArgumentMatchers.eq(13L), anyString(), anyBoolean()
        );
    }

    @Test
    void preservesFailurePersistenceExceptionAsSuppressed() {
        AnalysisJobInput input = input();
        IllegalStateException original = new IllegalStateException("Mock 분석 실패");
        IllegalStateException persistence = new IllegalStateException("FAILED 저장 실패");
        when(claimAnalyzer.analyze(input.texts(), input.images())).thenThrow(original);
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
        String claimText = "시력을 회복합니다.";
        ExtractedClaim claim = new ExtractedClaim("claim-1", claimText, Source.domText("#claim"));
        RiskSignalCandidate signal = new RiskSignalCandidate(
                "claim-1", claimText, "FUNCTION_CLAIM", null, Source.domText("#claim")
        );
        return new ClaimAnalysisResult(List.of(claim), List.of(), List.of(), List.of(signal));
    }

    private AnalysisJobInput input() {
        return new AnalysisJobInput(
                List.of(new PageTextEvidence("시력을 회복합니다.", "#claim")),
                List.of()
        );
    }
}
