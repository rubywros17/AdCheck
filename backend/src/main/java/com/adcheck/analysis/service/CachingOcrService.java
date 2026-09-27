package com.adcheck.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 같은 이미지 URL을 다시 만나면 앞서 인식한 텍스트를 그대로 돌려준다.
 *
 * <p><b>왜 필요한가.</b> 두 가지 때문이다.
 *
 * <p>첫째는 <b>정합성</b>이다. Google Cloud Vision도 같은 이미지를 실행할 때마다 몇 글자씩 다르게
 * 읽는다(실측: 같은 이미지가 982자 vs 1,010자). 그 텍스트가 AI#1 프롬프트로 그대로 들어가므로,
 * 입력이 미세하게 달라진 채 "같은 페이지인데 결과가 다르다"에 얹힌다. URL이 같으면 텍스트도 같게
 * 고정하면 변동 원인 하나가 아예 사라진다.
 *
 * <p>둘째는 <b>비용</b>이다. 재사용 캐시({@code AnalysisResultResolver})가 맞으면 파이프라인이
 * 아예 돌지 않지만, 페이지 텍스트가 조금 바뀌거나 재사용 TTL이 지나면 전체가 다시 돈다. 그때
 * 이미지 24장 중 23장이 그대로여도 전부 다시 내려받고 다시 인식한다.
 *
 * <p><b>빈 결과는 캐시하지 않는다.</b> {@link OcrService#extractTexts}의 계약상 빈 문자열은
 * "글자가 없는 이미지"와 "다운로드·인식 실패" 둘 다를 뜻해서 이 층위에서는 구분할 수 없다.
 * 실패를 캐시하면 일시적 장애가 만료될 때까지 굳어버리므로, 글자가 정말 없는 이미지를 매번 다시
 * 인식하는 비용을 감수하고 <b>성공한 것만</b> 담는다.
 *
 * <p>메모리에만 둔다. 서버를 재시작하면 비워지고 인스턴스끼리 공유되지 않는다 — 우선 효과를
 * 재 보고, 값이 확인되면 그때 영구 저장을 검토한다.
 */
@Service
@Primary
public class CachingOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(CachingOcrService.class);

    private final OcrService delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> cache;

    // 생성자가 둘이라 Spring이 어느 쪽을 쓸지 스스로 고르지 못한다 — 운영용은 이쪽이다.
    @Autowired
    public CachingOcrService(
            FallbackOcrService delegate,
            @Value("${adcheck.ocr.cache-ttl:P7D}") Duration ttl,
            @Value("${adcheck.ocr.cache-max-entries:2000}") int maxEntries
    ) {
        this(delegate, ttl, maxEntries, Clock.systemUTC());
    }

    /** 테스트에서 시간을 직접 밀어 만료를 확인하기 위한 생성자. */
    CachingOcrService(OcrService delegate, Duration ttl, int maxEntries, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > Math.max(1, maxEntries);
            }
        });
    }

    @Override
    public String extractText(String imageUrl) {
        return extractTexts(List.of(imageUrl)).getOrDefault(imageUrl, "");
    }

    @Override
    public Map<String, String> extractTexts(List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return Map.of();
        }

        Map<String, String> hits = new LinkedHashMap<>();
        // 같은 URL이 두 번 들어와도 아래 위임 호출은 한 번만 나가야 한다.
        LinkedHashSet<String> misses = new LinkedHashSet<>();
        for (String imageUrl : imageUrls) {
            String cached = lookup(imageUrl);
            if (cached != null) {
                hits.put(imageUrl, cached);
            } else {
                misses.add(imageUrl);
            }
        }

        Map<String, String> fresh = misses.isEmpty()
                ? Map.of()
                : delegate.extractTexts(new ArrayList<>(misses));
        fresh.forEach(this::store);

        if (!hits.isEmpty()) {
            log.info("OCR 캐시 적중 {}장 / 전체 {}장 — 새로 인식 {}장",
                    hits.size(), imageUrls.size(), misses.size());
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String imageUrl : imageUrls) {
            String cached = hits.get(imageUrl);
            result.put(imageUrl, cached != null ? cached : fresh.getOrDefault(imageUrl, ""));
        }
        return result;
    }

    private String lookup(String imageUrl) {
        Entry entry = cache.get(imageUrl);
        if (entry == null) {
            return null;
        }
        if (Duration.between(entry.storedAt(), clock.instant()).compareTo(ttl) >= 0) {
            cache.remove(imageUrl);
            return null;
        }
        return entry.text();
    }

    private void store(String imageUrl, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        cache.put(imageUrl, new Entry(text, clock.instant()));
    }

    private record Entry(String text, Instant storedAt) {
    }
}
