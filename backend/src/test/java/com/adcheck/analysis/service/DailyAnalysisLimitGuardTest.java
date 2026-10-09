package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisProperties;
import com.adcheck.analysis.repository.AnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyAnalysisLimitGuardTest {

    private AnalysisRepository repository;
    private AnalysisProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisRepository.class);
        properties = new AnalysisProperties();
        properties.setDailyLimit(20);
    }

    /** "지금"을 고정한 Guard를 만든다. utc 예: "2026-10-08T14:59:00Z" */
    private DailyAnalysisLimitGuard guardAt(String utc) {
        Clock fixed = Clock.fixed(Instant.parse(utc), ZoneOffset.UTC);
        return new DailyAnalysisLimitGuard(repository, properties, fixed);
    }

    @Test
    void allowsWhenBelowLimit() {
        when(repository.countByCreatedAtGreaterThanEqual(any())).thenReturn(19L);

        assertThatCode(() -> guardAt("2026-10-08T03:00:00Z").checkAvailable())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenLimitReached() {
        when(repository.countByCreatedAtGreaterThanEqual(any())).thenReturn(20L);

        assertThatThrownBy(() -> guardAt("2026-10-08T03:00:00Z").checkAvailable())
                .isInstanceOfSatisfying(DailyAnalysisLimitExceededException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getCode()).isEqualTo("DAILY_ANALYSIS_LIMIT_EXCEEDED");
                });
    }

    @Test
    void countsFromKstMidnightJustBeforeMidnight() {
        // KST 10/8 23:59 → 10/8 00:00 KST(= 10/7 15:00 UTC)부터 세야 함
        guardAt("2026-10-08T14:59:00Z").checkAvailable();

        verify(repository).countByCreatedAtGreaterThanEqual(Instant.parse("2026-10-07T15:00:00Z"));
    }

    @Test
    void resetsAtKstMidnight() {
        // KST 10/9 00:01 → 10/9 00:00 KST(= 10/8 15:00 UTC)부터 다시 세야 함
        guardAt("2026-10-08T15:01:00Z").checkAvailable();

        verify(repository).countByCreatedAtGreaterThanEqual(Instant.parse("2026-10-08T15:00:00Z"));
    }
}