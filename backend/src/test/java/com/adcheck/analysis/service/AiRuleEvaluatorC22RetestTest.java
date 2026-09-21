package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * C22_SUPERLATIVE 배치 프롬프트 수정 전후 비교.
 *
 * <p>C22는 배치에서 3회 내내 <b>같은 방향으로</b> 3건 전부 틀렸다. 모델이 남긴 판단 근거를
 * 읽어보니 실패 모드가 둘로 갈렸다.
 *
 * <ul>
 *   <li><b>케이스 1·2 → REVIEW_REQUIRED</b>: "실증 자료의 진위를 외부에서 확인해야 한다"를
 *       이유로 게이트를 켰다. C22의 {@code requiredEvidence}에 "실증자료"라는 단어가 박혀
 *       있어서, 그 자료를 지금 볼 수 없다는 사실을 문장 밖 정보가 필요하다는 신호로 읽은 것.</li>
 *   <li><b>케이스 3 → NOT_MATCHED</b>: "최고·최대 등 명시적 비교 표현이 아니므로 해당 없음".
 *       {@code candidateExamples}(유일/최고/최대/최초/고순도)에 없는 단어("남다른", "프리미엄")를
 *       썼다는 이유로 제외했다.</li>
 * </ul>
 *
 * <p>둘 다 C22 전용 문제가 아니라 프롬프트의 일반적 구멍이라, 규칙 텍스트를 넣는 그 자리에서
 * 각각 한 번 더 막도록 고쳤다. 기존 경고가 프롬프트 뒤쪽에 있긴 했지만 규칙 텍스트가 더 가깝고
 * 구체적이라 밀리고 있었다.
 *
 * <p>수정 전 기준선: <b>0/3, 3회 내내 동일</b>. GEMINI_API_KEY 없으면 스킵, 호출 3회.
 */
class AiRuleEvaluatorC22RetestTest {

    private static final String RULE_CODE = "C22_SUPERLATIVE";
    /** 반복 횟수. 기본 3회, {@code C22_RUNS} 환경변수로 조정(표본을 5회로 늘릴 때 씀). */
    private static final int RUNS = runsFromEnv();

    private static int runsFromEnv() {
        String raw = System.getenv("C22_RUNS");
        try {
            return raw == null || raw.isBlank() ? 3 : Math.max(1, Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private static final long PACING_MS = 4_500;

    @Test
    void C22_배치_프롬프트_수정_후_3회_재측정() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        List<Map<String, String>> cases = ValidationDatasetFixture.byRuleCode(List.of(RULE_CODE)).get(RULE_CODE);
        Assumptions.assumeTrue(cases != null && !cases.isEmpty(), "C22 검증 케이스 없음 - 스킵");

        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);
        Rule rule = CanonicalRuleFixture.rule(RULE_CODE);
        List<RuleAnalysisRequest> requests = cases.stream()
                .map(row -> new RuleAnalysisRequest(
                        new Claim("v-" + RULE_CODE, row.get("claimText"),
                                PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스"),
                        List.of(), Set.of(),
                        new RuleAnalysisRequest.OfficialFunctions(
                                List.<RuleOfficialFunctionContext>of(), false, false)))
                .toList();

        int correct = 0;
        int total = 0;
        for (int run = 1; run <= RUNS; run++) {
            List<RuleEvaluation> results = evaluator.evaluateAcrossClaims(rule, requests);
            System.out.printf("=== %d회차 ===%n", run);
            for (int i = 0; i < cases.size(); i++) {
                String expected = cases.get(i).get("expectedStatus");
                RuleEvaluation result = results.get(i);
                boolean match = result.status().name().equals(expected);
                total++;
                if (match) {
                    correct++;
                }
                System.out.printf("  expected=%-16s actual=%-16s(%s)  %s%n",
                        expected, result.status(), match ? "O" : "X",
                        cases.get(i).get("claimText"));
                System.out.printf("      근거: %s%n", result.reason());
            }
            sleep(PACING_MS);
        }

        System.out.printf("%n이번 실행: %d/%d (%.1f%%), %d회 반복   ·   수정 전 기준선: 3회 전부 0/3%n",
                correct, total, 100.0 * correct / total, RUNS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
