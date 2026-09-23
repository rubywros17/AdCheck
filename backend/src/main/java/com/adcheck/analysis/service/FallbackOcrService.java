package com.adcheck.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 파이프라인이 실제로 쓰는 OCR 진입점. Google Cloud Vision을 먼저 시도하고, 실패한 이미지만
 * Gemini로 다시 처리한다.
 *
 * <p><b>왜 Vision이 먼저인가</b>: 실제 상품페이지 147장 실측(2026-09-22)에서 Vision이 추출량
 * 96%를 유지하면서 훨씬 빨랐다(장당 0.69초 vs 2.9~5.1초). Claim/Finding까지 비교했을 때의 차이는
 * 파이프라인 자체의 실행 간 편차 범위 안이었다 — 같은 텍스트를 두 번 넣어도 Finding이 5건→4건으로
 * 바뀌는 수준이라, 두 엔진 사이의 차이가 그보다 크지 않았다.
 *
 * <p><b>왜 Gemini를 남기는가</b>: Vision은 API 키가 따로 필요하다. 키가 없는 환경(예: 저장소를
 * 그대로 받아 실행하는 팀원)에서도 기존과 똑같이 동작해야 하므로, 키가 없거나 호출이 실패하면
 * 자동으로 Gemini가 받아준다. 덕분에 새 설정 없이도 분석이 그대로 돌아간다.
 *
 * <p><b>무엇을 실패로 보는가</b>: 호출 오류와 키 없음만 실패로 본다. "글자가 없어서 빈 결과"는
 * 정상 응답이라 폴백하지 않는다 — 147장 중 11장이 양쪽 엔진 모두 빈 결과였던 실제로 글자가 없는
 * 이미지였고, 이런 것까지 폴백하면 Gemini 호출만 헛되이 늘어난다.
 */
@Service
@Primary
public class FallbackOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(FallbackOcrService.class);

    private final GoogleVisionOcrService visionOcrService;
    private final GeminiOcrService geminiOcrService;
    private final String provider;

    public FallbackOcrService(
            GoogleVisionOcrService visionOcrService,
            GeminiOcrService geminiOcrService,
            @Value("${adcheck.ocr.provider:auto}") String provider
    ) {
        this.visionOcrService = visionOcrService;
        this.geminiOcrService = geminiOcrService;
        this.provider = provider == null ? "auto" : provider.trim().toLowerCase();
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
        if ("gemini".equals(provider)) {
            return geminiOcrService.extractTexts(imageUrls);
        }
        if (!visionOcrService.isAvailable() && !"google-vision".equals(provider)) {
            log.info("Vision API 키가 없어 Gemini OCR로 진행합니다 (이미지 {}장)", imageUrls.size());
            return geminiOcrService.extractTexts(imageUrls);
        }

        Map<String, Optional<String>> visionResults = visionOcrService.extractTextsDetailed(imageUrls);
        List<String> failed = new ArrayList<>();
        visionResults.forEach((url, text) -> {
            if (text.isEmpty()) {
                failed.add(url);
            }
        });

        Map<String, String> fallbackResults = Map.of();
        if (!failed.isEmpty()) {
            if ("google-vision".equals(provider)) {
                log.warn("Vision OCR이 {}장 실패했지만 provider=google-vision이라 폴백하지 않습니다", failed.size());
            } else {
                log.warn("Vision OCR이 {}장 실패해 Gemini로 폴백합니다 (전체 {}장)", failed.size(), imageUrls.size());
                fallbackResults = geminiOcrService.extractTexts(failed);
            }
        }
        Map<String, String> geminiResults = fallbackResults;

        Map<String, String> result = new LinkedHashMap<>();
        for (String imageUrl : imageUrls) {
            Optional<String> vision = visionResults.getOrDefault(imageUrl, Optional.empty());
            result.put(imageUrl, vision.orElseGet(() -> geminiResults.getOrDefault(imageUrl, "")));
        }
        return result;
    }
}
