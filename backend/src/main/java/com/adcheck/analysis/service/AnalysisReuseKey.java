package com.adcheck.analysis.service;

import java.util.Objects;

public record AnalysisReuseKey(
        String normalizedUrl,
        String contentHash,
        String pipelineVersion
) {

    public AnalysisReuseKey {
        normalizedUrl = requireText(normalizedUrl, "normalizedUrl");
        contentHash = requireText(contentHash, "contentHash");
        pipelineVersion = requireText(pipelineVersion, "pipelineVersion");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + "은 null일 수 없습니다.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 비어 있을 수 없습니다.");
        }
        return value;
    }
}
