package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRequestFingerprintTest {

    private final AnalysisRequestFingerprint fingerprint = new AnalysisRequestFingerprint();

    @Test
    void returnsSameHashForIdenticalRequests() {
        CreateAnalysisRequest first = request(
                "상품",
                "제목",
                List.of(new PageTextEvidence("광고 문구", "#claim")),
                List.of(new PageImageEvidence("https://example.com/image.jpg", "이미지"))
        );
        CreateAnalysisRequest second = request(
                "상품",
                "제목",
                List.of(new PageTextEvidence("광고 문구", "#claim")),
                List.of(new PageImageEvidence("https://example.com/image.jpg", "이미지"))
        );

        assertThat(fingerprint.generate(first)).isEqualTo(fingerprint.generate(second));
    }

    @Test
    void ignoresWhitespaceDifferences() {
        CreateAnalysisRequest first = request(
                "루테인 제품",
                "제목",
                List.of(new PageTextEvidence("눈 건강에 도움", "#detail p")),
                List.of(new PageImageEvidence("https://example.com/image.jpg", "상품 이미지"))
        );
        CreateAnalysisRequest second = request(
                "  루테인   제품 ",
                "제목",
                List.of(new PageTextEvidence(" 눈  건강에\n도움 ", " #detail   p ")),
                List.of(new PageImageEvidence(" https://example.com/image.jpg ", " 상품   이미지 "))
        );

        assertThat(fingerprint.generate(first)).isEqualTo(fingerprint.generate(second));
    }

    @Test
    void ignoresTextOrdering() {
        PageTextEvidence firstText = new PageTextEvidence("첫 번째 문구", "#first");
        PageTextEvidence secondText = new PageTextEvidence("두 번째 문구", "#second");

        CreateAnalysisRequest first = request("상품", "제목", List.of(firstText, secondText), List.of());
        CreateAnalysisRequest second = request("상품", "제목", List.of(secondText, firstText), List.of());

        assertThat(fingerprint.generate(first)).isEqualTo(fingerprint.generate(second));
    }

    @Test
    void ignoresImageOrdering() {
        PageImageEvidence firstImage =
                new PageImageEvidence("https://example.com/first.jpg", "첫 번째");
        PageImageEvidence secondImage =
                new PageImageEvidence("https://example.com/second.jpg", "두 번째");

        CreateAnalysisRequest first = request("상품", "제목", List.of(), List.of(firstImage, secondImage));
        CreateAnalysisRequest second = request("상품", "제목", List.of(), List.of(secondImage, firstImage));

        assertThat(fingerprint.generate(first)).isEqualTo(fingerprint.generate(second));
    }

    @Test
    void changesHashWhenProductNameChanges() {
        CreateAnalysisRequest first = request("상품 A", "제목", List.of(), List.of());
        CreateAnalysisRequest second = request("상품 B", "제목", List.of(), List.of());

        assertThat(fingerprint.generate(first)).isNotEqualTo(fingerprint.generate(second));
    }

    @Test
    void changesHashWhenTextContentChanges() {
        CreateAnalysisRequest first = request(
                "상품",
                "제목",
                List.of(new PageTextEvidence("기존 문구", "#claim")),
                List.of()
        );
        CreateAnalysisRequest second = request(
                "상품",
                "제목",
                List.of(new PageTextEvidence("변경 문구", "#claim")),
                List.of()
        );

        assertThat(fingerprint.generate(first)).isNotEqualTo(fingerprint.generate(second));
    }

    @Test
    void changesHashWhenImageUrlChanges() {
        CreateAnalysisRequest first = request(
                "상품",
                "제목",
                List.of(),
                List.of(new PageImageEvidence("https://example.com/first.jpg", "이미지"))
        );
        CreateAnalysisRequest second = request(
                "상품",
                "제목",
                List.of(),
                List.of(new PageImageEvidence("https://example.com/second.jpg", "이미지"))
        );

        assertThat(fingerprint.generate(first)).isNotEqualTo(fingerprint.generate(second));
    }

    @Test
    void changesHashWhenImageAltChanges() {
        CreateAnalysisRequest first = request(
                "상품",
                "제목",
                List.of(),
                List.of(new PageImageEvidence("https://example.com/image.jpg", "기존 설명"))
        );
        CreateAnalysisRequest second = request(
                "상품",
                "제목",
                List.of(),
                List.of(new PageImageEvidence("https://example.com/image.jpg", "변경 설명"))
        );

        assertThat(fingerprint.generate(first)).isNotEqualTo(fingerprint.generate(second));
    }

    @Test
    void ignoresPageTitleChanges() {
        CreateAnalysisRequest first = request("상품", "이전 제목", List.of(), List.of());
        CreateAnalysisRequest second = request("상품", "변경 제목", List.of(), List.of());

        assertThat(fingerprint.generate(first)).isEqualTo(fingerprint.generate(second));
    }

    @Test
    void returnsLowercaseSha256Hex() {
        String value = fingerprint.generate(request("상품", "제목", List.of(), List.of()));

        assertThat(value).matches("[0-9a-f]{64}");
    }

    @Test
    void handlesNullAndEmptyValuesDeterministically() {
        CreateAnalysisRequest nullValues =
                new CreateAnalysisRequest("https://example.com", "제목", null, null, null);
        CreateAnalysisRequest emptyValues =
                new CreateAnalysisRequest("https://example.com", "다른 제목", "  ", List.of(), List.of());

        assertThat(fingerprint.generate(nullValues)).isEqualTo(fingerprint.generate(emptyValues));
    }

    /**
     * selector가 달라도 내용이 같으면 같은 요청으로 본다 — 재사용 캐시가 성립하는 근거다.
     *
     * <p>실측(2026-09-27, i-hi.co.kr/product_no=111): 같은 페이지를 연달아 분석했는데 텍스트
     * 146개·이미지 24장이 내용까지 동일한데도 해시가 갈렸다. 원인은 네이버페이 결제 위젯
     * 두 줄이었고, 하나는 id에 타임스탬프가 박혀 있었으며(#NPAY_PROMOTION_IDNC_ID_1790436451926390)
     * 다른 하나는 위젯 로딩 타이밍에 따라 nth-of-type 번호가 밀렸다. 그 탓에 캐시가 매번
     * 빗나가 같은 페이지를 볼 때마다 새로 분석했고, AI#1 편차가 그대로 드러나
     * "새로고침할 때마다 검출 문구 수가 다르다"는 증상이 됐다.
     *
     * <p>아래 두 selector는 그때 실제로 관측된 값이다.
     */
    @Test
    void selector만_달라도_같은_요청으로_본다() {
        List<PageTextEvidence> before = List.of(
                new PageTextEvidence("이벤트100% 지급! 최대 1만원 혜택 확인하기",
                        "#NPAY_PROMOTION_IDNC_ID_1790436451926390"),
                new PageTextEvidence("간 건강에 도움을 줄 수 있습니다",
                        "div:nth-of-type(7) > div:nth-of-type(2) > div > p"));
        List<PageTextEvidence> after = List.of(
                new PageTextEvidence("이벤트100% 지급! 최대 1만원 혜택 확인하기",
                        "#NPAY_PROMOTION_IDNC_ID_179043652200755"),
                new PageTextEvidence("간 건강에 도움을 줄 수 있습니다",
                        "div:nth-of-type(6) > div:nth-of-type(2) > div > p"));

        assertThat(fingerprint.generate(request("상품", "제목", before, List.of())))
                .isEqualTo(fingerprint.generate(request("상품", "제목", after, List.of())));
    }

    /** 반대로 내용이 바뀌면 반드시 다른 요청이어야 한다 — 위 완화가 과하지 않은지 함께 고정한다. */
    @Test
    void 내용이_다르면_여전히_다른_요청이다() {
        List<PageTextEvidence> one = List.of(
                new PageTextEvidence("간 건강에 도움을 줄 수 있습니다", "p.claim"));
        List<PageTextEvidence> other = List.of(
                new PageTextEvidence("간 건강을 완벽하게 회복시킵니다", "p.claim"));

        assertThat(fingerprint.generate(request("상품", "제목", one, List.of())))
                .isNotEqualTo(fingerprint.generate(request("상품", "제목", other, List.of())));
    }

    private CreateAnalysisRequest request(
            String productName,
            String pageTitle,
            List<PageTextEvidence> texts,
            List<PageImageEvidence> images
    ) {
        return new CreateAnalysisRequest(
                "https://example.com/product",
                pageTitle,
                productName,
                texts,
                images
        );
    }
}
