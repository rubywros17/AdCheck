package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;

import java.util.List;
import java.util.Objects;

public record AnalysisJobInput(
        List<PageTextEvidence> texts,
        List<PageImageEvidence> images
) {

    public AnalysisJobInput {
        texts = List.copyOf(Objects.requireNonNull(texts, "texts는 null일 수 없습니다."));
        images = List.copyOf(Objects.requireNonNull(images, "images는 null일 수 없습니다."));
    }

    public static AnalysisJobInput from(CreateAnalysisRequest request) {
        Objects.requireNonNull(request, "분석 요청은 null일 수 없습니다.");
        return new AnalysisJobInput(request.texts(), request.images());
    }
}
