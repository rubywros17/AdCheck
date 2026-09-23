package com.adcheck.analysis.result;

import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.FindingResponse;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AnalysisResultSnapshotMapper {

    public AnalysisResultSnapshot toSnapshot(AnalysisResponse response) {
        Objects.requireNonNull(response, "analysis response는 null일 수 없습니다.");

        AnalysisResultSnapshot.Summary summary = new AnalysisResultSnapshot.Summary(
                response.summary().findingCount(),
                response.summary().officialFunctionMatchedCount()
        );
        var findings = response.findings().stream()
                .map(this::toSnapshot)
                .toList();
        return new AnalysisResultSnapshot(summary, findings);
    }

    public AnalysisResponse toResponse(
            Long analysisId,
            AnalysisStatus status,
            AnalysisResultSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "analysis result snapshot은 null일 수 없습니다.");

        AnalysisSummary summary = new AnalysisSummary(
                snapshot.summary().findingCount(),
                snapshot.summary().officialFunctionMatchedCount()
        );
        var findings = snapshot.findings().stream()
                .map(this::toResponse)
                .toList();
        return new AnalysisResponse(analysisId, status, summary, findings);
    }

    private AnalysisResultSnapshot.Finding toSnapshot(FindingResponse finding) {
        return new AnalysisResultSnapshot.Finding(
                finding.sourceText(),
                finding.selector(),
                finding.riskLevel(),
                finding.category(),
                finding.message(),
                finding.officialFunction(),
                finding.sources(),
                finding.rules()
        );
    }

    private FindingResponse toResponse(AnalysisResultSnapshot.Finding finding) {
        return new FindingResponse(
                finding.sourceText(),
                finding.selector(),
                finding.riskLevel(),
                finding.category(),
                finding.message(),
                finding.officialFunction(),
                finding.sources(),
                finding.rules()
        );
    }
}
