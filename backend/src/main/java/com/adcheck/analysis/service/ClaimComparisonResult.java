package com.adcheck.analysis.service;

import java.util.List;

public record ClaimComparisonResult(List<ClaimComparison> claimComparisons) {
    public ClaimComparisonResult {
        claimComparisons = List.copyOf(claimComparisons);
    }
}
