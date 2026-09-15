package com.adcheck.analysis.service;

/**
 * AI #2(Comparison) 입력용 Claim — {@link ExtractedClaim}에서 source를 뺀 최소 형태다.
 * claimId는 AI #1이 채번한 값을 그대로 이어받아 Comparison 결과와 다시 연결한다.
 */
public record ComparisonClaim(String claimId, String claimText) {
}
