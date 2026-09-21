package com.adcheck.analysis.service;

import com.adcheck.rule.config.RuleJudgeProperties;
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
import java.util.TreeSet;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * allowlist에 켜진 AI 규칙 <b>전체</b>를 규칙 축 배치로 여러 번 돌려 정확도를 잰다.
 *
 * <p>프롬프트를 고칠 때마다 "다른 규칙이 나빠지지 않았는지"를 확인하는 회귀 측정용이다.
 * 규칙당 1회만 돌리면 판단이 불가능하다 — 같은 프롬프트라도 회차마다 답이 갈리기 때문이다.
 * 실측으로 확인한 사실인데, <b>{@code temperature: 0}을 넣어도 마찬가지였다</b>(요청 본문에
 * 정상 전송되는 것까지 확인했고, 그래도 C22가 5회 중 3회는 3/3, 2회는 1/3으로 갈렸다).
 * 그래서 호스팅 모델의 흔들림을 없앨 수는 없고, <b>반복 측정으로 평균을 봐야</b> 한다.
 *
 * <p>규칙 목록은 {@code RuleJudgeProperties} allowlist와 {@code AiRuleEvaluator}가 선언한
 * 코드의 교집합에서 직접 읽어온다 — 목록을 하드코딩하면 allowlist가 바뀔 때 조용히 어긋난다.
 *
 * <p>호출 수 = 규칙 수 × 반복 횟수(기본 3회). 20개 기준 60회로 무료 티어 분당 한도에 걸리지
 * 않도록 호출 간격을 둔다. {@code REGRESSION_RUNS} 환경변수로 반복 횟수를 조정할 수 있다.
 * GEMINI_API_KEY 없으면 스킵.
 */
class AiRuleEvaluatorBatchRegressionTest {

    private static final long PACING_MS = 4_500;

    @Test
    void allowlist_AI규칙_전체를_반복_측정한다() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        int runs = runsFromEnv();
        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        // REGRESSION_RULES로 특정 규칙만 골라 잴 수 있다 — 이때는 allowlist를 거치지 않는다.
        // allowlist는 "지금 실전에 켜진 것 전체가 안 나빠졌는지" 볼 때만 의미가 있고, 아직 켜지
        // 않은 백로그 규칙(예: C14/C21처럼 "매번 같은 케이스를 틀림"으로 보류된 것)을 프롬프트
        // 수정 후 재보려면 allowlist 밖 규칙도 evaluator가 알기만 하면 잴 수 있어야 한다.
        String only = System.getenv("REGRESSION_RULES");
        List<String> targets;
        if (only != null && !only.isBlank()) {
            Set<String> picked = java.util.Arrays.stream(only.split(","))
                    .map(String::strip).filter(code -> !code.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
            targets = new ArrayList<>(new TreeSet<>(
                    evaluator.ruleCodes().stream().filter(picked::contains).toList()));
            Assumptions.assumeTrue(!targets.isEmpty(), "REGRESSION_RULES와 겹치는 규칙 없음 - 스킵");
        } else {
            Set<String> allowlist = Set.copyOf(new RuleJudgeProperties().getEnabledRuleCodes());
            targets = new ArrayList<>(new TreeSet<>(
                    evaluator.ruleCodes().stream().filter(allowlist::contains).toList()));
        }
        Map<String, List<Map<String, String>>> byRule = ValidationDatasetFixture.byRuleCode(targets);
        Assumptions.assumeTrue(!byRule.isEmpty(), "검증 케이스 없음 - 스킵");

        System.out.printf("대상 규칙 %d개 × %d회 = 호출 %d회%n%n", byRule.size(), runs, byRule.size() * runs);

        Map<String, int[]> tally = new LinkedHashMap<>();   // ruleCode -> [정답, 전체]
        List<String> csv = new ArrayList<>();
        csv.add("run,ruleCode,expectedStatus,claimText,actualStatus,match,reasonCode,reason");
        long startedAt = System.currentTimeMillis();

        for (int run = 1; run <= runs; run++) {
            System.out.printf("=== %d회차 ===%n", run);
            for (Map.Entry<String, List<Map<String, String>>> entry : byRule.entrySet()) {
                String ruleCode = entry.getKey();
                List<Map<String, String>> cases = entry.getValue();
                Rule rule = CanonicalRuleFixture.rule(ruleCode);
                List<RuleEvaluation> results = evaluator.evaluateAcrossClaims(rule, toRequests(ruleCode, cases));

                int[] t = tally.computeIfAbsent(ruleCode, key -> new int[2]);
                StringBuilder marks = new StringBuilder();
                for (int i = 0; i < cases.size(); i++) {
                    String expected = cases.get(i).get("expectedStatus");
                    RuleEvaluation result = results.get(i);
                    boolean match = result.status().name().equals(expected);
                    t[1]++;
                    if (match) {
                        t[0]++;
                    }
                    marks.append(match ? 'O' : 'X');
                    csv.add(row(run, ruleCode, expected, cases.get(i).get("claimText"), result, match));
                }
                System.out.printf("  %-24s %s%n", ruleCode, marks);
                sleep(PACING_MS);
            }
        }

        System.out.printf("%n%-24s %10s %8s%n", "규칙", "정답", "비율");
        System.out.println("-".repeat(46));
        int correct = 0;
        int total = 0;
        for (Map.Entry<String, int[]> e : tally.entrySet()) {
            int[] t = e.getValue();
            correct += t[0];
            total += t[1];
            System.out.printf("  %-24s %5d/%-4d %6.0f%%%n", e.getKey(), t[0], t[1], 100.0 * t[0] / t[1]);
        }
        System.out.println("-".repeat(46));
        System.out.printf("  %-24s %5d/%-4d %6.1f%%   (%d회 반복, 총 소요 %.0f초)%n",
                "합계", correct, total, 100.0 * correct / total, runs,
                (System.currentTimeMillis() - startedAt) / 1000.0);

        Path out = Path.of("build", "batch-regression-results.csv");
        Files.createDirectories(out.getParent());
        Files.write(out, csv, StandardCharsets.UTF_8);
        System.out.println("결과 저장: " + out.toAbsolutePath());
    }

    /**
     * INGREDIENT_SPECIFIC 규칙 중 "인정 기능성 범위를 벗어나는지"를 판단하려면 실제
     * officialFunctionText가 있어야 하는데, 예전엔 이 메서드가 항상 빈 리스트를 넘겨서
     * B03_OTHER_ORAL 같은 규칙은 반복검증 자체가 불가능했다(officialFunctions 채워서
     * 재검증하면 정답이 나온다는 걸 확인했었음 — 로드맵 참고). 규칙별로 실제 원료의
     * 공식 기능성 문구(notified_ingredients_v0.2.csv 원문)를 채운다.
     */
    private static final Map<String, RuleOfficialFunctionContext> OFFICIAL_FUNCTIONS_BY_RULE = Map.of(
            "B03_OTHER_ORAL", new RuleOfficialFunctionContext(550L, "프로폴리스추출물",
                    "항산화 · 구강에서의 항균작용에 도움을 줄 수 있음 / ※구강에서의 항균작용은 구강에 "
                            + "직접 접촉할 수 있는 형태에 한하며, 섭취량을 적용하지 않음",
                    "고시형", null)
    );

    private static List<RuleAnalysisRequest> toRequests(String ruleCode, List<Map<String, String>> cases) {
        RuleOfficialFunctionContext context = OFFICIAL_FUNCTIONS_BY_RULE.get(ruleCode);
        List<RuleOfficialFunctionContext> officialFunctions = context == null ? List.of() : List.of(context);
        return cases.stream()
                .map(row -> new RuleAnalysisRequest(
                        new Claim("v-" + ruleCode, row.get("claimText"),
                                PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스"),
                        List.of(), Set.of(),
                        new RuleAnalysisRequest.OfficialFunctions(officialFunctions, false, false)))
                .toList();
    }

    private static String row(int run, String ruleCode, String expected, String claimText,
                              RuleEvaluation result, boolean match) {
        return String.join(",", String.valueOf(run), ruleCode, expected, quote(claimText),
                result.status().name(), String.valueOf(match), result.reasonCode().name(),
                quote(result.reason()));
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static int runsFromEnv() {
        String raw = System.getenv("REGRESSION_RUNS");
        try {
            return raw == null || raw.isBlank() ? 3 : Math.max(1, Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
