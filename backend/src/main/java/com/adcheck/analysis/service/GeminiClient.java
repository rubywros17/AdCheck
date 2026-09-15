package com.adcheck.analysis.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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

        GenerateContentResponse response = restClient.post()
                .uri(URI.create(ENDPOINT_TEMPLATE.formatted(model, apiKey)))
                .body(request)
                .retrieve()
                .body(GenerateContentResponse.class);

        return extractText(response);
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
