package com.adcheck.product.service;

import com.adcheck.product.domain.Product;

import java.util.List;

public record ProductIdentificationResult(
        Status status,
        Product product,
        List<Product> candidates,
        MatchType matchType
) {

    public ProductIdentificationResult {
        candidates = List.copyOf(candidates);
    }

    public static ProductIdentificationResult found(Product product, MatchType matchType) {
        return new ProductIdentificationResult(Status.FOUND, product, List.of(), matchType);
    }

    public static ProductIdentificationResult ambiguous(List<Product> candidates) {
        return new ProductIdentificationResult(
                Status.AMBIGUOUS,
                null,
                candidates,
                MatchType.EXACT_NAME_COMPANY
        );
    }

    public static ProductIdentificationResult notFound() {
        return new ProductIdentificationResult(Status.NOT_FOUND, null, List.of(), MatchType.NONE);
    }

    public enum Status {
        FOUND,
        AMBIGUOUS,
        NOT_FOUND
    }

    public enum MatchType {
        EXACT_REPORT_NO,
        EXACT_NAME_COMPANY,
        NONE
    }
}
