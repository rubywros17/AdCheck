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
                new AnalysisSummary(1, 1, 0),
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

    @Test
    void 미평가_건수가_없던_옛_스냅샷은_0으로_응답한다() {
        // unevaluatedClaimCount를 추가하기 전에 저장된 result_json은 이 키가 없어 null로 복원된다.
        // 응답 DTO는 int라 여기서 0으로 채워야 하며, 이걸 놓치면 NPE로 조회가 통째로 실패한다.
        AnalysisResultSnapshot old = new AnalysisResultSnapshot(
                new AnalysisResultSnapshot.Summary(2, 1, null), List.of());

        AnalysisResponse restored = mapper.toResponse(7L, AnalysisStatus.COMPLETED, old);

        assertThat(restored.summary().findingCount()).isEqualTo(2);
        assertThat(restored.summary().unevaluatedClaimCount()).isZero();
    }
}
