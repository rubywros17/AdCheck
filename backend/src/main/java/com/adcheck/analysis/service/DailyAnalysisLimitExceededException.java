package com.adcheck.analysis.service;

import org.springframework.http.HttpStatus;

import com.adcheck.global.exception.ApiException;

public class DailyAnalysisLimitExceededException extends ApiException {
    public DailyAnalysisLimitExceededException(int dailyLimit) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "DAILY_ANALYSIS_LIMIT_EXCEEDED",
                String.format("하루 분석 요청 한도(%d건)를 초과했습니다. 내일 다시 시도해 주세요.", dailyLimit)
        );
    }
}