package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vision 우선 + Gemini 폴백 규칙을 검증한다. 특히 <b>"글자가 없어서 빈 결과"를 실패로 오해해
 * 폴백하지 않는지</b>가 핵심이다 — 실측 147장 중 11장이 실제로 글자가 없는 이미지였고, 이걸
 * 폴백하면 Gemini 호출만 헛되이 늘어난다.
 */
class FallbackOcrServiceTest {

    private static final String IMAGE_A = "https://example.com/a.jpg";
    private static final String IMAGE_B = "https://example.com/b.jpg";

    @Test
    void Vision_키가_없으면_전부_Gemini로_처리한다() {
        StubVision vision = new StubVision(false, Map.of());
        StubGemini gemini = new StubGemini(Map.of(IMAGE_A, "제미나이 A", IMAGE_B, "제미나이 B"));
        FallbackOcrService service = new FallbackOcrService(vision, gemini, "auto");

        Map<String, String> result = service.extractTexts(List.of(IMAGE_A, IMAGE_B));

        assertThat(result).containsEntry(IMAGE_A, "제미나이 A").containsEntry(IMAGE_B, "제미나이 B");
        assertThat(gemini.requested).containsExactly(IMAGE_A, IMAGE_B);
    }

    @Test
    void Vision이_실패한_이미지만_Gemini로_다시_처리한다() {
        StubVision vision = new StubVision(true, Map.of(
                IMAGE_A, Optional.of("비전 A"),
                IMAGE_B, Optional.empty()));
        StubGemini gemini = new StubGemini(Map.of(IMAGE_B, "제미나이 B"));
        FallbackOcrService service = new FallbackOcrService(vision, gemini, "auto");

        Map<String, String> result = service.extractTexts(List.of(IMAGE_A, IMAGE_B));

        assertThat(result).containsEntry(IMAGE_A, "비전 A").containsEntry(IMAGE_B, "제미나이 B");
        assertThat(gemini.requested).containsExactly(IMAGE_B);
    }

    @Test
    void 글자가_없어_빈_결과인_이미지는_실패가_아니므로_폴백하지_않는다() {
        StubVision vision = new StubVision(true, Map.of(
                IMAGE_A, Optional.of("비전 A"),
                IMAGE_B, Optional.of("")));
        StubGemini gemini = new StubGemini(Map.of());
        FallbackOcrService service = new FallbackOcrService(vision, gemini, "auto");

        Map<String, String> result = service.extractTexts(List.of(IMAGE_A, IMAGE_B));

        assertThat(result).containsEntry(IMAGE_A, "비전 A").containsEntry(IMAGE_B, "");
        assertThat(gemini.requested).isEmpty();
    }

    @Test
    void provider가_gemini면_Vision을_아예_호출하지_않는다() {
        StubVision vision = new StubVision(true, Map.of(IMAGE_A, Optional.of("비전 A")));
        StubGemini gemini = new StubGemini(Map.of(IMAGE_A, "제미나이 A"));
        FallbackOcrService service = new FallbackOcrService(vision, gemini, "gemini");

        Map<String, String> result = service.extractTexts(List.of(IMAGE_A));

        assertThat(result).containsEntry(IMAGE_A, "제미나이 A");
        assertThat(vision.called).isFalse();
    }

    @Test
    void provider가_google_vision이면_실패해도_폴백하지_않는다() {
        StubVision vision = new StubVision(true, Map.of(IMAGE_A, Optional.empty()));
        StubGemini gemini = new StubGemini(Map.of(IMAGE_A, "제미나이 A"));
        FallbackOcrService service = new FallbackOcrService(vision, gemini, "google-vision");

        Map<String, String> result = service.extractTexts(List.of(IMAGE_A));

        assertThat(result).containsEntry(IMAGE_A, "");
        assertThat(gemini.requested).isEmpty();
    }

    private static final class StubVision extends GoogleVisionOcrService {
        private final boolean available;
        private final Map<String, Optional<String>> results;
        private boolean called;

        private StubVision(boolean available, Map<String, Optional<String>> results) {
            super(new OcrImageLoader(), available ? "test-key" : "");
            this.available = available;
            this.results = results;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Map<String, Optional<String>> extractTextsDetailed(List<String> imageUrls) {
            called = true;
            Map<String, Optional<String>> out = new LinkedHashMap<>();
            imageUrls.forEach(url -> out.put(url, results.getOrDefault(url, Optional.empty())));
            return out;
        }
    }

    private static final class StubGemini extends GeminiOcrService {
        private final Map<String, String> results;
        private final List<String> requested = new ArrayList<>();

        private StubGemini(Map<String, String> results) {
            super(new GeminiClient("test-key", "test-model"), new OcrImageLoader());
            this.results = results;
        }

        @Override
        public Map<String, String> extractTexts(List<String> imageUrls) {
            requested.addAll(imageUrls);
            Map<String, String> out = new LinkedHashMap<>();
            imageUrls.forEach(url -> out.put(url, results.getOrDefault(url, "")));
            return out;
        }
    }
}
