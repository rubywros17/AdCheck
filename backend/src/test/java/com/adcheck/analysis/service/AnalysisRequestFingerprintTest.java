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
