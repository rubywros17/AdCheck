package com.adcheck.analysis.service;

/**
 * AI #2(Comparison) 결과 1건 — 광고 Claim과 공식 인정 기능성의 의미 차이 판단 + 설명.
 * 최종 법적 판정이 아니라 의미 비교와 소비자용 설명 생성까지가 AI의 역할이다.
 *
 * <p>comparisonStatus는 {@code WITHIN_OFFICIAL_RANGE}(공식 범위 안), {@code
 * STRONGER_THAN_OFFICIAL}(공식보다 강하거나 다른 효과 암시), {@code NO_OFFICIAL_BASIS}
 * (관련 공식 기능성 없음), {@code REVIEW_REQUIRED}(판단 애매, 사람 검토 필요) 중 하나다 —
 * 이 명칭은 Backend Contract 확정 시 조정될 수 있다.
 */
public record ClaimComparison(
        String claimId,
        String comparisonStatus,
        String officialFunction,
        String reason,
        String explanation
) {
}
