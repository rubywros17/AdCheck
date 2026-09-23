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
            int officialFunctionMatchedCount
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
