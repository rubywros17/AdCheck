package com.adcheck.analysis.service;

import com.adcheck.global.exception.ApiException;
import org.springframework.http.HttpStatus;

public class AnalysisNotFoundException extends ApiException {

    public AnalysisNotFoundException(Long analysisId) {
        super(
                HttpStatus.NOT_FOUND,
                "ANALYSIS_NOT_FOUND",
                "분석 결과를 찾을 수 없습니다. analysisId=" + analysisId
        );
    }
}
