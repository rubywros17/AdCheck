package com.adcheck.finding.domain;

/**
 * {@code category}는 Rule Engine의 {@code judgmentCategory} 문자열(71종)을 그대로
 * 통과시킨다 — 예전에 있던 단일값 enum {@code FindingCategory}({@code FUNCTION_CLAIM}
 * 하나뿐)로는 71종을 표현할 수 없어 String으로 유지하기로 결정됨(2026-09-15).
 * {@code FindingCategory.java}는 더 이상 쓰이지 않아 삭제됐다(2026-09-15).
 */
public record Finding(
        String sourceText,
        String selector,
        RiskLevel riskLevel,
        String category,
        String message,
        String officialFunction
) {
}
