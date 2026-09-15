package com.adcheck.analysis.service;

/**
 * AI 1차 추출이 페이지에서 읽은 원료 후보 1건 — 아직 IngredientMaster에 확정 매칭되기
 * 전이다(Backend Contract 기준). 공식 DB로 확정된 결과는 Backend가 만드는
 * {@code ingredientMatches}(별도 개념)이고, 이 레코드는 그와 구분되는 AI 쪽 산출물이다.
 *
 * <p>confidence는 프롬프트가 원료표 그룹별로 반환하는 {@code labelGroupConfidences}를
 * 그대로 담는다({@link ProductContentExtractionService}) — 그룹 개수가 안 맞는 등 응답이
 * 불완전하면 null일 수 있다.
 */
public record IngredientCandidate(String rawText, Double confidence, Source source) {
}
