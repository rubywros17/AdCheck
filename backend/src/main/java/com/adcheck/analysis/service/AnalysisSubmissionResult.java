package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.AnalysisResponse;

import java.util.Objects;

public record AnalysisSubmissionResult(Outcome outcome, AnalysisResponse response) {

    public AnalysisSubmissionResult {
        outcome = Objects.requireNonNull(outcome, "outcome은 null일 수 없습니다.");
        response = Objects.requireNonNull(response, "analysis response는 null일 수 없습니다.");
    }

    public static AnalysisSubmissionResult created(AnalysisResponse response) {
        return new AnalysisSubmissionResult(Outcome.CREATED, response);
    }

    public static AnalysisSubmissionResult reused(AnalysisResponse response) {
        return new AnalysisSubmissionResult(Outcome.REUSED, response);
    }

    public static AnalysisSubmissionResult inProgress(AnalysisResponse response) {
        return new AnalysisSubmissionResult(Outcome.IN_PROGRESS, response);
    }

    public enum Outcome {
        CREATED,
        REUSED,
        IN_PROGRESS
    }
}
