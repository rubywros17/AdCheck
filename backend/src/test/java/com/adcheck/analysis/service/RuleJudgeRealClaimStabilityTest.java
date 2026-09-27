package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.config.RuleJudgeProperties;
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

    /**
     * <b>결정론 평가기가 맡은 규칙 전부</b>를 실제 문구에 돌려 판정 분포를 낸다.
     * Gemini 호출 0회다.
     *
     * <p>앞선 측정은 "판정을 한 번도 안 낸다"고 관찰된 다섯 개만 봤다. 그런데
     * {@link LiteralRuleEvaluator}는 원료별 규칙도 맡고 있고(G02·T01·T02·T03·B05·M03·S01),
     * 이쪽은 한 번도 측정된 적이 없다. 같은 방식(검증셋 예문을 문장째로 담은 정규식)이라면
     * 같은 문제가 있을 텐데 확인된 바가 없다.
     */
    @Test
    void 결정론_평가기가_맡은_규칙_전부의_판정_분포를_본다() {
        List<String> claims = loadClaims();
        List<RuleAnalysisRequest> requests = toRequests(claims);
        RuleEvaluator common = new CommonRuleEvaluator();
        RuleEvaluator literal = new LiteralRuleEvaluator();

        List<String> all = new ArrayList<>();
        all.addAll(common.ruleCodes());
        all.addAll(literal.ruleCodes());
        all = all.stream().sorted().toList();

        System.out.printf("%n==== 결정론 평가기 전체 %d개 · 실제 문구 %d건 (Gemini 호출 0회) ====%n",
                all.size(), claims.size());
        System.out.printf("%-26s %9s %12s %16s   %s%n",
                "규칙", "MATCHED", "NOT_MATCHED", "REVIEW_REQUIRED", "주된 reasonCode");

        List<String> neverJudges = new ArrayList<>();
        for (String ruleCode : all) {
            Rule rule;
            try {
                rule = CanonicalRuleFixture.rule(ruleCode);
            } catch (RuntimeException e) {
                System.out.printf("%-26s 픽스처 없음 - 건너뜀%n", ruleCode);
                continue;
            }
            RuleEvaluator evaluator = common.ruleCodes().contains(ruleCode) ? common : literal;
            List<RuleEvaluation> results = evaluator.evaluateAcrossClaims(rule, requests);
            Map<String, Integer> byStatus = count(results, e -> e.status().name());
            int decided = byStatus.getOrDefault("MATCHED", 0) + byStatus.getOrDefault("NOT_MATCHED", 0);
            if (decided == 0) {
                neverJudges.add(ruleCode);
            }
            System.out.printf("%-26s %9d %12d %16d   %s%n", ruleCode,
                    byStatus.getOrDefault("MATCHED", 0),
                    byStatus.getOrDefault("NOT_MATCHED", 0),
                    byStatus.getOrDefault("REVIEW_REQUIRED", 0),
                    top(count(results, e -> String.valueOf(e.reasonCode()))));
        }
        System.out.printf("%n  판정을 하나도 못 낸 규칙 %d/%d개: %s%n",
                neverJudges.size(), all.size(), neverJudges);
    }

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

    /**
     * <b>AI 판정 규칙 전체</b>를 한 번씩 돌려 "판정을 내기는 하는가"를 본다.
     *
     * <p>왜 편차가 아니라 이것부터 보나: 결정론 평가기 다섯(C05·C07·C08·C09·C24)이 실제 문구
     * 430건에 대해 <b>MATCHED와 NOT_MATCHED를 단 한 번도 내지 않는다</b>는 것이 측정으로
     * 드러났다(2026-09-27). 같은 일이 AI 규칙에도 있는지는 확인된 적이 없다 — 지금까지 잰 것은
     * 38개 중 4개뿐이다. 편차는 "판정을 내는 규칙"에 대해서만 의미가 있다.
     *
     * <p>대상은 allowlist({@link RuleJudgeProperties})에서 결정론 평가기가 맡는 코드를 뺀
     * 나머지다 — 하드코딩하지 않아 규칙이 늘어도 그대로 따라간다.
     *
     * <p>호출 수 = AI 규칙 수(각 1회). 편차를 재는 것이 아니므로 반복하지 않는다.
     */
    @Test
    void AI_규칙_전체가_판정을_내기는_하는지_한_번씩_본다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        Set<String> deterministic = new LinkedHashSet<>();
        deterministic.addAll(new CommonRuleEvaluator().ruleCodes());
        deterministic.addAll(new LiteralRuleEvaluator().ruleCodes());
        List<String> aiRules = new RuleJudgeProperties().getEnabledRuleCodes().stream()
                .distinct()
                .filter(code -> !deterministic.contains(code))
                .toList();

        List<String> claims = loadClaims().stream().limit(AI_CLAIM_LIMIT).toList();
        List<RuleAnalysisRequest> requests = toRequests(claims);
        AiRuleEvaluator evaluator =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        System.out.printf("%n==== AI 규칙 %d개 × 실제 문구 %d건 (각 1회) ====%n",
                aiRules.size(), claims.size());
        System.out.printf("%-30s %8s %12s %16s   %s%n",
                "규칙", "MATCHED", "NOT_MATCHED", "REVIEW_REQUIRED", "주된 reasonCode");

        List<String> neverJudges = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String ruleCode : aiRules) {
            Rule rule;
            try {
                rule = CanonicalRuleFixture.rule(ruleCode);
            } catch (RuntimeException e) {
                skipped.add(ruleCode);
                continue;
            }
            List<RuleEvaluation> results;
            try {
                results = evaluator.evaluateAcrossClaims(rule, requests);
            } catch (RuntimeException e) {
                System.out.printf("%-30s 호출 실패: %s%n", ruleCode, e.getMessage());
                continue;
            }
            Map<String, Integer> byStatus = count(results, e -> e.status().name());
            int decided = byStatus.getOrDefault("MATCHED", 0) + byStatus.getOrDefault("NOT_MATCHED", 0);
            if (decided == 0) {
                neverJudges.add(ruleCode);
            }
            System.out.printf("%-30s %8d %12d %16d   %s%n", ruleCode,
                    byStatus.getOrDefault("MATCHED", 0),
                    byStatus.getOrDefault("NOT_MATCHED", 0),
                    byStatus.getOrDefault("REVIEW_REQUIRED", 0),
                    top(count(results, e -> String.valueOf(e.reasonCode()))));
            sleep(PACING_MS);
        }

        System.out.printf("%n---- 요약 ----%n");
        System.out.printf("  대상 %d개 · 픽스처 없어 건너뜀 %d개%s%n",
                aiRules.size(), skipped.size(), skipped.isEmpty() ? "" : " " + skipped);
        System.out.printf("  이번 문구들에 대해 <판정을 하나도 못 낸> 규칙 %d개%s%n",
                neverJudges.size(), neverJudges.isEmpty() ? "" : ": " + neverJudges);
        System.out.println("  (문구 12건은 운영에서 REVIEW_REQUIRED만 붙었던 것들이라, "
                + "보류가 많은 것 자체는 예상된 결과다. 볼 것은 '한 번도 안 내는' 규칙이다.)");
    }

    /**
     * 정규식 평가기가 맡고 있는 규칙을 <b>AI 평가기에 그대로 넘기면 판정이 되는지</b>를
     * 같은 문구로 나란히 비교한다.
     *
     * <p>배경: {@link #결정론_평가기는_같은_문구에_같은_판정을_낸다()}에서 다섯 규칙이 실제 문구
     * 430건에 대해 판정을 한 건도 못 낸다는 것이 확인됐다. 패턴이 일반 규칙이 아니라 검증셋
     * 예문을 문장째로 담고 있어 실제 광고가 걸릴 수 없기 때문이다
     * (예: {@code "이제 (고민|관리) 끝[.!]?"}, {@code .matches()}라 문장 전체가 일치해야 한다).
     *
     * <p>그런데 AI 평가기는 같은 종류의 판단을 한다 — 규칙 38개를 같은 문구 12건에 돌렸을 때
     * 456건 중 432건(95%)을 판정했다. 문제는 {@code RuleEvaluatorRegistry}가 <b>규칙 코드 하나에
     * 평가기 하나만</b> 등록한다는 점이다({@code putIfAbsent} + 중복 시 예외). 정규식 평가기가
     * 그 코드를 들고 있는 한 <b>AI 평가기는 그 규칙을 볼 기회 자체가 없다.</b>
     *
     * <p>그래서 등록을 바꾸지 않고 AI 평가기를 <b>직접</b> 호출해 비교한다 — 운영 코드를 건드리지
     * 않고도 "넘기면 되는가"를 잴 수 있다.
     *
     * <p>호출 수 = 대상 규칙 수(각 1회).
     */
    @Test
    void 정규식_규칙을_AI_평가기로_넘기면_판정이_되는지_비교한다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        List<String> claims = loadClaims().stream().limit(AI_CLAIM_LIMIT).toList();
        List<RuleAnalysisRequest> requests = toRequests(claims);
        RuleEvaluator common = new CommonRuleEvaluator();
        RuleEvaluator literal = new LiteralRuleEvaluator();
        AiRuleEvaluator ai =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        System.out.printf("%n==== 정규식 vs AI · 같은 문구 %d건 ====%n", claims.size());
        System.out.printf("%-26s %-26s   %-26s%n", "규칙", "현재(정규식·코드)", "AI 평가기로 넘기면");

        for (String ruleCode : DETERMINISTIC_RULES) {
            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            RuleEvaluator deterministic = common.ruleCodes().contains(ruleCode) ? common : literal;

            List<RuleEvaluation> before = deterministic.evaluateAcrossClaims(rule, requests);
            List<RuleEvaluation> after;
            try {
                after = ai.evaluateAcrossClaims(rule, requests);
            } catch (RuntimeException e) {
                System.out.printf("%-26s AI 호출 실패: %s%n", ruleCode, e.getMessage());
                continue;
            }
            System.out.printf("%-26s %-26s   %-26s%n", ruleCode, verdicts(before), verdicts(after));
            // AI가 판정을 낸 것 중 몇 개만 이유를 보여 준다 — 근거가 실제 문장을 읽은 것인지 확인용.
            for (int i = 0; i < claims.size(); i++) {
                RuleEvaluation e = after.get(i);
                if (e.status() != RuleEvaluation.Status.REVIEW_REQUIRED
                        && before.get(i).status() == RuleEvaluation.Status.REVIEW_REQUIRED) {
                    System.out.printf("      «%s» → %s: %s%n",
                            claims.get(i).length() > 30 ? claims.get(i).substring(0, 30) + "…" : claims.get(i),
                            e.status(),
                            e.reason() == null ? "" : e.reason().length() > 70
                                    ? e.reason().substring(0, 70) + "…" : e.reason());
                    break;
                }
            }
            sleep(PACING_MS);
        }
        System.out.println();
        System.out.println("  (C05는 정규식이 아니라 코드 로직 + 데이터 게이트다.");
        System.out.println("   AI로 넘긴다고 풀리는 문제가 아니라서 대조군으로만 본다.)");
    }

    /**
     * AI 평가기가 <b>명백한 위반을 잡는지</b>와 <b>무관한 문장을 놓아주는지</b>를 함께 본다.
     *
     * <p>왜 필요한가: 정규식 규칙을 AI로 넘겼더니 {@code NOT_MATCHED}가 대부분이었다. 그게
     * 맞는 판단인지 "아니다"를 남발한 것인지 구분해야 하는데, <b>검증셋에 이 다섯 규칙이 한 건도
     * 없다</b>(docs/validation_dataset_v0.1.csv는 AI 규칙용으로 만들어졌고 정규식 규칙은 대상이
     * 아니었다). 정답 라벨이 없으므로 규칙 정의에서 <b>명백한 위반 문장을 직접 만들어</b> 넣는다.
     *
     * <p>위반 문장을 놓치면 그 규칙은 AI로 넘겨도 쓸 수 없다. 반대로 무관한 문장을 위반으로
     * 잡으면 오탐이 늘어 지금보다 나빠진다. 둘을 한 배치에 섞어 한 번에 본다.
     */
    @Test
    void AI_평가기가_명백한_위반을_잡고_무관한_문장은_놓아주는지_본다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        // 규칙 정의(applicationConditions)에서 뽑은, 그 규칙이 반드시 잡아야 할 문장들.
        Map<String, List<String>> violations = new LinkedHashMap<>();
        violations.put("C07_ABSOLUTE_EFFECT", List.of(
                "이 제품은 100% 효과를 보장합니다.",
                "누구나 무조건 효과를 봅니다.",
                "완벽하게 회복시켜 드립니다."));
        violations.put("C08_RESULT_TIME_AMOUNT", List.of(
                "30일이면 5kg 빠집니다.",
                "2주만에 확실한 효과를 봅니다.",
                "한 달이면 누구나 체지방이 감소합니다."));
        violations.put("C09_COMPLETE_SOLUTION", List.of(
                "이제 관절 고민 끝!",
                "이것 하나로 건강 관리 끝내세요.",
                "제품 하나로 모든 고민을 완전히 해결합니다."));
        violations.put("C24_OVERCONSUMPTION", List.of(
                "식사 대신 이 제품만 드세요.",
                "많이 먹을수록 효과가 좋습니다.",
                "하루 권장량의 세 배를 드시면 더 빠릅니다."));

        // 그 규칙과 무관한 실제 문구 — 위반으로 잡으면 오탐이다.
        List<String> unrelated = loadClaims().stream().limit(4).toList();

        AiRuleEvaluator ai =
                new AiRuleEvaluator(new GeminiClient(apiKey, "gemini-3.5-flash-lite"), null);

        System.out.printf("%n==== AI 평가기 · 명백한 위반을 잡는가 ====%n");
        System.out.printf("%-26s %14s %16s%n", "규칙", "위반 잡음", "무관한 문장 오탐");

        for (var entry : violations.entrySet()) {
            Rule rule = CanonicalRuleFixture.rule(entry.getKey());
            List<String> positives = entry.getValue();
            List<String> all = new ArrayList<>(positives);
            all.addAll(unrelated);
            List<RuleEvaluation> results = ai.evaluateAcrossClaims(rule, toRequests(all));

            int caught = 0;
            List<String> missed = new ArrayList<>();
            for (int i = 0; i < positives.size(); i++) {
                if (results.get(i).status() == RuleEvaluation.Status.MATCHED) {
                    caught++;
                } else {
                    missed.add(positives.get(i) + " → " + results.get(i).status());
                }
            }
            int falsePositives = 0;
            for (int i = positives.size(); i < all.size(); i++) {
                if (results.get(i).status() == RuleEvaluation.Status.MATCHED) {
                    falsePositives++;
                }
            }
            System.out.printf("%-26s %10d/%-3d %13d/%-3d%n", entry.getKey(),
                    caught, positives.size(), falsePositives, unrelated.size());
            missed.forEach(m -> System.out.println("      (놓침) " + m));
            sleep(PACING_MS);
        }
    }

    /** "M 2 / N 8 / R 2" 형태로 판정 분포를 한 칸에 담는다. */
    private String verdicts(List<RuleEvaluation> results) {
        Map<String, Integer> c = count(results, e -> e.status().name());
        return String.format("MATCHED %d · NOT_M %d · REVIEW %d",
                c.getOrDefault("MATCHED", 0), c.getOrDefault("NOT_MATCHED", 0),
                c.getOrDefault("REVIEW_REQUIRED", 0));
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
