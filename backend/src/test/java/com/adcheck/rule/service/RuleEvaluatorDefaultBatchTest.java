package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RuleEvaluator#evaluateBatch}의 기본 구현(오버라이드 없이 evaluate()를 규칙별로
 * 순회 호출)이 기존 평가기(CommonRuleEvaluator, 오버라이드 안 함)에서 그대로 동작하는지 확인.
 */
class RuleEvaluatorDefaultBatchTest {

    @Test
    void defaultBatchDelegatesToEvaluatePerRuleAndKeysByRuleCode() {
        CommonRuleEvaluator evaluator = new CommonRuleEvaluator();
        Claim claim = new Claim("claim-1", "100% 효과를 보장합니다.", PRODUCT_HEALTH_EFFECT_COPY,
                "page-1#copy: surrounding context checked");
        RuleAnalysisRequest request = new RuleAnalysisRequest(claim, List.of(), java.util.Set.of(), null);

        Rule c07 = CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT");
        Rule c24 = CanonicalRuleFixture.rule("C24_OVERCONSUMPTION");

        var batchResult = evaluator.evaluateBatch(List.of(c07, c24), request);

        assertThat(batchResult).hasSize(2);
        assertThat(batchResult.get("C07_ABSOLUTE_EFFECT")).isEqualTo(evaluator.evaluate(c07, request));
        assertThat(batchResult.get("C24_OVERCONSUMPTION")).isEqualTo(evaluator.evaluate(c24, request));
    }
}
