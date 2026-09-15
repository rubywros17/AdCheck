package com.adcheck.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini(gemini-3.5-flash-lite)를 OCR 역할로 사용 — 이미지 URL을 받아 그 안에 있는
 * 텍스트를 그대로 추출한다. 전용 OCR 엔진(CLOVA OCR 등)이 아니라 범용 비전 모델이라
 * 정밀도는 다를 수 있지만, 우선 이 역할로 연결해서 파이프라인을 검증한다.
 *
 * <p>이미지 여러 장은 각각 따로 호출하지 않고 한 번의 Gemini 요청에 다 담아서 보낸다
 * (Gemini 무료 티어의 하루 요청 수 제한 때문 — 이미지 개수만큼 호출이 나가면 상품 하나
 * 분석에도 할당량을 금방 다 쓴다). 요청 크기 제한보다 요청 "횟수" 제한이 실제 병목이라
 * 청크로 나누지 않고 상품 하나당 이미지 전체를 한 번에 보낸다.
 */
@Service
public class GeminiOcrService {

    private static final Logger log = LoggerFactory.getLogger(GeminiOcrService.class);

    private final GeminiClient geminiClient;
    private final RestClient downloadClient;
    private final ObjectMapper objectMapper;

    public GeminiOcrService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
        this.downloadClient = RestClient.builder().requestFactory(GeminiClient.timeoutRequestFactory()).build();
        this.objectMapper = new ObjectMapper();
    }

    /** 이미지 URL 안의 텍스트를 추출. 다운로드/호출이 실패하면 빈 문자열을 반환한다(다른 이미지 처리를 막지 않기 위함). */
    public String extractText(String imageUrl) {
        return extractTexts(List.of(imageUrl)).getOrDefault(imageUrl, "");
    }

    /**
     * 이미지 URL 목록 전체를 Gemini 호출 한 번으로 처리한다.
     * 반환값은 입력 순서를 보존하는 URL -&gt; 추출 텍스트 맵(빈 문자열 포함, 필터링은 호출자 몫).
     * 다운로드 실패한 URL은 Gemini 요청에서 제외하되 결과 맵에는 빈 문자열로 남긴다.
     */
    public Map<String, String> extractTexts(List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return Map.of();
        }

        List<String> sentUrls = new ArrayList<>();
        List<GeminiClient.ImageInput> images = new ArrayList<>();
        for (String imageUrl : imageUrls) {
            byte[] imageBytes = download(imageUrl);
            if (imageBytes == null || imageBytes.length == 0) {
                continue;
            }
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            sentUrls.add(imageUrl);
            images.add(new GeminiClient.ImageInput(guessMimeType(imageUrl), base64));
        }

        List<String> texts = sentUrls.isEmpty() ? List.of() : callGemini(sentUrls.size(), images);

        Map<String, String> result = new LinkedHashMap<>();
        for (String imageUrl : imageUrls) {
            int index = sentUrls.indexOf(imageUrl);
            result.put(imageUrl, (index >= 0 && index < texts.size()) ? texts.get(index) : "");
        }
        return result;
    }

    private byte[] download(String imageUrl) {
        try {
            return downloadClient.get().uri(imageUrl).retrieve().body(byte[].class);
        } catch (RestClientException e) {
            log.warn("이미지 다운로드 실패 (imageUrl={}): {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private List<String> callGemini(int imageCount, List<GeminiClient.ImageInput> images) {
        try {
            String rawResponse = geminiClient.generate(buildBatchPrompt(imageCount), images, true);
            List<String> texts = objectMapper.readValue(rawResponse, new TypeReference<List<String>>() {
            });
            if (texts.size() != imageCount) {
                log.warn("Gemini OCR 배치 응답 개수 불일치 (기대={}, 실제={})", imageCount, texts.size());
                return Collections.nCopies(imageCount, "");
            }
            return texts;
        } catch (RestClientException e) {
            log.warn("Gemini OCR 배치 호출 실패 (이미지 {}장): {}", imageCount, e.getMessage());
            return Collections.nCopies(imageCount, "");
        } catch (JacksonException e) {
            log.warn("Gemini OCR 배치 응답 JSON 파싱 실패: {}", e.getMessage());
            return Collections.nCopies(imageCount, "");
        }
    }

    private String buildBatchPrompt(int imageCount) {
        return """
                다음은 %d개의 이미지입니다. 각 이미지 안에 있는 모든 텍스트를 있는 그대로 추출해줘.
                텍스트가 없는 이미지는 빈 문자열("")로 표시해.
                반드시 JSON 배열로만 응답하고, 배열 길이는 정확히 %d여야 하며
                N번째 원소는 N번째로 제시된 이미지의 결과여야 해. 설명은 붙이지 마.
                """.formatted(imageCount, imageCount);
    }

    private String guessMimeType(String imageUrl) {
        String path = URI.create(imageUrl).getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".gif")) {
            return "image/gif";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
