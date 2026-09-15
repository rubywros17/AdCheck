package com.adcheck.analysis.service;

/**
 * Backend가 확정한 Ingredient — AI #2(Comparison) 입력. AI가 만드는 게 아니라 Backend가
 * {@link IngredientCandidate}를 IngredientMaster/ProductIngredientMatch로 확정한 뒤 넘겨준다.
 */
public record ConfirmedIngredient(String ingredientCode, String standardName) {
}
