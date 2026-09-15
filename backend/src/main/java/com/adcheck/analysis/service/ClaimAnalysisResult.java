package com.adcheck.analysis.service;

import java.util.List;

/**
 * AI 1차 추출(Extraction) 결과 — Backend Contract(2026-09-10) 기준으로
 * {@code claims}/{@code productCandidates}/{@code ingredientCandidates}/
 * {@code riskSignalCandidates} 네 종류의 후보만 담는다.
 *
 * <p>Product/Ingredient 확정, Rule 판정, 최종 Finding 조립은 전부 Backend 책임이라
 * 이 결과엔 담지 않는다 — riskLevel/officialFunction/Finding을 AI가 고정값으로 채워
 * 넘기던 이전 방식은 더 이상 쓰지 않는다.
 */
public record ClaimAnalysisResult(
        List<ExtractedClaim> claims,
        List<ProductCandidate> productCandidates,
        List<IngredientCandidate> ingredientCandidates,
        List<RiskSignalCandidate> riskSignalCandidates
) {
    public ClaimAnalysisResult {
        claims = List.copyOf(claims);
        productCandidates = List.copyOf(productCandidates);
        ingredientCandidates = List.copyOf(ingredientCandidates);
        riskSignalCandidates = List.copyOf(riskSignalCandidates);
    }
}
