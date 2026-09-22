package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * 조사용: 하나의 Claim이 여러 규칙에 동시에 걸릴 때 일부는 MATCHED, 일부는 REVIEW_REQUIRED가
 * 실제로 함께 나오는지 확인한다 — {@code FindingAssembler.assemble()}의 "matched가 하나라도
 * 있으면 reviewRequired는 완전히 버려진다" 로직이 실제로 영향을 미칠 상황인지 실측한다.
 *
 * <p><b>결과(2026-09-22)</b>: 실제 사례 BAD-09(3개 규칙 동시 태그)는 이번 실행에선 셋 다
 * MATCHED로 일치해 혼재가 안 나왔지만, C21_UNFAIR_COMPARISON의 불안정 케이스("다른 제품보다
 * 흡수가 잘 되는 이유가 있습니다")를 다른 COMMON 규칙과 나란히 돌리니 <b>C21=MATCHED,
 * C22_SUPERLATIVE=REVIEW_REQUIRED, C27_FUNCTION_SYNERGY=REVIEW_REQUIRED</b>로 같은 Claim
 * 안에서 실제로 혼재가 확인됐다 — 셋 다 COMMON 스코프라 모든 Claim에서 항상 함께 평가되므로
 * 우연이 아니라 구조적으로 재현 가능하다. 실제 파이프라인이라면 이 Claim의 Finding은 C21
 * 기준으로만 만들어지고 C22·C27의 REVIEW_REQUIRED 판단은 완전히 사라진다.
 */
class MatchedReviewRequiredCoexistCheckTest {

    private static final String CLAIM_TEXT = "외래진료 1위 치주질환 / 피가 난다 / 고름 / 붓는 잇몸";

    @Test
    void BAD_09_문구를_B01_B03_C04에_동시에_돌려_상태_조합을_확인한다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        AiRuleEvaluator evaluator = new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        RuleOfficialFunctionContext propolis = new RuleOfficialFunctionContext(550L, "프로폴리스추출물",
                "항산화 · 구강에서의 항균작용에 도움을 줄 수 있음 / ※구강에서의 항균작용은 구강에 "
                        + "직접 접촉할 수 있는 형태에 한하며, 섭취량을 적용하지 않음",
                "고시형", null);
        RuleAnalysisRequest.OfficialFunctions officialFunctions =
                new RuleAnalysisRequest.OfficialFunctions(List.of(propolis), false, false);

        RuleAnalysisRequest request = new RuleAnalysisRequest(
                new Claim("claim-1", CLAIM_TEXT, PRODUCT_HEALTH_EFFECT_COPY, "검증용 실제 사례(BAD-09)"),
                List.of(), Set.of(550L), officialFunctions);

        for (String ruleCode : List.of("B01_IMMUNE_INFLAMMATION", "B03_OTHER_ORAL", "C04_DISEASE_INFO_LINK")) {
            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            RuleEvaluation result = evaluator.evaluate(rule, request);
            System.out.printf("%-28s -> %-16s (%s) 근거: %s%n",
                    ruleCode, result.status(), result.reasonCode(), result.reason());
        }
    }

    /**
     * 두 번째 시도: 이미 불안정하다고 확인된 C21_UNFAIR_COMPARISON의 문제 claim("다른 제품보다
     * 흡수가 잘 되는 이유가 있습니다")을, 이 claim에서도 항상 함께 평가되는 다른 COMMON 규칙들과
     * 나란히 돌려서 실제로 MATCHED/REVIEW_REQUIRED가 한 Claim 안에서 섞이는지 확인한다.
     */
    @Test
    void C21_불안정_문구를_다른_COMMON_규칙과_나란히_돌려_상태_조합을_확인한다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        AiRuleEvaluator evaluator = new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);
        String claimText = "다른 제품보다 흡수가 잘 되는 이유가 있습니다";
        RuleAnalysisRequest request = new RuleAnalysisRequest(
                new Claim("claim-1", claimText, PRODUCT_HEALTH_EFFECT_COPY, "검증용(C21 불안정 케이스)"),
                List.of(), Set.of(),
                new RuleAnalysisRequest.OfficialFunctions(List.of(), false, false));

        for (String ruleCode : List.of("C01_DISEASE_PREVENTION", "C02_DISEASE_TREATMENT",
                "C04_DISEASE_INFO_LINK", "C14_EXPERT_ENDORSEMENT", "C21_UNFAIR_COMPARISON",
                "C22_SUPERLATIVE", "C27_FUNCTION_SYNERGY")) {
            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            RuleEvaluation result = evaluator.evaluate(rule, request);
            System.out.printf("%-28s -> %-16s (%s) 근거: %s%n",
                    ruleCode, result.status(), result.reasonCode(), result.reason());
        }
    }
}
