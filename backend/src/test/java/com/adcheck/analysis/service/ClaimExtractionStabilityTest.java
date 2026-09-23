package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 같은 입력을 여러 번 넣어 Claim 추출(AI#1)이 얼마나 흔들리는지 잰다.
 *
 * <p>배경: {@code docs/rule-judge-instability-log.md}에 기록한 대로, 완전히 동일한 텍스트를 두 번
 * 제출했는데 Finding이 5건→4건으로 바뀌고 그중 각각 2건씩이 상대 실행에 없는 항목이었다. 편차의
 * 주 발생지가 추출 단계인지 판정 단계인지 가르려면 추출만 떼어내 반복 측정해야 한다. 전체
 * 파이프라인은 1회에 Gemini 13~16콜이 들지만, 이 테스트는 <b>1회에 1콜</b>이라 반복 측정에 적합하다.
 *
 * <p><b>모델 쪽으로는 손댈 수 있는 게 없다는 것도 확인했다</b>(2026-09-22). Gemini 3.x는 공식
 * 문서가 {@code temperature}/{@code topP}/{@code topK}를 "모든 요청에서 제거하라"고 명시하고,
 * 대안으로 제시된 {@code thinkingLevel}은 이 모델에서 아예 거부된다 —
 * {@code 400 INVALID_ARGUMENT: Unknown name "thinkingLevel" at 'generation_config'}.
 * 즉 편차를 줄이려면 프롬프트·구조 쪽에서 방법을 찾아야 한다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵. 호출 수 = REPEAT회.
 */
class ClaimExtractionStabilityTest {

    private static final int REPEAT = 4;

    /** 실제 상세페이지에서 뽑은 문구 — 효능 주장과 일반 안내가 섞여 있어 추출 판단이 갈릴 여지가 있다. */
    private static final List<String> PAGE_TEXT = List.of(
            "밀크씨슬 간 건강 프리미엄",
            "간 건강에 도움을 줄 수 있는 실리마린과 에너지 생성에 필요한 비타민을 하나에 담았습니다.",
            "실리마린이 간 건강을 지키는 데 도움을 줄 수 있습니다.",
            "밀크씨슬추출물의 대표 성분이며 1정당 130mg 함유, 주원료 기준 100% 충족합니다.",
            "탄수화물, 지방, 단백질 대사에 관여하여 에너지를 만드는 데 필요합니다.",
            "밀크씨슬추출물(실리마린) 섭취군에서 ALT.AST 수치감소가 확인되었습니다.",
            "잦은 술자리와 피로한 일상에 지친 당신에게",
            "1일 1정, 물과 함께 섭취하세요.",
            "원재료명: 밀크씨슬추출물, 비타민B1, 비타민B2, 나이아신",
            "직사광선을 피해 서늘한 곳에 보관하십시오.",
            "본 제품은 건강기능식품입니다.",
            "질병의 예방 및 치료를 위한 의약품이 아닙니다.");

    @Test
    void 같은_입력을_반복해_Claim_추출_편차를_잰다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        Map<String, List<Set<String>>> byCondition = new LinkedHashMap<>();
        byCondition.put("기본 설정", runRepeats(apiKey));

        System.out.println();
        System.out.println("=== Claim 추출 안정성 (같은 입력 " + REPEAT + "회) ===");
        byCondition.forEach((condition, runs) -> {
            Set<String> union = new LinkedHashSet<>();
            runs.forEach(union::addAll);
            Set<String> intersection = new LinkedHashSet<>(union);
            runs.forEach(intersection::retainAll);

            String counts = runs.stream().map(run -> String.valueOf(run.size())).reduce((a, b) -> a + "," + b).orElse("");
            double stability = union.isEmpty() ? 0 : 100.0 * intersection.size() / union.size();
            System.out.printf("%-26s 건수[%s]  매번 나온 Claim %d건 / 한 번이라도 나온 Claim %d건 → 안정도 %.0f%%%n",
                    condition, counts, intersection.size(), union.size(), stability);

            Set<String> unstable = new LinkedHashSet<>(union);
            unstable.removeAll(intersection);
            unstable.forEach(claim -> System.out.println("    (흔들림) " + claim));
        });
    }

    private List<Set<String>> runRepeats(String apiKey) {
        // 원료 매칭은 Claim 추출 결과에 영향을 주지 않는다(원료표 후보 필터링에만 쓰임).
        // 이 테스트는 claims만 보므로 빈 사전으로 둔다.
        ProductContentExtractionService service = new ProductContentExtractionService(
                new GeminiClient(apiKey, "gemini-3.5-flash-lite"),
                new IngredientMatchingService(List.of(), List.of(), List.of(), List.of()));

        List<PageTextEvidence> blocks = PAGE_TEXT.stream()
                .map(text -> new PageTextEvidence(text, null))
                .toList();

        List<Set<String>> runs = new java.util.ArrayList<>();
        for (int i = 0; i < REPEAT; i++) {
            long startedAt = System.currentTimeMillis();
            List<ExtractedClaim> claims = service.extract(blocks, Map.of()).claims();
            Set<String> texts = new LinkedHashSet<>();
            claims.forEach(claim -> texts.add(claim.claimText().trim()));
            System.out.printf("  %d회차: Claim %d건 (%dms)%n",
                    i + 1, texts.size(), System.currentTimeMillis() - startedAt);
            runs.add(texts);
            sleep(4_000);
        }
        return runs;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
