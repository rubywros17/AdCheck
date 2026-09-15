package com.adcheck.analysis.service;

/**
 * Backend RuleAnalysisService가 판정한 Rule 적용 결과 — AI #2(Comparison) 입력.
 * AI는 이 값을 참고만 하고 severity/ruleCode를 다시 판정하지 않는다.
 *
 * <p>Rule Engine 쪽 원본({@code RuleAnalysisResult.RuleMatch})은 ruleId/ruleVersion/
 * evaluation/sources/diagnostics 등 훨씬 풍부하지만, 그 타입을 그대로 가져오지 않고 AI#2가
 * 실제로 쓰는 값만 이 레코드에 옮겨 담는다 — Rule Engine 내부 구조 변경에 이 계약이 영향받지
 * 않게 하고, 엔진 내부 진단 정보(diagnostics 등)가 섞이지 않게 하기 위함이다. {@code
 * judgmentCategory}/{@code reason}은 Rule Engine이 채워주면 담고, 없으면 {@code null}.
 *
 * <p>이 리스트에 어떤 판정 상태(MATCHED만? REVIEW_REQUIRED도?)까지 포함시킬지는 Backend가
 * 결정할 필터링 문제이지, 이 레코드의 필드 구성과는 별개다.
 */
public record RuleMatch(
        String claimId,
        String ruleCode,
        String severity,
        String judgmentCategory,
        String reason
) {
}
