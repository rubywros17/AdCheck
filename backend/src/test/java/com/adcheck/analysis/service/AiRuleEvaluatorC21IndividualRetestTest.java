package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * C21_UNFAIR_COMPARISON을 batchExcludedRuleCodes에 추가하기 전에, 배치 축(같은 프롬프트에
 * 다른 Claim이 섞이는 evaluateAcrossClaims)이 아니라 Claim마다 개별 호출(evaluate)로 돌리면
 * 실제로 정확도가 회복되는지 확인한다. 배치 축 반복 측정에서 18/24(75%)로 낮았던 것과 비교.
 */
class AiRuleEvaluatorC21IndividualRetestTest {

    private static final String RULE_CODE = "C21_UNFAIR_COMPARISON";
    private static final int RUNS = 3;
    private static final long PACING_MS = 4_500;

    @Test
    void C21_개별_호출로_3회_재측정() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        List<Map<String, String>> cases = ValidationDatasetFixture.byRuleCode(List.of(RULE_CODE)).get(RULE_CODE);
        Assumptions.assumeTrue(cases != null && !cases.isEmpty(), "C21 검증 케이스 없음 - 스킵");

        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);
        Rule rule = CanonicalRuleFixture.rule(RULE_CODE);

        int correct = 0;
        int total = 0;
        for (int run = 1; run <= RUNS; run++) {
            System.out.printf("=== %d회차 ===%n", run);
            for (Map<String, String> testCase : cases) {
                RuleAnalysisRequest request = new RuleAnalysisRequest(
                        new Claim("v-" + RULE_CODE, testCase.get("claimText"),
                                PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스"),
                        List.of(), Set.of(),
                        new RuleAnalysisRequest.OfficialFunctions(
                                List.<RuleOfficialFunctionContext>of(), false, false));
                RuleEvaluation result = evaluator.evaluate(rule, request);
                String expected = testCase.get("expectedStatus");
                boolean match = result.status().name().equals(expected);
                total++;
                if (match) {
                    correct++;
                }
                System.out.printf("  expected=%-16s actual=%-16s(%s)  %s%n",
                        expected, result.status(), match ? "O" : "X", testCase.get("claimText"));
                sleep(PACING_MS);
            }
        }

        System.out.printf("%n개별 호출 결과: %d/%d (%.1f%%), %d회 반복   ·   배치 축 기준선: 18/24 (75%%)%n",
                correct, total, 100.0 * correct / total, RUNS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
