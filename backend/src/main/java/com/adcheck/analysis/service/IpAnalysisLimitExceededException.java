package com.adcheck.analysis.service;

import com.adcheck.global.exception.ApiException;
import org.springframework.http.HttpStatus;

public class IpAnalysisLimitExceededException extends ApiException {

    public IpAnalysisLimitExceededException(int ipDailyLimit) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "IP_ANALYSIS_LIMIT_EXCEEDED",
                String.format("하루 IP 분석 요청 한도(%d건)를 초과했습니다. 내일 다시 시도해 주세요.", ipDailyLimit)
        );
    }

}
