package com.adcheck.analysis.service;

import com.adcheck.global.exception.ApiException;
import org.springframework.http.HttpStatus;

public class AnalysisConflictRecoveryException extends ApiException {

    public AnalysisConflictRecoveryException() {
        super(
                HttpStatus.CONFLICT,
                "ANALYSIS_CONFLICT_RECOVERY_FAILED",
                "동일한 분석 요청의 진행 상태를 확인하지 못했습니다."
        );
    }
}
