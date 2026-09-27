package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CachingOcrServiceTest {

    private static final Duration TTL = Duration.ofDays(7);

    /** 호출될 때마다 무엇을 요청받았는지 기록하고, URL마다 다른 텍스트를 돌려주는 가짜 OCR. */
    private static final class RecordingOcr implements OcrService {
        private final Map<String, String> answers;
        private final List<List<String>> calls = new ArrayList<>();

        private RecordingOcr(Map<String, String> answers) {
            this.answers = answers;
        }

        @Override
        public String extractText(String imageUrl) {
            return extractTexts(List.of(imageUrl)).getOrDefault(imageUrl, "");
        }

        @Override
        public Map<String, String> extractTexts(List<String> imageUrls) {
            calls.add(List.copyOf(imageUrls));
            Map<String, String> result = new LinkedHashMap<>();
            imageUrls.forEach(url -> result.put(url, answers.getOrDefault(url, "")));
            return result;
        }
    }

    /** 테스트가 시간을 직접 밀 수 있는 시계. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    @Test
    void 같은_이미지를_다시_요청하면_인식하지_않고_캐시를_돌려준다() {
        RecordingOcr delegate = new RecordingOcr(Map.of("https://x/a.png", "원재료명 밀크씨슬"));
        CachingOcrService cache =
                new CachingOcrService(delegate, TTL, 100, new MovableClock());

        Map<String, String> first = cache.extractTexts(List.of("https://x/a.png"));
        Map<String, String> second = cache.extractTexts(List.of("https://x/a.png"));

        assertThat(first).isEqualTo(second);
        assertThat(delegate.calls).hasSize(1);
    }

    @Test
    void 캐시에_없는_것만_뒤로_넘긴다() {
        RecordingOcr delegate = new RecordingOcr(
                Map.of("https://x/a.png", "첫째", "https://x/b.png", "둘째"));
        CachingOcrService cache =
                new CachingOcrService(delegate, TTL, 100, new MovableClock());

        cache.extractTexts(List.of("https://x/a.png"));
        Map<String, String> both =
                cache.extractTexts(List.of("https://x/a.png", "https://x/b.png"));

        assertThat(both).containsExactly(
                Map.entry("https://x/a.png", "첫째"),
                Map.entry("https://x/b.png", "둘째"));
        // 두 번째 호출에서 b만 넘어가야 한다 — 입력 순서도 그대로 보존된다.
        assertThat(delegate.calls).containsExactly(
                List.of("https://x/a.png"), List.of("https://x/b.png"));
    }

    /**
     * 빈 문자열은 "글자가 없는 이미지"와 "다운로드·인식 실패" 둘 다를 뜻해 이 층위에서 구분할 수
     * 없다. 실패를 캐시하면 일시적 장애가 TTL 내내 굳어버리므로 담지 않는다.
     */
    @Test
    void 빈_결과는_캐시하지_않는다() {
        RecordingOcr delegate = new RecordingOcr(Map.of());
        CachingOcrService cache =
                new CachingOcrService(delegate, TTL, 100, new MovableClock());

        cache.extractTexts(List.of("https://x/empty.png"));
        cache.extractTexts(List.of("https://x/empty.png"));

        assertThat(delegate.calls).hasSize(2);
    }

    @Test
    void TTL이_지나면_다시_인식한다() {
        RecordingOcr delegate = new RecordingOcr(Map.of("https://x/a.png", "원재료명 밀크씨슬"));
        MovableClock clock = new MovableClock();
        CachingOcrService cache = new CachingOcrService(delegate, TTL, 100, clock);

        cache.extractTexts(List.of("https://x/a.png"));
        clock.advance(Duration.ofDays(6));
        cache.extractTexts(List.of("https://x/a.png"));
        assertThat(delegate.calls).as("만료 전에는 캐시를 쓴다").hasSize(1);

        clock.advance(Duration.ofDays(2));
        cache.extractTexts(List.of("https://x/a.png"));
        assertThat(delegate.calls).as("7일이 지나면 다시 인식한다").hasSize(2);
    }

    @Test
    void 같은_URL이_두_번_들어와도_한_번만_인식한다() {
        RecordingOcr delegate = new RecordingOcr(Map.of("https://x/a.png", "원재료명 밀크씨슬"));
        CachingOcrService cache =
                new CachingOcrService(delegate, TTL, 100, new MovableClock());

        Map<String, String> result =
                cache.extractTexts(List.of("https://x/a.png", "https://x/a.png"));

        assertThat(result).containsExactly(Map.entry("https://x/a.png", "원재료명 밀크씨슬"));
        assertThat(delegate.calls).containsExactly(List.of("https://x/a.png"));
    }

    @Test
    void 상한을_넘으면_오래된_것부터_버린다() {
        RecordingOcr delegate = new RecordingOcr(
                Map.of("https://x/a.png", "첫째", "https://x/b.png", "둘째"));
        CachingOcrService cache =
                new CachingOcrService(delegate, TTL, 1, new MovableClock());

        cache.extractTexts(List.of("https://x/a.png"));
        cache.extractTexts(List.of("https://x/b.png"));
        cache.extractTexts(List.of("https://x/a.png"));

        // 상한이 1이라 b가 들어올 때 a가 밀려나고, a를 다시 물으면 또 인식해야 한다.
        assertThat(delegate.calls).hasSize(3);
    }
}
