package com.adcheck.analysis.service;

import com.adcheck.finding.domain.FindingSource;
import com.adcheck.finding.domain.RiskLevel;
import com.adcheck.rule.service.RuleAnalysisResult;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.SEMANTIC_COMPARISON_REQUIRED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.UNSUPPORTED_RULE;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FindingAssembler#mostSevere}가 severity만으로 대표를 고르면 "evaluator가 아예
 * 없어 판정 시도조차 안 된"(reasonCode=UNSUPPORTED_RULE) 규칙이, severity가 우연히 더
 * 높다는 이유로 "실제로 판정을 시도한" 규칙을 밀어낼 수 있는 문제를 고쳤는지 검증한다.
 * DB/Spring 컨텍스트 없이 {@link RuleAnalysisResult.RuleMatch}를 직접 만들어 빠르게 확인 —
 * 생성자 인자는 이 메서드가 안 쓰므로 전부 null로 둬도 된다.
 */
class FindingAssemblerMostSevereTest {

    private final FindingAssembler assembler =
            new FindingAssembler(null, null, null, null, null, null, null, null, null);

    @Test
    void severity가_더_높아도_UNSUPPORTED_RULE은_실제_판정_결과에_밀린다() {
        RuleAnalysisResult.RuleMatch unsupported = ruleMatch("HIGH", UNSUPPORTED_RULE);
        RuleAnalysisResult.RuleMatch realJudgment = ruleMatch("CAUTION", SEMANTIC_COMPARISON_REQUIRED);

        var result = assembler.mostSevere(List.of(unsupported, realJudgment));

        assertThat(result).isPresent();
        assertThat(result.get().evaluation().reasonCode()).isEqualTo(SEMANTIC_COMPARISON_REQUIRED);
    }

    @Test
    void 둘_다_실제_판정이면_기존대로_severity가_높은_쪽이_대표() {
        RuleAnalysisResult.RuleMatch high = ruleMatch("HIGH", SEMANTIC_COMPARISON_REQUIRED);
        RuleAnalysisResult.RuleMatch caution = ruleMatch("CAUTION", SEMANTIC_COMPARISON_REQUIRED);

        var result = assembler.mostSevere(List.of(caution, high));

        assertThat(result).isPresent();
        assertThat(RiskLevel.fromSeverity(result.get().severity())).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void 대표_RuleMatch의_sources를_인용에_필요한_필드만_추려_옮긴다() {
        RuleAnalysisResult.SourceMetadata law = new RuleAnalysisResult.SourceMetadata(
                1L, "LAW-01", "식품 등의 표시·광고에 관한 법률", "LAW", "식약처",
                "https://www.law.go.kr/example", "2025", "UNVERIFIED",
                "제8조제1항", null, null);
        RuleAnalysisResult.RuleMatch match = ruleMatchWithSources(List.of(law));

        List<FindingSource> sources = FindingAssembler.toFindingSources(Optional.of(match));

        assertThat(sources).containsExactly(
                new FindingSource("식품 등의 표시·광고에 관한 법률", "제8조제1항", "https://www.law.go.kr/example"));
    }

    @Test
    void 대표_RuleMatch가_없으면_빈_리스트를_돌려준다() {
        assertThat(FindingAssembler.toFindingSources(Optional.empty())).isEmpty();
    }

    private static RuleAnalysisResult.RuleMatch ruleMatch(String severity, RuleEvaluation.ReasonCode reasonCode) {
        return new RuleAnalysisResult.RuleMatch(
                "claim-1", 1L, "SOME_RULE", "0.1", "COMMON", "CATEGORY", severity, "APPROVED",
                true, new RuleEvaluation(REVIEW_REQUIRED, reasonCode, "테스트용 사유"),
                List.of(), List.of());
    }

    private static RuleAnalysisResult.RuleMatch ruleMatchWithSources(List<RuleAnalysisResult.SourceMetadata> sources) {
        return new RuleAnalysisResult.RuleMatch(
                "claim-1", 1L, "SOME_RULE", "0.1", "COMMON", "CATEGORY", "HIGH", "APPROVED",
                true, new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED, "테스트용 사유"),
                sources, List.of());
    }
}
