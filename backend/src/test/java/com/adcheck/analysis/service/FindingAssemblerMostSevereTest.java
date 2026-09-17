package com.adcheck.analysis.service;

import com.adcheck.finding.domain.RiskLevel;
import com.adcheck.rule.service.RuleAnalysisResult;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static RuleAnalysisResult.RuleMatch ruleMatch(String severity, RuleEvaluation.ReasonCode reasonCode) {
        return new RuleAnalysisResult.RuleMatch(
                "claim-1", 1L, "SOME_RULE", "0.1", "COMMON", "CATEGORY", severity, "APPROVED",
                true, new RuleEvaluation(REVIEW_REQUIRED, reasonCode, "테스트용 사유"),
                List.of(), List.of());
    }
}
