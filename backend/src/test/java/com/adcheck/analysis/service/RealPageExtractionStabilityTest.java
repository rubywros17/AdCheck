package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>실제 상품 페이지 입력</b>으로 Claim 추출(AI#1)의 편차를 잰다.
 *
 * <p>왜 따로 만들었나: {@link ClaimExtractionStabilityTest}는 손으로 쓴 12줄짜리 합성 데이터로
 * 안정도 100%를 보이는데, 실제 페이지를 돌리면 같은 URL·같은 입력인데도 Claim이
 * <b>2~25건까지</b> 흔들린다(2026-09-23, i-hi.co.kr/product_no=111 반복 분석 실측:
 * 11 → 25 → 2 → 5 → 4 → 13 → 2 → 3). 규모가 다른 입력에서만 드러나는 편차라 합성 데이터로는
 * 잡히지 않는다.
 *
 * <p>입력이 정말 고정인지도 먼저 확인했다. 익스텐션이 보낸 요청을 세 번 받아 비교하니
 * 텍스트 146개·4,215자와 이미지 23장이 매번 같았고(순서만 한 번 달랐다), 따라서
 * <b>편차는 익스텐션 추출이 아니라 AI#1에서 난다</b>는 것이 확정됐다.
 *
 * <p>OCR까지 고정한 이유는 GCV도 실행마다 몇 글자씩 달라지기 때문이다(예: 982자 vs 1,010자).
 * 그 미세한 차이가 편차의 원인인지 아닌지를 가르려면 OCR을 상수로 묶어야 한다.
 *
 * <p><b>이 테스트는 측정 도구이며 CI 대상이 아니다.</b> GEMINI_API_KEY가 없으면 스킵한다
 * (호출 수 = REPEAT회). 고정 입력은 {@code src/test/resources/real-page-input.json}이며,
 * 다른 페이지로 갈아끼우려면 익스텐션이 보낸 요청 JSON을 받아 그 이미지들을 한 번 OCR한
 * 결과를 같은 형태({@code texts}: 문자열 배열, {@code ocr}: 이미지 URL → 텍스트)로 저장하면 된다.
 */
class RealPageExtractionStabilityTest {

    private static final int REPEAT = 5;

    /**
     * 실제 상품 페이지에서 뜬 고정 입력 — 익스텐션이 보낸 요청의 텍스트와, 그 이미지들을
     * Google Vision으로 한 번 OCR해 동결한 결과다. OCR까지 묶어둔 이유는 GCV도 실행마다
     * 몇 글자씩 달라지기 때문이다(예: 982자 vs 1,010자). 그 미세한 차이가 편차의 원인인지
     * 아닌지 가르려면 입력 전체가 상수여야 한다.
     */
    private static final String FROZEN_INPUT = "real-page-input.json";

    @Test
    void 실제_페이지_입력으로_추출_편차를_잰다() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");
        try (var in = getClass().getClassLoader().getResourceAsStream(FROZEN_INPUT)) {
            Assumptions.assumeTrue(in != null, "고정 입력 없음 - 스킵: " + FROZEN_INPUT);
        }
        JsonNode root;
        try (var in = getClass().getClassLoader().getResourceAsStream(FROZEN_INPUT)) {
            root = new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        List<PageTextEvidence> blocks = new ArrayList<>();
        root.get("texts").forEach(node -> blocks.add(new PageTextEvidence(node.asString(), null)));
        Map<String, String> ocr = new LinkedHashMap<>();
        root.get("ocr").properties().forEach(e -> {
            if (!e.getValue().asString().isBlank()) {
                ocr.put(e.getKey(), e.getValue().asString());
            }
        });

        System.out.printf("%n=== 고정 입력: 텍스트 %d개 / OCR %d장 ===%n", blocks.size(), ocr.size());

        ProductContentExtractionService service = new ProductContentExtractionService(
                new GeminiClient(apiKey, "gemini-3.5-flash-lite"),
                new IngredientMatchingService(List.of(), List.of(), List.of(), List.of()));

        // claimText가 입력에 실제로 있는지 대조할 원본 뭉치. 공백까지 그대로 둔 것과, 공백을
        // 없앤 것 두 벌을 만든다 — "글자를 바꿨는가"와 "띄어쓰기만 다른가"를 갈라 보기 위해서다.
        String corpus = String.join("\n", root.get("texts").valueStream().map(JsonNode::asString).toList())
                + "\n" + String.join("\n", ocr.values());
        String corpusNoSpace = corpus.replaceAll("\\s+", "");

        List<Set<String>> claimRuns = new ArrayList<>();
        List<Set<String>> ingredientRuns = new ArrayList<>();
        int totalClaims = 0;
        int verbatim = 0;
        int spacingOnly = 0;
        List<String> fabricated = new ArrayList<>();

        for (int i = 0; i < REPEAT; i++) {
            long startedAt = System.currentTimeMillis();
            var result = service.extract(blocks, ocr);

            Set<String> claims = new LinkedHashSet<>();
            result.claims().forEach(c -> claims.add(c.claimText().trim()));
            Set<String> ingredients = new LinkedHashSet<>();
            result.ingredientCandidates().forEach(c -> ingredients.add(c.rawText().trim()));

            for (String claim : claims) {
                totalClaims++;
                if (corpus.contains(claim)) {
                    verbatim++;
                } else if (corpusNoSpace.contains(claim.replaceAll("\\s+", ""))) {
                    spacingOnly++;
                } else if (fabricated.size() < 20) {
                    fabricated.add(claim);
                }
            }

            System.out.printf("  %d회차: Claim %2d건 · 원료 후보 %d건 (%dms)%n",
                    i + 1, claims.size(), ingredients.size(), System.currentTimeMillis() - startedAt);
            claimRuns.add(claims);
            ingredientRuns.add(ingredients);
            Thread.sleep(4_000);
        }

        System.out.printf("%n=== 원문 보존 (이번 수정의 직접 지표) ===%n");
        System.out.printf("전체 %d건 · 원문 그대로 %d건(%.0f%%) · 띄어쓰기만 다름 %d건 · 원문에 없음 %d건%n",
                totalClaims, verbatim, 100.0 * verbatim / Math.max(totalClaims, 1),
                spacingOnly, totalClaims - verbatim - spacingOnly);
        fabricated.forEach(claim -> System.out.println("   (원문에 없음) "
                + (claim.length() > 70 ? claim.substring(0, 70) + "…" : claim)));

        report("Claim 추출 — 문자열 그대로", claimRuns);
        // 같은 문장인데 줄바꿈·띄어쓰기만 다른 경우를 한 건으로 묶어 다시 센다. OCR 텍스트에는
        // 줄바꿈이 섞여 있고 모델이 회차마다 그걸 살리거나 합치는데, 둘 다 "원문 그대로"라
        // 지시 위반이 아니다. 실제 편차를 보려면 이 잡음을 걷어내야 한다.
        report("Claim 추출 — 공백 정규화 후", claimRuns.stream()
                .map(run -> run.stream()
                        .map(c -> c.replaceAll("\\s+", ""))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                .map(s -> (Set<String>) s)
                .toList());
        report("원료 후보", ingredientRuns);
    }

    /** 매번 나온 것(교집합)과 한 번이라도 나온 것(합집합)의 비로 안정도를 낸다. */
    private void report(String label, List<Set<String>> runs) {
        Set<String> union = new LinkedHashSet<>();
        runs.forEach(union::addAll);
        Set<String> intersection = new LinkedHashSet<>(union);
        runs.forEach(intersection::retainAll);

        String counts = runs.stream().map(r -> String.valueOf(r.size()))
                .reduce((a, b) -> a + "," + b).orElse("");
        double stability = union.isEmpty() ? 0 : 100.0 * intersection.size() / union.size();
        System.out.printf("%n=== %s ===%n", label);
        System.out.printf("건수[%s] · 매번 %d건 / 한 번이라도 %d건 → 안정도 %.0f%%%n",
                counts, intersection.size(), union.size(), stability);

        Set<String> unstable = new LinkedHashSet<>(union);
        unstable.removeAll(intersection);
        unstable.stream().limit(15).forEach(item -> {
            long seen = runs.stream().filter(r -> r.contains(item)).count();
            System.out.printf("   (%d/%d회) %s%n", seen, runs.size(),
                    item.length() > 60 ? item.substring(0, 60) + "…" : item);
        });
        if (unstable.size() > 15) {
            System.out.printf("   … 외 %d건%n", unstable.size() - 15);
        }
    }
}
