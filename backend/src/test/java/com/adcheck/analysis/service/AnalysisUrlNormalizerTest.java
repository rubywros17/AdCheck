package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisUrlNormalizerTest {

    private final AnalysisUrlNormalizer normalizer = new AnalysisUrlNormalizer();

    @Test
    void normalizesSchemeAndHostToLowercase() {
        assertThat(normalizer.normalize("HTTPS://SHOP.EXAMPLE.COM/Product"))
                .isEqualTo("https://shop.example.com/Product");
    }

    @Test
    void removesFragment() {
        assertThat(normalizer.normalize("https://shop.example.com/product?id=123#reviews"))
                .isEqualTo("https://shop.example.com/product?id=123");
    }

    @Test
    void removesDefaultPorts() {
        assertThat(normalizer.normalize("http://shop.example.com:80/product"))
                .isEqualTo("http://shop.example.com/product");
        assertThat(normalizer.normalize("https://shop.example.com:443/product"))
                .isEqualTo("https://shop.example.com/product");
    }

    @Test
    void removesTrackingParametersCaseInsensitively() {
        assertThat(normalizer.normalize(
                "https://shop.example.com/product?UTM_Source=google&id=123&fbclid=value"
        )).isEqualTo("https://shop.example.com/product?id=123");
    }

    @Test
    void preservesProductIdentificationQueryParameter() {
        assertThat(normalizer.normalize(
                "https://shop.example.com/product?id=123&utm_campaign=spring"
        )).isEqualTo("https://shop.example.com/product?id=123");
    }

    @Test
    void keepsDifferentProductQueriesDifferent() {
        String first = normalizer.normalize("https://shop.example.com/product?id=123");
        String second = normalizer.normalize("https://shop.example.com/product?id=456");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void removesNewerGoogleAdsParametersThatSurvivedBefore() {
        // 저장된 분석 34건의 normalized_url을 훑어보니 이 다섯 개가 그대로 남아 있었다
        // (2026-09-23 실측: gad_source·gad_campaignid·gbraid 각 14건, utm_id 5건, srsltid 3건).
        assertThat(normalizer.normalize(
                "https://shop.example.com/product?product_no=87&gad_source=1&gad_campaignid=241"
                        + "&gbraid=0AAAAA9z9xYr&srsltid=AU7gw4W&utm_id=2416"
        )).isEqualTo("https://shop.example.com/product?product_no=87");
    }

    @Test
    void removesAnyUtmPrefixedParameter() {
        // utm_은 표준 규약이라 변형이 계속 늘어난다 — 이름을 나열하는 대신 접두어로 막는다.
        assertThat(normalizer.normalize(
                "https://shop.example.com/product?id=1&utm_id=9&utm_marketing_tactic=x&utm_creative_format=y"
        )).isEqualTo("https://shop.example.com/product?id=1");
    }

    @Test
    void treatsSameProductReachedThroughDifferentAdClicksAsOneUrl() {
        // gbraid·srsltid는 클릭할 때마다 값이 바뀐다 — 제거하지 않으면 광고로 들어온 사용자는
        // 같은 상품을 볼 때마다 캐시가 빗나가 매번 새로 분석된다.
        String firstClick = normalizer.normalize(
                "https://happytori.kr/70/?idx=56&gbraid=AAA111&srsltid=BBB222&gad_source=4"
        );
        String secondClick = normalizer.normalize(
                "https://happytori.kr/70/?idx=56&gbraid=CCC333&srsltid=DDD444&gad_source=4"
        );

        assertThat(firstClick).isEqualTo(secondClick);
    }

    @Test
    void producesSameUrlWhenOnlyTrackingParametersDiffer() {
        String first = normalizer.normalize(
                "https://shop.example.com/product?id=123&utm_source=google"
        );
        String second = normalizer.normalize(
                "https://shop.example.com/product?fbclid=value&id=123"
        );

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsInvalidOrUnsupportedUrl() {
        assertThatThrownBy(() -> normalizer.normalize("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("분석 URL은 올바른 HTTP 또는 HTTPS URL이어야 합니다.");
        assertThatThrownBy(() -> normalizer.normalize("ftp://shop.example.com/product"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("분석 URL은 올바른 HTTP 또는 HTTPS URL이어야 합니다.");
    }
}
