package com.adcheck.rule.model;

/** Engine input contract; the Backend orchestrator maps its query DTO to this record. */
public record RuleOfficialFunctionContext(
        Long ingredientMasterId, String canonicalName, String officialFunctionText,
        String sourceType, String recognitionNo
) { }
