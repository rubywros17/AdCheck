package com.adcheck.analysis.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagRetrievalService를 실제 임베딩 리소스(rag/reference_sources_v0.1_embeddings.json,
 * PDF-BASE 111쪽)로 검증하는 표본 테스트. MSM 관련 과장 표현을 질의해서 MSM 근거 문단이
 * 상위로 검색되는지 확인한다. GEMINI_API_KEY 없으면 스킵.
 */
class RagRetrievalServiceSampleTest {

    private static final String CLAIM = "이 제품은 관절염을 완전히 치료합니다";

    @Test
    void 범위_제한_없이_질의하면_전체_청크에서_MSM_근거_문단을_찾는다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiEmbeddingClient embeddingClient = new GeminiEmbeddingClient(apiKey, "gemini-embedding-001");
        RagRetrievalService ragService = new RagRetrievalService(embeddingClient);

        var results = ragService.search(CLAIM, List.of(), 3);

        System.out.println("=== RAG 검색 결과 (범위 제한 없음, " + results.size() + "건) ===");
        for (Evidence evidence : results) {
            System.out.println("- [" + evidence.sourceId() + "] " + evidence.text().substring(0, Math.min(80, evidence.text().length())));
        }
    }

    @Test
    void referenceSourceIds로_범위를_좁히면_그_안에서만_검색한다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiEmbeddingClient embeddingClient = new GeminiEmbeddingClient(apiKey, "gemini-embedding-001");
        RagRetrievalService ragService = new RagRetrievalService(embeddingClient);

        var results = ragService.search(CLAIM, List.of("REVIEW-03"), 5);

        System.out.println("=== RAG 검색 결과 (REVIEW-03로 범위 제한, " + results.size() + "건) ===");
        for (Evidence evidence : results) {
            System.out.println("- [" + evidence.sourceId() + "] " + evidence.text().substring(0, Math.min(80, evidence.text().length())));
            assertTrue(evidence.sourceId().equals("REVIEW-03"), "범위를 REVIEW-03로 제한했는데 다른 sourceId가 나옴: " + evidence.sourceId());
        }

        var emptyScope = ragService.search(CLAIM, List.of("FUNC-01"), 3);
        System.out.println("=== RAG 검색 결과 (아직 임베딩 안 된 FUNC-01로 제한, " + emptyScope.size() + "건 — 0건이어야 정상) ===");
    }
}
