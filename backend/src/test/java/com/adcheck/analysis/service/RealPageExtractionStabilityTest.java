package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p><b>여러 페이지를 함께 재는 이유:</b> 한 페이지의 안정도만으로는 그 수치가 파이프라인의
 * 성질인지 그 페이지 특유의 성질인지 가릴 수 없다. {@code -Dstability.inputs=a.json,b.json}으로
 * 고정 입력을 여러 개 주면 페이지별로 재고 마지막에 한 표로 모아 보여준다. 값을 주지 않으면
 * 기본 고정 입력 하나만 잰다.
 *
 * <p><b>이 테스트는 측정 도구이며 CI 대상이 아니다.</b> GEMINI_API_KEY가 없으면 스킵한다
 * (호출 수 = 페이지 수 × REPEAT회). 기본 고정 입력은
 * {@code src/test/resources/real-page-input.json}이며, 다른 페이지로 갈아끼우려면 익스텐션이
 * 보낸 요청 JSON을 받아 그 이미지들을 한 번 OCR한 결과를 같은 형태({@code texts}: 문자열 배열,
 * {@code ocr}: 이미지 URL → 텍스트)로 저장하면 된다.
 */
class RealPageExtractionStabilityTest {

    private static final int REPEAT = 5;

    /**
     * 한 번 뽑은 결과를 그대로 쓰지 않고 <b>K회 뽑아 합집합</b>을 쓰면 어떻게 되는지 재기 위한
     * 설정({@code STABILITY_UNION}, 기본 1 = 끄기).
     *
     * <p>왜 합집합인가: 출현율이 55%라는 것은 claim 하나가 5회 중 2~3회만 잡힌다는 뜻이다.
     * 독립이라고 보면 3회 합집합의 기대 포착률은 1 − 0.45³ ≈ 91%다. 호출 비용은 거의 안 든다 —
     * 규칙 판정은 규칙 축으로, AI#2는 Claim 전체를 한 번에 묶으므로 <b>Claim이 늘어도 뒤쪽
     * 호출 수는 그대로</b>이고, 늘어나는 것은 AI#1 호출 K−1회뿐이다.
     *
     * <p>대신 잡음도 같이 데려온다. 그래서 재현율만이 아니라 <b>claim 수가 몇 배가 되는지</b>를
     * 함께 본다.
     *
     * <p>켜면 페이지당 {@code UNION_SAMPLES × K}회 호출한다. 같은 실행에서 단일 실행 지표와
     * 합집합 지표를 모두 내므로 둘을 같은 조건에서 견줄 수 있다.
     */
    private static final int UNION_SAMPLES = 3;
    /** 한 페이지를 마치고 다음 페이지로 넘어가기 전 간격. 무료 티어 15 RPM에 걸리지 않게 둔다. */
    private static final long PAGE_GAP_MS = 20_000L;

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

        // 시스템 프로퍼티가 우선이지만, gradle 명령줄의 -D는 테스트 JVM까지 오지 않으므로
        // 환경변수도 받는다(테스트 워커는 환경을 물려받는다).
        String configured = System.getProperty("stability.inputs", System.getenv("STABILITY_INPUTS"));
        List<String> inputs = List.of((configured == null || configured.isBlank()
                ? FROZEN_INPUT : configured).split(","));
        String model = model();
        System.out.printf("%n모델: %s%n", model);
        ProductContentExtractionService service = new ProductContentExtractionService(
                new GeminiClient(apiKey, model),
                new IngredientMatchingService(List.of(), List.of(), List.of(), List.of()));

        List<PageOutcome> outcomes = new ArrayList<>();
        for (String input : inputs) {
            byte[] raw = read(input.trim());
            if (raw == null) {
                System.out.printf("%n(건너뜀) 고정 입력을 찾지 못했습니다: %s%n", input.trim());
                continue;
            }
            if (!outcomes.isEmpty()) {
                Thread.sleep(PAGE_GAP_MS);
            }
            outcomes.add(measure(shortName(input.trim()), new ObjectMapper()
                    .readTree(new String(raw, StandardCharsets.UTF_8)), service));
        }
        Assumptions.assumeFalse(outcomes.isEmpty(), "읽을 수 있는 고정 입력이 없음 - 스킵");

        summarize(outcomes);
    }

    private PageOutcome measure(String name, JsonNode root, ProductContentExtractionService service)
            throws Exception {
        List<PageTextEvidence> blocks = new ArrayList<>();
        root.get("texts").forEach(node -> blocks.add(new PageTextEvidence(node.asString(), null)));
        Map<String, String> ocr = new LinkedHashMap<>();
        root.get("ocr").properties().forEach(e -> {
            if (!e.getValue().asString().isBlank()) {
                ocr.put(e.getKey(), e.getValue().asString());
            }
        });

        System.out.printf("%n==== %s - 텍스트 %d개 / OCR %d장 ====%n", name, blocks.size(), ocr.size());

        // claimText가 입력에 실제로 있는지 대조할 원본 뭉치. 공백까지 그대로 둔 것과, 공백을
        // 없앤 것 두 벌을 만든다 — "글자를 바꿨는가"와 "띄어쓰기만 다른가"를 갈라 보기 위해서다.
        String corpus = String.join("\n", root.get("texts").valueStream().map(JsonNode::asString).toList())
                + "\n" + String.join("\n", ocr.values());
        String corpusNoSpace = corpus.replaceAll("\\s+", "");

        int unionSize = unionSize();
        int runs = unionSize == 1 ? REPEAT : unionSize * UNION_SAMPLES;
        List<Set<String>> claimRuns = new ArrayList<>();
        List<Set<String>> ingredientRuns = new ArrayList<>();
        int totalClaims = 0;
        int verbatim = 0;
        int spacingOnly = 0;
        List<String> fabricated = new ArrayList<>();

        for (int i = 0; i < runs; i++) {
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

        int notInOriginal = totalClaims - verbatim - spacingOnly;
        System.out.printf("%n  -- 원문 보존 --%n");
        System.out.printf("  전체 %d건 · 원문 그대로 %d건(%.0f%%) · 띄어쓰기만 다름 %d건 · 원문에 없음 %d건%n",
                totalClaims, verbatim, 100.0 * verbatim / Math.max(totalClaims, 1),
                spacingOnly, notInOriginal);
        fabricated.forEach(claim -> System.out.println("     (원문에 없음) "
                + (claim.length() > 70 ? claim.substring(0, 70) + "…" : claim)));

        double recurrence = recurrence(claimRuns);
        double avgClaims = claimRuns.stream().mapToInt(Set::size).average().orElse(0);
        double unionRecurrence = recurrence;
        double avgUnionClaims = avgClaims;
        if (unionSize > 1) {
            List<Set<String>> unions = new ArrayList<>();
            for (int sample = 0; sample < UNION_SAMPLES; sample++) {
                Set<String> merged = new LinkedHashSet<>();
                for (int k = 0; k < unionSize; k++) {
                    merged.addAll(claimRuns.get(sample * unionSize + k));
                }
                unions.add(merged);
            }
            unionRecurrence = recurrence(unions);
            avgUnionClaims = unions.stream().mapToInt(Set::size).average().orElse(0);
            System.out.printf("%n  -- %d회 합집합 (표본 %d개) --%n", unionSize, UNION_SAMPLES);
            System.out.printf("  합집합 건수%s · 평균 %.1f건 (단일 실행 평균 %.1f건, %.1f배)%n",
                    unions.stream().map(u -> String.valueOf(u.size())).toList(),
                    avgUnionClaims, avgClaims, avgUnionClaims / Math.max(avgClaims, 0.01));
            System.out.printf("  재현율: 단일 %.0f%% → 합집합 %.0f%%%n", recurrence, unionRecurrence);
            report("Claim 추출 - 합집합", unions);
        }

        double claimStability = report("Claim 추출 - 문자열 그대로", claimRuns);
        // 같은 문장인데 줄바꿈·띄어쓰기만 다른 경우를 한 건으로 묶어 다시 센다. OCR 텍스트에는
        // 줄바꿈이 섞여 있고 모델이 회차마다 그걸 살리거나 합치는데, 둘 다 "원문 그대로"라
        // 지시 위반이 아니다. 실제 편차를 보려면 이 잡음을 걷어내야 한다.
        double normalizedStability = report("Claim 추출 - 공백 정규화 후", claimRuns.stream()
                .map(run -> run.stream()
                        .map(c -> c.replaceAll("\\s+", ""))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                .map(s -> (Set<String>) s)
                .toList());
        report("원료 후보", ingredientRuns);

        String counts = claimRuns.stream().map(r -> String.valueOf(r.size()))
                .reduce((a, b) -> a + "," + b).orElse("");
        return new PageOutcome(name, blocks.size(), ocr.size(), counts,
                claimStability, normalizedStability, notInOriginal,
                recurrence, unionRecurrence, avgClaims, avgUnionClaims);
    }

    /** 페이지별 결과를 한 표로 모은다. 한 페이지만 잰 수치를 파이프라인의 성질로 오해하지 않기 위해서다. */
    private void summarize(List<PageOutcome> outcomes) {
        System.out.printf("%n%n==== 페이지 교차 요약 (%d개 페이지 x %d회) ====%n", outcomes.size(), REPEAT);
        boolean union = unionSize() > 1;
        System.out.printf("%-26s %6s %5s %-16s %8s %8s %10s %8s%n",
                "페이지", "텍스트", "OCR", "Claim 건수", "안정도", "재현율",
                union ? "합집합재현" : "정규화후", union ? "평균건수" : "원문없음");
        for (PageOutcome o : outcomes) {
            System.out.printf("%-26s %6d %5d %-16s %7.0f%% %7.0f%% %9.0f%% %8s%n",
                    o.name(), o.textCount(), o.ocrCount(), "[" + o.counts() + "]",
                    o.stability(), o.recurrence(),
                    union ? o.unionRecurrence() : o.normalizedStability(),
                    union ? String.format("%.1f→%.1f", o.avgClaims(), o.avgUnionClaims())
                          : String.valueOf(o.notInOriginal()));
        }
        System.out.printf("%n평균 안정도 %.0f%% · 평균 재현율 %.0f%% · 원문에 없음 합계 %d건%n",
                outcomes.stream().mapToDouble(PageOutcome::stability).average().orElse(0),
                outcomes.stream().mapToDouble(PageOutcome::recurrence).average().orElse(0),
                outcomes.stream().mapToInt(PageOutcome::notInOriginal).sum());
        if (union) {
            System.out.printf("합집합(%d회) 평균 재현율 %.0f%% · claim 평균 %.1f건 → %.1f건 (%.1f배)%n",
                    unionSize(),
                    outcomes.stream().mapToDouble(PageOutcome::unionRecurrence).average().orElse(0),
                    outcomes.stream().mapToDouble(PageOutcome::avgClaims).average().orElse(0),
                    outcomes.stream().mapToDouble(PageOutcome::avgUnionClaims).average().orElse(0),
                    outcomes.stream().mapToDouble(PageOutcome::avgUnionClaims).average().orElse(0)
                            / Math.max(outcomes.stream().mapToDouble(PageOutcome::avgClaims).average().orElse(1), 0.01));
        }
    }

    /** 매번 나온 것(교집합)과 한 번이라도 나온 것(합집합)의 비로 안정도를 낸다. */
    private double report(String label, List<Set<String>> runs) {
        Set<String> union = new LinkedHashSet<>();
        runs.forEach(union::addAll);
        Set<String> intersection = new LinkedHashSet<>(union);
        runs.forEach(intersection::retainAll);

        String counts = runs.stream().map(r -> String.valueOf(r.size()))
                .reduce((a, b) -> a + "," + b).orElse("");
        double stability = union.isEmpty() ? 0 : 100.0 * intersection.size() / union.size();
        System.out.printf("%n  -- %s --%n", label);
        System.out.printf("  건수[%s] · 매번 %d건 / 한 번이라도 %d건 → 안정도 %.0f%%%n",
                counts, intersection.size(), union.size(), stability);

        Set<String> unstable = new LinkedHashSet<>(union);
        unstable.removeAll(intersection);
        unstable.stream().limit(15).forEach(item -> {
            long seen = runs.stream().filter(r -> r.contains(item)).count();
            System.out.printf("     (%d/%d회) %s%n", seen, runs.size(),
                    item.length() > 60 ? item.substring(0, 60) + "…" : item);
        });
        if (unstable.size() > 15) {
            System.out.printf("     … 외 %d건%n", unstable.size() - 15);
        }
        return stability;
    }

    /**
     * 각 항목이 몇 회에 등장했는지의 평균. 안정도(교집합/합집합)는 항목이 적은 페이지에서
     * 하나만 빠져도 0%가 되어 페이지끼리 비교가 안 되는데, 이 값은 그렇지 않다 — 2026-09-27
     * 교차 측정에서 6개 페이지가 전부 50~65%로 일정했다.
     */
    private double recurrence(List<Set<String>> runs) {
        Set<String> union = new LinkedHashSet<>();
        runs.forEach(union::addAll);
        if (union.isEmpty()) {
            return 0;
        }
        long seen = union.stream()
                .mapToLong(item -> runs.stream().filter(run -> run.contains(item)).count())
                .sum();
        return 100.0 * seen / ((double) union.size() * runs.size());
    }

    /**
     * {@code STABILITY_MODEL} — 어느 모델로 잴지. 기본은 운영과 같은 {@code gemini-3.5-flash-lite}.
     *
     * <p>"편차가 큰 게 가벼운 모델 탓인가"를 가르려고 둔다. 같은 세대에서 한 단계 위
     * ({@code gemini-3.5-flash})와 견주면 세대 차이가 아니라 <b>모델 크기</b>의 효과만 분리된다.
     * 상위 모델은 무료 티어 분당 한도가 더 낮으므로 회차 간격을 넉넉히 둘 것.
     */
    private String model() {
        String raw = System.getProperty("stability.model", System.getenv("STABILITY_MODEL"));
        return raw == null || raw.isBlank() ? "gemini-3.5-flash-lite" : raw.strip();
    }

    /** {@code STABILITY_UNION} — K회 뽑아 합집합을 쓰는 실험. 1이면 끈다. */
    private int unionSize() {
        String raw = System.getProperty("stability.union", System.getenv("STABILITY_UNION"));
        try {
            return raw == null || raw.isBlank() ? 1 : Math.max(1, Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 클래스패스 자원으로 먼저 찾고, 없으면 파일 경로로 읽는다. */
    private byte[] read(String reference) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(reference)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        Path path = Path.of(reference);
        return Files.exists(path) ? Files.readAllBytes(path) : null;
    }

    private String shortName(String reference) {
        int slash = Math.max(reference.lastIndexOf('/'), reference.lastIndexOf('\\'));
        return slash < 0 ? reference : reference.substring(slash + 1);
    }

    private record PageOutcome(String name, int textCount, int ocrCount, String counts,
                               double stability, double normalizedStability, int notInOriginal,
                               double recurrence, double unionRecurrence,
                               double avgClaims, double avgUnionClaims) {
    }
}
