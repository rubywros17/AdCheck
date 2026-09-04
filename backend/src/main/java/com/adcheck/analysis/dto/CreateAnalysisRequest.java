package com.adcheck.analysis.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.List;

public record CreateAnalysisRequest(
        @NotBlank(message = "페이지 URL은 필수입니다.")
        @Size(max = 2_048, message = "페이지 URL은 2,048자 이하여야 합니다.")
        @URL(message = "올바른 페이지 URL 형식이어야 합니다.")
        @Pattern(regexp = "https?://.+", message = "페이지 URL은 HTTP 또는 HTTPS 형식이어야 합니다.")
        String pageUrl,

        @Size(max = 300, message = "페이지 제목은 300자 이하여야 합니다.")
        String pageTitle,

        @Size(max = 200, message = "상품명은 200자 이하여야 합니다.")
        String productName,

        @Size(max = 500, message = "텍스트 항목은 최대 500개까지 전송할 수 있습니다.")
        List<@Valid PageTextEvidence> texts,

        @Size(max = 100, message = "이미지 항목은 최대 100개까지 전송할 수 있습니다.")
        List<@Valid PageImageEvidence> images
) {
    public CreateAnalysisRequest {
        texts = texts == null ? List.of() : List.copyOf(texts);
        images = images == null ? List.of() : List.copyOf(images);
    }
}
