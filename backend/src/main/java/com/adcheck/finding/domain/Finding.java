package com.adcheck.finding.domain;

import java.util.List;

/**
 * {@code category}는 Rule Engine의 {@code judgmentCategory} 문자열(71종)을 그대로
 * 통과시킨다 — 예전에 있던 단일값 enum {@code FindingCategory}({@code FUNCTION_CLAIM}
 * 하나뿐)로는 71종을 표현할 수 없어 String으로 유지하기로 결정됨(2026-09-15).
 * {@code FindingCategory.java}는 더 이상 쓰이지 않아 삭제됐다(2026-09-15).
 *
 * <p>{@code sources}는 이 Finding의 대표 규칙(가장 심각한 RuleMatch)이 참조하는 법령·심의기준
 * 조문이다(2026-09-22 추가) — RAG가 검색한 {@code Evidence}가 아니라, 규칙 판정 시점에 이미
 * 확정되는 {@code RuleMatch.sources()}에서 그대로 옮긴 값이라 AI 호출 없이 항상 채워진다.
 */
public record Finding(
        String sourceText,
        String selector,
        RiskLevel riskLevel,
        String category,
        String message,
        String officialFunction,
        List<FindingSource> sources,
        List<FindingRule> rules
) {
}
