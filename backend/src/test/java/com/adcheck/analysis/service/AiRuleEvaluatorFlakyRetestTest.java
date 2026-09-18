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
 *
 * <p><b>로컬 실험용 테스트다.</b> {@code .scratch/}는 {@code .gitignore} 대상이라 이 목록 파일은
 * 저장소에 없다 — 파일이 없거나 {@code GEMINI_API_KEY}가 없으면 스킵된다. 따라서 CI나 다른
 * 팀원 환경에서는 항상 스킵되고, 목록을 직접 만든 사람의 로컬에서만 실제로 돈다.
 * 돌 때는 Gemini를 <b>40회</b> 호출하므로(20건 × 2회, 각 4.5초 간격) 무료 티어 예산에 주의.
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

        // 대상 목록은 .scratch/ 아래에 있는데 이 디렉터리는 .gitignore에 걸려 있어서(로컬 실험
        // 산출물 보관용) 다른 사람이 받아보면 파일이 없다. API 키가 없을 때만 스킵하고 파일은
        // 그냥 읽던 예전 코드는, 키가 설정된 환경에서 NoSuchFileException으로 실패했다 —
        // 실제로 팀원 환경에서 한 번은 실패하고 한 번은(키가 없어서) 스킵되는 일이 있었다.
        // 이 테스트는 어차피 로컬 실험용이므로 파일이 없으면 스킵이 맞다.
        Path jsonPath = Path.of("..", ".scratch", "flaky-claims.json");
        if (!Files.exists(jsonPath)) {
            jsonPath = Path.of(".scratch", "flaky-claims.json");
        }
        Assumptions.assumeTrue(Files.exists(jsonPath),
                "flaky-claims.json 없음(로컬 실험 산출물, git 미추적) - 스킵");

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
