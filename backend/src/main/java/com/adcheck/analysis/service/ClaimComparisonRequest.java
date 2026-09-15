package com.adcheck.analysis.service;

import java.util.List;

/**
 * AI #2(Comparison) 호출 입력 — Backend Contract(2026-09-10) 기준. Backend가 Product/
 * Ingredient/Official Function을 확정하고 RuleAnalysisService·RAG까지 마친 뒤에 이 값을
 * 채워서 넘겨준다. 정확한 필드명은 팀원과 구현하며 맞추기로 했으므로 최종 확정은 아니다.
 */
public record ClaimComparisonRequest(
        List<ComparisonClaim> claims,
        ConfirmedProduct product,
        List<ConfirmedIngredient> ingredients,
        List<OfficialFunction> officialFunctions,
        List<RuleMatch> ruleMatches,
        List<Evidence> evidence
) {
    public ClaimComparisonRequest {
        claims = List.copyOf(claims);
        ingredients = List.copyOf(ingredients);
        officialFunctions = List.copyOf(officialFunctions);
        ruleMatches = List.copyOf(ruleMatches);
        evidence = List.copyOf(evidence);
    }
}
