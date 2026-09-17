package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiRuleEvaluator}가 {@link RuleGroundingProvider}로 가져온 근거 문단을 프롬프트에
 * 실제로 포함시키는지, 근거가 없을 때는 그 섹션 없이 기존과 동일하게 폴백하는지 검증한다.
 * 실제 Gemini 호출 없이, 프롬프트를 가로채는 {@link RecordingGeminiClient}로 대신한다.
 */
class AiRuleEvaluatorGroundingTest {

    private static RuleAnalysisRequest request(Claim claim) {
        return new RuleAnalysisRequest(claim, List.of(), Set.of(),
                new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));
    }

    @Test
    void 근거_문단이_있으면_프롬프트에_포함된다() {
        RecordingGeminiClient geminiClient = new RecordingGeminiClient();
        RuleGroundingProvider groundingProvider = (rule, claimText) ->
                List.of("[REVIEW-03] 특정 성분의 효능을 과장 없이 공식 인정 범위 내에서만 표시해야 한다.");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, groundingProvider);

        Claim claim = new Claim("claim-1", "이 제품은 최고의 효과를 보장합니다.", PRODUCT_HEALTH_EFFECT_COPY, "본문 문단 확인됨");
        Rule rule = CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION");

        RuleEvaluation result = evaluator.evaluate(rule, request(claim));

        assertThat(geminiClient.capturedPrompt).contains("[근거 문서 원문]");
        assertThat(geminiClient.capturedPrompt).contains("[REVIEW-03] 특정 성분의 효능을 과장 없이 공식 인정 범위 내에서만 표시해야 한다.");
        assertThat(result.status()).isEqualTo(RuleEvaluation.Status.NOT_MATCHED);
    }

    @Test
    void 근거_문단이_없으면_해당_섹션_없이_기존과_동일하게_폴백한다() {
        RecordingGeminiClient geminiClient = new RecordingGeminiClient();
        RuleGroundingProvider groundingProvider = (rule, claimText) -> List.of();
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, groundingProvider);

        Claim claim = new Claim("claim-1", "이 제품은 최고의 효과를 보장합니다.", PRODUCT_HEALTH_EFFECT_COPY, "본문 문단 확인됨");
        Rule rule = CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION");

        evaluator.evaluate(rule, request(claim));

        assertThat(geminiClient.capturedPrompt).doesNotContain("[근거 문서 원문]");
    }

    @Test
    void groundingProvider가_null이어도_기존과_동일하게_동작한다() {
        RecordingGeminiClient geminiClient = new RecordingGeminiClient();
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        Claim claim = new Claim("claim-1", "이 제품은 최고의 효과를 보장합니다.", PRODUCT_HEALTH_EFFECT_COPY, "본문 문단 확인됨");
        Rule rule = CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION");

        evaluator.evaluate(rule, request(claim));

        assertThat(geminiClient.capturedPrompt).doesNotContain("[근거 문서 원문]");
    }

    /** 실제 네트워크 호출 없이 프롬프트만 가로채고, 항상 같은 판정 JSON을 돌려주는 테스트 대역. */
    private static final class RecordingGeminiClient extends GeminiClient {
        String capturedPrompt;

        RecordingGeminiClient() {
            super("test-api-key", "test-model");
        }

        @Override
        String generate(String prompt, boolean jsonMode) {
            this.capturedPrompt = prompt;
            return "{\"status\": \"NOT_MATCHED\", \"reasonCode\": \"CONDITION_NOT_MET\", \"reason\": \"테스트용 고정 응답\"}";
        }
    }
}
