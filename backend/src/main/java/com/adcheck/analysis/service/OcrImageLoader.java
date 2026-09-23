package com.adcheck.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * OCR 엔진에 보내기 전 단계(다운로드 + 걸러내기)를 담당한다. 어떤 OCR 엔진을 쓰든 똑같이
 * 필요한 부분이라, {@link GeminiOcrService}와 {@link GoogleVisionOcrService}가 공유한다.
 *
 * <p>여기서 거르는 것이 세 가지다 — 공지·배너로 추정되는 URL, 너무 작은 이미지(로고·아이콘),
 * 너무 큰 이미지(초대형 애니메이션 GIF). 각 기준의 근거는 상수 주석에 적어뒀다.
 */
@Component
public class OcrImageLoader {

    private static final Logger log = LoggerFactory.getLogger(OcrImageLoader.class);

    /** 이미지 동시 다운로드 상한 — 상대 서버 부담과 스레드 수를 함께 제한한다. */
    private static final int DOWNLOAD_CONCURRENCY = 6;

    /**
     * 크기 상한은 엔진마다 다르므로 {@link #loadAll(List, int)} 인자로 받는다 — Gemini는 초대형
     * 이미지가 OCR 호출을 19초까지 끌어올린 실측 때문에 4MB로 묶어야 하지만, Vision은 12.3MB
     * GIF도 1.4초에 처리하므로(2026-09-22 실측) 같은 제한을 걸 이유가 없다. 각 상한의 근거는
     * 호출부 상수 주석에 적어뒀다.
     */

    /**
     * 이 크기 미만인 이미지는 제외한다 — 사이트 로고·아이콘·버튼류 방어. 실제 상품페이지
     * 2건(아루침·라이락틴, 각 50장)의 실측 기준으로 정했다: 두 사이트 모두 장식용 이미지는 전부
     * 10KB 미만이었고(cafe24 스킨 아이콘·버튼 1~10KB), 실제 상세 이미지는 두 사이트 모두 12KB
     * 이상부터 시작했다(아루침 최소 19.8KB, 라이락틴 최소 12.2KB).
     */
    private static final int MIN_IMAGE_BYTES = 10 * 1024;

    /**
     * 공지·배송안내·교환환불 배너류로 추정되는 URL은 다운로드조차 하지 않고 제외한다
     * (Extension의 {@code page-extractor.ts} IRRELEVANT_IMAGE_PATTERN과 같은 목적 — 여기는
     * 백엔드 마지막 방어선이라 Extension 필터를 통과해 넘어온 것만 잡는다). 실측에서
     * "notice_05.png" 같은 파일명으로 들어온 사례를 근거로 정했고, 판매자마다 명명 규칙이
     * 달라 이 패턴으로 전부 잡히진 않는다 — 완전한 필터가 아니라 흔한 패턴만 줄이는 용도.
     */
    private static final Pattern IRRELEVANT_IMAGE_URL_PATTERN =
            Pattern.compile("(?:^|[/_\\-.])(notice|banner)(?:[/_\\-.]|$)", Pattern.CASE_INSENSITIVE);

    private final RestClient downloadClient;

    public OcrImageLoader() {
        this.downloadClient = RestClient.builder().requestFactory(GeminiClient.timeoutRequestFactory()).build();
    }

    /** 걸러지지 않고 살아남은 이미지 한 장. */
    public record LoadedImage(String url, byte[] bytes, String mimeType) {
    }

    /**
     * 이미지를 동시에 내려받는다 — 상세페이지는 이미지가 수십 장이라 한 장씩 받으면 다운로드만으로
     * 수십 초가 걸린다(순수 HTTP라 OCR API 할당량과는 무관하다).
     *
     * <p>수집은 각 워커가 <b>자기 인덱스에만</b> 쓰는 방식이라 순서가 그대로 보존되고 동기화도
     * 필요 없다.
     *
     * @param maxImageBytes 이 크기를 넘는 이미지는 제외한다(엔진별로 다르다 — 클래스 주석 참고).
     * @return {@code imageUrls}와 같은 순서·크기의 리스트. 실패하거나 걸러진 자리는 {@code null}.
     */
    public List<LoadedImage> loadAll(List<String> imageUrls, int maxImageBytes) {
        int concurrency = Math.min(DOWNLOAD_CONCURRENCY, Math.max(1, imageUrls.size()));
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "ocr-image-download-");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<LoadedImage>> futures = imageUrls.stream()
                    .map(imageUrl -> CompletableFuture.supplyAsync(() -> load(imageUrl, maxImageBytes), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private LoadedImage load(String imageUrl, int maxImageBytes) {
        if (IRRELEVANT_IMAGE_URL_PATTERN.matcher(imageUrl).find()) {
            log.info("공지·배송안내 배너로 추정되는 URL이라 다운로드 없이 OCR에서 제외합니다: {}", imageUrl);
            return null;
        }
        byte[] imageBytes = download(imageUrl);
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        if (imageBytes.length > maxImageBytes) {
            log.info("이미지가 너무 커서 OCR에서 제외합니다 ({}KB, 상한 {}KB): {}",
                    imageBytes.length / 1024, maxImageBytes / 1024, imageUrl);
            return null;
        }
        if (imageBytes.length < MIN_IMAGE_BYTES) {
            log.info("이미지가 너무 작아 OCR에서 제외합니다 (로고·아이콘 추정, {}B, 하한 {}KB): {}",
                    imageBytes.length, MIN_IMAGE_BYTES / 1024, imageUrl);
            return null;
        }
        return new LoadedImage(imageUrl, imageBytes, guessMimeType(imageUrl));
    }

    private byte[] download(String imageUrl) {
        try {
            return downloadClient.get().uri(URI.create(imageUrl)).retrieve().body(byte[].class);
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("이미지 다운로드 실패: {} ({})", imageUrl, e.getMessage());
            return null;
        }
    }

    private String guessMimeType(String imageUrl) {
        String path;
        try {
            path = URI.create(imageUrl).getPath().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "image/jpeg";
        }
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
