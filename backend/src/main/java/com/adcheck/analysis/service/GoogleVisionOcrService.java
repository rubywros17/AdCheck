package com.adcheck.analysis.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Cloud Vision({@code DOCUMENT_TEXT_DETECTION})으로 OCR을 수행한다.
 *
 * <p><b>왜 Gemini와 구조가 다른가</b>: {@link GeminiOcrService}는 호출 횟수를 아끼려고 이미지
 * 10장을 한 요청에 묶고 동시성을 3으로 묶어뒀다(무료 티어 분당 15회 제약). Vision은 요청
 * 1건 = 이미지 1장이 자연스럽고 할당량도 분당 수천 건이라 그 제약이 없으므로, <b>장당 1호출을
 * 높은 동시성으로</b> 던진다. 프롬프트도, 응답 JSON 배열을 이미지 순서에 맞춰 되짚는 과정도
 * 필요 없다 — 요청과 응답이 1:1이라 순서가 어긋나 엉뚱한 이미지에 텍스트가 붙는 부류의 문제가
 * 구조적으로 생기지 않는다.
 *
 * <p>실측(2026-09-22, 실제 상품페이지 14곳에서 모은 147장): 추출 글자수는 Gemini의 96%였고
 * "한쪽만 빈 결과"가 나온 이미지는 0장이었다(양쪽 다 빈 결과 11장으로 동일). 소요시간은 동시성
 * 8 기준 12.6초로, 같은 이미지를 운영 조건(10장 배치·동시성 3)으로 돌린 Gemini의 약 44초 대비
 * 3.5배가량 빨랐다.
 */
@Service
public class GoogleVisionOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(GoogleVisionOcrService.class);

    private static final String ENDPOINT = "https://vision.googleapis.com/v1/images:annotate?key=%s";

    /**
     * 장당 1호출이므로 이 값이 곧 OCR 병렬도다. Vision 기본 할당량(분당 수천 건) 대비 여유가
     * 크고, 실측에서 8로 장당 0.69초가 나왔다. 더 올릴 수도 있으나 상대 서버·스레드 부담과
     * 균형을 보고 8로 둔다.
     */
    private static final int OCR_CONCURRENCY = 8;

    /**
     * 이 크기를 넘는 이미지는 OCR에서 제외한다. Gemini(4MB)보다 느슨한 이유는 Vision에는 그
     * 제한의 근거였던 "초대형 이미지가 호출을 19초까지 끌어올리는" 문제가 없기 때문이다 —
     * 실측에서 12.3MB GIF를 1.4초에 처리했고(애니메이션 GIF는 첫 프레임만 본다), 과금도 용량과
     * 무관하게 장당 고정이다.
     *
     * <p>그래도 상한을 두는 건 Vision의 <b>JSON 요청 크기 제한 10MB</b> 때문이다. 본문을 base64로
     * 실어 보내면 약 1.33배로 불어나므로, 원본 7MB가 실질 한계다(7MB × 1.33 ≈ 9.3MB).
     *
     * <p>이 상한으로도 12.3MB짜리 애니메이션 GIF는 여전히 걸러진다 — 그런 이미지에서 실제 광고
     * 문구를 놓치는 사례를 확인했고(docs/troubleshooting.md), 첫 프레임만 뽑아 재인코딩하면 수백
     * KB로 줄어 살릴 수 있다. 해상도 사전 검사·PNG 재인코딩이 함께 필요해 별도 과제로 남겨둔다.
     */
    private static final int MAX_IMAGE_BYTES = 7 * 1024 * 1024;

    private final OcrImageLoader imageLoader;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public GoogleVisionOcrService(
            OcrImageLoader imageLoader,
            @Value("${google.vision.api-key:}") String apiKey
    ) {
        this.imageLoader = imageLoader;
        this.restClient = RestClient.builder().requestFactory(GeminiClient.timeoutRequestFactory()).build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
    }

    @Override
    public String extractText(String imageUrl) {
        return extractTexts(List.of(imageUrl)).getOrDefault(imageUrl, "");
    }

    /** API 키가 설정돼 있는지 — {@link FallbackOcrService}가 Vision을 시도할지 판단할 때 쓴다. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Map<String, String> extractTexts(List<String> imageUrls) {
        Map<String, java.util.Optional<String>> detailed = extractTextsDetailed(imageUrls);
        Map<String, String> result = new LinkedHashMap<>();
        detailed.forEach((url, text) -> result.put(url, text.orElse("")));
        return result;
    }

    /**
     * {@link #extractTexts}와 같지만 <b>실패</b>와 <b>글자가 없어서 빈 결과</b>를 구분해서 돌려준다
     * — 값이 비어 있는 {@code Optional}이 실패다.
     *
     * <p>이 구분이 필요한 이유: 실측 147장에서 <b>양쪽 엔진 모두 빈 결과였던 이미지가 11장</b>
     * 있었다(실제로 글자가 없는 이미지). 빈 결과를 실패로 보고 폴백하면 이런 이미지마다 Gemini를
     * 헛되이 부르게 된다. 그래서 폴백은 호출 오류·키 없음 같은 <b>진짜 실패</b>에만 건다.
     */
    public Map<String, java.util.Optional<String>> extractTextsDetailed(List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return Map.of();
        }
        if (!isAvailable()) {
            log.warn("GOOGLE_VISION_API_KEY가 없어 Vision OCR을 건너뜁니다 (이미지 {}장)", imageUrls.size());
            return failedResult(imageUrls);
        }

        long downloadStartedAt = System.currentTimeMillis();
        List<OcrImageLoader.LoadedImage> loaded = imageLoader.loadAll(imageUrls, MAX_IMAGE_BYTES);
        List<OcrImageLoader.LoadedImage> targets = loaded.stream().filter(java.util.Objects::nonNull).toList();
        log.info("[TIMING] 이미지 병렬 다운로드 완료 — {}ms ({}장 요청 중 {}장 성공)",
                System.currentTimeMillis() - downloadStartedAt, imageUrls.size(), targets.size());

        long ocrStartedAt = System.currentTimeMillis();
        Map<String, java.util.Optional<String>> byUrl = targets.isEmpty() ? Map.of() : annotateAll(targets);
        log.info("[TIMING] OCR 완료 — {}ms ({}장, 추출 텍스트 {}자)",
                System.currentTimeMillis() - ocrStartedAt, targets.size(),
                byUrl.values().stream().mapToInt(text -> text.map(String::length).orElse(0)).sum());

        Map<String, java.util.Optional<String>> result = new LinkedHashMap<>();
        for (String imageUrl : imageUrls) {
            // 로더가 걸러낸 이미지(크기 미달·초과, 배너 URL, 다운로드 실패)는 실패로 보지 않는다 —
            // Gemini도 같은 로더를 쓰므로 폴백해봐야 결과가 같다.
            result.put(imageUrl, byUrl.getOrDefault(imageUrl, java.util.Optional.of("")));
        }
        logExtractedTexts(result);
        return result;
    }

    /** 장당 1호출을 동시에 던진다. 한 장이 실패해도 나머지는 그대로 진행한다. */
    private Map<String, java.util.Optional<String>> annotateAll(List<OcrImageLoader.LoadedImage> images) {
        int concurrency = Math.min(OCR_CONCURRENCY, images.size());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "gcv-ocr-");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<java.util.Optional<String>>> futures = images.stream()
                    .map(image -> CompletableFuture.supplyAsync(() -> annotate(image), executor))
                    .toList();
            Map<String, java.util.Optional<String>> result = new LinkedHashMap<>();
            for (int i = 0; i < images.size(); i++) {
                result.put(images.get(i).url(), futures.get(i).join());
            }
            return result;
        } finally {
            executor.shutdown();
        }
    }

    /** @return 인식 성공이면 텍스트(글자가 없으면 빈 문자열), 호출·응답 오류면 {@code Optional.empty()}. */
    private java.util.Optional<String> annotate(OcrImageLoader.LoadedImage image) {
        AnnotateRequest request = new AnnotateRequest(List.of(new ImageRequest(
                new ImageContent(Base64.getEncoder().encodeToString(image.bytes())),
                List.of(new Feature("DOCUMENT_TEXT_DETECTION")),
                new ImageContext(List.of("ko", "en")))));
        try {
            AnnotateResponse response = restClient.post()
                    .uri(URI.create(ENDPOINT.formatted(apiKey)))
                    .body(request)
                    .retrieve()
                    .body(AnnotateResponse.class);
            if (response == null || response.responses() == null || response.responses().isEmpty()) {
                log.warn("Vision OCR 응답이 비어 있습니다: {}", image.url());
                return java.util.Optional.empty();
            }
            ImageResponse first = response.responses().get(0);
            if (first.error() != null) {
                log.warn("Vision OCR 실패({}): {}", first.error().message(), image.url());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                    first.fullTextAnnotation() != null && first.fullTextAnnotation().text() != null
                            ? first.fullTextAnnotation().text().trim()
                            : "");
        } catch (RestClientException e) {
            log.warn("Vision OCR 호출 실패: {} ({})", image.url(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static Map<String, java.util.Optional<String>> failedResult(List<String> imageUrls) {
        Map<String, java.util.Optional<String>> result = new LinkedHashMap<>();
        imageUrls.forEach(url -> result.put(url, java.util.Optional.empty()));
        return result;
    }

    /** {@code GeminiOcrService}와 같은 형식으로 남긴다 — 두 엔진의 로그를 나란히 비교하기 위함. */
    private void logExtractedTexts(Map<String, java.util.Optional<String>> result) {
        if (!log.isInfoEnabled()) {
            return;
        }
        List<String> lengths = new ArrayList<>();
        result.values().forEach(text -> lengths.add(text.map(t -> String.valueOf(t.length())).orElse("실패")));
        log.info("OCR 이미지별 추출 길이(자): [{}]", String.join(",", lengths));
        if (log.isDebugEnabled()) {
            result.forEach((url, text) -> log.debug("OCR 원문 [{}]\n{}", url, text));
        }
    }

    private record AnnotateRequest(List<ImageRequest> requests) {
    }

    private record ImageRequest(ImageContent image, List<Feature> features, ImageContext imageContext) {
    }

    private record ImageContent(String content) {
    }

    private record Feature(String type) {
    }

    private record ImageContext(List<String> languageHints) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnnotateResponse(List<ImageResponse> responses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageResponse(FullTextAnnotation fullTextAnnotation, ApiError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FullTextAnnotation(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ApiError(Integer code, String message) {
    }
}
