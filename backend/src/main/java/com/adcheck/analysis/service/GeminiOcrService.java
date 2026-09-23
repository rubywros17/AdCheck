package com.adcheck.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
public class GeminiOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(GeminiOcrService.class);

    /**
     * OCR 한 호출에 담을 이미지 수. 요청 하나가 너무 커지지 않게 나누고, 청크끼리는 동시에
     * 보낸다.
     *
     * <p>20으로 올려서 실측했다가(2026-09-22) 10으로 되돌렸다 — 동시성 상한(3)은 그대로인데
     * 청크를 키우면 청크 개수가 줄어 병렬 레인을 덜 쓰게 되고, 청크당 처리시간은 이미지 수에
     * 거의 비례해서 늘어나 "묶어서 고정비용을 아낀다"는 기대가 틀렸다. 같은 이미지 25장(23장
     * 성공) 기준 직접 비교: 청크 10(3콜, 동시 3병렬) OCR 9.6초 vs 청크 20(2콜, 동시 2병렬)
     * OCR 12.3초 — 오히려 느려졌다. 호출 수를 줄이는 방향 자체가 틀린 게 아니라, 동시성
     * 상한을 그대로 둔 채로는 청크를 키워도 이득이 없다는 뜻이다.
     */
    private static final int OCR_CHUNK_SIZE = 10;

    /** OCR 청크 동시 호출 상한 — 무료 티어 분당 한도(15회)를 한꺼번에 소진하지 않도록 제한한다. */
    private static final int OCR_CHUNK_CONCURRENCY = 3;

    /**
     * 이 크기를 넘는 이미지는 OCR에서 제외한다. 실제 상품페이지에서 9MB·8.4MB짜리 애니메이션
     * GIF 2장이 전체 용량(21.6MB)의 80%를 차지하면서 <b>OCR 호출을 19초까지</b> 끌어올린 사례가
     * 근거다 — 이런 초대형 이미지는 대개 움짤·배너라 글자 정보 가치는 낮은데 비용만 압도적이다.
     * Vision은 같은 부담이 없어 상한이 다르다({@link GoogleVisionOcrService} 참고).
     */
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /**
     * OCR로 보낼 때 맞출 가로 폭 상한. 비전 모델의 타일 격자(약 768px)에 맞춘 값이라, 이보다
     * 넓은 이미지는 가로로 타일이 2칸 이상 잡혀 토큰이 배로 든다({@link #downscaleForOcr} 참고).
     */
    private static final int OCR_MAX_WIDTH = 768;

    private final GeminiClient geminiClient;
    private final OcrImageLoader imageLoader;
    private final ObjectMapper objectMapper;

    public GeminiOcrService(GeminiClient geminiClient, OcrImageLoader imageLoader) {
        this.geminiClient = geminiClient;
        this.imageLoader = imageLoader;
        this.objectMapper = new ObjectMapper();
    }

    /** 이미지 URL 안의 텍스트를 추출. 다운로드/호출이 실패하면 빈 문자열을 반환한다(다른 이미지 처리를 막지 않기 위함). */
    @Override
    public String extractText(String imageUrl) {
        return extractTexts(List.of(imageUrl)).getOrDefault(imageUrl, "");
    }

    /**
     * 이미지 URL 목록 전체를 Gemini 호출 한 번으로 처리한다.
     * 반환값은 입력 순서를 보존하는 URL -&gt; 추출 텍스트 맵(빈 문자열 포함, 필터링은 호출자 몫).
     * 다운로드 실패한 URL은 Gemini 요청에서 제외하되 결과 맵에는 빈 문자열로 남긴다.
     */
    @Override
    public Map<String, String> extractTexts(List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return Map.of();
        }

        long downloadStartedAt = System.currentTimeMillis();
        List<OcrImageLoader.LoadedImage> downloaded = imageLoader.loadAll(imageUrls, MAX_IMAGE_BYTES);

        List<String> sentUrls = new ArrayList<>();
        List<GeminiClient.ImageInput> images = new ArrayList<>();
        for (int i = 0; i < imageUrls.size(); i++) {
            OcrImageLoader.LoadedImage loaded = downloaded.get(i);
            if (loaded != null) {
                sentUrls.add(imageUrls.get(i));
                images.add(new GeminiClient.ImageInput(
                        loaded.mimeType(), Base64.getEncoder().encodeToString(loaded.bytes())));
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
     * 이미지를 가로 {@link #OCR_MAX_WIDTH}px로 줄이고 JPEG로 다시 인코딩한다.
     *
     * <p><b>파이프라인에서는 쓰지 않는다 — 실측으로 기각된 최적화다(2026-09-22).</b> 비전 모델이
     * 약 768px 격자로 타일을 잡으니 가로를 768px로 맞추면 타일이 절반으로 줄어 OCR이 빨라질
     * 것이라는 가설이었는데, {@code GeminiOcrDownscaleExperimentTest}로 같은 이미지를 두 번씩
     * 비교한 결과: 전송 용량은 2,400KB→424KB(18%)로 확실히 줄었지만 <b>소요 시간은 116%/77%로
     * 실행마다 뒤집혀 이득이 없었고</b>(Gemini 호출 편차 범위 안), <b>추출 글자수는 85%/83%로
     * 두 번 다 일관되게 15~17% 줄었다</b>. Gemini가 서버 쪽에서 어차피 이미지를 정규화해
     * 토큰화하는 것으로 보이며, 그래서 미리 줄여봐야 업로드 바이트만 아끼고 화질 손실만 남는다.
     *
     * <p>메서드를 지우지 않고 남겨두는 이유는 그 실험을 다시 돌려볼 수 있게 하기 위함이다 —
     * 원본이 훨씬 큰 이미지(2000px 이상)만 모인 페이지에서는 결론이 달라질 수 있어서, 같은
     * 실험을 그런 표본으로 다시 해볼 여지를 남긴다.
     *
     * <p>어떤 이유로든(디코딩 실패, 알 수 없는 포맷 등) 변환이 안 되면 원본 바이트를 그대로
     * 돌려준다. 애니메이션 GIF는 {@code ImageIO.read}가 첫 프레임만 읽어오므로 자연히 정지
     * 이미지 한 장으로 바뀐다.
     */
    static byte[] downscaleForOcr(byte[] imageBytes, String imageUrl) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) {
                return imageBytes;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return imageBytes;
            }
            int targetWidth = Math.min(width, OCR_MAX_WIDTH);
            int targetHeight = Math.max(1, (int) Math.round((double) height * targetWidth / width));

            // JPEG는 알파 채널이 없어서, 투명 PNG를 그대로 그리면 배경이 검게 깔려 글자가 묻힌다.
            // 흰 배경을 먼저 칠하고 그 위에 그린다.
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(target, "jpg", out)) {
                return imageBytes;
            }
            byte[] converted = out.toByteArray();
            log.info("OCR 전처리 — {}x{} {}KB → {}x{} {}KB: {}",
                    width, height, imageBytes.length / 1024,
                    targetWidth, targetHeight, converted.length / 1024, imageUrl);
            return converted;
        } catch (IOException | RuntimeException e) {
            log.warn("OCR 전처리 실패, 원본을 그대로 보냅니다 ({}): {}", e.getMessage(), imageUrl);
            return imageBytes;
        }
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

}
