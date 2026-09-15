package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockClaimAnalyzerTest {

    private final MockClaimAnalyzer analyzer = new MockClaimAnalyzer();

    @Test
    void extractsClaimWithoutRiskSignalForNormalWording() {
        ClaimAnalysisResult result = analyzer.analyze(
                List.of(new PageTextEvidence("건강한 일상을 위한 영양 성분을 담았습니다.", ".description")),
                List.of()
        );

        assertThat(result.claims()).hasSize(1);
        assertThat(result.claims().getFirst().claimText()).isEqualTo("건강한 일상을 위한 영양 성분을 담았습니다.");
        assertThat(result.riskSignalCandidates()).isEmpty();
        assertThat(result.productCandidates()).isEmpty();
        assertThat(result.ingredientCandidates()).isEmpty();
    }

    @Test
    void extractsRiskSignalCandidateForDevelopmentKeyword() {
        ClaimAnalysisResult result = analyzer.analyze(
                List.of(new PageTextEvidence("시력을 회복하고 노안을 예방합니다.", "#claim")),
                List.of()
        );

        assertThat(result.claims()).hasSize(1);
        assertThat(result.riskSignalCandidates()).hasSize(1);
        RiskSignalCandidate signal = result.riskSignalCandidates().getFirst();
        assertThat(signal.claimId()).isEqualTo(result.claims().getFirst().claimId());
        assertThat(signal.text()).contains("회복");
        assertThat(signal.source().selector()).isEqualTo("#claim");
    }
}
