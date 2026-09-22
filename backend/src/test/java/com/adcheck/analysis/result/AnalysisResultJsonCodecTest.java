package com.adcheck.analysis.result;

import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisResultJsonCodecTest {

    private final AnalysisResultJsonCodec codec =
            new AnalysisResultJsonCodec(new ObjectMapper());

    @Test
    void serializesAndDeserializesSnapshot() {
        AnalysisResultSnapshot expected = snapshot();

        String json = codec.serialize(expected);
        AnalysisResultSnapshot restored = codec.deserialize(json);

        assertThat(restored).isEqualTo(expected);
    }

    @Test
    void rejectsNullOrBlankJson() {
        assertThatThrownBy(() -> codec.deserialize(null))
                .isInstanceOf(AnalysisResultJsonException.class)
                .hasMessage("복원할 분석 결과 JSON이 없습니다.");
        assertThatThrownBy(() -> codec.deserialize("  "))
                .isInstanceOf(AnalysisResultJsonException.class)
                .hasMessage("복원할 분석 결과 JSON이 없습니다.");
        assertThatThrownBy(() -> codec.deserialize("null"))
                .isInstanceOf(AnalysisResultJsonException.class)
                .hasMessage("분석 결과 JSON이 null입니다.");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.deserialize("{malformed"))
                .isInstanceOf(AnalysisResultJsonException.class)
                .hasMessage("분석 결과 JSON을 복원하지 못했습니다.");
    }

    @Test
    void rejectsNullSnapshotForSerialization() {
        assertThatThrownBy(() -> codec.serialize(null))
                .isInstanceOf(AnalysisResultJsonException.class)
                .hasMessage("저장할 분석 결과가 없습니다.");
    }

    private AnalysisResultSnapshot snapshot() {
        return new AnalysisResultSnapshot(
                new AnalysisResultSnapshot.Summary(1, 1),
                List.of(new AnalysisResultSnapshot.Finding(
                        "광고 원문",
                        "#claim",
                        RiskLevel.CAUTION,
                        "FUNCTION_CLAIM",
                        "확인이 필요합니다.",
                        "눈 건강에 도움을 줄 수 있음",
                        List.of()
                ))
        );
    }
}
