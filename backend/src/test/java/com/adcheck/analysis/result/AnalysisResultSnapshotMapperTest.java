package com.adcheck.analysis.result;

import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.FindingResponse;
import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisResultSnapshotMapperTest {

    private final AnalysisResultSnapshotMapper mapper = new AnalysisResultSnapshotMapper();

    @Test
    void mapsHttpResponseToPersistenceSnapshotAndBackWithoutDataLoss() {
        AnalysisResponse response = new AnalysisResponse(
                7L,
                AnalysisStatus.COMPLETED,
                new AnalysisSummary(1, 1),
                List.of(new FindingResponse(
                        "광고 원문",
                        "#claim",
                        RiskLevel.CAUTION,
                        "FUNCTION_CLAIM",
                        "확인이 필요합니다.",
                        "눈 건강에 도움을 줄 수 있음",
                        List.of(),
                        List.of()
                ))
        );

        AnalysisResultSnapshot snapshot = mapper.toSnapshot(response);
        AnalysisResponse restored = mapper.toResponse(
                response.analysisId(),
                response.status(),
                snapshot
        );

        assertThat(restored).isEqualTo(response);
    }
}
