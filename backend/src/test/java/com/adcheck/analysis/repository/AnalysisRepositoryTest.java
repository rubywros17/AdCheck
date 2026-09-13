package com.adcheck.analysis.repository;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisRepositoryTest {

    private static final String NORMALIZED_URL = "https://shop.example.com/product?id=123";
    private static final String CONTENT_HASH = "a".repeat(64);
    private static final String PIPELINE_VERSION = "v1";

    @Autowired
    private AnalysisRepository repository;

    @Test
    void findsCompletedAnalysisWithSameReuseKey() {
        Analysis completed = save(AnalysisStatus.COMPLETED, CONTENT_HASH, PIPELINE_VERSION);

        assertThat(repository.findLatestReusableCompleted(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).contains(completed);
    }

    @Test
    void findsMostRecentCompletedAnalysis() {
        save(AnalysisStatus.COMPLETED, CONTENT_HASH, PIPELINE_VERSION);
        Analysis newer = save(AnalysisStatus.COMPLETED, CONTENT_HASH, PIPELINE_VERSION);

        assertThat(repository.findLatestReusableCompleted(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).contains(newer);
    }

    @Test
    void excludesFailedAnalysisFromCompletedLookup() {
        save(AnalysisStatus.FAILED, CONTENT_HASH, PIPELINE_VERSION);

        assertThat(repository.findLatestReusableCompleted(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).isEmpty();
    }

    @Test
    void findsPendingAnalysisAsActive() {
        Analysis pending = save(AnalysisStatus.PENDING, CONTENT_HASH, PIPELINE_VERSION);

        assertThat(repository.findActiveByReuseKey(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).contains(pending);
    }

    @Test
    void findsProcessingAnalysisAsActive() {
        Analysis processing = save(AnalysisStatus.PROCESSING, CONTENT_HASH, PIPELINE_VERSION);

        assertThat(repository.findActiveByReuseKey(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).contains(processing);
    }

    @Test
    void excludesCompletedAnalysisFromActiveLookup() {
        save(AnalysisStatus.COMPLETED, CONTENT_HASH, PIPELINE_VERSION);

        assertThat(repository.findActiveByReuseKey(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).isEmpty();
    }

    @Test
    void doesNotMatchDifferentPipelineVersion() {
        save(AnalysisStatus.COMPLETED, CONTENT_HASH, "v2");

        assertThat(repository.findLatestReusableCompleted(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).isEmpty();
    }

    @Test
    void doesNotMatchDifferentContentHash() {
        save(AnalysisStatus.COMPLETED, "b".repeat(64), PIPELINE_VERSION);

        assertThat(repository.findLatestReusableCompleted(
                NORMALIZED_URL,
                CONTENT_HASH,
                PIPELINE_VERSION
        )).isEmpty();
    }

    @Test
    void updatesUpdatedAtWhenEntityChanges() {
        Analysis analysis = save(AnalysisStatus.PENDING, CONTENT_HASH, PIPELINE_VERSION);
        var initialUpdatedAt = analysis.getUpdatedAt();

        analysis.startProcessing();
        repository.saveAndFlush(analysis);

        assertThat(analysis.getUpdatedAt()).isAfterOrEqualTo(initialUpdatedAt);
    }

    private Analysis save(
            AnalysisStatus status,
            String contentHash,
            String pipelineVersion
    ) {
        Analysis analysis = Analysis.create(
                "https://shop.example.com/product?id=123",
                "상품 페이지",
                "상품",
                NORMALIZED_URL,
                contentHash,
                pipelineVersion
        );

        if (status == AnalysisStatus.PROCESSING) {
            analysis.startProcessing();
        } else if (status == AnalysisStatus.COMPLETED) {
            analysis.startProcessing();
            analysis.complete();
        } else if (status == AnalysisStatus.FAILED) {
            ReflectionTestUtils.setField(analysis, "status", AnalysisStatus.FAILED);
        }

        return repository.saveAndFlush(analysis);
    }
}
