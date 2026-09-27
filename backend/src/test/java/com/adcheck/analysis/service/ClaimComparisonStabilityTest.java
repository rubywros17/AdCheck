package com.adcheck.analysis.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI#2(비교·설명 생성)가 같은 입력에 같은 답을 내는지 잰다.
 *
 * <p>왜 필요한가: 파이프라인의 세 AI 단계 중 여기만 한 번도 측정된 적이 없다. AI#1은 claim
 * 출현율 50~65%, AI#3은 판정 안정도 92~100%로 각각 기준선이 있는데, 사용자가 화면에서 실제로
 * <b>읽는 문장</b>을 만드는 단계의 편차는 아무도 모른다. 같은 페이지를 두 번 봤을 때 설명이
 * 통째로 달라지면 "정합성"에 대한 체감은 그대로 나빠진다.
 *
 * <p>두 가지를 나눠 본다. {@code comparisonStatus}는 값이 정해진 분류라 회차마다 같아야 하고
 * (다르면 판단이 뒤집힌 것), {@code explanation}은 자유 서술이라 글자까지 같기를 기대할 수는
 * 없다 — 대신 <b>얼마나 같은지</b>를 글자 수 범위와 완전 일치율로 남기고, 실제 문장을 그대로
 * 출력해 사람이 "같은 말인지"를 판단할 수 있게 한다.
 *
 * <p><b>주의:</b> 이 측정은 현재 브랜치의 프롬프트를 잰다. {@code origin/main}(= 프론트 기준
 * 브랜치)에는 explanation을 "행동 유도형 결론으로 끝내라"고 바꾼 커밋 {@code 54036b5}가 있고
 * 그 변경은 여기에 반영돼 있지 않다. 병합 후에는 다시 재야 한다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵. 호출 수 = REPEAT회(배치라 Claim 수와 무관).
 */
class ClaimComparisonStabilityTest {

    private static final int REPEAT = 5;
    /** 무료 티어 15 RPM. */
    private static final long PACING_MS = 5_000;

    /**
     * 실제 페이지에서 나올 법한 위반 후보 문구들. AI#2는 MATCHED인 Claim에만 도는 단계라
     * {@code review-only-claims.txt}(판단 보류만 붙었던 문구)는 이 단계의 입력이 아니다.
     */
    private static final List<ComparisonClaim> CLAIMS = List.of(
            new ComparisonClaim("c1", "눈의 피로를 완벽하게 개선해 시력을 회복시켜 줍니다."),
            new ComparisonClaim("c2", "3개월이면 누구나 체지방이 확 줄어듭니다."),
            new ComparisonClaim("c3", "루테인은 눈 건강에 도움을 줄 수 있습니다."));

    /** 확정된 제품·원료·공식 기능성 — 비교 대상이 있어야 이 단계가 의미를 갖는다. */
    private static final ConfirmedProduct PRODUCT =
            new ConfirmedProduct("201900012345", "아이눈퓨");
    private static final List<ConfirmedIngredient> INGREDIENTS = List.of(
            new ConfirmedIngredient("I-LUTEIN", "루테인"));
    private static final List<OfficialFunction> OFFICIAL_FUNCTIONS = List.of(
            new OfficialFunction("I-LUTEIN", "노화로 인해 감소할 수 있는 황반색소밀도를 유지시켜 눈 건강에 도움을 줄 수 있음"));
    private static final List<RuleMatch> RULE_MATCHES = List.of(
            new RuleMatch("c1", "C07_ABSOLUTE_EFFECT", "HIGH", "ABSOLUTE_EFFECT",
                    "개인차 없이 결과를 확정하는 표현"),
            new RuleMatch("c2", "C08_RESULT_TIME_AMOUNT", "HIGH", "RESULT_TIME_AMOUNT",
                    "기간과 결과를 함께 보장하는 표현"),
            new RuleMatch("c3", "C06_OFFICIAL_FUNCTION", "CAUTION", "OFFICIAL_FUNCTION",
                    "공식 인정 기능성 표현"));

    @Test
    void 같은_입력을_반복해_AI2_설명의_편차를_잰다() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClaimComparisonService service =
                new GeminiClaimComparisonService(new GeminiClient(apiKey, "gemini-3.5-flash-lite"));
        ClaimComparisonRequest request = new ClaimComparisonRequest(
                CLAIMS, PRODUCT, INGREDIENTS, OFFICIAL_FUNCTIONS, RULE_MATCHES, List.of());

        System.out.printf("%n==== AI#2 비교·설명 · Claim %d건 × %d회 ====%n", CLAIMS.size(), REPEAT);

        // claimId -> 회차별 (status, explanation)
        Map<String, List<String>> statusRuns = new LinkedHashMap<>();
        Map<String, List<String>> explanationRuns = new LinkedHashMap<>();
        CLAIMS.forEach(claim -> {
            statusRuns.put(claim.claimId(), new ArrayList<>());
            explanationRuns.put(claim.claimId(), new ArrayList<>());
        });

        for (int run = 1; run <= REPEAT; run++) {
            long startedAt = System.currentTimeMillis();
            ClaimComparisonResult result = service.compare(request);
            for (ClaimComparison comparison : result.claimComparisons()) {
                statusRuns.computeIfAbsent(comparison.claimId(), k -> new ArrayList<>())
                        .add(String.valueOf(comparison.comparisonStatus()));
                explanationRuns.computeIfAbsent(comparison.claimId(), k -> new ArrayList<>())
                        .add(comparison.explanation() == null ? "" : comparison.explanation().strip());
            }
            System.out.printf("  %d회차: 결과 %d건 (%dms)%n",
                    run, result.claimComparisons().size(), System.currentTimeMillis() - startedAt);
            Thread.sleep(PACING_MS);
        }

        reportStatus(statusRuns);
        reportExplanation(explanationRuns);
    }

    /** comparisonStatus는 정해진 분류라 회차마다 같아야 한다 — 다르면 판단이 뒤집힌 것이다. */
    private void reportStatus(Map<String, List<String>> statusRuns) {
        System.out.printf("%n---- comparisonStatus ----%n");
        int flipped = 0;
        for (var entry : statusRuns.entrySet()) {
            Set<String> distinct = new LinkedHashSet<>(entry.getValue());
            boolean flaky = distinct.size() > 1;
            if (flaky) {
                flipped++;
            }
            System.out.printf("  %-4s %-28s %s%n", entry.getKey(),
                    String.join(" / ", distinct), flaky ? "<<< 뒤집힘 " + entry.getValue() : "");
        }
        System.out.printf("  → Claim %d건 중 판단이 뒤집힌 것 %d건%n", statusRuns.size(), flipped);
    }

    /**
     * explanation은 자유 서술이라 글자까지 같기를 기대하지 않는다. 대신 완전 일치율과 글자 수
     * 범위를 남기고 실제 문장을 그대로 출력한다 — "같은 말을 다르게 썼는가"와 "다른 말을
     * 했는가"는 수치로 가를 수 없고 사람이 읽어야 한다.
     */
    private void reportExplanation(Map<String, List<String>> explanationRuns) {
        System.out.printf("%n---- explanation ----%n");
        for (var entry : explanationRuns.entrySet()) {
            List<String> runs = entry.getValue();
            Set<String> distinct = new LinkedHashSet<>(runs);
            int min = runs.stream().mapToInt(String::length).min().orElse(0);
            int max = runs.stream().mapToInt(String::length).max().orElse(0);
            System.out.printf("%n  [%s] 서로 다른 문장 %d개 / %d회 · 길이 %d~%d자%n",
                    entry.getKey(), distinct.size(), runs.size(), min, max);
            int index = 1;
            for (String text : distinct) {
                long seen = runs.stream().filter(text::equals).count();
                System.out.printf("    (%d/%d회) %s%n", seen, runs.size(),
                        text.length() > 160 ? text.substring(0, 160) + "…" : text);
                if (index++ >= 5) {
                    System.out.printf("    … 외 %d개%n", distinct.size() - 5);
                    break;
                }
            }
        }
    }
}
