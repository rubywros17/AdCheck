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
