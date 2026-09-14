package com.adcheck.analysis.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisResultLifecycleTest {

    @Test
    void completesAnalysis() {
        Analysis analysis = analysis();
        analysis.startProcessing();

        analysis.complete();

        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getCompletedAt()).isNotNull();
        assertThat(analysis.getErrorMessage()).isNull();
    }

    @Test
    void completesAnalysisWithSerializedResult() {
        Analysis analysis = analysis();
        analysis.startProcessing();

        analysis.completeWithResult("{\"summary\":{\"findingCount\":0},\"findings\":[]}", false);

        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getResultJson())
                .isEqualTo("{\"summary\":{\"findingCount\":0},\"findings\":[]}");
        assertThat(analysis.getCompletedAt()).isNotNull();
        assertThat(analysis.getErrorMessage()).isNull();
        assertThat(analysis.isHasFinding()).isFalse();
    }

    @Test
    void completesAnalysisWithHasFindingFlagSetTrue() {
        Analysis analysis = analysis();
        analysis.startProcessing();

        analysis.completeWithResult("{\"summary\":{\"findingCount\":1},\"findings\":[{}]}", true);

        assertThat(analysis.isHasFinding()).isTrue();
    }

    @Test
    void failsAnalysisWithErrorMessage() {
        Analysis analysis = analysis();
        analysis.onCreate();
        var updatedAtBeforeFailure = analysis.getUpdatedAt();
        analysis.startProcessing();
        analysis.completeWithResult(String.valueOf(1), false);

        analysis.fail("AI 분석 호출에 실패했습니다.");

        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(analysis.getErrorMessage()).isEqualTo("AI 분석 호출에 실패했습니다.");
        assertThat(analysis.getResultJson()).isNull();
        assertThat(analysis.getCompletedAt()).isNull();
        assertThat(analysis.getUpdatedAt()).isEqualTo(updatedAtBeforeFailure);

        analysis.onUpdate();

        assertThat(analysis.getUpdatedAt()).isAfterOrEqualTo(updatedAtBeforeFailure);
    }

    private Analysis analysis() {
        return Analysis.create(
                "https://shop.example.com/product?id=123",
                "상품 페이지",
                "루테인 제품"
        );
    }
}
