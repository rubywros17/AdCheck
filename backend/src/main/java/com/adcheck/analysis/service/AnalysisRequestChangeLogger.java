package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.CreateAnalysisRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 같은 URL을 다시 분석할 때 <b>직전 요청과 무엇이 달라졌는지</b>만 로그로 남긴다.
 *
 * <p>왜 필요한가: 재사용 캐시는 URL + 요청 내용 해시로 판정하는데, 해시가 갈리면 같은 페이지를
 * 볼 때마다 새로 분석하고 그만큼 AI 편차가 화면에 드러난다. 그런데 <b>해시가 왜 갈렸는지는
 * 어디에도 남지 않는다</b> — {@code analyses} 테이블에는 {@code content_hash}만 있고 원본
 * texts/images가 없어서, 두 요청을 견줘 볼 방법이 없었다.
 *
 * <p>실제로 2026-09-27에 이것 때문에 시간을 썼다. 임시로 요청 JSON을 파일로 떠서 비교하고 나서야
 * 원인이 네이버페이 결제 위젯 두 줄이라는 걸 알았다 — 하나는 id에 타임스탬프가 박혀 있었고
 * ({@code #NPAY_PROMOTION_IDNC_ID_1790436451926390}), 다른 하나는 위젯 로딩 순서에 따라
 * {@code nth-of-type} 번호가 밀렸다. selector를 해시에서 빼서 그 두 건은 해결했지만, 그 뒤에도
 * 해시가 갈리는 경우가 남아 있어 <b>무엇이 바뀌는지 계속 관찰할 수단</b>이 필요하다.
 *
 * <p>메모리에는 URL당 텍스트 앞부분만 잘라 담고 {@link #MAX_TRACKED_URLS}개까지만 유지한다.
 * 진단이 목적이라 요청 전체를 들고 있을 이유가 없다.
 */
@Component
public class AnalysisRequestChangeLogger {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestChangeLogger.class);

    /** 최근 이만큼의 URL만 추적한다. 넘으면 가장 오래된 것부터 버린다. */
    private static final int MAX_TRACKED_URLS = 20;
    /** 텍스트는 앞부분만 보관한다 — 무엇이 바뀌었는지 알아볼 정도면 충분하다. */
    private static final int TEXT_KEEP_LENGTH = 90;
    /** 한 번에 출력할 변경 항목 수. 페이지 전체가 바뀌면 로그가 의미를 잃으므로 끊는다. */
    private static final int MAX_REPORTED = 8;

    private final Map<String, Snapshot> lastByUrl = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                    return size() > MAX_TRACKED_URLS;
                }
            });

    /**
     * 직전 요청과 비교해 달라진 항목을 남긴다. 같은 URL을 처음 보면 기록만 하고 넘어간다.
     *
     * <p>진단용이라 어떤 실패도 분석을 막지 않는다.
     */
    public void logChangesSince(String normalizedUrl, String contentHash, CreateAnalysisRequest request) {
        try {
            Snapshot current = snapshot(contentHash, request);
            Snapshot previous = lastByUrl.put(normalizedUrl, current);
            if (previous == null || previous.contentHash().equals(current.contentHash())) {
                return;
            }

            Set<String> addedTexts = difference(current.texts(), previous.texts());
            Set<String> removedTexts = difference(previous.texts(), current.texts());
            Set<String> addedImages = difference(current.images(), previous.images());
            Set<String> removedImages = difference(previous.images(), current.images());

            log.info("같은 URL인데 요청 내용이 달라져 재사용하지 못했습니다 — 텍스트 +{}/-{}, 이미지 +{}/-{}",
                    addedTexts.size(), removedTexts.size(), addedImages.size(), removedImages.size());
            report("추가된 텍스트", addedTexts);
            report("사라진 텍스트", removedTexts);
            report("추가된 이미지", addedImages);
            report("사라진 이미지", removedImages);
        } catch (Exception e) {
            log.debug("요청 변경 비교에 실패했습니다: {}", e.toString());
        }
    }

    private void report(String label, Set<String> items) {
        if (items.isEmpty()) {
            return;
        }
        List<String> shown = items.stream().limit(MAX_REPORTED).toList();
        log.info("  {} {}건: {}{}", label, items.size(), shown,
                items.size() > MAX_REPORTED ? " 외 " + (items.size() - MAX_REPORTED) + "건" : "");
    }

    private Snapshot snapshot(String contentHash, CreateAnalysisRequest request) {
        Set<String> texts = new LinkedHashSet<>();
        if (request.texts() != null) {
            request.texts().stream()
                    .filter(text -> text != null && text.content() != null)
                    .forEach(text -> texts.add(trim(text.content())));
        }
        Set<String> images = new LinkedHashSet<>();
        if (request.images() != null) {
            request.images().stream()
                    .filter(image -> image != null && image.url() != null)
                    .forEach(image -> images.add(image.url()));
        }
        return new Snapshot(contentHash, texts, images);
    }

    private Set<String> difference(Set<String> from, Set<String> other) {
        Set<String> result = new LinkedHashSet<>(from);
        result.removeAll(other);
        return result;
    }

    private String trim(String value) {
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= TEXT_KEEP_LENGTH
                ? normalized
                : normalized.substring(0, TEXT_KEEP_LENGTH) + "…";
    }

    private record Snapshot(String contentHash, Set<String> texts, Set<String> images) {
    }
}
