package com.adcheck.product.service;

import com.adcheck.product.domain.MatchMethod;
import com.adcheck.product.domain.MatchStatus;

public record ProductIngredientReadModel(
        Long ingredientMasterId,
        String canonicalName,
        String rawText,
        MatchMethod matchMethod,
        MatchStatus matchStatus
) {
}
