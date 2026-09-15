package com.adcheck.analysis.service;

/**
 * 확정된 Ingredient의 공식 인정 기능성 — Backend가 FunctionalIngredient/NotifiedIngredient
 * 테이블에서 조회해 AI #2(Comparison) 입력으로 넘긴다.
 */
public record OfficialFunction(String ingredientCode, String functionText) {
}
