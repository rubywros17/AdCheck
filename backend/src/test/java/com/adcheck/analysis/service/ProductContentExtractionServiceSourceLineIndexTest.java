package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GeminiClaimAnalyzer가 PageTextEvidence의 selector 정보를 버려서 Finding.selector가
 * 항상 null이 되던 문제(2026-09-18)를 고친 뒤의 회귀 테스트 — claims[].sourceLineIndex로
 * 원본 텍스트 블록의 selector를 정확히 역참조하는지, 그리고 인덱스가 없거나 범위를
 * 벗어나도 예외 없이 안전하게(기존 문자열 기반 판정으로) 폴백하는지 확인한다.
 */
class ProductContentExtractionServiceSourceLineIndexTest {

    private static final String BLOCK_1_SELECTOR = "#desc > p:nth-child(1)";
    private static final String BLOCK_2_SELECTOR = "#desc > p:nth-child(2)";
    private static final String IMAGE_URL = "https://example.com/label.png";

    @Test
    void claim의_sourceLineIndex로_원본_블록의_selector를_정확히_역참조한다() {
        String cannedResponse = """
                {"claims": [
                  {"claimText": "간 건강에 도움을 줍니다.", "source": "본문", "context": "UNKNOWN", "contextEvidence": null, "sourceLineIndex": 1},
                  {"claimText": "체지방 감소 효과가 있습니다.", "source": "본문", "context": "UNKNOWN", "contextEvidence": null, "sourceLineIndex": 3},
                  {"claimText": "이미지 속 효과 문구", "source": "%s", "context": "UNKNOWN", "contextEvidence": null, "sourceLineIndex": 4}
                ], "productCandidates": [], "labelReview": "", "labelLineGroups": [], "labelGroupConfidences": [], "riskSignals": []}
                """.formatted(IMAGE_URL);

        ProductContentExtractionService service = new ProductContentExtractionService(
                new CannedGeminiClient(cannedResponse), mock(IngredientMatchingService.class));

        List<PageTextEvidence> textBlocks = List.of(
                new PageTextEvidence("첫 번째 문단입니다.\n간 건강에 도움을 줍니다.", BLOCK_1_SELECTOR),
                new PageTextEvidence("두 번째 문단입니다.\n체지방 감소 효과가 있습니다.", BLOCK_2_SELECTOR)
        );
        Map<String, String> ocrResults = Map.of(IMAGE_URL, "이미지 속 효과 문구");

        List<ExtractedClaim> claims = service.extract(textBlocks, ocrResults).claims();

        assertThat(claims).hasSize(3);

        assertThat(claims.get(0).claimText()).isEqualTo("간 건강에 도움을 줍니다.");
        assertThat(claims.get(0).source().sourceType()).isEqualTo("DOM_TEXT");
        assertThat(claims.get(0).source().selector()).isEqualTo(BLOCK_1_SELECTOR);

        assertThat(claims.get(1).claimText()).isEqualTo("체지방 감소 효과가 있습니다.");
        assertThat(claims.get(1).source().sourceType()).isEqualTo("DOM_TEXT");
        assertThat(claims.get(1).source().selector()).isEqualTo(BLOCK_2_SELECTOR);

        assertThat(claims.get(2).source().sourceType()).isEqualTo("OCR_IMAGE");
        assertThat(claims.get(2).source().imageUrl()).isEqualTo(IMAGE_URL);
    }

    @Test
    void sourceLineIndex가_null이거나_범위를_벗어나면_예외_없이_selector_null로_폴백한다() {
        String cannedResponse = """
                {"claims": [
                  {"claimText": "인덱스 없는 claim", "source": "본문", "context": "UNKNOWN", "contextEvidence": null, "sourceLineIndex": null},
                  {"claimText": "범위 밖 인덱스 claim", "source": "본문", "context": "UNKNOWN", "contextEvidence": null, "sourceLineIndex": 999}
                ], "productCandidates": [], "labelReview": "", "labelLineGroups": [], "labelGroupConfidences": [], "riskSignals": []}
                """;

        ProductContentExtractionService service = new ProductContentExtractionService(
                new CannedGeminiClient(cannedResponse), mock(IngredientMatchingService.class));

        List<PageTextEvidence> textBlocks = List.of(new PageTextEvidence("본문입니다.", BLOCK_1_SELECTOR));

        List<ExtractedClaim> claims = service.extract(textBlocks, Map.of()).claims();

        assertThat(claims).hasSize(2);
        assertThat(claims).allSatisfy(claim -> {
            assertThat(claim.source().sourceType()).isEqualTo("DOM_TEXT");
            assertThat(claim.source().selector()).isNull();
        });
    }

    private static final class CannedGeminiClient extends GeminiClient {
        private final String response;

        CannedGeminiClient(String response) {
            super("test-api-key", "test-model");
            this.response = response;
        }

        @Override
        String generate(String prompt, boolean jsonMode) {
            return response;
        }
    }
}
