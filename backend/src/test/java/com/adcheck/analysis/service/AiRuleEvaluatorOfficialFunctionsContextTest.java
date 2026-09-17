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
 * E04_ALIAS/B03_OTHER_ORAL이 검증 테스트에서 나쁘게 나온 게 프롬프트 결함이 아니라
 * {@code officialFunctions}를 항상 빈 리스트로 넘겨서(운영에서는 ②단계가 실제 데이터를
 * 채워줌)인지 확인한다 — 실제와 비슷한 공식 기능성 컨텍스트를 채워 같은 문장을 재평가.
 * GEMINI_API_KEY 없으면 스킵. 호출 4회(E04 3건 + B03 1건).
 */
class AiRuleEvaluatorOfficialFunctionsContextTest {

    private static RuleAnalysisRequest requestWith(String claimText, List<RuleOfficialFunctionContext> functions) {
        Claim claim = new Claim("claim-1", claimText, PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스");
        return new RuleAnalysisRequest(claim, List.of(), Set.of(),
                new RuleAnalysisRequest.OfficialFunctions(functions, false, false));
    }

    @Test
    void E04_ALIAS_오메가3_공식기능성_채워서_재평가() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);
        Rule rule = CanonicalRuleFixture.rule("E04_ALIAS");

        List<RuleOfficialFunctionContext> omega3Functions = List.of(
                new RuleOfficialFunctionContext(1L, "정제어유(오메가-3 지방산 함유)", "혈행 개선에 도움을 줄 수 있음", "고시형", null));

        record Case(String claimText, String expected) {
        }
        List<Case> cases = List.of(
                new Case("오메가3 500mg", "MATCHED"),
                new Case("EPA·DHA 함유 유지(오메가3) 500mg", "NOT_MATCHED"),
                new Case("오메가3(지방산 복합체) 500mg", "REVIEW_REQUIRED"));

        for (Case c : cases) {
            RuleEvaluation result = callWithRetry(
                    () -> evaluator.evaluate(rule, requestWith(c.claimText(), omega3Functions)), c.claimText());
            boolean match = result.status().name().equals(c.expected());
            System.out.printf("claim=\"%s\" expected=%s actual=%s(%s) %s / %s%n",
                    c.claimText(), c.expected(), result.status(), match ? "O" : "X",
                    result.reasonCode(), result.reason());
            sleep(4500);
        }
    }

    @Test
    void B03_OTHER_ORAL_구강_공식기능성_채워서_재평가() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);
        Rule rule = CanonicalRuleFixture.rule("B03_OTHER_ORAL");

        List<RuleOfficialFunctionContext> oralFunctions = List.of(
                new RuleOfficialFunctionContext(2L, "인디언주엽나무열매껌추출물", "구강 내 항균작용에 도움을 줄 수 있음", "고시형", null));

        String claimText = "외래진료 1위 치주질환 / 피가 난다 / 고름 / 붓는 잇몸";
        RuleEvaluation result = callWithRetry(
                () -> evaluator.evaluate(rule, requestWith(claimText, oralFunctions)), claimText);
        System.out.printf("claim=\"%s\" expected=MATCHED actual=%s(%s) %s / %s%n",
                claimText, result.status(), result.status().name().equals("MATCHED") ? "O" : "X",
                result.reasonCode(), result.reason());
    }

    private static RuleEvaluation callWithRetry(java.util.function.Supplier<RuleEvaluation> call, String label) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                sleep(3000L * (attempt + 1));
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
