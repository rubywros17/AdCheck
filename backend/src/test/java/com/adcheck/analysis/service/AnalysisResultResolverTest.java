package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisProperties;
import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.analysis.repository.AnalysisRepository;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisResultResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-13T03:00:00Z");
    private static final Duration REUSE_TTL_WITH_FINDING = Duration.ofDays(7);
    private static final Duration REUSE_TTL_CLEAN = Duration.ofDays(30);
    private static final String PIPELINE_VERSION = "v1";

    private AnalysisRepository repository;
    private AnalysisProperties properties;
    private AnalysisUrlNormalizer urlNormalizer;
    private AnalysisRequestFingerprint requestFingerprint;
    private AnalysisResultJsonCodec resultJsonCodec;
    private AnalysisResultResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisRepository.class);
        properties = new AnalysisProperties();
        properties.setPipelineVersion(PIPELINE_VERSION);
        properties.setReuseTtlWithFinding(REUSE_TTL_WITH_FINDING);
        properties.setReuseTtlClean(REUSE_TTL_CLEAN);
        urlNormalizer = new AnalysisUrlNormalizer();
        requestFingerprint = new AnalysisRequestFingerprint();
        resultJsonCodec = new AnalysisResultJsonCodec(new ObjectMapper());
        resolver = new AnalysisResultResolver(
                urlNormalizer,
                requestFingerprint,
                properties,
                repository,
                resultJsonCodec,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        when(repository.findLatestReusableCompleted(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findActiveByReuseKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void reusesFreshCompletedAnalysisWithValidResultJson() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        Analysis completed = completedAnalysis(1L, NOW.minus(Duration.ofDays(1)), validResultJson());
        stubCompleted(request, completed);

        AnalysisResultResolution resolution = resolver.resolve(request);

        assertThat(resolution.type()).isEqualTo(AnalysisResultResolution.Type.REUSED);
    }

    @Test
    void reusedResolutionContainsExistingAnalysisId() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(42L, NOW.minus(Duration.ofHours(1)), validResultJson())
        );

        AnalysisResultResolution resolution = resolver.resolve(request);

        assertThat(resolution).isInstanceOfSatisfying(
                AnalysisResultResolution.Reused.class,
                reused -> assertThat(reused.analysisId()).isEqualTo(42L)
        );
    }

    @Test
    void restoresStoredSnapshotWhenReused() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        AnalysisResultSnapshot expected = snapshot();
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(Duration.ofHours(1)),
                        resultJsonCodec.serialize(expected)
                )
        );

        AnalysisResultResolution resolution = resolver.resolve(request);

        assertThat(resolution).isInstanceOfSatisfying(
                AnalysisResultResolution.Reused.class,
                reused -> assertThat(reused.snapshot()).isEqualTo(expected)
        );
    }

    @Test
    void reusesCompletedAnalysisWithinTtl() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(REUSE_TTL_WITH_FINDING).plusNanos(1),
                        validResultJson()
                )
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.REUSED);
    }

    @Test
    void returnsNewWhenCompletedAnalysisIsExpiredAndNoActiveExists() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(REUSE_TTL_WITH_FINDING).minusNanos(1),
                        validResultJson()
                )
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    @Test
    void returnsInProgressWhenCompletedAnalysisIsExpiredAndActiveExists() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(REUSE_TTL_WITH_FINDING).minusNanos(1),
                        validResultJson()
                )
        );
        stubActive(request, activeAnalysis(2L, AnalysisStatus.PROCESSING));

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.IN_PROGRESS);
    }

    @Test
    void treatsExactTtlBoundaryAsFresh() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(REUSE_TTL_WITH_FINDING), validResultJson())
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.REUSED);
    }

    @Test
    void reusesCleanCompletedAnalysisWithinLongerTtl() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(REUSE_TTL_CLEAN).plusNanos(1),
                        validResultJson(),
                        false
                )
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.REUSED);
    }

    @Test
    void returnsNewWhenCleanCompletedAnalysisExceedsLongerTtl() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(REUSE_TTL_CLEAN).minusNanos(1),
                        validResultJson(),
                        false
                )
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    @Test
    void treatsExactCleanTtlBoundaryAsFresh() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(REUSE_TTL_CLEAN), validResultJson(), false)
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.REUSED);
    }

    @Test
    void cleanAnalysisPastWithFindingTtlIsStillReusedUnderLongerCleanTtl() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(
                        1L,
                        NOW.minus(REUSE_TTL_WITH_FINDING).minusNanos(1),
                        validResultJson(),
                        false
                )
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.REUSED);
    }

    @Test
    void returnsInProgressForPendingAnalysis() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubActive(request, activeAnalysis(10L, AnalysisStatus.PENDING));

        AnalysisResultResolution resolution = resolver.resolve(request);

        assertThat(resolution).isInstanceOfSatisfying(
                AnalysisResultResolution.InProgress.class,
                inProgress -> {
                    assertThat(inProgress.analysisId()).isEqualTo(10L);
                    assertThat(inProgress.status()).isEqualTo(AnalysisStatus.PENDING);
                }
        );
    }

    @Test
    void returnsInProgressForProcessingAnalysis() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubActive(request, activeAnalysis(11L, AnalysisStatus.PROCESSING));

        AnalysisResultResolution resolution = resolver.resolve(request);

        assertThat(resolution).isInstanceOfSatisfying(
                AnalysisResultResolution.InProgress.class,
                inProgress -> assertThat(inProgress.status())
                        .isEqualTo(AnalysisStatus.PROCESSING)
        );
    }

    @Test
    void prefersFreshCompletedAnalysisOverActiveAnalysis() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), validResultJson())
        );
        stubActive(request, activeAnalysis(2L, AnalysisStatus.PROCESSING));

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.REUSED);
        AnalysisReuseKey key = reuseKey(request);
        verify(repository, never()).findActiveByReuseKey(
                key.normalizedUrl(),
                key.contentHash(),
                key.pipelineVersion()
        );
    }

    @Test
    void returnsNewForCompletedAnalysisWithoutResultJson() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), null)
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    @Test
    void returnsInProgressWhenCompletedResultJsonIsMissingAndActiveExists() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), null)
        );
        stubActive(request, activeAnalysis(2L, AnalysisStatus.PENDING));

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.IN_PROGRESS);
    }

    @Test
    void doesNotReuseMalformedResultJson() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), "{malformed")
        );

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    @Test
    void returnsNewWhenOnlyFailedAnalysisExists() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");

        assertThat(resolver.resolve(request).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    @Test
    void doesNotReuseAnalysisWithDifferentContentHash() {
        CreateAnalysisRequest existingRequest =
                request("https://shop.example.com/product?id=123", "기존 문구");
        stubCompleted(
                existingRequest,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), validResultJson())
        );

        CreateAnalysisRequest changedRequest =
                request("https://shop.example.com/product?id=123", "변경 문구");

        assertThat(resolver.resolve(changedRequest).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    @Test
    void doesNotReuseAnalysisWithDifferentPipelineVersion() {
        CreateAnalysisRequest request = request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                request,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), validResultJson())
        );
        properties.setPipelineVersion("v2");

        AnalysisResultResolution resolution = resolver.resolve(request);

        assertThat(resolution.type()).isEqualTo(AnalysisResultResolution.Type.NEW);
        assertThat(resolution.reuseKey().pipelineVersion()).isEqualTo("v2");
    }

    @Test
    void doesNotReuseAnalysisWithDifferentNormalizedUrl() {
        CreateAnalysisRequest existingRequest =
                request("https://shop.example.com/product?id=123", "광고 문구");
        stubCompleted(
                existingRequest,
                completedAnalysis(1L, NOW.minus(Duration.ofHours(1)), validResultJson())
        );

        CreateAnalysisRequest anotherProduct =
                request("https://shop.example.com/product?id=456", "광고 문구");

        assertThat(resolver.resolve(anotherProduct).type())
                .isEqualTo(AnalysisResultResolution.Type.NEW);
    }

    private void stubCompleted(CreateAnalysisRequest request, Analysis analysis) {
        AnalysisReuseKey key = reuseKey(request);
        when(repository.findLatestReusableCompleted(
                key.normalizedUrl(),
                key.contentHash(),
                key.pipelineVersion()
        )).thenReturn(Optional.of(analysis));
    }

    private void stubActive(CreateAnalysisRequest request, Analysis analysis) {
        AnalysisReuseKey key = reuseKey(request);
        when(repository.findActiveByReuseKey(
                key.normalizedUrl(),
                key.contentHash(),
                key.pipelineVersion()
        )).thenReturn(Optional.of(analysis));
    }

    private AnalysisReuseKey reuseKey(CreateAnalysisRequest request) {
        return new AnalysisReuseKey(
                urlNormalizer.normalize(request.pageUrl()),
                requestFingerprint.generate(request),
                properties.getPipelineVersion()
        );
    }

    private Analysis completedAnalysis(Long id, Instant completedAt, String resultJson) {
        return completedAnalysis(id, completedAt, resultJson, true);
    }

    private Analysis completedAnalysis(Long id, Instant completedAt, String resultJson, boolean hasFinding) {
        Analysis analysis = mock(Analysis.class);
        when(analysis.getId()).thenReturn(id);
        when(analysis.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(analysis.getCompletedAt()).thenReturn(completedAt);
        when(analysis.getResultJson()).thenReturn(resultJson);
        when(analysis.isHasFinding()).thenReturn(hasFinding);
        return analysis;
    }

    private Analysis activeAnalysis(Long id, AnalysisStatus status) {
        Analysis analysis = mock(Analysis.class);
        when(analysis.getId()).thenReturn(id);
        when(analysis.getStatus()).thenReturn(status);
        return analysis;
    }

    private String validResultJson() {
        return resultJsonCodec.serialize(snapshot());
    }

    private AnalysisResultSnapshot snapshot() {
        return new AnalysisResultSnapshot(
                new AnalysisResultSnapshot.Summary(1, 1),
                List.of(new AnalysisResultSnapshot.Finding(
                        "시력을 회복합니다.",
                        "#claim",
                        RiskLevel.CAUTION,
                        "FUNCTION_CLAIM",
                        "공식 기능성보다 강한 표현일 가능성이 있습니다.",
                        "눈 건강에 도움을 줄 수 있음",
                        List.of(),
                        List.of()
                ))
        );
    }

    private CreateAnalysisRequest request(String pageUrl, String content) {
        return new CreateAnalysisRequest(
                pageUrl,
                "상품 페이지",
                "루테인 제품",
                List.of(new PageTextEvidence(content, "#claim")),
                List.of(new PageImageEvidence(
                        "https://shop.example.com/image.jpg",
                        "상품 이미지"
                ))
        );
    }
}
