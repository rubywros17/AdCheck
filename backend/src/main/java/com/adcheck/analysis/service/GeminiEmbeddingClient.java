package com.adcheck.analysis.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

/**
 * Gemini embedContent 호출을 감싸는 클라이언트 — 텍스트를 임베딩 벡터로 변환한다.
 * {@link GeminiClient}(generateContent, 텍스트 생성용)와는 별도 엔드포인트/모델을 쓴다
 * (예: gemini-embedding-001) — 생성 모델과 임베딩 모델은 같은 이름 규칙을 따르지 않으므로
 * 설정을 분리했다.
 */
@Component
class GeminiEmbeddingClient {

    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:embedContent?key=%s";
    private static final String BATCH_ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:batchEmbedContents?key=%s";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    GeminiEmbeddingClient(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.embedding-model}") String model
    ) {
        this.restClient = RestClient.builder().requestFactory(GeminiClient.timeoutRequestFactory()).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    /** @param taskType "RETRIEVAL_QUERY"(검색어) 또는 "RETRIEVAL_DOCUMENT"(색인 대상 문서) */
    List<Double> embed(String text, String taskType) {
        EmbedContentRequest request = new EmbedContentRequest(
                "models/" + model, new Content(List.of(new Part(text))), taskType);

        EmbedContentResponse response = restClient.post()
                .uri(URI.create(ENDPOINT_TEMPLATE.formatted(model, apiKey)))
                .body(request)
                .retrieve()
                .body(EmbedContentResponse.class);

        return response != null && response.embedding() != null ? response.embedding().values() : List.of();
    }

    /**
     * 여러 텍스트를 한 번의 호출로 임베딩한다(OCR 배치 호출과 같은 방식). 호출 순서를 그대로
     * 보존해서 반환하되, 응답 개수가 요청 개수와 다르면 예외를 던진다 — 밀림(index 오정렬)이
     * 조용히 일어나는 걸 막기 위해 호출 쪽에서 반드시 이 경우를 감지해서 처리하게 한다
     * (예: 개별 호출로 fallback).
     */
    List<List<Double>> embedBatch(List<String> texts, String taskType) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<EmbedContentRequest> requests = texts.stream()
                .map(text -> new EmbedContentRequest("models/" + model, new Content(List.of(new Part(text))), taskType))
                .toList();

        BatchEmbedContentsResponse response = restClient.post()
                .uri(URI.create(BATCH_ENDPOINT_TEMPLATE.formatted(model, apiKey)))
                .body(new BatchEmbedContentsRequest(requests))
                .retrieve()
                .body(BatchEmbedContentsResponse.class);

        List<Embedding> embeddings = response != null && response.embeddings() != null ? response.embeddings() : List.of();
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "배치 임베딩 응답 개수 불일치 (요청 %d, 응답 %d)".formatted(texts.size(), embeddings.size()));
        }
        return embeddings.stream().map(Embedding::values).toList();
    }

    private record EmbedContentRequest(String model, Content content, @JsonProperty("taskType") String taskType) {
    }

    private record BatchEmbedContentsRequest(List<EmbedContentRequest> requests) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BatchEmbedContentsResponse(List<Embedding> embeddings) {
    }

    private record Content(List<Part> parts) {
    }

    private record Part(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedContentResponse(Embedding embedding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Embedding(List<Double> values) {
    }
}
