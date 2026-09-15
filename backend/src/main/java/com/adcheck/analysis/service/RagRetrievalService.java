package com.adcheck.analysis.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI #2(Comparison)에 넘길 근거 문단을 찾는 RAG 검색 — {@code docs/reference_sources_v0.1.csv}의
 * 13개 ReferenceSource(PDF-BASE 계열 6종 + LAW-01~05, FUNC-02, NOTICE-01)를 문단 단위로
 * 미리 청크·임베딩해둔 {@code rag/reference_sources_v0.1_embeddings.json}
 * (gemini-embedding-001, 3072차원, 총 123개 청크)을 기동 시 메모리에 올려두고,
 * 질의마다 코사인 유사도로 상위 문단을 찾는다. FUNC-01(공전 전문), FUNC-03(제품별
 * 개별 인정 자료)은 manifest의 설계 의도(REVIEW-03 대체, corpus화 대상 아님)에 따라
 * 의도적으로 제외했다.
 *
 * <p>문서(청크) 쪽 임베딩은 이 리소스 파일을 만들 때(1회성 스크립트) 이미 끝난 것이고,
 * 여기서는 질의 텍스트 임베딩 1번 + 저장된 벡터들과의 코사인 유사도 비교만 한다 — 문서
 * 개수가 적어(100여 개) 별도 벡터 DB 없이 인메모리 brute-force 비교로 충분하다.
 *
 * <p>{@code src/main/resources/rag/reference_sources_v0.1_embeddings.json} 리소스는
 * 아직 이 브랜치에 없다 — 없으면 {@link #loadChunks()}가 경고 로그만 남기고 빈 리스트로
 * 시작하므로 기동에는 영향이 없지만, 실제 검색 결과는 항상 빈 리스트가 된다.
 */
@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);
    private static final String EMBEDDINGS_RESOURCE = "rag/reference_sources_v0.1_embeddings.json";

    private final GeminiEmbeddingClient embeddingClient;
    private final List<RagChunk> chunks;

    public RagRetrievalService(GeminiEmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
        this.chunks = loadChunks();
        log.info("RAG 청크 {}개 로드 완료", chunks.size());
    }

    /**
     * queryText와 가장 관련 있는 근거 문단 상위 topK개를 코사인 유사도 순으로 반환한다.
     *
     * @param referenceSourceIds Backend Rule Analysis가 확정한 검색 범위({@code ReferenceSource}
     *                           id 목록) — null이거나 비어 있으면 로드된 청크 전체를 대상으로 검색한다.
     *                           지정하면 그 sourceId에 해당하는 청크로만 좁혀서 검색한다(예:
     *                           특정 Rule의 근거 문서만 뒤지고 싶을 때). corpus에 없는 sourceId
     *                           (예: FUNC-01, FUNC-03 — 의도적으로 미임베딩)를 넘기면 그 범위에선
     *                           결과가 안 나온다.
     */
    public List<Evidence> search(String queryText, List<String> referenceSourceIds, int topK) {
        if (chunks.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        List<Double> queryVector = embeddingClient.embed(queryText, "RETRIEVAL_QUERY");
        return rank(queryVector, referenceSourceIds, topK);
    }

    /**
     * Claim이 여러 건일 때 쿼리 임베딩을 한 번의 배치 호출로 묶어서 처리한다 — Claim 수만큼
     * 임베딩 API를 따로 호출하는 대신 호출 횟수를 줄인다. Claim마다 {@code referenceSourceIds}
     * (Rule Analysis가 확정한 근거 범위)가 다를 수 있으므로 {@link ClaimQuery}로 Claim별
     * 범위를 따로 받는다 — 임베딩은 한 번에 묶어서 하되, 범위 필터링은 Claim마다 각자 적용한다.
     *
     * <p>배치 호출이 실패하거나(레이트리밋 등) 응답 개수가 안 맞으면(밀림 위험) 그 배치를
     * 통째로 버리지 않고 <b>{@link #search}로 하나씩 순차 호출하는 방식으로 자동 폴백</b>한다 —
     * 배치 최적화가 실패해도 결과 자체는 나오게 하기 위함.
     *
     * @return claimId -&gt; 그 Claim에 대한 근거 문단 목록
     */
    public Map<String, List<Evidence>> searchBatch(List<ClaimQuery> queries, int topK) {
        if (chunks.isEmpty() || queries.isEmpty()) {
            return Map.of();
        }

        List<String> texts = queries.stream().map(ClaimQuery::queryText).toList();

        List<List<Double>> vectors;
        try {
            vectors = embeddingClient.embedBatch(texts, "RETRIEVAL_QUERY");
        } catch (Exception e) {
            log.warn("배치 임베딩 실패, 개별 호출로 폴백: {}", e.getMessage());
            Map<String, List<Evidence>> fallback = new LinkedHashMap<>();
            for (ClaimQuery query : queries) {
                fallback.put(query.claimId(), search(query.queryText(), query.referenceSourceIds(), topK));
            }
            return fallback;
        }

        Map<String, List<Evidence>> results = new LinkedHashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            ClaimQuery query = queries.get(i);
            results.put(query.claimId(), rank(vectors.get(i), query.referenceSourceIds(), topK));
        }
        return results;
    }

    private List<Evidence> rank(List<Double> queryVector, List<String> referenceSourceIds, int topK) {
        List<RagChunk> scope = (referenceSourceIds == null || referenceSourceIds.isEmpty())
                ? chunks
                : chunks.stream().filter(chunk -> referenceSourceIds.contains(chunk.sourceId())).toList();
        if (scope.isEmpty()) {
            return List.of();
        }
        return scope.stream()
                .map(chunk -> new ScoredChunk(chunk, cosineSimilarity(queryVector, chunk.embedding())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(scored -> new Evidence(
                        scored.chunk().sourceId() + "-p" + scored.chunk().pdfPage(),
                        scored.chunk().sourceId(),
                        scored.chunk().pdfPage(),
                        scored.chunk().text(),
                        scored.score()))
                .toList();
    }

    /** searchBatch()의 Claim 1건 — claimId별로 쿼리 텍스트와 근거 검색 범위를 따로 지정한다. */
    public record ClaimQuery(String claimId, String queryText, List<String> referenceSourceIds) {
    }

    private static double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<RagChunk> loadChunks() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(EMBEDDINGS_RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<RagChunk>>() {
            });
        } catch (IOException e) {
            log.warn("RAG 임베딩 리소스 로드 실패({}): {}", EMBEDDINGS_RESOURCE, e.getMessage());
            return List.of();
        }
    }

    private record ScoredChunk(RagChunk chunk, double score) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RagChunk(String sourceId, int pdfPage, String text, List<Double> embedding) {
    }
}
