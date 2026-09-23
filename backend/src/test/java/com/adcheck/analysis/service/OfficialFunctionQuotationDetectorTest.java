package com.adcheck.analysis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OfficialFunctionQuotationDetector}를 DB에 실제로 들어 있는 공식 인정 기능성 문구
 * 전량으로 양방향 측정한다. Gemini 호출 없이 순수 자바 로직만 돌린다.
 *
 * <h2>왜 양방향인가</h2>
 * 이 필터는 두 방향으로 틀릴 수 있고 무게가 전혀 다르다.
 * <ul>
 *   <li><b>인용을 못 알아봄</b> → 정상 표기가 계속 HIGH로 뜬다. 지금과 같으니 나빠지지 않는다.</li>
 *   <li><b>위반을 인용으로 오판</b> → 위반을 걸러서 사용자에게 안 보여준다. 검수 도구가 위반을
 *       숨기는 것이라 치명적이다.</li>
 * </ul>
 * 그래서 아래쪽을 <b>0건</b>으로 단언하고, 위쪽은 비율만 기록한다. 숫자를 맞추려고 기준을
 * 느슨하게 만드는 일이 없도록, 두 단언의 엄격도를 일부러 다르게 뒀다.
 *
 * <p><b>이 테스트가 보장하지 못하는 것</b>: 변형 패턴과 과장어는 사람이 떠올린 목록이라 실제
 * 페이지의 표기 다양성을 다 담지 못한다. 통과했다고 "안전하다"가 아니라 "떠올린 함정에는
 * 빠지지 않는다"까지만 말할 수 있다. 실제 데이터 교차 확인은
 * {@code OfficialFunctionQuotationRealDataTest}가 맡는다.
 */
class OfficialFunctionQuotationDetectorTest {

    private final OfficialFunctionQuotationDetector detector = new OfficialFunctionQuotationDetector();

    /**
     * 실제 페이지가 공식 문구를 옮길 때 나타나는 표기 차이. DB 원문을 이렇게 흔든 뒤에도
     * 인용으로 알아봐야 한다.
     */
    private static final List<Variation> VARIATIONS = List.of(
            new Variation("원문 그대로", (name, text) -> text),
            new Variation("구분자 교체", (name, text) -> text.replace("･", "·").replace("ㆍ", "·")),
            new Variation("구분자 띄어쓰기", (name, text) -> text.replaceAll("[·･ㆍ]", " · ")),
            new Variation("원료명 머리말", (name, text) -> "[" + name + "] " + text),
            new Variation("어미 변형", (name, text) ->
                    text.replace("도움을 줄 수 있음", "도움을 줄 수 있는 건강기능식품입니다")));

    /**
     * 공식 문구에 덧붙는 과장 표현. <b>일부러 짧은 것을 섞었다</b> — 길이 비교로는 잡히지 않는
     * 경우를 확인해야 해서다("간 건강에 도움! 완치!"는 공식 문구보다 짧다).
     */
    private static final List<String> EXAGGERATIONS = List.of(
            "완치",
            "100% 보장",
            "부작용 없음",
            "의사도 추천하는",
            "단 2주 만에 암 예방");

    @Test
    @DisplayName("① 공식 문구를 옮긴 문장은 표기가 흔들려도 인용으로 알아본다")
    void 표기가_흔들린_공식문구를_인용으로_알아본다() {
        List<Row> rows = loadOfficialFunctions();
        assertThat(rows).as("측정 자료가 비어 있으면 아래 수치가 의미 없다").hasSizeGreaterThan(400);

        int total = 0;
        int detected = 0;
        List<String> missed = new ArrayList<>();
        for (Row row : rows) {
            List<OfficialFunction> context = List.of(new OfficialFunction(row.ingredient(), row.functionText()));
            for (Variation variation : VARIATIONS) {
                total++;
                String claim = variation.apply(row.ingredient(), row.functionText());
                if (detector.isQuotation(claim, context)) {
                    detected++;
                } else if (missed.size() < 10) {
                    missed.add("[" + variation.label() + "] " + claim);
                }
            }
        }

        System.out.printf("① 인용 인식률: %d/%d (%.1f%%)%n", detected, total, 100.0 * detected / total);
        missed.forEach(miss -> System.out.println("   (못 알아봄) " + miss));

        // 못 알아봐도 지금과 같을 뿐이라 하한만 둔다. 이 수치는 개선 폭을 보려고 기록한다.
        assertThat(detected).as("공식 문구 원문조차 못 알아보면 필터가 무용지물이다").isGreaterThan(0);
    }

    @Test
    @DisplayName("② 공식 문구에 과장을 덧붙인 문장은 단 1건도 인용으로 새면 안 된다")
    void 공식문구에_과장을_덧붙이면_인용이_아니다() {
        List<Row> rows = loadOfficialFunctions();

        int total = 0;
        List<String> leaked = new ArrayList<>();
        for (Row row : rows) {
            List<OfficialFunction> context = List.of(new OfficialFunction(row.ingredient(), row.functionText()));
            for (String exaggeration : EXAGGERATIONS) {
                total++;
                String claim = row.functionText() + " " + exaggeration;
                if (detector.isQuotation(claim, context)) {
                    leaked.add(claim);
                }
            }
        }

        System.out.printf("② 위반 오판(새어나감): %d/%d%n", leaked.size(), total);
        leaked.stream().limit(20).forEach(claim -> System.out.println("   (샘) " + claim));

        assertThat(leaked)
                .as("공식 문구를 담고 있어도 그 너머로 가는 표현은 걸러지면 안 된다 — 1건이라도 새면 이 필터를 넣을 수 없다")
                .isEmpty();
        assertThat(total).as("측정 자료가 비어 있으면 위 단언이 공허하다").isGreaterThan(2000);
    }

    @Test
    @DisplayName("③ 손으로 고른 경계 사례")
    void 경계_사례를_고정한다() {
        List<OfficialFunction> context = List.of(
                new OfficialFunction("밀크씨슬 추출물", "간 건강에 도움을 줄 수 있음"),
                new OfficialFunction("홍삼", "면역력 증진･피로개선･혈소판 응집억제를 통한 혈액흐름･기억력 개선･항산화･갱년기 여성의 건강에 도움을 줄 수 있음"));

        assertThat(detector.isQuotation("간 건강에 도움을 줄 수 있음", context)).isTrue();
        assertThat(detector.isQuotation("[밀크씨슬추출물] 간 건강에 도움을 줄 수 있음", context)).isTrue();
        // 분석 48에서 실제로 새어나간 문장 — 항목 하나를 빼고 옮겼고 머리말이 없다.
        assertThat(detector.isQuotation(
                "면역력 증진 · 피로개선·혈소판 응집 억제를 통한 혈액 흐름 · 기억력 개선, 항산화에 도움을 줄 수 있는 건강기능식품입니다.",
                context)).isTrue();

        // 공식 문구를 담되 그 너머로 가는 표현. 길이가 짧은 쪽도 반드시 걸러야 한다.
        assertThat(detector.isQuotation("간 건강에 도움을 줄 수 있음 — 나아가 간경화까지 완치!", context)).isFalse();
        assertThat(detector.isQuotation("간 건강에 도움! 완치!", context)).isFalse();
        assertThat(detector.isQuotation("간 건강에 도움을 줄 수 있음, 부작용 없이 안전합니다", context)).isFalse();
        // 공식 문구와 무관한 순수 광고 카피.
        assertThat(detector.isQuotation("단 2주 만에 손상된 간세포를 완벽하게 되살려 드립니다", context)).isFalse();
        // 확정 원료가 없으면(공식 문구 목록이 비면) 아무것도 거르지 않는다.
        assertThat(detector.isQuotation("간 건강에 도움을 줄 수 있음", List.of())).isFalse();
    }

    private record Row(String ingredient, String functionText) {
    }

    private record Variation(String label, java.util.function.BinaryOperator<String> transform) {
        String apply(String name, String text) {
            return transform.apply(name, text);
        }
    }

    /** {@code src/test/resources/official-functions.tsv} — 로컬 Postgres에서 뽑은 공식 문구 전량. */
    private List<Row> loadOfficialFunctions() {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("official-functions.tsv")) {
            if (in == null) {
                return rows;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                        rows.add(new Row(parts[0].trim(), parts[1].trim()));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("공식 기능성 문구 측정 자료를 읽지 못했습니다.", e);
        }
        return rows;
    }
}
