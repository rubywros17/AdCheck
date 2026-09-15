package com.adcheck.analysis.service;

import java.util.List;

/** 콤마로 나열된 원료 필드 전체를 분리·매칭한 결과. */
public record IngredientFieldMatchResult(boolean parsed, List<IngredientMatchResult> items) {
}
