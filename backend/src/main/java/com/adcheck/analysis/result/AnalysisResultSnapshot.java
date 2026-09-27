package com.adcheck.analysis.result;

import com.adcheck.finding.domain.FindingRule;
import com.adcheck.finding.domain.FindingSource;
import com.adcheck.finding.domain.RiskLevel;

import java.util.List;
import java.util.Objects;

public record AnalysisResultSnapshot(
        Summary summary,
        List<Finding> findings
) {

    public AnalysisResultSnapshot {
        summary = Objects.requireNonNull(summary, "summary는 null일 수 없습니다.");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings는 null일 수 없습니다."));
    }

    public record Summary(
            int findingCount,
            int officialFunctionMatchedCount,
            /*
             * 뒤에 추가된 필드라 이미 저장된 result_json에는 이 키가 없다. int로 두면 Jackson이
             * "Cannot map null into type int"로 복원 자체를 실패시켜 <b>저장된 모든 분석 결과가
             * 읽히지 않는다</b>(실제로 테스트에서 재현됐다). 그래서 Integer로 받고 읽는 쪽에서
             * 0으로 채운다.
             *
             * 옛 분석은 "미평가 0건"으로 보인다. 그 시점에는 애초에 이 구분이 없었으므로 값을
             * 지어내는 것보다 낫고, 저장된 JSON에 버전이 없어 마이그레이션도 불가능하다.
             */
            Integer unevaluatedClaimCount
    ) {
    }

    public record Finding(
            String sourceText,
            String selector,
            RiskLevel riskLevel,
            String category,
            String message,
            String officialFunction,
            List<FindingSource> sources,
            List<FindingRule> rules
    ) {
        /**
         * 해당 필드가 없던 옛 저장 JSON을 역직렬화하면 null이 들어오므로 빈 리스트로 정규화한다.
         * {@code result_json}은 마이그레이션 대상이 아니라서, 필드를 추가할 때마다 이미 쌓인 행이
         * 그대로 읽혀야 한다({@code AnalysisResultJsonCodecTest}에서 양방향으로 고정해뒀다).
         */
        public Finding {
            sources = sources == null ? List.of() : List.copyOf(sources);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
