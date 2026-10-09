package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisProperties;
import com.adcheck.analysis.repository.AnalysisRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class DailyAnalysisLimitGuard {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AnalysisRepository analysisRepository;
    private final AnalysisProperties properties;
    private final Clock clock;

    public DailyAnalysisLimitGuard(
            AnalysisRepository analysisRepository,
            AnalysisProperties properties,
            Clock clock
    ) {
        this.analysisRepository = analysisRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 오늘(KST) 생성된 분석 수가 한도 이상이면 예외를 던진다. */
    public void checkAvailable() {
        Instant startOfDay = LocalDate.now(clock.withZone(KST))
                .atStartOfDay(KST)
                .toInstant();

        long todayCount = analysisRepository.countByCreatedAtGreaterThanEqual(startOfDay);

        int dailyLimit = properties.getDailyLimit();
        if (todayCount >= dailyLimit) {
            throw new DailyAnalysisLimitExceededException(dailyLimit);
        }
    }
}