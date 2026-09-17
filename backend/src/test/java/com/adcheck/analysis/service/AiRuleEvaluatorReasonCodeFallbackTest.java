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
 * 모델이 목록에 없는 reasonCode를 지어내도 판정(status)까지 통째로 버리지 않는지 검증한다.
 * 실제 운영 로그에서 {@code CONDITION_NOT_MET} 대신 {@code "CONDITION_NOT_MATCHED"}를 반환하는
 * 사례가 관찰됐고, 그때 멀쩡한 NOT_MATCHED 판정이 REVIEW_REQUIRED로 둔갑했다 —
 * C03_MEDICINE_CONFUSION이 반복해서 "AI 응답을 해석할 수 없어..."로 실패하던 원인이다.
 */
class AiRuleEvaluatorReasonCodeFallbackTest {

    private static RuleAnalysisRequest request() {
        Claim claim = new Claim("claim-1", "전국 약국 및 온라인몰에서 구매 가능합니다", PRODUCT_HEALTH_EFFECT_COPY, "본문 문단 확인됨");
        return new RuleAnalysisRequest(claim, List.of(), Set.of(),
                new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));
    }

    @Test
    void 존재하지_않는_reasonCode여도_status는_유지하고_기본코드로_보정한다() {
        FixedResponseGeminiClient geminiClient = new FixedResponseGeminiClient(
                "{\"needsOutsideContext\": false, \"status\": \"NOT_MATCHED\", "
                        + "\"reasonCode\": \"CONDITION_NOT_MATCHED\", \"reason\": \"단순 판매처 안내입니다.\"}");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);
        Rule rule = CanonicalRuleFixture.rule("C03_MEDICINE_CONFUSION");

        RuleEvaluation result = evaluator.evaluate(rule, request());

        assertThat(result.status()).isEqualTo(RuleEvaluation.Status.NOT_MATCHED);
        assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.CONDITION_NOT_MET);
        assertThat(result.reason()).isEqualTo("단순 판매처 안내입니다.");
    }

    @Test
    void MATCHED인데_reasonCode를_모르면_지지_코드로_보정한다() {
        FixedResponseGeminiClient geminiClient = new FixedResponseGeminiClient(
                "{\"status\": \"MATCHED\", \"reasonCode\": \"엉뚱한값\", \"reason\": \"의약품 오인 우려\"}");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        RuleEvaluation result = evaluator.evaluate(CanonicalRuleFixture.rule("C03_MEDICINE_CONFUSION"), request());

        assertThat(result.status()).isEqualTo(RuleEvaluation.Status.MATCHED);
        assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.SUPPORTED_CONDITION_CONFIRMED);
    }

    @Test
    void status_자체를_해석할_수_없으면_기존대로_REVIEW_REQUIRED로_떨어진다() {
        FixedResponseGeminiClient geminiClient = new FixedResponseGeminiClient(
                "{\"status\": \"WHO_KNOWS\", \"reasonCode\": \"CONDITION_NOT_MET\", \"reason\": \"...\"}");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        RuleEvaluation result = evaluator.evaluate(CanonicalRuleFixture.rule("C03_MEDICINE_CONFUSION"), request());

        assertThat(result.status()).isEqualTo(RuleEvaluation.Status.REVIEW_REQUIRED);
        assertThat(result.reason()).contains("해석할 수 없어");
    }

    /** 네트워크 호출 없이 정해진 JSON만 돌려주는 테스트 대역. */
    private static final class FixedResponseGeminiClient extends GeminiClient {
        private final String response;

        FixedResponseGeminiClient(String response) {
            super("test-api-key", "test-model");
            this.response = response;
        }

        @Override
        String generate(String prompt, boolean jsonMode) {
            return response;
        }
    }
}
