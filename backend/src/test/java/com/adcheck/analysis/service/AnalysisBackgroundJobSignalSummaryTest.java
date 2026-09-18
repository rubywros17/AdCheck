package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분석 1건이 끝날 때 남기는 요약 로그 중 "AI#1 신호" 부분을 만드는 로직을 검증한다.
 *
 * <p>이 로그를 넣은 목적은 <b>깔때기(risk signal로 평가할 규칙을 좁히는 방안)의 효과를 재는
 * 것</b>이다 — 지금은 AI#1이 만든 신호가 규칙 선택에 쓰이지 않고 프롬프트 힌트로만 들어가서,
 * 페이지당 신호가 몇 건·어떤 종류로 뜨는지 기록이 전혀 없었다. 신호가 적게 뜨면 깔때기가
 * 효과적이고 많이 뜨면 의미가 없는데, 지금은 어느 쪽인지 알 방법이 없다.
 *
 * <p>로그 한 줄이라 가볍게 보이지만 실패하면 분석 전체가 예외로 죽는 자리라, 값이 비거나
 * 이상해도 버티는지 확인해둔다.
 */
class AnalysisBackgroundJobSignalSummaryTest {

    @Test
    void 신호_종류별_개수를_이름순으로_요약한다() {
        String summary = AnalysisBackgroundJob.signalSummary(List.of(
                signal("TESTIMONIAL"), signal("SUPERLATIVE"),
                signal("TESTIMONIAL"), signal("ABSOLUTE_EFFECT"), signal("TESTIMONIAL")));

        // 이름순 고정 — 실행마다 순서가 달라지면 로그를 눈으로 비교할 수 없다.
        assertThat(summary).isEqualTo("ABSOLUTE_EFFECT 1, SUPERLATIVE 1, TESTIMONIAL 3");
    }

    @Test
    void 신호가_하나도_없으면_없음으로_표시한다() {
        assertThat(AnalysisBackgroundJob.signalSummary(List.of())).isEqualTo("없음");
    }

    @Test
    void 종류가_비어있는_신호는_세지_않는다() {
        // AI#1이 signalType을 빈 값이나 null로 내는 경우가 실제로 있어, 여기서 걸러진다.
        String summary = AnalysisBackgroundJob.signalSummary(List.of(
                signal("SUPERLATIVE"), signal(null), signal("  "), signal("")));

        assertThat(summary).isEqualTo("SUPERLATIVE 1");
    }

    @Test
    void 종류가_전부_비어있으면_없음으로_표시한다() {
        assertThat(AnalysisBackgroundJob.signalSummary(List.of(signal(null), signal("")))).isEqualTo("없음");
    }

    private static RiskSignalCandidate signal(String signalType) {
        return new RiskSignalCandidate("claim-1", "문구", signalType, 0.9, null);
    }
}
