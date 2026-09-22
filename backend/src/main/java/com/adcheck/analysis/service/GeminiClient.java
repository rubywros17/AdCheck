package com.adcheck.analysis.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini generateContent 호출을 감싸는 공용 클라이언트. OCR 역할(이미지+프롬프트)과
 * Claim 추출 역할(텍스트+JSON 강제 응답) 양쪽에서 공통으로 사용한다.
 */
@Component
class GeminiClient {

    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    /**
     * 타임아웃을 안 걸어두면 Gemini 쪽 응답이 안 올 때 요청 스레드가 영원히 블로킹된다 —
     * 실제 API 서버(AnalysisPipelineService의 @Async 스레드)에서 이 문제가 실제로 재현돼서
     * (jstack으로 CompletableFuture.get()에 무한 대기 중인 걸 확인) 추가한 안전장치.
     */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** 429/5xx 재시도 정책 — 무료 티어 분당 한도(15회)는 최대 1분이면 리셋되므로 3회면 충분하다. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GeminiClient.class);
    /** 파이프라인 1건이 Gemini를 실제로 몇 번 부르는지/각 호출이 몇 초 걸리는지 실측용 카운터. */
    private static final java.util.concurrent.atomic.AtomicInteger CALL_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 429를 맞은 횟수(재시도 1회당 1). 무료 티어 분당 한도에 얼마나 붙어 있는지 보는 지표. */
    private static final java.util.concurrent.atomic.AtomicInteger RATE_LIMITED_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 앱 기동 후 누적 호출 수. 분석 1건이 몇 회를 썼는지는 분석 시작·종료 시점의 값을 빼서 구한다
     * ({@code AnalysisBackgroundJob} 참고) — 분석이 동시에 돌면 서로 섞이지만, 무료 티어에서는
     * 사실상 한 번에 하나만 도는 전제라 예산을 보는 용도로는 충분하다.
     */
    public static int totalCalls() {
        return CALL_COUNTER.get();
    }

    /** 앱 기동 후 누적 429 횟수. */
    public static int totalRateLimited() {
        return RATE_LIMITED_COUNTER.get();
    }

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    GeminiClient(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.model}") String model
    ) {
        this.restClient = RestClient.builder().requestFactory(timeoutRequestFactory()).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return factory;
    }

    /** 텍스트 프롬프트만으로 호출. */
    String generate(String prompt, boolean jsonMode) {
        return generate(prompt, null, null, jsonMode);
    }

    /** 텍스트 프롬프트 + 이미지(base64) 1장으로 호출. */
    String generate(String prompt, String imageMimeType, String imageBase64Data, boolean jsonMode) {
        List<ImageInput> images = (imageMimeType != null && imageBase64Data != null)
                ? List.of(new ImageInput(imageMimeType, imageBase64Data))
                : List.of();
        return generate(prompt, images, jsonMode);
    }

    /** 텍스트 프롬프트 + 이미지(base64) 여러 장을 한 요청에 담아 호출. */
    String generate(String prompt, List<ImageInput> images, boolean jsonMode) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(prompt, null));
        for (ImageInput image : images) {
            parts.add(new Part(null, new InlineData(image.mimeType(), image.base64Data())));
        }

        GenerationConfig config = jsonMode ? new GenerationConfig("application/json") : null;
        GenerateContentRequest request = new GenerateContentRequest(List.of(new Content(parts)), config);

        int callNo = CALL_COUNTER.incrementAndGet();
        long startedAt = System.currentTimeMillis();
        GenerateContentResponse response = postWithRetry(request, callNo);
        log.info("[TIMING] Gemini 호출 #{} 완료 — {}ms (프롬프트 {}자, 이미지 {}장)",
                callNo, System.currentTimeMillis() - startedAt, prompt.length(), images.size());

        return extractText(response);
    }

    /**
     * 429(분당 한도 초과)와 5xx(일시 장애)는 재시도한다. 재시도가 없으면 호출 하나가 실패할 때
     * {@code FindingAssembler}의 Claim 단위 catch가 <b>그 Claim을 통째로 버리고</b> 분석은
     * COMPLETED로 끝나서, 사용자는 "검사했는데 문제 없음"과 "검사하다 실패함"을 구분할 수 없다
     * (실측에서 Claim 6건 중 3건이 이렇게 조용히 유실되는 것을 확인했다). 응답 본문의
     * {@code retryDelay}를 우선 사용하고, 없으면 지수 백오프로 물러난다.
     */
    private GenerateContentResponse postWithRetry(GenerateContentRequest request, int callNo) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri(URI.create(ENDPOINT_TEMPLATE.formatted(model, apiKey)))
                        .body(request)
                        .retrieve()
                        .body(GenerateContentResponse.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                RATE_LIMITED_COUNTER.incrementAndGet();
                String body = e.getResponseBodyAsString();
                if (isDailyQuotaExceeded(body)) {
                    // 일일 한도는 응답의 retryDelay(분당 한도용 안내)가 찍혀 있어도 그 시간을
                    // 기다린 뒤 재시도해도 똑같이 막힌다 — 하루가 지나야 풀린다. 재시도로 시간만
                    // 낭비하지 말고 바로 실패시켜서, 원인이 분당 한도인지 일일 한도인지 호출부가
                    // 구분할 수 있게 한다(실측: 25개 규칙 회귀 도중 이 둘을 못 구분해 3회 재시도
                    // 대기 후에야 실패, 에러 메시지도 "429"로만 남아 원인 파악에 시간이 걸렸다).
                    log.error("Gemini 호출 #{} 일일 한도 초과 — 재시도하지 않고 즉시 실패 처리", callNo);
                    throw new GeminiDailyQuotaExceededException(model, e);
                }
                last = e;
                long waitMs = retryDelayMillis(body, attempt);
                log.warn("Gemini 호출 #{} 429(분당 한도 초과) — {}ms 후 재시도 ({}/{})",
                        callNo, waitMs, attempt, MAX_ATTEMPTS);
                sleep(waitMs);
            } catch (HttpServerErrorException e) {
                last = e;
                long waitMs = backoffMillis(attempt);
                log.warn("Gemini 호출 #{} 서버 오류({}) — {}ms 후 재시도 ({}/{})",
                        callNo, e.getStatusCode(), waitMs, attempt, MAX_ATTEMPTS);
                sleep(waitMs);
            }
        }
        throw last;
    }

    /**
     * 429 응답 본문의 {@code quotaId}가 일일 한도인지 확인한다. Gemini 무료 티어 quotaId는
     * {@code GenerateRequestsPerMinutePerProjectPerModel-FreeTier}(분당)와
     * {@code GenerateRequestsPerDayPerProjectPerModel-FreeTier}(일일)처럼 "PerMinute"/"PerDay"로
     * 구분되므로, "PerDay" 포함 여부만 봐도 안전하게 갈린다.
     */
    /* package-private for unit test */ static boolean isDailyQuotaExceeded(String responseBody) {
        return responseBody != null && responseBody.contains("PerDay");
    }

    /** 응답 본문의 {@code "retryDelay": "6s"}를 우선 쓰고, 파싱 실패 시 지수 백오프로 물러난다. */
    /* package-private for unit test */ static long retryDelayMillis(String responseBody, int attempt) {
        if (responseBody != null) {
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(responseBody);
            if (matcher.find()) {
                try {
                    // 서버가 알려준 값보다 살짝 더 기다려야 경계에서 또 걸리지 않는다.
                    return Math.round(Double.parseDouble(matcher.group(1)) * 1000) + 500;
                } catch (NumberFormatException ignored) {
                    // 아래 지수 백오프로 폴백
                }
            }
        }
        return backoffMillis(attempt);
    }

    private static long backoffMillis(int attempt) {
        return Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << (attempt - 1)));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini 호출 재시도 대기 중 인터럽트됨", e);
        }
    }

    /** 여러 이미지를 한 요청에 담을 때 이미지 하나를 표현. */
    record ImageInput(String mimeType, String base64Data) {
    }

    private String extractText(GenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return "";
        }
        Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return "";
        }
        String text = content.parts().get(0).text();
        return text != null ? text.trim() : "";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GenerateContentRequest(List<Content> contents, GenerationConfig generationConfig) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GenerationConfig(@JsonProperty("responseMimeType") String responseMimeType) {
    }

    // Gemini 응답은 계속 필드가 추가되므로(예: thoughtSignature, finishReason 등)
    // 모르는 필드가 있어도 깨지지 않도록 ignoreUnknown을 켜둔다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Content(List<Part> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Part(String text, @JsonProperty("inline_data") InlineData inlineData) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record InlineData(@JsonProperty("mime_type") String mimeType, String data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerateContentResponse(List<Candidate> candidates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Candidate(Content content) {
    }
}
