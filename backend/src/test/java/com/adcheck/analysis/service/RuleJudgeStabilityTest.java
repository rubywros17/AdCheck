package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * 같은 (규칙, 문장) 조합을 여러 번 판정해서 규칙 판정(AI#3)이 얼마나 흔들리는지 잰다.
 *
 * <p>배경: {@code ClaimExtractionStabilityTest}로 추출 단계의 편차는 프롬프트 명문화로 잡았다
 * (안정도 60%→100%). 그런데 실측에서 <b>문구가 완전히 같은 Claim에 카테고리가 다르게 붙는</b>
 * 현상이 따로 관찰됐다 — happytori.kr의 "내장지방면적 감소"가 한 번은 FUNCTION_EXCEED, 다른
 * 번은 MEDICINE_CONFUSION이었다. {@code category}는 AI가 고르는 값이 아니라 매칭된 Rule의
 * 속성이므로, 이는 <b>어떤 규칙이 MATCHED로 걸리는지가 실행마다 달라졌다</b>는 뜻이다. 그래서
 * 재는 대상은 "규칙 하나당 판정 결과가 실행마다 같은가"다.
 *
 * <p>추출 단계와 달리 여기서는 판정 대상이 여러 규칙이므로, 규칙별로 몇 번 흔들렸는지를 나눠서
 * 본다 — 전부 흔들리는지, 특정 규칙만 흔들리는지에 따라 대응이 달라진다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵. 호출 수 = 규칙 수 × REPEAT(기본 4×3=12회).
 */
class RuleJudgeStabilityTest {

    private static final int REPEAT = 3;
    private static final long PACING_MS = 4_000;

    /** 실측에서 카테고리가 번갈아 붙었던 문구를 포함해, 판단이 갈릴 만한 문장들을 고른다. */
    private static final List<String> CLAIMS = List.of(
            "내장지방면적 감소",
            "1정당 130mg 함유, 주원료 기준 100% 충족합니다.",
            "탄수화물, 지방, 단백질 대사에 관여하여 에너지를 만드는 데 필요합니다.");

    /** 위 문장들이 실제로 걸릴 만한 COMMON 규칙들. */
    private static final List<String> RULE_CODES = List.of(
            "C05_FUNCTION_EXCEED",
            "C03_MEDICINE_CONFUSION",
            "C11_SUB_INGREDIENT_FUNCTION",
            "C22_SUPERLATIVE");

    @Test
    void 같은_규칙과_문장을_반복_판정해_편차를_잰다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        List<RuleAnalysisRequest> requests = CLAIMS.stream()
                .map(text -> new RuleAnalysisRequest(
                        new Claim("stability-" + CLAIMS.indexOf(text), text,
                                PRODUCT_HEALTH_EFFECT_COPY, "안정성 측정용 고정 입력"),
                        List.of(), Set.of(),
                        new RuleAnalysisRequest.OfficialFunctions(
                                List.<RuleOfficialFunctionContext>of(), false, false)))
                .toList();

        // (규칙코드 -> 문장 -> 회차별 판정)
        Map<String, Map<String, List<String>>> verdicts = new LinkedHashMap<>();

        for (String ruleCode : RULE_CODES) {
            Rule rule;
            try {
                rule = CanonicalRuleFixture.rule(ruleCode);
            } catch (RuntimeException e) {
                System.out.printf("[%s] 규칙 픽스처 없음 - 건너뜀 (%s)%n", ruleCode, e.getMessage());
                continue;
            }
            Map<String, List<String>> byClaim = new LinkedHashMap<>();
            CLAIMS.forEach(claim -> byClaim.put(claim, new ArrayList<>()));

            for (int run = 1; run <= REPEAT; run++) {
                List<RuleEvaluation> results = evaluator.evaluateAcrossClaims(rule, requests);
                for (int i = 0; i < CLAIMS.size(); i++) {
                    byClaim.get(CLAIMS.get(i)).add(results.get(i).status().name());
                }
                System.out.printf("[%s] %d회차 완료%n", ruleCode, run);
                sleep(PACING_MS);
            }
            verdicts.put(ruleCode, byClaim);
        }

        System.out.println();
        System.out.println("=== 규칙 판정 안정성 (같은 입력 " + REPEAT + "회) ===");
        int totalPairs = 0;
        int flakyPairs = 0;
        for (Map.Entry<String, Map<String, List<String>>> ruleEntry : verdicts.entrySet()) {
            for (Map.Entry<String, List<String>> claimEntry : ruleEntry.getValue().entrySet()) {
                List<String> runs = claimEntry.getValue();
                Set<String> distinct = new LinkedHashSet<>(runs);
                totalPairs++;
                boolean flaky = distinct.size() > 1;
                if (flaky) {
                    flakyPairs++;
                }
                System.out.printf("%-30s %-58s %s %s%n",
                        ruleEntry.getKey(),
                        claimEntry.getKey().length() > 56
                                ? claimEntry.getKey().substring(0, 56) + ".."
                                : claimEntry.getKey(),
                        String.join(",", runs),
                        flaky ? "<<< 흔들림" : "");
            }
        }
        double stability = totalPairs == 0 ? 0 : 100.0 * (totalPairs - flakyPairs) / totalPairs;
        System.out.printf("%n(규칙,문장) 조합 %d건 중 흔들린 것 %d건 → 안정도 %.0f%%%n",
                totalPairs, flakyPairs, stability);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
