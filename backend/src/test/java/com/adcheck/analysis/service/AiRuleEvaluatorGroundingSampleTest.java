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
 * RAG grounding이 실제 Gemini 판정을 개선하는지 확인하는 표본 테스트. C22_SUPERLATIVE/
 * C30_NATURAL_FREE는 정규식(LiteralRuleEvaluator)로는 예외 조건을 절대 확정 못 해 항상
 * REVIEW_REQUIRED만 반환하던 규칙 — grounding 없이/있이 같은 Claim을 돌려 결과를 비교한다.
 * <code>docs/validation_dataset_v0.1.csv</code>엔 아직 이 두 규칙 케이스가 없어(이관 전
 * 기준으로 작성됨) 여기서 직접 만든 샘플을 쓴다. GEMINI_API_KEY 없으면 스킵.
 *
 * <p>이 환경엔 DB가 없어 {@code RuleSourceResolver}(JPA)를 못 쓰므로, rule_sources에
 * 실제로 적재된 sourceId(<code>rules_v0.1.csv</code>에서 직접 확인: C22_SUPERLATIVE →
 * REVIEW-02/REVIEW-03, C30_NATURAL_FREE → LAW-04)를 하드코딩해서 그 범위로
 * {@link RagRetrievalService#search}를 직접 호출한다 — 운영에서는 이 조회를
 * {@code RagRuleGroundingProvider}가 자동으로 한다.
 */
class AiRuleEvaluatorGroundingSampleTest {

    private static RuleAnalysisRequest request(Claim claim) {
        return new RuleAnalysisRequest(claim, List.of(), Set.of(),
                new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));
    }

    private static Claim claim(String text) {
        return new Claim("claim-1", text, PRODUCT_HEALTH_EFFECT_COPY, "상세페이지 본문 확인됨");
    }

    @Test
    void C22_SUPERLATIVE_grounding_유무_비교() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        GeminiEmbeddingClient embeddingClient = new GeminiEmbeddingClient(apiKey, "gemini-embedding-001");
        RagRetrievalService ragService = new RagRetrievalService(embeddingClient);
        RuleGroundingProvider grounded = (rule, claimText) ->
                ragService.search(claimText, List.of("REVIEW-02", "REVIEW-03"), 3).stream()
                        .map(e -> "[" + e.sourceId() + "] " + e.text())
                        .toList();

        Rule rule = CanonicalRuleFixture.rule("C22_SUPERLATIVE");
        String[] samples = {
                "업계 최초로 개발된 고순도 원료를 사용합니다.",
                "2024년 12월 기준 자사 임상시험 결과, 동일 성분 함량 국내 제품 중 최대 함량입니다.",
                "국내 유일한 고순도 제품입니다."
        };

        for (String text : samples) {
            RuleEvaluation without = new AiRuleEvaluator(geminiClient, null).evaluate(rule, request(claim(text)));
            RuleEvaluation with = new AiRuleEvaluator(geminiClient, grounded).evaluate(rule, request(claim(text)));
            System.out.println("=== C22_SUPERLATIVE: \"" + text + "\" ===");
            System.out.println("  grounding 없음: " + without.status() + " / " + without.reasonCode() + " / " + without.reason());
            System.out.println("  grounding 있음: " + with.status() + " / " + with.reasonCode() + " / " + with.reason());
        }
    }

    @Test
    void C30_NATURAL_FREE_grounding_유무_비교() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        GeminiEmbeddingClient embeddingClient = new GeminiEmbeddingClient(apiKey, "gemini-embedding-001");
        RagRetrievalService ragService = new RagRetrievalService(embeddingClient);
        RuleGroundingProvider grounded = (rule, claimText) ->
                ragService.search(claimText, List.of("LAW-04"), 3).stream()
                        .map(e -> "[" + e.sourceId() + "] " + e.text())
                        .toList();

        Rule rule = CanonicalRuleFixture.rule("C30_NATURAL_FREE");
        String[] samples = {
                "천연 원료만 사용했습니다.",
                "무첨가, 무검출입니다.",
                "합성첨가물 없이 천연 유래 원료만으로 제조했습니다."
        };

        for (String text : samples) {
            RuleEvaluation without = new AiRuleEvaluator(geminiClient, null).evaluate(rule, request(claim(text)));
            RuleEvaluation with = new AiRuleEvaluator(geminiClient, grounded).evaluate(rule, request(claim(text)));
            System.out.println("=== C30_NATURAL_FREE: \"" + text + "\" ===");
            System.out.println("  grounding 없음: " + without.status() + " / " + without.reasonCode() + " / " + without.reason());
            System.out.println("  grounding 있음: " + with.status() + " / " + with.reasonCode() + " / " + with.reason());
        }
    }
}
