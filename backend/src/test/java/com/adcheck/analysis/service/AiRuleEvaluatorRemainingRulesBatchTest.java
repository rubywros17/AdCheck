package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * {@link AiRuleEvaluatorSameRuleBatchTest}가 다룬 1차 파일럿 9개 규칙을 뺀 <b>나머지 11개</b>를
 * 규칙 축 배치로 측정한다. 이 둘을 합치면 allowlist의 AI 규칙 20개가 전부 덮인다.
 *
 * <p>왜 필요한가: 지금까지 "배치에서 판정이 달라지는 규칙"으로 알려진 것은 B02_VIRUS와
 * R02_MENOPAUSE 2개뿐인데, 그건 <b>9개만 재본 결과</b>였다. 나머지 11개는 개별 호출로만
 * 검증하고 배치에서는 한 번도 돌려보지 않아서, 팀이 논의 중인 "배치 제외 + 개별 호출"
 * 하이브리드의 제외 목록을 이 상태로 확정하면 모르는 유실이 남는다. 특히 앞 3개
 * (C01/C02/C22)는 {@code COMMON}이라 원료와 무관하게 <b>모든 분석·모든 Claim</b>에 적용되므로,
 * 원료별 규칙인 B02/R02보다 노출 빈도가 훨씬 높다.
 *
 * <p>검증 데이터셋의 {@code expectedStatus}가 곧 개별 호출 기준선이다 — 이 11개는 개별 호출로
 * 라벨과 맞는 것이 확인돼 allowlist에 들어왔기 때문이다. 그래서 여기서는 배치 결과만 라벨과
 * 대조하면 된다.
 *
 * <p>2단계로 돈다: <b>①</b> 11개를 배치로 한 번씩 돌리고(호출 11회), <b>②</b> 라벨과 어긋난
 * 규칙만 2회 더 돌린다. 1회 실행으로는 "배치라서 틀린 것"과 "원래 흔들리는 것(flaky)"을
 * 구분할 수 없어서다 — 반복해도 일관되게 틀리면 제외 목록 후보이고, 매번 다르면 개별 호출로
 * 바꿔도 소용없으니 별도 백로그다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵.
 */
class AiRuleEvaluatorRemainingRulesBatchTest {

    /** allowlist의 AI 규칙 20개 중 배치로 아직 측정하지 않은 11개. 앞 3개는 COMMON. */
    private static final List<String> TARGET_RULE_CODES = List.of(
            "C01_DISEASE_PREVENTION", "C02_DISEASE_TREATMENT", "C22_SUPERLATIVE",
            "P01_VAGINAL_SCOPE", "P03_DISEASE_GUT",
            "E01_VESSEL", "E02_OTHER_FUNCTION", "E03_GENERATION",
            "O02_ENERGY_EXPANSION", "L01_VISION", "L03_EYE_DISEASE");

    private static final int RECHECK_RUNS = 2;
    private static final long PACING_MS = 4_500;

    @Test
    void 나머지_11개_규칙의_배치_판정을_정답_라벨과_대조한다() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);
        Map<String, List<Map<String, String>>> byRule = ValidationDatasetFixture.byRuleCode(TARGET_RULE_CODES);

        List<String> csv = new ArrayList<>();
        csv.add("run,ruleCode,expectedStatus,claimText,actualStatus,match,reasonCode,reason");
        Map<String, List<String>> mismatchedClaims = new LinkedHashMap<>();
        int correct = 0;
        int total = 0;
        long startedAt = System.currentTimeMillis();

        System.out.println("=== 1단계: 11개 규칙 배치 1회씩 ===");
        for (Map.Entry<String, List<Map<String, String>>> entry : byRule.entrySet()) {
            String ruleCode = entry.getKey();
            List<Map<String, String>> cases = entry.getValue();
            List<RuleEvaluation> results = evaluate(evaluator, ruleCode, cases);

            for (int i = 0; i < cases.size(); i++) {
                String expected = cases.get(i).get("expectedStatus");
                String claimText = cases.get(i).get("claimText");
                RuleEvaluation result = results.get(i);
                boolean match = result.status().name().equals(expected);
                total++;
                if (match) {
                    correct++;
                } else {
                    mismatchedClaims.computeIfAbsent(ruleCode, key -> new ArrayList<>()).add(claimText);
                }
                System.out.printf("  %-24s expected=%-16s actual=%-16s(%s) %s%n",
                        ruleCode, expected, result.status(), match ? "O" : "X", result.reasonCode());
                csv.add(row(1, ruleCode, expected, claimText, result, match));
            }
            sleep(PACING_MS);
        }

        System.out.printf("%n1단계 결과: %d/%d (%.1f%%), 배치 호출 %d회%n",
                correct, total, 100.0 * correct / total, byRule.size());

        if (mismatchedClaims.isEmpty()) {
            System.out.println("어긋난 규칙이 없어 2단계(반복 확인)는 건너뜁니다.");
        } else {
            System.out.printf("%n=== 2단계: 어긋난 %d개 규칙만 %d회 더 (flaky인지 확인) ===%n",
                    mismatchedClaims.size(), RECHECK_RUNS);
        }
        for (String ruleCode : mismatchedClaims.keySet()) {
            List<Map<String, String>> cases = byRule.get(ruleCode);
            for (int run = 2; run <= RECHECK_RUNS + 1; run++) {
                List<RuleEvaluation> results = evaluate(evaluator, ruleCode, cases);
                for (int i = 0; i < cases.size(); i++) {
                    String expected = cases.get(i).get("expectedStatus");
                    RuleEvaluation result = results.get(i);
                    boolean match = result.status().name().equals(expected);
                    System.out.printf("  [%d회차] %-24s expected=%-16s actual=%-16s(%s)%n",
                            run, ruleCode, expected, result.status(), match ? "O" : "X");
                    csv.add(row(run, ruleCode, expected, cases.get(i).get("claimText"), result, match));
                }
                sleep(PACING_MS);
            }
        }

        System.out.printf("%n총 소요 %.1f초%n", (System.currentTimeMillis() - startedAt) / 1000.0);
        Path out = Path.of("build", "remaining-rules-batch-results.csv");
        Files.createDirectories(out.getParent());
        Files.write(out, csv, StandardCharsets.UTF_8);
        System.out.println("결과 저장: " + out.toAbsolutePath());
    }

    private static List<RuleEvaluation> evaluate(
            AiRuleEvaluator evaluator, String ruleCode, List<Map<String, String>> cases) {
        Rule rule = CanonicalRuleFixture.rule(ruleCode);
        List<RuleAnalysisRequest> requests = cases.stream()
                .map(row -> new RuleAnalysisRequest(
                        new Claim("v-" + ruleCode, row.get("claimText"),
                                PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스"),
                        List.of(), Set.of(),
                        new RuleAnalysisRequest.OfficialFunctions(
                                List.<RuleOfficialFunctionContext>of(), false, false)))
                .toList();
        return evaluator.evaluateAcrossClaims(rule, requests);
    }

    private static String row(int run, String ruleCode, String expected, String claimText,
                              RuleEvaluation result, boolean match) {
        return String.join(",", String.valueOf(run), ruleCode, expected,
                quote(claimText), result.status().name(), String.valueOf(match),
                result.reasonCode().name(), quote(result.reason()));
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
