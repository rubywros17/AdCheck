package com.adcheck.analysis.service;

import com.adcheck.rule.service.RuleAnalysisRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimContextClassifierTest {

    private final ClaimContextClassifier classifier = new ClaimContextClassifier();

    @Test
    void classifiesDomTextClaimAsProductHealthEffectCopyWithSelectorEvidence() {
        ExtractedClaim claim = new ExtractedClaim("claim-1", "시력을 회복합니다.", Source.domText("#product-detail p"));

        assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY);
        assertThat(classifier.contextEvidence(claim)).isEqualTo("DOM_TEXT: #product-detail p");
    }

    @Test
    void classifiesOcrImageClaimAsProductHealthEffectCopyWithImageUrlEvidence() {
        ExtractedClaim claim = new ExtractedClaim(
                "claim-2", "관절 건강에 도움", Source.ocrImage("https://example.com/label.jpg")
        );

        assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY);
        assertThat(classifier.contextEvidence(claim)).isEqualTo("OCR_IMAGE: https://example.com/label.jpg");
    }

    @Test
    void classifiesNullSourceAsUnknownWithNoEvidence() {
        ExtractedClaim claim = new ExtractedClaim("claim-3", "효과가 있습니다.", null);

        assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.UNKNOWN);
        assertThat(classifier.contextEvidence(claim)).isNull();
    }

    @Test
    void classifiesUnknownSourceTypeAsUnknown() {
        ExtractedClaim claim = new ExtractedClaim("claim-4", "효과가 있습니다.", new Source("UNKNOWN_TYPE", null, null));

        assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.UNKNOWN);
        assertThat(classifier.contextEvidence(claim)).isNull();
    }

    @Test
    void isDeterministicAcrossRepeatedCalls() {
        ExtractedClaim claim = new ExtractedClaim("claim-5", "효과가 있습니다.", Source.domText("#a"));

        for (int i = 0; i < 5; i++) {
            assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY);
            assertThat(classifier.contextEvidence(claim)).isEqualTo("DOM_TEXT: #a");
        }
    }

    @Test
    void prefersAi1JudgedContextOverSourceBasedFallback() {
        // source는 DOM_TEXT라 폴백 로직이면 PRODUCT_HEALTH_EFFECT_COPY로 나오겠지만,
        // AI#1(ProductContentExtractionService)이 이미 리뷰로 판단해뒀다면 그 값이 우선한다.
        ExtractedClaim claim = new ExtractedClaim(
                "claim-6", "3개월 먹었는데 시력이 좋아졌어요", Source.domText("#review-list .review-item"),
                RuleAnalysisRequest.Context.NON_PRODUCT_INFORMATION,
                "'김OO님 후기'라는 서명이 함께 있어 구매자 리뷰이며, 판매자가 직접 한 주장이 아님"
        );

        assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.NON_PRODUCT_INFORMATION);
        assertThat(classifier.contextEvidence(claim))
                .isEqualTo("'김OO님 후기'라는 서명이 함께 있어 구매자 리뷰이며, 판매자가 직접 한 주장이 아님");
    }

    @Test
    void fallsBackToSourceBasedLogicWhenAi1ContextIsUnknown() {
        // AI#1이 UNKNOWN을 준 경우(3개 인자 생성자와 동일한 기본값)에는 기존 source 기반 로직을 그대로 탄다.
        ExtractedClaim claim = new ExtractedClaim(
                "claim-7", "건강한 하루를 시작하세요", Source.domText("#a"),
                RuleAnalysisRequest.Context.UNKNOWN, null
        );

        assertThat(classifier.classify(claim)).isEqualTo(RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY);
        assertThat(classifier.contextEvidence(claim)).isEqualTo("DOM_TEXT: #a");
    }
}
