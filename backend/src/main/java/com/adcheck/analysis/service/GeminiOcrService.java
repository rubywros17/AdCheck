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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 이미지 동시 다운로드 상한 — 상대 서버 부담과 스레드 수를 함께 제한한다. */
    private static final int DOWNLOAD_CONCURRENCY = 6;

    /** OCR 한 호출에 담을 이미지 수. 요청 하나가 너무 커지지 않게 나누고, 청크끼리는 동시에 보낸다. */
    private static final int OCR_CHUNK_SIZE = 10;

    /** OCR 청크 동시 호출 상한 — 무료 티어 분당 한도(15회)를 한꺼번에 소진하지 않도록 제한한다. */
    private static final int OCR_CHUNK_CONCURRENCY = 3;

    /** 이 크기를 넘는 이미지는 OCR에서 제외한다(애니메이션 GIF 같은 초대형 배너 방어). */
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /**
     * 이 크기 미만인 이미지는 OCR에서 제외한다 — 사이트 로고·아이콘·버튼류 방어. 실제 상품페이지
     * 2건(아루침·라이락틴, 각 50장)의 실측 기준으로 정했다: 두 사이트 모두 장식용 이미지는 전부
     * 10KB 미만이었고(cafe24 스킨 아이콘·버튼 1~10KB), 실제 상세 이미지는 두 사이트 모두 12KB
     * 이상부터 시작했다(아루침 최소 19.8KB, 라이락틴 최소 12.2KB) — 그 사이 어디를 잡아도
     * 안전하지만, 두 표본의 여유를 함께 보고 10KB로 잡았다. 아루침 실측에서 UI 이미지 13장의
     * OCR 결과 합계가 42자(10장은 0자)였던 것과 대조된다 — 청크 슬롯만 차지하고 얻는 게 없었다.
     */
    private static final int MIN_IMAGE_BYTES = 10 * 1024;

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

        long downloadStartedAt = System.currentTimeMillis();
        List<GeminiClient.ImageInput> downloaded = downloadAll(imageUrls);

        List<String> sentUrls = new ArrayList<>();
        List<GeminiClient.ImageInput> images = new ArrayList<>();
        for (int i = 0; i < imageUrls.size(); i++) {
            GeminiClient.ImageInput image = downloaded.get(i);
            if (image != null) {
                sentUrls.add(imageUrls.get(i));
                images.add(image);
            }
        }
        log.info("[TIMING] 이미지 병렬 다운로드 완료 — {}ms ({}장 요청 중 {}장 성공)",
                System.currentTimeMillis() - downloadStartedAt, imageUrls.size(), sentUrls.size());

        long ocrStartedAt = System.currentTimeMillis();
        List<String> texts = sentUrls.isEmpty() ? List.of() : callGeminiInChunks(images);
        log.info("[TIMING] OCR 완료 — {}ms ({}장, 추출 텍스트 {}자)",
                System.currentTimeMillis() - ocrStartedAt, images.size(),
                texts.stream().mapToInt(String::length).sum());

        Map<String, String> result = new LinkedHashMap<>();
        for (String imageUrl : imageUrls) {
            int index = sentUrls.indexOf(imageUrl);
            result.put(imageUrl, (index >= 0 && index < texts.size()) ? texts.get(index) : "");
        }
        logExtractedTexts(result);
        return result;
    }

    /**
     * OCR 원문을 진단용으로 남긴다 — 이미지에서 뽑은 텍스트가 2만 자인데 Claim은 2건만 추출되는
     * 현상이 실측에서 관찰됐다. 원인(추출 프롬프트가 긴 입력을 놓치는지, OCR 품질이 나쁜지)을
     * 가리려면 중간 산출물인 이 텍스트가 필요하다. 길이가 커서 전문은 DEBUG로만 남긴다.
     */
    private void logExtractedTexts(Map<String, String> result) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String lengths = result.values().stream()
                .map(text -> String.valueOf(text.length()))
                .collect(java.util.stream.Collectors.joining(","));
        log.info("OCR 이미지별 추출 길이(자): [{}]", lengths);
        if (log.isDebugEnabled()) {
            result.forEach((url, text) -> {
                if (!text.isBlank()) {
                    log.debug("OCR 원문 ({}): {}", url, text);
                }
            });
        }
    }

    /**
     * 이미지를 동시에 내려받는다 — 상세페이지는 이미지가 수십 장이라 한 장씩 받으면 다운로드만으로
     * 수십 초가 걸린다(Gemini 호출이 아니라 순수 HTTP라 API 할당량과는 무관하다).
     *
     * <p>동시 실행 수에 상한을 두는 이유는 두 가지다: 상대 서버에 한꺼번에 몰아치지 않기 위함과,
     * 이미지 수만큼 스레드를 만들지 않기 위함이다. 수집은 각 워커가 <b>자기 인덱스에만</b> 쓰는
     * 방식이라(공유 리스트에 add 하지 않음) 순서가 그대로 보존되고 동기화도 필요 없다.
     *
     * @return {@code imageUrls}와 같은 순서·크기의 리스트. 다운로드 실패한 자리는 {@code null}.
     */
    private List<GeminiClient.ImageInput> downloadAll(List<String> imageUrls) {
        int concurrency = Math.min(DOWNLOAD_CONCURRENCY, Math.max(1, imageUrls.size()));
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "ocr-image-download-");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<GeminiClient.ImageInput>> futures = imageUrls.stream()
                    .map(imageUrl -> CompletableFuture.supplyAsync(() -> toImageInput(imageUrl), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private GeminiClient.ImageInput toImageInput(String imageUrl) {
        byte[] imageBytes = download(imageUrl);
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        // 실제 상품페이지에서 9MB·8.4MB짜리 애니메이션 GIF 2장이 전체 용량(21.6MB)의 80%를
        // 차지하면서 OCR 호출을 19초까지 끌어올린 사례가 있었다. 이런 초대형 이미지는 대개
        // 움짤·배너라 글자 정보 가치는 낮은데 비용만 압도적이라 제외한다.
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            log.info("이미지가 너무 커서 OCR에서 제외합니다 ({}KB, 상한 {}KB): {}",
                    imageBytes.length / 1024, MAX_IMAGE_BYTES / 1024, imageUrl);
            return null;
        }
        if (imageBytes.length < MIN_IMAGE_BYTES) {
            log.info("이미지가 너무 작아 OCR에서 제외합니다 (로고·아이콘 추정, {}B, 하한 {}KB): {}",
                    imageBytes.length, MIN_IMAGE_BYTES / 1024, imageUrl);
            return null;
        }
        return new GeminiClient.ImageInput(guessMimeType(imageUrl), Base64.getEncoder().encodeToString(imageBytes));
    }

    /**
     * 이미지를 청크로 나눠 동시에 OCR한다. 한 요청에 전부 담으면 요청이 수십 MB로 커져 호출
     * 하나가 오래 걸리는데(실측 49장 21MB에서 19.2초), 나눠서 동시에 보내면 같은 작업이 한
     * 묶음 시간으로 끝난다. 호출 수는 늘지만 무료 티어 분당 한도(15회) 안에서 감당 가능한 수준이다.
     *
     * <p>청크 하나가 실패해도 그 청크의 이미지만 빈 문자열이 되고 나머지는 살린다 — 예전처럼
     * 전체를 빈 결과로 만들지 않는다.
     *
     * @return {@code images}와 같은 순서·크기의 텍스트 리스트.
     */
    private List<String> callGeminiInChunks(List<GeminiClient.ImageInput> images) {
        List<List<GeminiClient.ImageInput>> chunks = new ArrayList<>();
        for (int start = 0; start < images.size(); start += OCR_CHUNK_SIZE) {
            chunks.add(images.subList(start, Math.min(images.size(), start + OCR_CHUNK_SIZE)));
        }
        if (chunks.size() == 1) {
            return callGemini(chunks.get(0).size(), chunks.get(0));
        }

        int concurrency = Math.min(OCR_CHUNK_CONCURRENCY, chunks.size());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "ocr-chunk-");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<List<String>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> callGemini(chunk.size(), chunk), executor))
                    .toList();
            // 각 청크가 자기 결과만 반환하고, 합치는 건 호출 스레드가 순서대로 한다 — 이미지와
            // 텍스트의 짝이 어긋나지 않도록 순서 보존이 중요하다.
            List<String> merged = new ArrayList<>(images.size());
            futures.forEach(future -> merged.addAll(future.join()));
            return merged;
        } finally {
            executor.shutdown();
        }
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
