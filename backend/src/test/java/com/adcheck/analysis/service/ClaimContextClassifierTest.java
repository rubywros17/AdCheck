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
}
