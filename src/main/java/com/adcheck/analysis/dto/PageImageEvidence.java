package com.adcheck.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record PageImageEvidence(
        @NotBlank(message = "이미지 URL은 비어 있을 수 없습니다.")
        @Size(max = 2_048, message = "이미지 URL은 2,048자 이하여야 합니다.")
        @URL(message = "올바른 이미지 URL 형식이어야 합니다.")
        @Pattern(regexp = "https?://.+", message = "이미지 URL은 HTTP 또는 HTTPS 형식이어야 합니다.")
        String url,

        @Size(max = 500, message = "이미지 대체 텍스트는 500자 이하여야 합니다.")
        String alt
) {
}
