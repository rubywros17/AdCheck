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
        server.createContext("/ok", exchange -> {
            sleep(DELAY_MS);
            exchange.sendResponseHeaders(200, PNG_BYTES.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(PNG_BYTES);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
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
        GeminiOcrService service = new GeminiOcrService(new EchoIndexGeminiClient());

        long startedAt = System.currentTimeMillis();
        Map<String, String> result = service.extractTexts(urls);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.keySet()).containsExactlyElementsOf(urls);
        assertThat(result.values()).containsExactly("text-0", "text-1", "text-2", "text-3", "text-4", "text-5");
        // 순차였다면 6 × 300ms = 1,800ms 이상. 병렬이면 한 묶음(300ms) 수준이라 여유를 둬도 충분히 빠르다.
        assertThat(elapsed).isLessThan(1_200);
    }

    @Test
    void 다운로드에_실패한_이미지는_빈_문자열로_남고_나머지는_정상_처리된다() {
        List<String> urls = List.of(
                baseUrl + "/ok/1.png", baseUrl + "/missing/2.png", baseUrl + "/ok/3.png");
        GeminiOcrService service = new GeminiOcrService(new EchoIndexGeminiClient());

        Map<String, String> result = service.extractTexts(urls);

        assertThat(result.keySet()).containsExactlyElementsOf(urls);
        assertThat(result.get(urls.get(0))).isEqualTo("text-0");
        assertThat(result.get(urls.get(1))).isEmpty();
        assertThat(result.get(urls.get(2))).isEqualTo("text-1");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 실제 Gemini 대신, 전달받은 이미지 수만큼 "text-i" 배열을 돌려주는 테스트 대역. */
    private static final class EchoIndexGeminiClient extends GeminiClient {
        EchoIndexGeminiClient() {
            super("test-api-key", "test-model");
        }

        @Override
        String generate(String prompt, List<ImageInput> images, boolean jsonMode) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < images.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append("text-").append(i).append('"');
            }
            return json.append(']').toString();
        }
    }
}
