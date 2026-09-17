package com.adcheck.rule.service;

import com.adcheck.analysis.service.AiRuleEvaluator;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.NON_PRODUCT_INFORMATION;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.UNKNOWN;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.CONTEXT_UNVERIFIED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.EXCEPTION_CONFIRMED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.MISSING_CLAIM_TEXT;
import static com.adcheck.rule.service.RuleEvaluation.Status.NOT_MATCHED;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiRuleEvaluator의 사전 체크(AI 호출 전 필터링) 로직만 검증한다 — 실제 Gemini 호출은
 * 여기서 안 하므로 GeminiClient에 null을 넘겨도 된다(사전 체크에서 전부 막히므로 도달 안 함).
 */
class AiRuleEvaluatorPreCheckTest {

    private final AiRuleEvaluator evaluator = new AiRuleEvaluator(null, null);

    private static RuleAnalysisRequest request(Claim claim) {
        return new RuleAnalysisRequest(claim, List.of(), Set.of(),
                new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));
    }

    @Test
    void ruleCodesCoversExactly39InterpretiveAndHybridRules() {
        assertThat(evaluator.ruleCodes()).hasSize(39);
        assertThat(evaluator.ruleCodes()).contains("C01_DISEASE_PREVENTION", "P03_DISEASE_GUT", "L02_UV");
        // 원래 리터럴형이었으나 정규식으로는 예외 판단이 불가해(항상 REVIEW_REQUIRED) 이관한 2개.
        assertThat(evaluator.ruleCodes()).contains("C22_SUPERLATIVE", "C30_NATURAL_FREE");
        // 리터럴형/데이터부재형/특수, 그리고 텍스트만으로 예외 판단 가능한 혼합 2개
        // (M03_ALCOHOL, S01_PAIN, LiteralRuleEvaluator 담당)는 이 평가기 대상이 아니다.
        assertThat(evaluator.ruleCodes()).doesNotContain(
                "C05_FUNCTION_EXCEED", "C09_COMPLETE_SOLUTION", "C06_OFFICIAL_FUNCTION", "C25_REQUIRED_IDENTITY",
                "M03_ALCOHOL", "S01_PAIN");
    }

    @Test
    void missingClaimTextIsReviewRequiredWithoutCallingGemini() {
        Claim claim = new Claim("claim-1", "  ", PRODUCT_HEALTH_EFFECT_COPY, "evidence");
        RuleEvaluation result = evaluator.evaluate(
                CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION"), request(claim));

        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(MISSING_CLAIM_TEXT);
    }

    @Test
    void unknownContextIsReviewRequiredWithoutCallingGemini() {
        Claim claim = new Claim("claim-1", "감기 예방에 좋아요", UNKNOWN, null);
        RuleEvaluation result = evaluator.evaluate(
                CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION"), request(claim));

        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(CONTEXT_UNVERIFIED);
    }

    @Test
    void blankContextEvidenceIsReviewRequiredEvenWithKnownContext() {
        Claim claim = new Claim("claim-1", "감기 예방에 좋아요", PRODUCT_HEALTH_EFFECT_COPY, " ");
        RuleEvaluation result = evaluator.evaluate(
                CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION"), request(claim));

        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(CONTEXT_UNVERIFIED);
    }

    @Test
    void nonProductInformationIsAutoNotMatchedWithoutCallingGemini() {
        Claim claim = new Claim("claim-1", "김OO님 후기: 3개월 먹었는데 좋아졌어요", NON_PRODUCT_INFORMATION, "구매자 리뷰로 확인됨");
        RuleEvaluation result = evaluator.evaluate(
                CanonicalRuleFixture.rule("C13_TESTIMONIAL"), request(claim));

        assertThat(result.status()).isEqualTo(NOT_MATCHED);
        assertThat(result.reasonCode()).isEqualTo(EXCEPTION_CONFIRMED);
    }
}
