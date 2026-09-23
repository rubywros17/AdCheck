package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim 추출(AI#1)이 <b>법정 기능성 내용 표시란</b>을 광고 문구로 뽑지 않는지 고정한다.
 *
 * <p>배경(2026-09-23, 분석 45 — exxxtreme.co.kr/product_no=69): 실제 상세페이지를 돌렸더니
 * 뽑힌 Claim 3건이 전부 표시란이었다.
 * <pre>
 *   [밀크씨슬추출물] 간 건강에 도움을 줄 수 있음
 *   [비오틴] 지방, 탄수화물, 단백질 대사와 에너지 생성에 필요
 *   실리마린 130mg
 * </pre>
 * {@code [원료명] 인정 기능성 문구} 형태는 식약처가 인정한 문구를 그대로 옮긴 표시사항이라
 * 과장 광고가 아니라 오히려 규정을 지킨 표기인데, 이게 Claim으로 올라가 규칙 판정까지 받고
 * HIGH Finding으로 화면에 떴다.
 *
 * <p>원인은 {@link ProductContentExtractionService}의 성분 문장 분류 기준이 <b>원재료명
 * 표시(표 형태)만</b> 제외 대상으로 명문화하고 기능성 내용 표시란은 빠뜨린 것이었다. 그래서
 * "성분의 기능·작용을 설명하는 문장 → 포함"에 걸렸다. 그 세 갈래 기준 자체는 전날(2026-09-22)
 * 추출 안정도를 60%→100%로 올리려고 넣은 것이라 되돌릴 수 없고, 제외 갈래를 하나 더
 * 명문화하는 쪽으로 고쳤다.
 *
 * <p>이 테스트는 표시란만 확인하지 <b>않는다</b> — 제외 기준을 넓히면 진짜 광고 문구까지
 * 같이 사라질 수 있어서, 같은 입력에 섞어 둔 과장 문구가 그대로 남는지도 함께 본다.
 * 한쪽만 보면 "전부 제외"라는 퇴행을 통과시켜 버린다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵. 호출 1회.
 */
class ClaimExtractionLabelSectionTest {

    /**
     * 분석 45에서 실제로 문제가 된 표시란 줄. 이 문장들은 Claim으로 올라오면 안 된다.
     * 마지막 줄은 함량만 적은 표기로, 광고가 아니라 표시란의 일부다.
     */
    private static final List<String> LABEL_SECTION_LINES = List.of(
            "[밀크씨슬추출물] 간 건강에 도움을 줄 수 있음",
            "[비오틴] 지방, 탄수화물, 단백질 대사와 에너지 생성에 필요",
            "실리마린 130mg");

    /**
     * 같은 페이지에 있어도 반드시 Claim으로 남아야 하는 진짜 광고 문구의 핵심 어구.
     *
     * <p>문장 전체가 아니라 어구로 확인하는 이유: 모델은 여러 절이 붙은 줄에서 주장에 해당하는
     * 절만 잘라 인용한다(실측: "지친 간, 이제 확실하게 관리하세요. 효과는 100% 보장입니다."
     * → Claim은 "효과는 100% 보장입니다."). 이건 정상 동작이라 완전 일치로 단언하면 고쳐야 할
     * 게 없는데도 테스트가 깨진다.
     */
    private static final List<String> REAL_AD_COPY_PHRASES = List.of("간세포", "100% 보장");

    private static final List<String> PAGE_TEXT = List.of(
            "밀크씨슬 간 건강 프리미엄",
            "단 2주 만에 손상된 간세포를 완벽하게 되살려 드립니다.",
            "지친 간, 이제 확실하게 관리하세요. 효과는 100% 보장입니다.",
            "기능성 내용",
            "[밀크씨슬추출물] 간 건강에 도움을 줄 수 있음",
            "[비오틴] 지방, 탄수화물, 단백질 대사와 에너지 생성에 필요",
            "실리마린 130mg",
            "원재료명: 밀크씨슬추출물, 비오틴, 비타민B1, 나이아신",
            "1일 1정, 물과 함께 섭취하세요.",
            "본 제품은 건강기능식품입니다.",
            "질병의 예방 및 치료를 위한 의약품이 아닙니다.");

    @Test
    void 기능성_내용_표시란은_Claim으로_뽑지_않고_광고_문구는_남긴다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        ProductContentExtractionService service = new ProductContentExtractionService(
                new GeminiClient(apiKey, "gemini-3.5-flash-lite"),
                new IngredientMatchingService(List.of(), List.of(), List.of(), List.of()));

        List<PageTextEvidence> blocks = PAGE_TEXT.stream()
                .map(text -> new PageTextEvidence(text, null))
                .toList();

        Set<String> claims = new LinkedHashSet<>();
        service.extract(blocks, Map.of()).claims()
                .forEach(claim -> claims.add(claim.claimText().trim()));

        System.out.println("=== 추출된 Claim " + claims.size() + "건 ===");
        claims.forEach(claim -> System.out.println("  - " + claim));

        assertThat(claims)
                .as("법정 기능성 내용 표시란은 광고 주장이 아니므로 Claim에서 제외돼야 한다")
                .doesNotContainAnyElementsOf(LABEL_SECTION_LINES);
        REAL_AD_COPY_PHRASES.forEach(phrase -> assertThat(claims)
                .as("제외 기준을 넓힌 탓에 진짜 광고 문구('%s')까지 사라지면 안 된다", phrase)
                .anyMatch(claim -> claim.contains(phrase)));
    }
}
