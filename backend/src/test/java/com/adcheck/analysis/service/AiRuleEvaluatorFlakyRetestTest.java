package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * 3회 실행에서 "왔다갔다"(flaky) 했던 (규칙, 문장) 20건만 골라 2회 더 재실행한다(총 5회 데이터
 * 확보). 117행 전체를 또 도는 대신 실제로 불확실한 것만 추가 검증 — 호출은 20회뿐. 대상 목록은
 * {@code .scratch/flaky-claims.json}(3회 실행 결과에서 직접 뽑음)에서 읽는다.
 */
class AiRuleEvaluatorFlakyRetestTest {

    private record FlakyClaim(String ruleCode, String claimText, String expectedStatus) {
    }

    @Test
    void flaky_20건_추가_2회_재실행() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        Path jsonPath = Path.of("..", ".scratch", "flaky-claims.json");
        if (!Files.exists(jsonPath)) {
            jsonPath = Path.of(".scratch", "flaky-claims.json");
        }
        String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
        List<Map<String, String>> flakyList = new ObjectMapper()
                .readValue(json, new TypeReference<List<Map<String, String>>>() {
                });

        List<String> lines = new java.util.ArrayList<>();
        lines.add("runNumber,ruleCode,expectedStatus,claimText,actualStatus,match,reasonCode,reason");

        for (int runNumber = 4; runNumber <= 5; runNumber++) {
            System.out.println("=== 재실행 #" + runNumber + " ===");
            for (Map<String, String> row : flakyList) {
                String ruleCode = row.get("ruleCode");
                String claimText = row.get("claimText");
                String expected = row.get("expectedStatus");

                Rule rule = CanonicalRuleFixture.rule(ruleCode);
                Claim claim = new Claim("v-" + ruleCode, claimText, PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스");
                RuleAnalysisRequest request = new RuleAnalysisRequest(claim, List.of(), Set.of(),
                        new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));

                RuleEvaluation result = callWithRetry(() -> evaluator.evaluate(rule, request), ruleCode);
                boolean match = result.status().name().equals(expected);
                System.out.printf("  %-25s expected=%-16s actual=%-16s(%s) %s%n",
                        ruleCode, expected, result.status(), match ? "O" : "X", result.reasonCode());
                lines.add(String.join(",", String.valueOf(runNumber), ruleCode, expected,
                        "\"" + claimText.replace("\"", "\"\"") + "\"",
                        result.status().name(), String.valueOf(match), result.reasonCode().name(),
                        "\"" + result.reason().replace("\"", "\"\"") + "\""));
                sleep(4500);
            }
        }

        Path outPath = Path.of("build", "flaky-retest-results.csv");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, lines, StandardCharsets.UTF_8);
        System.out.println("결과 저장: " + outPath.toAbsolutePath());
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
