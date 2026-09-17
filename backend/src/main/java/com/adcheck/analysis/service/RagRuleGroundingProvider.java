package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.service.RuleAnalysisResult.SourceMetadata;
import com.adcheck.rule.service.RuleSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * {@link RuleGroundingProvider}의 실제 구현. {@link RuleSourceResolver}로 Rule에 매핑된
 * 근거 문서(sourceId) 범위를 조회한 뒤, 그 범위로 좁힌 {@link RagRetrievalService#search}를
 * 호출해 Claim과 가장 관련 있는 문단을 가져온다.
 *
 * <p>같은 {@code RagRetrievalService.search(query, referenceSourceIds, topK)} 메서드를
 * {@code FindingAssembler.searchEvidence()}가 판정 "후" AI#2 설명용으로 이미 쓰고 있다 —
 * 이 클래스는 그 동일한 메커니즘(Rule→출처 범위 조회 + 범위 내 유사도 검색)을 판정 "전"
 * 단계에 재사용할 뿐, 새 검색 인프라를 추가한 게 아니다.
 *
 * <p><b>2026-09-17 팀 결정 — 기본값 OFF, 그래서 {@code @Component} 없음.</b> 검증
 * 데이터셋 117행 실측 결과, "문장 밖 정보 필요 여부" 게이트 프롬프트에서는 판정 전
 * grounding이 오히려 정확도를 낮췄다(79.5%→74.4%, 주로 NOT_MATCHED 하락) — 근거 문서
 * 원문을 보여주면 모델이 "판단 근거가 충분하다"고 여겨 게이트(REVIEW_REQUIRED 인정)를
 * 덜 발동시키는 것으로 추정. 일부러 {@code @Component}를 빼서, {@code AiRuleEvaluator}가
 * 나중에 {@code @Component}로 등록돼도 Spring이 이 빈을 자동 주입하지 않게(=grounding이
 * 조용히 다시 켜지지 않게) 막아뒀다 — 이 결정을 뒤집을 때만 다시 붙이면 된다.
 *
 * <p><b>판정 후 근거 표시(⑤→⑥, {@code FindingAssembler.searchEvidence()})는 이것과
 * 무관하게 그대로 동작한다</b> — MATCHED로 확정된 Claim에 AI#2가 설명을 쓸 때 인용하는
 * 근거는 이 클래스가 아니라 {@code RagRetrievalService.searchBatch()}를 판정 후 단계에서
 * 직접 호출하는 별도 경로라, 이번 결정으로 사라지지 않는다.
 */
public class RagRuleGroundingProvider implements RuleGroundingProvider {

    private static final Logger log = LoggerFactory.getLogger(RagRuleGroundingProvider.class);
    private static final int TOP_K = 3;

    private final RuleSourceResolver ruleSourceResolver;
    private final RagRetrievalService ragRetrievalService;

    public RagRuleGroundingProvider(RuleSourceResolver ruleSourceResolver, RagRetrievalService ragRetrievalService) {
        this.ruleSourceResolver = ruleSourceResolver;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public List<String> groundingTexts(Rule rule, String claimText) {
        if (rule.getId() == null || claimText == null || claimText.isBlank()) {
            return List.of();
        }
        try {
            Map<Long, List<SourceMetadata>> sourcesByRuleId = ruleSourceResolver.resolve(List.of(rule.getId()));
            List<String> sourceIds = sourcesByRuleId.getOrDefault(rule.getId(), List.of()).stream()
                    .map(SourceMetadata::sourceId)
                    .distinct()
                    .toList();
            if (sourceIds.isEmpty()) {
                return List.of();
            }
            return ragRetrievalService.search(claimText, sourceIds, TOP_K).stream()
                    .map(evidence -> "[" + evidence.sourceId() + "] " + evidence.text())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Rule Judge RAG grounding 조회 실패, 근거 문단 없이 진행합니다: ruleCode={}, error={}",
                    rule.getRuleCode(), e.getMessage());
            return List.of();
        }
    }
}
