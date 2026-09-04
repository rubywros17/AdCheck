package com.adcheck.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PageTextEvidence(
        @NotBlank(message = "텍스트 내용은 비어 있을 수 없습니다.")
        @Size(max = 10_000, message = "텍스트 내용은 10,000자 이하여야 합니다.")
        String content,

        @Size(max = 1_000, message = "selector는 1,000자 이하여야 합니다.")
        String selector
) {
}
