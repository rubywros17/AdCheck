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

    /**
     * 저장된 {@code result_json}은 마이그레이션 대상이 아니라서, 스키마를 바꿀 때마다 <b>이미
     * 쌓인 행이 새 코드로 읽히는지</b>를 따로 확인해야 한다(CLAUDE.md에도 적혀 있는 주의사항).
     * 아래 두 방향을 모두 고정해둔다.
     */
    @Test
    void 필드가_없던_옛_JSON도_읽힌다() {
        // sources를 추가하기 전에 저장된 형태 — 필드 자체가 없다.
        String oldJson = """
                {"summary":{"findingCount":1,"officialFunctionMatchedCount":0},
                 "findings":[{"sourceText":"간 건강에 좋습니다","selector":null,
                 "riskLevel":"HIGH","category":"FUNCTION_EXCEED","message":"확인이 필요합니다",
                 "officialFunction":null}]}
                """;

        AnalysisResultSnapshot restored = codec.deserialize(oldJson);

        assertThat(restored.findings()).hasSize(1);
        assertThat(restored.findings().get(0).sources()).isEmpty();
    }

    @Test
    void 나중에_추가된_모르는_필드가_있어도_읽힌다() {
        // 새 코드가 저장한 JSON을 옛 코드가 읽는 방향. 모르는 필드는 무시돼야 한다.
        String newerJson = """
                {"summary":{"findingCount":1,"officialFunctionMatchedCount":0},
                 "findings":[{"sourceText":"간 건강에 좋습니다","selector":null,
                 "riskLevel":"HIGH","category":"FUNCTION_EXCEED","message":"확인이 필요합니다",
                 "officialFunction":null,"sources":[],
                 "rules":[{"ruleCode":"C05_FUNCTION_EXCEED","status":"MATCHED"}],
                 "someFutureField":123}]}
                """;

        AnalysisResultSnapshot restored = codec.deserialize(newerJson);

        assertThat(restored.findings()).hasSize(1);
        assertThat(restored.findings().get(0).sourceText()).isEqualTo("간 건강에 좋습니다");
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
                        List.of(),
                        List.of()
                ))
        );
    }
}
