package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.CommonRuleEvaluator;
import com.adcheck.rule.service.LiteralRuleEvaluator;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import com.adcheck.rule.service.RuleEvaluator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실제 상품 페이지에서 뽑힌 문구</b>로 규칙 판정(AI#3)을 잰다.
 *
 * <p>왜 따로 만들었나: {@link RuleJudgeStabilityTest}는 손으로 고른 문장 3개로 재는데,
 * 추출 단계에서 똑같은 함정을 이미 겪었다 — 합성 12줄로는 안정도 100%가 나오지만 실제 페이지를
 * 넣으면 전혀 다른 수치가 나온다(2026-09-27, `docs/rule-judge-instability-log.md`). 그래서
 * 판정 단계도 실제 문구로 재 둔다. 입력은 `review-only-claims.txt` — 운영 DB에 쌓인 Finding
 * 중 <b>REVIEW_REQUIRED만 붙은</b> Claim들이라, "왜 판단 보류가 이렇게 많은가"를 묻기에
 * 정확히 맞는 모집단이다.
 *
 * <p><b>두 가지를 나눠 잰다.</b> 다섯 규칙(C05·C07·C08·C09·C24)이 MATCHED를 한 번도 낸 적이
 * 없다는 관찰이 있었는데, 그 원인이 "AI가 흔들려서"인지 "정규식이 애초에 아무것도 못 짚어서"인지
 * 구분되지 않았다. 앞의 셋은 결정론 평가기(정규식·키워드)라 <b>편차가 원인일 수 없고</b>,
 * 흔들림은 {@link AiRuleEvaluator}에서만 발생할 수 있다.
 *
 * <ol>
 *   <li>{@link #결정론_평가기는_같은_문구에_같은_판정을_낸다()} — Gemini 호출 0회. 두 번 돌려
 *       결정론임을 확인하고, 실제 문구에 어떤 판정이 나오는지 분포를 남긴다.</li>
 *   <li>{@link #AI_평가기가_실제_문구에서_회차마다_갈리는지_잰다()} — GEMINI_API_KEY 필요.
 *       호출 수 = 규칙 수 × REPEAT.</li>
 * </ol>
 */
class RuleJudgeRealClaimStabilityTest {

    /** 운영 DB에서 뽑은, REVIEW_REQUIRED만 붙었던 실제 Claim 문구들. */
    private static final String REAL_CLAIMS = "review-only-claims.txt";

    /**
     * 판정을 한 번도 낸 적이 없다고 관찰된 다섯. 전부 결정론 평가기가 담당한다 —
     * C05·C07·C24는 {@link CommonRuleEvaluator}, C08·C09는 {@link LiteralRuleEvaluator}.
     */
    private static final List<String> DETERMINISTIC_RULES =
            List.of("C05_FUNCTION_EXCEED", "C07_ABSOLUTE_EFFECT", "C24_OVERCONSUMPTION",
                    "C08_RESULT_TIME_AMOUNT", "C09_COMPLETE_SOLUTION");

    /** AI가 판정하는 COMMON scope 규칙 중, 실제로 자주 걸리는 것들. */
    private static final List<String> AI_RULES =
            List.of("C01_DISEASE_PREVENTION", "C02_DISEASE_TREATMENT",
                    "C21_UNFAIR_COMPARISON", "C22_SUPERLATIVE");

    private static final int REPEAT = 3;
    /** 무료 티어 15 RPM. 호출 사이를 이만큼 띄우지 않으면 429 재시도 대기가 붙어 측정이 길어진다. */
    private static final long PACING_MS = 5_000;
    /** AI 측정에 쓸 문구 수 — 프롬프트가 지나치게 길어지지 않게 앞에서부터 자른다. */
    private static final int AI_CLAIM_LIMIT = 12;

    @Test
    void 결정론_평가기는_같은_문구에_같은_판정을_낸다() {
        List<String> claims = loadClaims();
        List<RuleAnalysisRequest> requests = toRequests(claims);
        RuleEvaluator common = new CommonRuleEvaluator();
        RuleEvaluator literal = new LiteralRuleEvaluator();

        System.out.printf("%n==== 결정론 평가기 · 실제 문구 %d건 (Gemini 호출 0회) ====%n", claims.size());
        System.out.printf("%-24s %9s %12s %16s   %s%n",
                "규칙", "MATCHED", "NOT_MATCHED", "REVIEW_REQUIRED", "주된 reasonCode");

        for (String ruleCode : DETERMINISTIC_RULES) {
            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            RuleEvaluator evaluator = common.ruleCodes().contains(ruleCode) ? common : literal;

            List<RuleEvaluation> first = evaluator.evaluateAcrossClaims(rule, requests);
            List<RuleEvaluation> second = evaluator.evaluateAcrossClaims(rule, requests);

            // 정규식·키워드 평가기는 같은 입력에 같은 답을 내야 한다. 이게 깨지면 "다섯 규칙이
            // 판정을 못 낸다"의 원인을 편차 쪽에서 찾아야 하므로, 전제로 못박아 둔다.
            assertThat(statuses(first))
                    .as("%s 는 결정론 평가기이므로 두 번 돌려도 같아야 한다", ruleCode)
                    .isEqualTo(statuses(second));

            Map<String, Integer> byStatus = count(first, e -> e.status().name());
            Map<String, Integer> byReason = count(first, e -> String.valueOf(e.reasonCode()));
            System.out.printf("%-24s %9d %12d %16d   %s%n", ruleCode,
                    byStatus.getOrDefault("MATCHED", 0),
                    byStatus.getOrDefault("NOT_MATCHED", 0),
                    byStatus.getOrDefault("REVIEW_REQUIRED", 0),
                    top(byReason));
        }
        System.out.printf("%n(문구 %d건 × 규칙 %d개 = 판정 %d건)%n",
                claims.size(), DETERMINISTIC_RULES.size(), claims.size() * DETERMINISTIC_RULES.size());
    }

    @Test
    void AI_평가기가_실제_문구에서_회차마다_갈리는지_잰다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        List<String> claims = loadClaims().stream().limit(AI_CLAIM_LIMIT).toList();
        List<RuleAnalysisRequest> requests = toRequests(claims);
        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        System.out.printf("%n==== AI 평가기 · 실제 문구 %d건 × 규칙 %d개 × %d회 ====%n",
                claims.size(), AI_RULES.size(), REPEAT);

        // (규칙코드 -> 문구 -> 회차별 판정)
        Map<String, Map<String, List<String>>> verdicts = new LinkedHashMap<>();
        for (String ruleCode : AI_RULES) {
            Rule rule;
            try {
                rule = CanonicalRuleFixture.rule(ruleCode);
            } catch (RuntimeException e) {
                System.out.printf("  [%s] 규칙 픽스처 없음 - 건너뜀%n", ruleCode);
                continue;
            }
            Map<String, List<String>> byClaim = new LinkedHashMap<>();
            claims.forEach(claim -> byClaim.put(claim, new ArrayList<>()));

            for (int run = 1; run <= REPEAT; run++) {
                long startedAt = System.currentTimeMillis();
                List<RuleEvaluation> results = evaluator.evaluateAcrossClaims(rule, requests);
                for (int i = 0; i < claims.size(); i++) {
                    byClaim.get(claims.get(i)).add(results.get(i).status().name());
                }
                System.out.printf("  [%s] %d회차 (%dms)%n",
                        ruleCode, run, System.currentTimeMillis() - startedAt);
                sleep(PACING_MS);
            }
            verdicts.put(ruleCode, byClaim);
        }

        report(verdicts);
    }

    /** 규칙별로 몇 쌍이 흔들렸는지 세고, 흔들린 쌍은 회차별 판정을 그대로 보여준다. */
    private void report(Map<String, Map<String, List<String>>> verdicts) {
        System.out.printf("%n---- 흔들린 (규칙, 문구) ----%n");
        int totalPairs = 0;
        int flakyPairs = 0;
        Map<String, Integer> flakyByRule = new LinkedHashMap<>();

        for (var ruleEntry : verdicts.entrySet()) {
            for (var claimEntry : ruleEntry.getValue().entrySet()) {
                List<String> runs = claimEntry.getValue();
                totalPairs++;
                if (new LinkedHashSet<>(runs).size() <= 1) {
                    continue;
                }
                flakyPairs++;
                flakyByRule.merge(ruleEntry.getKey(), 1, Integer::sum);
                String claim = claimEntry.getKey();
                System.out.printf("  %-24s %-46s %s%n", ruleEntry.getKey(),
                        claim.length() > 44 ? claim.substring(0, 44) + "…" : claim,
                        String.join(" / ", runs));
            }
        }
        if (flakyPairs == 0) {
            System.out.println("  (없음)");
        }

        // 판정 분포도 같이 낸다 — 흔들림 비율만 보면 "무엇과 무엇 사이에서 흔들렸는지"와
        // "애초에 MATCHED가 나오긴 했는지"를 알 수 없다. MATCHED가 0건인 표본에서 잰
        // 안정도는 "위반을 놓치지 않는가"에 대해 아무 말도 해주지 않는다.
        Map<String, Integer> distribution = new LinkedHashMap<>();
        verdicts.values().forEach(byClaim -> byClaim.values()
                .forEach(runs -> runs.forEach(status -> distribution.merge(status, 1, Integer::sum))));
        System.out.printf("%n---- 판정 분포 (총 %d회) ----%n",
                distribution.values().stream().mapToInt(Integer::intValue).sum());
        distribution.forEach((status, count) -> System.out.printf("  %-18s %d회%n", status, count));

        System.out.printf("%n---- 요약 ----%n");
        verdicts.keySet().forEach(ruleCode -> System.out.printf("  %-24s 흔들림 %d건%n",
                ruleCode, flakyByRule.getOrDefault(ruleCode, 0)));
        double stability = totalPairs == 0 ? 0 : 100.0 * (totalPairs - flakyPairs) / totalPairs;
        System.out.printf("%n(규칙,문구) 조합 %d건 중 흔들린 것 %d건 → 안정도 %.0f%%%n",
                totalPairs, flakyPairs, stability);
    }

    private List<String> loadClaims() {
        try (var in = getClass().getClassLoader().getResourceAsStream(REAL_CLAIMS)) {
            Assumptions.assumeTrue(in != null, "실제 문구 파일 없음 - 스킵: " + REAL_CLAIMS);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .distinct()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 확정 원료 없이 구성한다 — 운영에서 원료 확정이 0건인 경우가 흔하고, 그 상태가 바로
     * "판단 보류가 쏟아지는" 조건이라 재현 대상이 그쪽이다.
     */
    private List<RuleAnalysisRequest> toRequests(List<String> claims) {
        List<RuleAnalysisRequest> requests = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            requests.add(new RuleAnalysisRequest(
                    new Claim("real-" + i, claims.get(i), PRODUCT_HEALTH_EFFECT_COPY,
                            "실제 페이지에서 추출된 문구"),
                    List.of(), Set.of(),
                    new RuleAnalysisRequest.OfficialFunctions(
                            List.<RuleOfficialFunctionContext>of(), false, false)));
        }
        return requests;
    }

    private List<String> statuses(List<RuleEvaluation> evaluations) {
        return evaluations.stream().map(e -> e.status().name()).toList();
    }

    private Map<String, Integer> count(List<RuleEvaluation> evaluations,
                                       java.util.function.Function<RuleEvaluation, String> key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        evaluations.forEach(e -> counts.merge(key.apply(e), 1, Integer::sum));
        return counts;
    }

    private String top(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " " + e.getValue() + "건")
                .orElse("-");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
