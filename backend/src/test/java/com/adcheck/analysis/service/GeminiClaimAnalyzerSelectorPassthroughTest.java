package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GeminiClaimAnalyzer가 여러 PageTextEvidence 블록을 하나의 문자열로 합치지 않고,
 * 각 블록의 selector를 보존한 채로 ProductContentExtractionService까지 그대로
 * 전달하는지 확인한다 — 예전엔 texts.stream().reduce(...)로 content만 이어붙여서
 * selector가 여기서부터 유실됐었다(2026-09-18 수정).
 */
class GeminiClaimAnalyzerSelectorPassthroughTest {

    @Test
    void 여러_텍스트_블록의_selector가_합쳐지지_않고_그대로_전달된다() {
        GeminiOcrService ocrService = mock(GeminiOcrService.class);
        when(ocrService.extractTexts(anyList())).thenReturn(Map.of());

        ProductContentExtractionService extractionService = mock(ProductContentExtractionService.class);
        when(extractionService.extract(anyList(), anyMap())).thenReturn(
                new ProductContentExtractionService.ExtractionResult(List.of(), List.of(), List.of(), List.of()));

        GeminiClaimAnalyzer analyzer =
                new GeminiClaimAnalyzer(new DetailTextCleaner(), ocrService, extractionService);

        List<PageTextEvidence> texts = List.of(
                new PageTextEvidence("간 건강에 도움을 줍니다.", "#desc > p:nth-child(1)"),
                new PageTextEvidence("체지방 감소 효과가 있습니다.", "#desc > p:nth-child(2)")
        );

        analyzer.analyze(texts, List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PageTextEvidence>> captor = ArgumentCaptor.forClass(List.class);
        verify(extractionService).extract(captor.capture(), anyMap());

        List<PageTextEvidence> passed = captor.getValue();
        assertThat(passed).hasSize(2);
        assertThat(passed.get(0).content()).isEqualTo("간 건강에 도움을 줍니다.");
        assertThat(passed.get(0).selector()).isEqualTo("#desc > p:nth-child(1)");
        assertThat(passed.get(1).content()).isEqualTo("체지방 감소 효과가 있습니다.");
        assertThat(passed.get(1).selector()).isEqualTo("#desc > p:nth-child(2)");
    }
}
