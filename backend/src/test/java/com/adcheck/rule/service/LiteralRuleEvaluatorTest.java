package com.adcheck.rule.service;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.adcheck.rule.service.RuleAnalysisRequest.*;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.*;
import static org.assertj.core.api.Assertions.*;

class LiteralRuleEvaluatorTest {
    private final LiteralRuleEvaluator evaluator = new LiteralRuleEvaluator();

    static RuleAnalysisRequest request(String text) {
        return new RuleAnalysisRequest(new Claim("claim-1", text, PRODUCT_HEALTH_EFFECT_COPY, "page-1#copy: surrounding context checked"),
                List.of(), Set.of(), null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // C08_RESULT_TIME_AMOUNT
        "C08_RESULT_TIME_AMOUNT|30일이면 효과를 봅니다.|MATCHED",
        "C08_RESULT_TIME_AMOUNT|3kg 감량했어요.|MATCHED",
        "C08_RESULT_TIME_AMOUNT|30일분입니다.|NOT_MATCHED",
        "C08_RESULT_TIME_AMOUNT|이 제품은 맛있습니다.|REVIEW_REQUIRED",
        // C09_COMPLETE_SOLUTION
        "C09_COMPLETE_SOLUTION|이제 고민 끝!|MATCHED",
        "C09_COMPLETE_SOLUTION|한 번에 끝!|MATCHED",
        "C09_COMPLETE_SOLUTION|두 제품을 한 번에 섭취하세요.|NOT_MATCHED",
        // G02_PERIOD
        "G02_PERIOD|2주만에 살이 빠집니다.|MATCHED",
        "G02_PERIOD|30일 다이어트!|MATCHED",
        "G02_PERIOD|4주분입니다.|NOT_MATCHED",
        // T01_WEIGHT_RESULT
        "T01_WEIGHT_RESULT|5kg 빠졌어요.|MATCHED",
        "T01_WEIGHT_RESULT|맛이 좋습니다.|REVIEW_REQUIRED",
        // T02_DETOX
        "T02_DETOX|디톡스 효과!|MATCHED",
        "T02_DETOX|맛이 좋습니다.|REVIEW_REQUIRED",
        // T03_DIET_DRUG
        "T03_DIET_DRUG|이 제품은 다이어트제입니다.|MATCHED",
        "T03_DIET_DRUG|맛이 좋습니다.|REVIEW_REQUIRED",
        // B05_ANTIBACTERIAL_WORD
        "B05_ANTIBACTERIAL_WORD|향균 효과가 있는 원료입니다.|MATCHED",
        "B05_ANTIBACTERIAL_WORD|항균 효과가 있는 원료입니다.|REVIEW_REQUIRED",
        // M03_ALCOHOL
        "M03_ALCOHOL|회식 전에 드시면 다음날 숙취 해소에 도움을 줍니다.|MATCHED",
        "M03_ALCOHOL|간 건강에 도움을 줄 수 있습니다.|NOT_MATCHED",
        // S01_PAIN
        "S01_PAIN|무릎 통증이 사라졌어요.|MATCHED",
        "S01_PAIN|관절/연골 건강에 도움을 줄 수 있습니다.|NOT_MATCHED"
    })
    void evaluatesBoundedLiteralTemplates(String code, String text, RuleEvaluation.Status expected) {
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule(code), request(text)).status()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "C08_RESULT_TIME_AMOUNT|30일이면 효과를 봅니다.",
        "S01_PAIN|무릎 통증이 사라졌어요."
    })
    void reviewsWhenContextUnverified(String code, String text) {
        var claim = new Claim("claim-1", text, UNKNOWN, null);
        var request = new RuleAnalysisRequest(claim, List.of(), Set.of(), null);
        var result = evaluator.evaluate(CanonicalRuleFixture.rule(code), request);
        assertThat(result.status()).isEqualTo(RuleEvaluation.Status.REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.CONTEXT_UNVERIFIED);
    }

    @org.junit.jupiter.api.Test
    void ruleCodesCoversExactlyTheNineLiteralAndHybridRules() {
        assertThat(evaluator.ruleCodes()).containsExactlyInAnyOrder(
                "C08_RESULT_TIME_AMOUNT", "C09_COMPLETE_SOLUTION",
                "G02_PERIOD", "T01_WEIGHT_RESULT", "T02_DETOX", "T03_DIET_DRUG", "B05_ANTIBACTERIAL_WORD",
                "M03_ALCOHOL", "S01_PAIN");
    }
}
