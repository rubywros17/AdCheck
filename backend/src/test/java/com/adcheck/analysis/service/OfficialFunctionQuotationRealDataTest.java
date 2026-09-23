package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OfficialFunctionQuotationDetector}를 <b>실제로 쌓인 Claim 원문</b>에 통과시켜, 무엇이
 * 인용으로 분류되는지 목록으로 출력한다.
 *
 * <p>{@link OfficialFunctionQuotationDetectorTest}가 쓰는 변형·과장 조합은 사람이 떠올린
 * 목록이라 실제 페이지의 표기 다양성을 다 담지 못한다. 그 테스트를 통과했다는 건 "떠올린
 * 함정에는 빠지지 않는다"까지이지 "안전하다"가 아니다. 그래서 실측 데이터로 따로 본다.
 *
 * <h2>일부러 최악의 조건으로 돌린다</h2>
 * 저장된 Finding에는 그 분석에서 어떤 원료가 확정됐는지가 남아 있지 않다. 그래서 DB에 있는
 * <b>공식 문구 483종 전부</b>를 컨텍스트로 준다 — 실제 운영보다 훨씬 넓어서 인용 판정이 더
 * 후하게 나오는 조건이다. 이 조건에서도 광고 카피가 인용으로 분류되지 않으면, 실제의 좁은
 * 컨텍스트에서는 더더욱 분류되지 않는다.
 *
 * <p>이 테스트는 단언이 느슨하다 — 분류 결과가 맞는지는 사람이 목록을 보고 판단해야 하고,
 * 그게 이 테스트의 용도다. 자동으로 막는 건 "전부 인용으로 분류"라는 명백한 폭주뿐이다.
 */
class OfficialFunctionQuotationRealDataTest {

    private final OfficialFunctionQuotationDetector detector = new OfficialFunctionQuotationDetector();

    @Test
    void 실제_저장된_Claim에_돌려_분류_결과를_출력한다() {
        List<OfficialFunction> allOfficialFunctions = loadAllOfficialFunctions();
        List<String> claims = loadLines("stored-claims.txt");
        assertThat(allOfficialFunctions).hasSizeGreaterThan(400);
        assertThat(claims).hasSizeGreaterThan(50);

        List<String> quoted = new ArrayList<>();
        List<String> notQuoted = new ArrayList<>();
        for (String claim : claims) {
            if (detector.isQuotation(claim, allOfficialFunctions)) {
                // 왜 인용으로 봤는지 사람이 검토할 수 있게 매칭된 공식 문구를 찾아 함께 남긴다.
                // 판정 자체는 전체 목록으로 하고, 근거는 하나씩 단독으로 다시 돌려 되짚는다.
                String matched = allOfficialFunctions.stream()
                        .filter(official -> detector.isQuotation(claim, List.of(official)))
                        .findFirst()
                        .map(official -> official.ingredientCode() + " → " + official.functionText())
                        .orElse("(머리말 원료명 등 다른 원료 정보와 함께여야 성립)");
                quoted.add(claim + "\n          ↳ 매칭: " + matched);
            } else {
                notQuoted.add(claim);
            }
        }

        System.out.printf("%n=== 실제 저장 Claim %d건 분류 (공식 문구 %d종 전부를 컨텍스트로) ===%n",
                claims.size(), allOfficialFunctions.size());
        System.out.printf("인용으로 분류: %d건 / 광고로 남김: %d건%n%n", quoted.size(), notQuoted.size());
        System.out.println("--- 인용으로 분류(= 앞으로 Finding에서 제외됨). 여기에 진짜 광고 카피가 있으면 안 된다 ---");
        quoted.forEach(claim -> System.out.println("  [인용] " + claim));
        System.out.println();
        System.out.println("--- 광고로 남김(= 지금과 동일하게 검수 대상) ---");
        notQuoted.forEach(claim -> System.out.println("  [광고] " + claim));

        assertThat(quoted)
                .as("실제 Claim이 전부 인용으로 분류되면 필터가 폭주한 것이다")
                .hasSizeLessThan(claims.size());
    }


    /**
     * "확인이 필요한 표현입니다." 고정 문구로 뜨던 Finding(REVIEW_REQUIRED만 있던 것)이 표시란
     * 제외로 얼마나 줄어드는지 잰다 — 남은 양이 B(표시 정책 재검토)가 필요한지를 가른다.
     * 여기서도 공식 문구 483종 전부를 컨텍스트로 주므로, 나오는 감소폭은 <b>상한</b>이다.
     */
    @Test
    void 고정문구_Finding이_표시란_제외로_얼마나_줄어드는지_잰다() {
        List<OfficialFunction> all = loadAllOfficialFunctions();
        List<String> claims = loadLines("review-only-claims.txt");

        List<String> removed = new ArrayList<>();
        List<String> remaining = new ArrayList<>();
        for (String claim : claims) {
            (detector.isQuotation(claim, all) ? removed : remaining).add(claim);
        }

        System.out.printf("%n=== 고정 문구 Finding %d건 중 표시란 제외로 사라지는 것 ===%n", claims.size());
        System.out.printf("사라짐: %d건 / 남음: %d건 (%.0f%% 감소)%n%n",
                removed.size(), remaining.size(), 100.0 * removed.size() / claims.size());
        System.out.println("--- 남는 것(= 여전히 \"확인이 필요한 표현입니다\" HIGH로 뜸) ---");
        remaining.forEach(claim -> System.out.println("  [남음] " + claim));

        assertThat(claims).isNotEmpty();
    }

    private List<OfficialFunction> loadAllOfficialFunctions() {
        List<OfficialFunction> rows = new ArrayList<>();
        for (String line : loadLines("official-functions.tsv")) {
            String[] parts = line.split("\t", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                rows.add(new OfficialFunction(parts[0].trim(), parts[1].trim()));
            }
        }
        return rows;
    }

    private List<String> loadLines(String resource) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("측정 자료를 찾지 못했습니다: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("측정 자료를 읽지 못했습니다: " + resource, e);
        }
        return lines;
    }
}
