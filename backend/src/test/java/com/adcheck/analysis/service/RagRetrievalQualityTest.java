package com.adcheck.analysis.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 근거 검색이 <b>같은 Claim에 같은 근거를 주는지</b>, 그리고 그 근거가 <b>관련 있는지</b>를 잰다.
 *
 * <p>왜 필요한가: 파이프라인 다섯 단계 중 여기만 한 번도 측정된 적이 없다. AI#1(claim 출현율
 * 50~65%), AI#3(판정 안정도 92~100%), AI#2(설명 내용 일치)는 각각 기준선이 있는데, <b>AI#2가
 * 인용하는 근거의 출처</b>는 아무도 모른다. 엉뚱한 문단을 가져와도 지금은 알아챌 방법이 없다.
 *
 * <p>두 가지를 나눠 본다.
 *
 * <ul>
 *   <li><b>일관성</b> — 같은 질의를 반복해 같은 청크가 같은 순위로 오는지. 임베딩은 결정론에
 *       가깝다고 기대되지만 확인된 적이 없다. 여기가 흔들리면 같은 Claim의 설명이 회차마다
 *       다른 근거를 달게 된다.</li>
 *   <li><b>관련성</b> — 점수와 실제 문단을 그대로 출력한다. "관련 있는가"는 수치로 못 가르고
 *       사람이 읽어야 한다. 점수만 높고 내용이 무관한 경우를 눈으로 잡기 위해서다.</li>
 * </ul>
 *
 * <p><b>비용</b>: 생성 모델이 아니라 {@code gemini-embedding-001}을 쓰므로
 * {@code generateContent} 일일 한도와 <b>별개 할당량</b>이다(분당 100회·일일 1,000회).
 * 호출 수 = REPEAT회(질의는 배치로 묶인다).
 *
 * <p>GEMINI_API_KEY 없으면 스킵.
 */
class RagRetrievalQualityTest {

    private static final int REPEAT = 3;
    private static final int TOP_K = 3;

    /**
     * 실제 파이프라인에서 RAG를 타는 것은 <b>MATCHED가 있는 Claim</b>뿐이다. 그래서 위반으로
     * 판정될 만한 문구를 쓴다 — 판단 보류만 붙는 문구는 이 단계에 오지도 않는다.
     */
    private static final List<RagRetrievalService.ClaimQuery> QUERIES = List.of(
            new RagRetrievalService.ClaimQuery("c1",
                    "눈의 피로를 완벽하게 개선해 시력을 회복시켜 줍니다.", List.of()),
            new RagRetrievalService.ClaimQuery("c2",
                    "3개월이면 누구나 체지방이 확 줄어듭니다.", List.of()),
            new RagRetrievalService.ClaimQuery("c3",
                    "매일 한 알로 감기를 예방하세요.", List.of()),
            new RagRetrievalService.ClaimQuery("c4",
                    "루테인은 눈 건강에 도움을 줄 수 있습니다.", List.of()));

    @Test
    void 같은_질의에_같은_근거를_주는지_그리고_관련_있는지_본다() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        RagRetrievalService service = new RagRetrievalService(
                new GeminiEmbeddingClient(apiKey, "gemini-embedding-001"));

        // claimId -> 회차별 (chunkId 순서)
        Map<String, List<List<String>>> rankingRuns = new LinkedHashMap<>();
        Map<String, List<Evidence>> lastEvidence = new LinkedHashMap<>();
        QUERIES.forEach(q -> rankingRuns.put(q.claimId(), new ArrayList<>()));

        System.out.printf("%n==== RAG 근거 검색 · 질의 %d건 × %d회 (topK=%d) ====%n",
                QUERIES.size(), REPEAT, TOP_K);

        for (int run = 1; run <= REPEAT; run++) {
            long startedAt = System.currentTimeMillis();
            Map<String, List<Evidence>> result = service.searchBatch(QUERIES, TOP_K);
            System.out.printf("  %d회차: %d건 응답 (%dms)%n",
                    run, result.size(), System.currentTimeMillis() - startedAt);
            result.forEach((claimId, evidence) -> {
                rankingRuns.computeIfAbsent(claimId, k -> new ArrayList<>())
                        .add(evidence.stream().map(Evidence::chunkId).toList());
                lastEvidence.put(claimId, evidence);
            });
            Thread.sleep(2_000);
        }

        reportConsistency(rankingRuns);
        reportRelevance(lastEvidence);
    }

    /** 회차마다 같은 청크가 같은 순위로 오는지. 흔들리면 설명의 근거가 매번 달라진다. */
    private void reportConsistency(Map<String, List<List<String>>> rankingRuns) {
        System.out.printf("%n---- 일관성 (같은 질의 %d회) ----%n", REPEAT);
        int identical = 0;
        for (var entry : rankingRuns.entrySet()) {
            List<List<String>> runs = entry.getValue();
            if (runs.isEmpty()) {
                System.out.printf("  %-4s 응답 없음%n", entry.getKey());
                continue;
            }
            Set<List<String>> distinctOrders = new LinkedHashSet<>(runs);
            Set<String> union = new LinkedHashSet<>();
            runs.forEach(union::addAll);
            Set<String> always = new LinkedHashSet<>(union);
            runs.forEach(always::retainAll);

            boolean same = distinctOrders.size() == 1;
            if (same) {
                identical++;
            }
            System.out.printf("  %-4s 순서까지 동일 %-5s · 매번 나온 청크 %d개 / 한 번이라도 %d개%n",
                    entry.getKey(), same ? "예" : "아니오", always.size(), union.size());
            if (!same) {
                runs.forEach(order -> System.out.printf("        %s%n", order));
            }
        }
        System.out.printf("  → 질의 %d건 중 순서까지 동일한 것 %d건%n", rankingRuns.size(), identical);
    }

    /**
     * 점수와 실제 문단을 그대로 낸다. "관련 있는가"는 수치로 가를 수 없어 사람이 읽어야 하고,
     * 점수만 높고 내용이 무관한 경우가 있는지 눈으로 확인하기 위한 출력이다.
     */
    private void reportRelevance(Map<String, List<Evidence>> lastEvidence) {
        System.out.printf("%n---- 관련성 (마지막 회차의 근거) ----%n");
        for (var entry : lastEvidence.entrySet()) {
            String claimText = QUERIES.stream()
                    .filter(q -> q.claimId().equals(entry.getKey()))
                    .map(RagRetrievalService.ClaimQuery::queryText)
                    .findFirst().orElse("");
            System.out.printf("%n  [%s] «%s»%n", entry.getKey(), claimText);
            if (entry.getValue().isEmpty()) {
                System.out.println("      근거 없음");
                continue;
            }
            for (Evidence evidence : entry.getValue()) {
                String text = evidence.text() == null ? "" : evidence.text().replaceAll("\\s+", " ");
                System.out.printf("      %.4f  %s p%s  %s%n",
                        evidence.score() == null ? 0 : evidence.score(),
                        evidence.sourceId(), evidence.pdfPage(),
                        text.length() > 110 ? text.substring(0, 110) + "…" : text);
            }
        }
    }
}
