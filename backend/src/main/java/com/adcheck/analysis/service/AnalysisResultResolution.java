package com.adcheck.analysis.service;

import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.result.AnalysisResultSnapshot;

import java.util.Objects;

public sealed interface AnalysisResultResolution permits
        AnalysisResultResolution.Reused,
        AnalysisResultResolution.InProgress,
        AnalysisResultResolution.NewAnalysis {

    Type type();

    AnalysisReuseKey reuseKey();

    enum Type {
        REUSED,
        IN_PROGRESS,
        NEW
    }

    record Reused(
            Long analysisId,
            AnalysisResultSnapshot snapshot,
            AnalysisReuseKey reuseKey
    ) implements AnalysisResultResolution {

        public Reused {
            analysisId = requireAnalysisId(analysisId);
            snapshot = Objects.requireNonNull(snapshot, "snapshot은 null일 수 없습니다.");
            reuseKey = Objects.requireNonNull(reuseKey, "reuseKey는 null일 수 없습니다.");
        }

        @Override
        public Type type() {
            return Type.REUSED;
        }
    }

    record InProgress(
            Long analysisId,
            AnalysisStatus status,
            AnalysisReuseKey reuseKey
    ) implements AnalysisResultResolution {

        public InProgress {
            analysisId = requireAnalysisId(analysisId);
            status = Objects.requireNonNull(status, "status는 null일 수 없습니다.");
            if (status != AnalysisStatus.PENDING && status != AnalysisStatus.PROCESSING) {
                throw new IllegalArgumentException("진행 중 상태는 PENDING 또는 PROCESSING이어야 합니다.");
            }
            reuseKey = Objects.requireNonNull(reuseKey, "reuseKey는 null일 수 없습니다.");
        }

        @Override
        public Type type() {
            return Type.IN_PROGRESS;
        }
    }

    record NewAnalysis(AnalysisReuseKey reuseKey) implements AnalysisResultResolution {

        public NewAnalysis {
            reuseKey = Objects.requireNonNull(reuseKey, "reuseKey는 null일 수 없습니다.");
        }

        @Override
        public Type type() {
            return Type.NEW;
        }
    }

    private static Long requireAnalysisId(Long analysisId) {
        Objects.requireNonNull(analysisId, "analysisId는 null일 수 없습니다.");
        if (analysisId <= 0) {
            throw new IllegalArgumentException("analysisId는 양수여야 합니다.");
        }
        return analysisId;
    }
}
