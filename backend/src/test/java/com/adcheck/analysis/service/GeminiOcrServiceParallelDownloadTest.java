package com.adcheck.analysis.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이미지 다운로드가 실제로 동시에 일어나는지, 그러면서도 결과 순서가 보존되는지 검증한다.
 * 상세페이지는 이미지가 수십 장이라 한 장씩 받으면 다운로드만으로 수십 초가 걸리는데, 이건
 * Gemini 호출이 아니라 순수 HTTP라 API 할당량과 무관하게 병렬화할 수 있다.
 *
 * <p>테스트용 로컬 HTTP 서버가 이미지마다 {@value #DELAY_MS}ms를 쉬었다 응답하므로,
 * 순차 처리였다면 최소 (이미지 수 × 지연)이 걸린다 — 그보다 훨씬 빨리 끝나면 병렬이라는 뜻이다.
 */
class GeminiOcrServiceParallelDownloadTest {

    private static final int DELAY_MS = 300;
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G'};

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // 이미지마다 내용을 다르게 준다 — 어떤 이미지가 어떤 텍스트로 매핑됐는지 검증하려면
        // 이미지가 서로 구별돼야 한다(전부 같은 바이트면 순서가 틀려도 테스트가 통과해버린다).
        server.createContext("/ok", exchange -> {
            sleep(DELAY_MS);
            byte[] body = imageBytesFor(markerOf(exchange.getRequestURI().getPath()));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/huge", exchange -> {
            byte[] huge = new byte[5 * 1024 * 1024]; // OCR 제외 상한(4MB)을 넘는 이미지
            exchange.sendResponseHeaders(200, huge.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(huge);
            }
        });
        server.createContext("/tiny", exchange -> {
            byte[] tiny = PNG_BYTES; // OCR 제외 하한(10KB) 미만 — 로고·아이콘 크기
            exchange.sendResponseHeaders(200, tiny.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(tiny);
            }
        });
        // 요청마다 스레드를 새로 쓰도록 — 기본(null) executor는 단일 스레드라 병렬성이 안 드러난다.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 이미지를_동시에_내려받고_결과_순서는_입력_순서를_지킨다() {
        List<String> urls = List.of(
                baseUrl + "/ok/1.png", baseUrl + "/ok/2.png", baseUrl + "/ok/3.png",
                baseUrl + "/ok/4.png", baseUrl + "/ok/5.png", baseUrl + "/ok/6.png");
        GeminiOcrService service = new GeminiOcrService(new CountingGeminiClient());

        long startedAt = System.currentTimeMillis();
        Map<String, String> result = service.extractTexts(urls);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.keySet()).containsExactlyElementsOf(urls);
        assertThat(result.values()).containsExactly("text-1", "text-2", "text-3", "text-4", "text-5", "text-6");
        // 순차였다면 6 × 300ms = 1,800ms 이상. 병렬이면 한 묶음(300ms) 수준이라 여유를 둬도 충분히 빠르다.
        assertThat(elapsed).isLessThan(1_200);
    }

    @Test
    void 이미지가_많으면_청크로_나눠_호출하고_결과_순서는_유지된다() {
        // OCR_CHUNK_SIZE=10 기준으로 25장이면 10+10+5 세 청크가 된다.
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            urls.add(baseUrl + "/ok/" + i + ".png");
        }
        CountingGeminiClient geminiClient = new CountingGeminiClient();
        GeminiOcrService service = new GeminiOcrService(geminiClient);

        Map<String, String> result = service.extractTexts(urls);

        assertThat(geminiClient.callCount.get()).isEqualTo(3);
        assertThat(geminiClient.imageCounts).containsExactlyInAnyOrder(10, 10, 5);
        assertThat(result.keySet()).containsExactlyElementsOf(urls);
        // 청크를 나눠 동시에 보내도 이미지-텍스트 짝은 입력 순서 그대로여야 한다.
        assertThat(result.values()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 25).mapToObj(i -> "text-" + i).toList());
    }

    @Test
    void 지나치게_큰_이미지는_OCR에서_제외된다() {
        List<String> urls = List.of(
                baseUrl + "/ok/1.png", baseUrl + "/huge/2.png", baseUrl + "/ok/3.png");
        CountingGeminiClient geminiClient = new CountingGeminiClient();
        GeminiOcrService service = new GeminiOcrService(geminiClient);

        Map<String, String> result = service.extractTexts(urls);

        // 4MB를 넘는 2번 이미지는 Gemini로 보내지 않는다 — 실제 상품페이지의 9MB 애니메이션 GIF가
        // OCR 시간을 19초까지 끌어올린 사례에 대한 방어.
        assertThat(geminiClient.imageCounts).containsExactly(2);
        assertThat(result.get(urls.get(1))).isEmpty();
        assertThat(result.get(urls.get(0))).isEqualTo("text-1");
        assertThat(result.get(urls.get(2))).isEqualTo("text-3");
    }

    @Test
    void 이_크기_미만인_이미지는_OCR에서_제외된다() {
        List<String> urls = List.of(
                baseUrl + "/ok/1.png", baseUrl + "/tiny/2.png", baseUrl + "/ok/3.png");
        CountingGeminiClient geminiClient = new CountingGeminiClient();
        GeminiOcrService service = new GeminiOcrService(geminiClient);

        Map<String, String> result = service.extractTexts(urls);

        // 10KB 미만인 2번 이미지는 로고·아이콘으로 보고 Gemini로 보내지 않는다 — 실제
        // 상품페이지에서 이런 이미지 13장의 OCR 결과 합계가 42자(10장은 0자)였던 것에 대한 방어.
        assertThat(geminiClient.imageCounts).containsExactly(2);
        assertThat(result.get(urls.get(1))).isEmpty();
        assertThat(result.get(urls.get(0))).isEqualTo("text-1");
        assertThat(result.get(urls.get(2))).isEqualTo("text-3");
    }

    @Test
    void 다운로드에_실패한_이미지는_빈_문자열로_남고_나머지는_정상_처리된다() {
        List<String> urls = List.of(
                baseUrl + "/ok/1.png", baseUrl + "/missing/2.png", baseUrl + "/ok/3.png");
        GeminiOcrService service = new GeminiOcrService(new CountingGeminiClient());

        Map<String, String> result = service.extractTexts(urls);

        assertThat(result.keySet()).containsExactlyElementsOf(urls);
        assertThat(result.get(urls.get(0))).isEqualTo("text-1");
        assertThat(result.get(urls.get(1))).isEmpty();
        assertThat(result.get(urls.get(2))).isEqualTo("text-3");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** "/ok/7.png" → "7" */
    private static String markerOf(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return fileName.substring(0, fileName.indexOf('.'));
    }

    /**
     * PNG 시그니처 뒤에 구분자를 붙여 이미지마다 내용을 다르게 만들고, OCR 제외 하한(10KB)을
     * 넘도록 패딩한다 — 안 그러면 이 헬퍼로 만든 모든 테스트 이미지가 하한 필터에 걸린다.
     */
    private static byte[] imageBytesFor(String marker) {
        byte[] markerBytes = marker.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int padded = Math.max(PNG_BYTES.length + markerBytes.length, 10 * 1024 + 1);
        byte[] body = new byte[padded];
        System.arraycopy(PNG_BYTES, 0, body, 0, PNG_BYTES.length);
        System.arraycopy(markerBytes, 0, body, PNG_BYTES.length, markerBytes.length);
        return body;
    }

    /** 대역이 받은 이미지에서 구분자를 되읽어 "text-<구분자>"를 만든다. */
    private static String textFor(GeminiClient.ImageInput image) {
        byte[] decoded = java.util.Base64.getDecoder().decode(image.base64Data());
        // imageBytesFor()가 마커 뒤를 0으로 패딩하므로(하한 필터 통과용), 첫 0바이트 앞까지만
        // 마커로 읽는다 — 안 그러면 패딩 전체가 마커에 섞여 들어간다.
        int end = PNG_BYTES.length;
        while (end < decoded.length && decoded[end] != 0) {
            end++;
        }
        String marker = new String(decoded, PNG_BYTES.length, end - PNG_BYTES.length,
                java.nio.charset.StandardCharsets.US_ASCII);
        return "text-" + marker;
    }

    /**
     * 호출 횟수와 청크별 이미지 수를 기록하면서, 전체에 걸쳐 연속된 "text-i"를 돌려주는 대역.
     * 청크가 동시에 실행돼도 번호가 겹치지 않도록 전역 카운터로 부여한다.
     */
    private static final class CountingGeminiClient extends GeminiClient {
        final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<Integer> imageCounts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        CountingGeminiClient() {
            super("test-api-key", "test-model");
        }

        @Override
        String generate(String prompt, List<ImageInput> images, boolean jsonMode) {
            callCount.incrementAndGet();
            imageCounts.add(images.size());
            // 받은 이미지 각각의 구분자를 그대로 돌려준다 — 청크가 어떤 순서로 실행되든
            // 이미지와 텍스트의 짝이 맞는지 검증할 수 있다.
            return images.stream()
                    .map(image -> '"' + textFor(image) + '"')
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
    }
}
