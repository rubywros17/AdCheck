package com.adcheck.product.service;

public record OfficialFunctionReadModel(
        Long ingredientMasterId,
        String canonicalName,
        String officialFunctionText,
        SourceType sourceType,
        String recognitionNo,
        String sourceName
) {

    public enum SourceType {
        FUNCTIONAL,
        NOTIFIED
    }
}
