package com.adcheck.analysis.service;

/**
 * 원료 표기 문자열 1개에 대한 매칭 결과. Data Contract v0.2의 ProductIngredientMatch와
 * 필드를 맞췄다 — matchStatus는 "MATCHED" | "REVIEW_REQUIRED" | "UNMATCHED",
 * matchMethod는 "RAW_RECOGNITION_NO" | "PRODUCT_TYPE_RECOGNITION_NO" | "SYNONYM_EXACT"
 * | "NORMALIZED_NAME" | null(UNMATCHED일 때) 중 하나. 이 필드 구성은 팀원이 확정한
 * AnalysisResponse.ingredientMatches Contract와 그대로 맞춘 것이다.
 *
 * <p>standardName/ingredientCode는 계약(ProductIngredientMatch) 자체엔 없는 필드지만,
 * 연결된 IngredientMaster 정보를 같이 반환하는 값이다 — ingredientCode는 MVP에서 지원하는
 * 원료(10개)만 값이 있고 나머지는 null일 수 있다.
 */
public record IngredientMatchResult(
        String rawText,
        Long ingredientMasterId,
        String ingredientCode,
        String standardName,
        String matchMethod,
        String matchStatus
) {
}
