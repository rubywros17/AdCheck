package com.adcheck.analysis.service;

import com.adcheck.global.exception.ApiException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;

public class AnalysisQueueFullException extends ApiException {

    public AnalysisQueueFullException(TaskRejectedException cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ANALYSIS_QUEUE_FULL",
                "분석 요청이 많아 작업을 제출하지 못했습니다. 잠시 후 다시 시도해 주세요."
        );
        initCause(cause);
    }
}
