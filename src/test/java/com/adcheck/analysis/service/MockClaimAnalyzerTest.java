package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.finding.domain.FindingCategory;
import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockClaimAnalyzerTest {

    private final MockClaimAnalyzer analyzer = new MockClaimAnalyzer();

    @Test
    void returnsNoFindingForNormalWording() {
        ClaimAnalysisResult result = analyzer.analyze(List.of(
                new PageTextEvidence("건강한 일상을 위한 영양 성분을 담았습니다.", ".description")
        ));

        assertThat(result.findings()).isEmpty();
        assertThat(result.officialFunctionMatchedCount()).isZero();
    }

    @Test
    void returnsCautionFindingForDevelopmentKeyword() {
        ClaimAnalysisResult result = analyzer.analyze(List.of(
                new PageTextEvidence("시력을 회복하고 노안을 예방합니다.", "#claim")
        ));

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().riskLevel()).isEqualTo(RiskLevel.CAUTION);
        assertThat(result.findings().getFirst().category()).isEqualTo(FindingCategory.FUNCTION_CLAIM);
        assertThat(result.findings().getFirst().sourceText()).contains("회복");
    }
}
