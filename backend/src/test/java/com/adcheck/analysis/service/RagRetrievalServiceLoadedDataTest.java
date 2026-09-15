package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GEMINI_API_KEY 없이도(네트워크 호출 없이) 실제로 이식된
 * {@code rag/reference_sources_v0.1_embeddings.json}의 123개 청크가 정확히 로드되고,
 * 코사인 유사도 랭킹 로직이 그 실제 데이터로 올바르게 동작해 {@link Evidence}를 만들어내는지
 * 검증한다. 쿼리 임베딩은 Gemini API를 호출하지 않고, 로드된 청크 중 하나의 임베딩 벡터를
 * 그대로 질의 벡터로 재사용한다 — 자기 자신과의 코사인 유사도는 항상 1.0이므로, 그 청크가
 * 1위로 반환되는지로 랭킹 로직 자체를 검증할 수 있다.
 *
 * <p>{@link RagRetrievalService}의 {@code chunks} 필드와 {@code rank(...)} 메서드는 의도적으로
 * private이라(최소 공개 API 유지), 리플렉션으로만 접근한다 — 이 클래스는 그 소스를 전혀
 * 수정하지 않는다.
 */
class RagRetrievalServiceLoadedDataTest {

    @Test
    void loadsAllChunksFromThePortedResourceAndRanksRealDataCorrectly() throws Exception {
        RagRetrievalService ragService = new RagRetrievalService(
                new GeminiEmbeddingClient("dummy-key-not-used", "gemini-embedding-001")
        );

        Field chunksField = RagRetrievalService.class.getDeclaredField("chunks");
        chunksField.setAccessible(true);
        List<?> chunks = (List<?>) chunksField.get(ragService);

        // 오늘 이식한 파일 전체가 실제로 로드됐는지 (13개 ReferenceSource, 123개 청크로
        // 사전 조사에서 확인된 값과 일치해야 함).
        assertThat(chunks).hasSize(123);

        Object firstChunk = chunks.getFirst();
        Class<?> ragChunkClass = firstChunk.getClass();
        String sourceId = (String) ragChunkClass.getMethod("sourceId").invoke(firstChunk);
        String text = (String) ragChunkClass.getMethod("text").invoke(firstChunk);
        @SuppressWarnings("unchecked")
        List<Double> ownEmbedding = (List<Double>) ragChunkClass.getMethod("embedding").invoke(firstChunk);

        assertThat(sourceId).isNotBlank();
        assertThat(text).isNotBlank();
        assertThat(ownEmbedding).hasSize(3072);

        Method rank = RagRetrievalService.class.getDeclaredMethod(
                "rank", List.class, List.class, int.class
        );
        rank.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Evidence> topMatch = (List<Evidence>) rank.invoke(ragService, ownEmbedding, null, 1);

        assertThat(topMatch).hasSize(1);
        Evidence evidence = topMatch.getFirst();
        // 자기 자신과의 코사인 유사도이므로 1위는 정확히 그 청크여야 하고 점수는 1.0에 근접해야 한다.
        assertThat(evidence.sourceId()).isEqualTo(sourceId);
        assertThat(evidence.text()).isEqualTo(text);
        assertThat(evidence.score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(evidence.chunkId()).isEqualTo(sourceId + "-p" + evidence.pdfPage());

        System.out.println("=== 실제 로드된 데이터로 랭킹 검증 (네트워크 호출 없음) ===");
        System.out.println("- [" + evidence.sourceId() + ", p" + evidence.pdfPage() + ", score=" + evidence.score() + "] "
                + evidence.text().substring(0, Math.min(80, evidence.text().length())));
    }
}
