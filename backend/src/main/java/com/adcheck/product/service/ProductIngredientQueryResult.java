package com.adcheck.product.service;

import java.util.List;

public record ProductIngredientQueryResult(
        List<ProductIngredientReadModel> ingredients,
        boolean fallbackRequired
) {

    public ProductIngredientQueryResult {
        ingredients = List.copyOf(ingredients);
    }

    public static ProductIngredientQueryResult from(List<ProductIngredientReadModel> ingredients) {
        return new ProductIngredientQueryResult(ingredients, ingredients.isEmpty());
    }
}
