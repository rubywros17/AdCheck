package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;

import java.util.List;

/**
 * {@link AiRuleEvaluator}가 판정 프롬프트를 만들기 "전"에, 그 Rule의 근거 문서 범위 안에서
 * Claim 문장과 관련된 실제 원문 문단을 가져오기 위한 경계. RAG grounding 구현을 교체하거나
 * (예: 캐싱 추가) 테스트에서 가짜 문단을 주입하기 쉽게 하려고 인터페이스로 분리했다.
 */
public interface RuleGroundingProvider {

    /**
     * @return ruleCode의 근거 문서 범위 안에서 claimText와 관련도가 높은 순으로 정렬된 문단
     *         목록. 매핑된 근거가 없거나 조회에 실패하면 빈 목록.
     */
    List<String> groundingTexts(Rule rule, String claimText);
}
